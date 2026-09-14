# Scheduled Tasks — @Scheduled, Cron & ShedLock
**✅ Completed · plan: Phase 2, Task 9** — fixedRate vs fixedDelay, cron, scheduler pool, distributed locking

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** `@Scheduled` is a **metronome with one hand** — by default a
SINGLE thread plays every scheduled task in the whole app. Two questions decide
everything: *when does the clock start counting* (rate = from previous START,
delay = from previous END), and *how many instances are ticking* (3 pods = 3
metronomes → duplicate emails, unless a distributed lock says only one plays).

```
fixedRate=10s,  task 2s:  |██|........|██|........      ticks at 0,10,20 (start→start)
fixedDelay=10s, task 3s:  |███|..........|███|......    ticks at 0,13,26 (end→start)
fixedRate=5s,   task 7s:  |███████|███████|             next fires IMMEDIATELY — overlap pressure
```

**Five rules you must never get wrong:**
1. **Default scheduler = 1 thread.** One slow task delays every other schedule. Always configure `ThreadPoolTaskScheduler` (pool ~10, named threads).
2. `fixedDelay` when runs must never overlap (cleanup, sync); `fixedRate` for time-critical ticks (heartbeat, polling).
3. **Wrap the whole task body in try-catch.** An escaping exception kills that run — and you must never gamble on whether future runs survive.
4. N instances = N executions. **ShedLock** (`@SchedulerLock` + DB lock row) makes exactly one instance run it.
5. Cron = **6 fields with seconds**: `sec min hour dom month dow` — `0 0 2 * * ?` = daily 02:00. `?` = "no specific value" for the dom/dow pair.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> Default Spring scheduler (no config). Task A: <code>fixedRate=60s</code>, takes 5 minutes (slow API). Task B: <code>fixedRate=10s</code> health check. What happens to B while A runs?</summary>

**B doesn't run at all for 5 minutes.** One scheduler thread; A occupies it, B's
ticks queue up behind. Health checks silently stop — and monitoring built on B
now lies. That's why a custom `ThreadPoolTaskScheduler` with poolSize ~10 is
mandatory config, not tuning.
</details>

<details>
<summary><b>P2.</b> You deploy the order service to 3 pods. <code>@Scheduled(cron = "0 0 2 * * ?") sendDailyReport()</code>. What lands in customer inboxes at 02:00?</summary>

**Three copies of the report.** Every instance has its own scheduler and its own
02:00. @Scheduled has zero cluster awareness. Fix: ShedLock — a lock row in a
shared DB; the first pod to grab it runs, the other two see the lock and skip.
</details>

<details>
<summary><b>P3.</b> ShedLock: <code>lockAtMostFor = "5m"</code>. The node holding the lock crashes mid-run. Is the task locked out forever? And what is <code>lockAtLeastFor</code> protecting against?</summary>

Not forever: `lockAtMostFor` is the lease TTL — after 5 minutes the lock expires
even though nobody released it, so the next run can proceed. Set it > worst-case
runtime. `lockAtLeastFor` keeps the lock held a minimum time — protection
against clock-skewed instances or ultra-fast tasks finishing before the *other*
pods' 02:00 arrives, which would let a second pod run it "again."
</details>

---

## 📖 THE STORY

### 1. The two timing modes (+ startup grace)

- `fixedRate = 10000` — a tick every 10s measured **start → start**. If a run
  exceeds the rate, the next fires as soon as the thread frees: back-to-back
  runs, no breathing room. For heartbeats/polls where cadence matters.
- `fixedDelay = 10000` — next run 10s **after the previous one ends**. Overlap
  is structurally impossible. For cleanup/sync jobs.
- `initialDelay = 5000` — skip the first ticks until the app has warmed up
  (DB/Kafka connections ready), instead of firing during startup chaos.

### 2. Cron — six fields, seconds first

`second minute hour day-of-month month day-of-week`

| Expression | Meaning |
|---|---|
| `0 0 2 * * ?` | daily 02:00 |
| `0 0/15 * * * ?` | every 15 min |
| `0 0 9 ? * MON-FRI` | weekdays 09:00 |
| `0 0/30 9-18 * * ?` | every 30 min, business hours |
| `0 59 23 L * ?` | last day of month, 23:59 |

Specials: `*` any · `?` "no specific value" (dom/dow — set one, `?` the other) ·
`-` range · `,` list · `/` step · `L` last. (Spring cron ≠ Unix cron: the extra
seconds field is the classic off-by-one-field bug.)

### 3. The scheduler pool — mandatory config

```java
@Configuration @EnableScheduling
public class SchedulingConfig implements SchedulingConfigurer {
    @Override
    public void configureTasks(ScheduledTaskRegistrar registrar) {
        registrar.setTaskScheduler(taskScheduler());
    }
    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler s = new ThreadPoolTaskScheduler();
        s.setPoolSize(10);
        s.setThreadNamePrefix("scheduled-");
        s.setWaitForTasksToCompleteOnShutdown(true);
        s.setAwaitTerminationSeconds(30);
        s.initialize();
        return s;
    }
}
```

Same shutdown-grace and thread-naming discipline as the async pools
([task 8](01-thread-pools-completablefuture.md)) — scheduled threads show up in dumps as `scheduled-3`,
not `pool-2-thread-1`.

### 4. Defensive task bodies

```java
@Scheduled(cron = "0 0 2 * * ?")
@Transactional
public void cleanupOldPendingOrders() {
    try {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        // find stale PENDING orders older than cutoff → cancel/archive
    } catch (Exception e) {
        log.error("Cleanup failed", e);      // log + metric + alert — NEVER rethrow
    }
}
```

The scheduler generally survives an escaped exception, but the contract you
should code to is stricter: **a scheduled task owns its failures**. Catch
everything at the top, log with context, emit a metric, decide
tolerate-vs-alert (e.g. `HttpClientErrorException` → WARN and continue;
unknown → ERROR + alert). Built in this task: 2 a.m. stale-order cleanup,
23:00 daily summary, 60s DB health ping, 5-min pending-count threshold alert.

### 5. The clustered world — ShedLock

`@Scheduled` scales *executions*, not *work*: every pod fires. ShedLock adds a
lock table (`name PK, lock_until, locked_at, locked_by`) in the shared DB:

```java
@EnableSchedulerLock(defaultLockAtMostFor = "10m")
@Bean LockProvider lockProvider(DataSource ds) { return new JdbcTemplateLockProvider(ds); }

@Scheduled(cron = "0 0 2 * * ?")
@SchedulerLock(name = "dailyReport", lockAtMostFor = "5m", lockAtLeastFor = "1m")
public void sendDailyReport() { /* exactly one instance */ }
```

First instance UPDATEs the row (atomic — the DB is the arbiter, same
"push the invariant into the database" move as
[02-concurrency/03](../02-concurrency/03-atomicity-races-cas.md)); the rest skip this
tick entirely (skip, not queue). `lockAtMostFor` = crash insurance (lease TTL >
worst-case runtime); `lockAtLeastFor` = clock-skew/fast-finish insurance.
Manual alternative: a `tryLock/unlock` service over a lock table — same idea,
more code.

### 6. Runtime-controlled schedules & monitoring

`@Scheduled` is compile-time fixed. For "admin changes the cron in the UI":
hold the `TaskScheduler`, call `schedule(runnable, new CronTrigger(expr))`,
keep the returned `ScheduledFuture`, `cancel(false)` + reschedule to change.
(`cancel(false)` = let an in-flight run finish; `true` interrupts —
[02-concurrency/01](../02-concurrency/01-threads-lifecycle-interruption.md).)

Monitor the scheduler like any pool: `getScheduledThreadPoolExecutor()` →
active/queue/completed; alert when queue depth grows (ticks falling behind).

**@Scheduled vs Quartz:** @Scheduled = in-memory, stateless, simple — schedules
die with the JVM. Quartz = persistent JDBC job store, native clustering,
misfire policies, dynamic job graphs. Reach for Quartz only when you need jobs
to *survive restarts* or complex orchestration; ShedLock covers plain
"don't duplicate in a cluster."

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> fixedRate vs fixedDelay — measurement point, overlap behavior, canonical use case each.</summary>

fixedRate: start→start; if run > rate the next fires immediately after (pressure
toward overlap) — heartbeats/polling. fixedDelay: end→start; overlap impossible —
cleanup/sync jobs.
</details>

<details><summary><b>Q2.</b> Why is the default @Scheduled setup a production bug waiting to happen?</summary>

Single scheduler thread shared by ALL @Scheduled methods — one slow task delays
or starves every other schedule (health checks included). Fix:
ThreadPoolTaskScheduler, poolSize ~10, named threads, graceful shutdown.
</details>

<details><summary><b>Q3.</b> Three pods, one daily-report cron — what happens and how does ShedLock fix it?</summary>

Three reports: each pod schedules independently. ShedLock: shared DB lock row
per task name; one instance atomically claims it and runs, others skip that
tick. lockAtMostFor = lease TTL so a crashed holder can't block forever.
</details>

<details><summary><b>Q4.</b> Cron `0 0/15 9-18 ? * MON-FRI` — decode it, and name the Spring-vs-Unix trap.</summary>

Second 0, every 15 min, 09:00–18:59, weekdays. Trap: Spring cron has SIX fields
(leading seconds) — pasting a 5-field Unix cron shifts every field by one.
</details>

<details><summary><b>Q5.</b> Error-handling contract for a scheduled task body?</summary>

Own your failures: try-catch around the entire body; log with context; metric +
alert on unexpected; tolerate known transient errors (external API 5xx → WARN).
Never rethrow — the next tick should always be able to run.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> What exactly do lockAtMostFor and lockAtLeastFor each insure against?</summary>

lockAtMostFor: holder crashes without releasing → lease expires, task not locked
out forever; must exceed worst-case runtime or a slow run gets a duplicate.
lockAtLeastFor: task finishes in seconds + other pods' clocks lag → lock held a
minimum window so late-arriving pods still see it and skip.
</details>

<details><summary><b>Q7.</b> When is Quartz actually justified over @Scheduled + ShedLock?</summary>

When schedules must survive restarts (persistent JDBC job store), when you need
misfire policies (what to do about ticks missed while down), dynamic per-tenant
job creation at scale, or job chains/dependencies. For "run this cron once per
cluster," ShedLock is the lighter answer.
</details>

<details><summary><b>Q8.</b> ScheduledFuture.cancel(false) vs cancel(true) for a running task?</summary>

false: unschedule future runs, let the in-flight execution complete. true:
additionally interrupt the running thread — only safe if the task handles
InterruptedException/checks the interrupt flag (cooperative cancellation,
02-concurrency/01).
</details>

<details><summary><b>Q9.</b> initialDelay — what class of bug does it prevent?</summary>

First tick firing into a half-initialized app: DB pool not warmed, Kafka
producers not connected, caches empty → spurious failures/alerts at every
deploy. Delay the first run past startup.
</details>

---

## 🃏 FLASHCARDS

```
fixedRate measures from	START of previous run (can stack back-to-back)
fixedDelay measures from	END of previous run (overlap impossible)
Default @Scheduled threads	ONE — all tasks serialize; always configure ThreadPoolTaskScheduler
Spring cron field count	6 — seconds first: sec min hour dom month dow
Cron daily 2 AM	0 0 2 * * ?
? in cron	"No specific value" — required for the unused one of dom/dow
N pods + @Scheduled	N executions — ShedLock for exactly-one
lockAtMostFor	Lease TTL — crash insurance; must exceed worst-case runtime
lockAtLeastFor	Minimum hold — clock-skew / fast-finish insurance
Scheduled task exceptions	Catch ALL in body; log+metric; never rethrow
Dynamic scheduling	taskScheduler.schedule(runnable, new CronTrigger(expr)) → ScheduledFuture
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| ShedLock DB arbiter | "push the invariant into the database" | `02-concurrency/03`, `01-foundations/05` |
| scheduler pool config | executor tuning + graceful shutdown | `03-async-and-scheduling/01` |
| cancel(true) semantics | cooperative interruption | `02-concurrency/01` |
| duplicate execution in cluster | idempotent consumers (same problem, Kafka) | `04-kafka/02` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
