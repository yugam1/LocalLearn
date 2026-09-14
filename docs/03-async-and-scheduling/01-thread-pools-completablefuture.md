# Thread Pools, @Async & CompletableFuture
**✅ Completed · plan: Phase 2, Task 8** — ThreadPoolTaskExecutor, sizing, rejection, async composition

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** A thread pool is a **restaurant kitchen**: core threads =
permanent cooks, queue = the order rail, max threads = temp cooks called in
*only when the rail is full*, rejection policy = what the maître d' does when
even that fails. Every production incident with pools is one of these four
numbers being wrong for the workload.

```
task → cooks free (< core)?  → run
     → rail has space?       → queue                ← NOTE: queue BEFORE growing!
     → can hire temps (< max)? → new thread
     → else                  → REJECTION POLICY
```

**Five rules you must never get wrong:**
1. **Queue fills BEFORE the pool grows past core.** maxPoolSize is meaningless with a huge/unbounded queue — it never triggers.
2. Sizing: CPU-bound = `cores + 1`; I/O-bound = `cores × (1 + wait/cpu)`. Queue = `peak_RPS × avg_duration_s × safety`.
3. Never `Executors.newFixedThreadPool` (unbounded queue → OOM) or `newCachedThreadPool` (unbounded threads) in prod — configure `ThreadPoolTaskExecutor` with a **bounded** queue.
4. `CallerRunsPolicy` = production default: the submitter runs the task itself → natural backpressure, nothing lost.
5. `@Async` = proxy again: public method, called **from another bean**, return `void` or `CompletableFuture` — and void methods **swallow exceptions** unless you configure `AsyncUncaughtExceptionHandler`.

---

## 🔮 PREDICT FIRST

<details>
<summary><b>P1.</b> Pool: core=10, max=50, queueCapacity=100. 60 long tasks arrive at once. How many threads are running?</summary>

**10.** Tasks 1–10 take the core threads; tasks 11–60 sit in the queue (capacity
100, not full). The pool grows toward max **only when the queue is full** — the
most counter-intuitive fact about ThreadPoolExecutor. You'd need 111 concurrent
tasks before thread #11 is created. With queueCapacity 5000, max=50 is
decoration.
</details>

<details>
<summary><b>P2.</b> <code>@Async public void sendEmail()</code> throws a RuntimeException. What do you see in logs/caller by default?</summary>

**Nothing, anywhere.** The task ran on a pool thread; with a `void` return
there's no future to carry the exception, and the default handler may only log
at levels nobody watches. The failure is silent. Fixes: return
`CompletableFuture` (exception surfaces on `.join()`/`.exceptionally`), or
register an `AsyncUncaughtExceptionHandler` in `AsyncConfigurer`.
</details>

<details>
<summary><b>P3.</b> Three independent 500ms lookups. Pipeline A: <code>thenApplyAsync</code> chain of the three. Pipeline B: three <code>supplyAsync</code> + <code>allOf</code>. Wall time of each?</summary>

A ≈ **1500ms** — `thenApply*` chains are *sequential by construction*: each stage
waits for the previous result. B ≈ **500ms** — three futures start immediately
and run in parallel; `allOf` just awaits them. Chaining is for dependency,
`allOf` is for fan-out. Confusing them silently triples latency.
</details>

---

## 📖 THE STORY

### 1. Why pools, and why never the `Executors` shortcuts

A thread costs ~1MB stack and ~ms to create; pools amortize that. But the JDK
factory methods hide fatal defaults: `newFixedThreadPool` = unbounded
LinkedBlockingQueue (backlog grows until OOM, zero backpressure);
`newCachedThreadPool` = unbounded threads (traffic spike → thousands of threads
→ context-switch collapse). Production = `ThreadPoolTaskExecutor` where all four
numbers are explicit and bounded.

### 2. The lifecycle that everyone gets wrong

Submit order: **core → queue → grow to max → reject** (see Core Card diagram).
Consequences:
- Big queue = high latency tolerance, max rarely used (throughput pool).
- Small queue = fail-fast, pool grows quickly (latency-critical pool).
- `keepAliveSeconds` retires threads above core; `allowCoreThreadTimeOut(true)`
  lets even core threads idle out.
- Graceful shutdown: `waitForTasksToCompleteOnShutdown(true)` +
  `awaitTerminationSeconds(30)` — don't kill in-flight work on deploys.

### 3. Sizing — a formula, then a monitor

- CPU-bound: `cores + 1` (more just context-switches).
- I/O-bound: `cores × (1 + wait/cpu)` — 8 cores, 90% waiting → ~80 threads.
- Queue: `peak_RPS × avg_task_seconds × safety(2)` — 100 rps × 0.5s × 2 = 100.

Then verify with numbers: expose `activeCount`, `poolSize`, `queue.size()`,
utilization (via `((ThreadPoolTaskExecutor) e).getThreadPoolExecutor()`).
Sustained utilization > 80% or queue near capacity → resize or shed load.

### 4. Rejection — choose your failure mode on purpose

| Policy | Behavior | When |
|---|---|---|
| `AbortPolicy` (default) | throw `RejectedExecutionException` | want loud, immediate failure |
| **`CallerRunsPolicy`** | submitter runs it itself | **default choice** — backpressure, no loss |
| `DiscardPolicy` | drop silently | truly disposable work |
| `DiscardOldestPolicy` | drop oldest queued | freshest-data-wins (analytics ticks) |

`CallerRunsPolicy`'s genius: when the pool saturates, the *producer* slows down
because it's busy doing the work itself — the system self-throttles instead of
dying. (Same backpressure idea as Kafka consumer pause/resume — [task 11](../04-kafka/02-reliability-dlt-idempotency.md).)

### 5. Bulkheads — one pool per concern

```java
@Bean("emailExecutor")     core 5,  max 10, queue 200   // slow, tolerant
@Bean("inventoryExecutor") core 15, max 30, queue 50    // critical, fail fast
@Bean("analyticsExecutor") core 3,  max 10, queue 500, DiscardOldestPolicy
```

If email's SMTP hangs, it saturates *its own* pool — inventory keeps flowing.
One shared pool = one slow dependency starves everything. This is the bulkhead
pattern; Resilience4j formalizes it ([phase 7](../09-resilience.md)). Name
your threads (`setThreadNamePrefix`) — thread dumps become readable — and set
`setTaskDecorator(new MDCTaskDecorator())` on **every** pool ([task 7](../01-foundations/07-logging-mdc-correlation-ids.md)).

### 6. @Async — the proxy rules, again

Same machinery as `@Transactional` ([task 5](../01-foundations/05-transactions-isolation-locking.md)), same traps:
public method; call must cross the proxy (self-invocation runs synchronously —
silently!); pick the pool by name `@Async("emailExecutor")` or the pool is the
default one. Return `CompletableFuture` so callers can compose and see failures:

```java
CompletableFuture<Void> email = asyncService.sendEmailAsync(orderId);
CompletableFuture<Void> stock = asyncService.updateInventoryAsync(orderId);
CompletableFuture.allOf(email, stock).join();      // parallel fan-out, then wait
```

### 7. CompletableFuture — four patterns cover 95%

```java
// SEQUENTIAL dependency — total = sum
cf.supplyAsync(() -> validate(x), pool)
  .thenApplyAsync(v -> transform(v), pool);

// PARALLEL fan-out — total = max
CompletableFuture.allOf(op1, op2).thenApply(v -> combine(op1.join(), op2.join()));

// RACE — first one wins
CompletableFuture.anyOf(provider1, provider2);

// FAILURE + TIMEOUT policy
cf.supplyAsync(() -> risky())
  .orTimeout(2, TimeUnit.SECONDS)
  .exceptionally(ex -> ex instanceof TimeoutException ? cached() : fallback());
```

Two footguns: no-executor overloads run on `ForkJoinPool.commonPool()` (shared
with parallel streams — always pass your executor), and `join()` on a request
thread turns "async" back into blocking — compose instead of joining early.

---

## 🎯 RETRIEVAL GYM

**Tier 1 — must be automatic**

<details><summary><b>Q1.</b> Recite the task-submission order in ThreadPoolExecutor, and the trap it creates.</summary>

Below core → new thread. Core busy → **queue**. Queue full → grow toward max.
At max + queue full → rejection policy. Trap: with a large/unbounded queue the
pool never grows past core — maxPoolSize is dead config.
</details>

<details><summary><b>Q2.</b> Sizing formulas for CPU-bound, I/O-bound, and the queue.</summary>

CPU: cores + 1. I/O: cores × (1 + wait_time/cpu_time) — e.g. 8 cores, 90% wait →
80. Queue: peak_RPS × avg_task_duration_s × safety factor. Then monitor
utilization/queue depth and adjust.
</details>

<details><summary><b>Q3.</b> Why CallerRunsPolicy in production?</summary>

On saturation the submitting thread executes the task itself: nothing is lost,
no exception, and the producer is automatically slowed (it's busy working) —
built-in backpressure and graceful degradation.
</details>

<details><summary><b>Q4.</b> The three @Async rules + the void-exception trap.</summary>

Public method; invoked through the proxy (different bean — self-call runs
synchronously); returns void or CompletableFuture. Void + exception = silently
swallowed → configure AsyncUncaughtExceptionHandler or return a future.
</details>

<details><summary><b>Q5.</b> allOf vs anyOf vs thenApply — and the latency consequence of picking wrong.</summary>

thenApply/thenCompose = sequential dependency (times add). allOf = parallel
fan-out, wait for all (time = slowest). anyOf = race, first result wins.
Chaining independent calls instead of allOf multiplies latency by the number of
calls.
</details>

**Tier 2 — depth**

<details><summary><b>Q6.</b> Why multiple named pools instead of one big one?</summary>

Failure isolation (bulkhead): a hung dependency saturates only its own pool.
Plus per-concern tuning (fail-fast small queue for critical, big
discard-oldest queue for analytics) and per-pool metrics/thread names for
diagnosis.
</details>

<details><summary><b>Q7.</b> What's wrong with `CompletableFuture.supplyAsync(() -> ...)` with no executor argument?</summary>

It runs on ForkJoinPool.commonPool — a JVM-wide shared pool also used by
parallel streams, sized cores−1, no MDC decorator, no isolation, and easily
starved by anyone else's blocking tasks. Always pass an explicit executor.
</details>

<details><summary><b>Q8.</b> How do you detect a saturating pool before it rejects?</summary>

Expose getActiveCount/getPoolSize/queue size from the underlying
ThreadPoolExecutor; alert on utilization > 80% or queue > 80% capacity.
(Micrometer binds these automatically — phase 5.) Rejections are the last
symptom, not the first.
</details>

<details><summary><b>Q9.</b> Where does the MDC TaskDecorator run, exactly?</summary>

`decorate()` runs on the SUBMITTING thread (captures getCopyOfContextMap); the
returned wrapper runs on the WORKER thread (setContextMap → run → clear).
The capture-at-submit / restore-at-run split is the whole trick.
</details>

---

## 🃏 FLASHCARDS

```
Pool growth order	core → QUEUE → max → reject (queue before growth!)
maxPoolSize dead when	Queue is large/unbounded — it fills first, pool never grows
CPU-bound / IO-bound sizing	cores+1 / cores × (1 + wait/cpu)
Queue size formula	peak_RPS × avg_duration_s × safety_factor
Production rejection policy	CallerRunsPolicy — submitter runs task = backpressure, no loss
newFixedThreadPool danger	Unbounded queue → silent backlog → OOM
@Async self-invocation	Runs synchronously — proxy bypassed, no warning
@Async void + exception	Swallowed — AsyncUncaughtExceptionHandler or return CompletableFuture
allOf wall time	= slowest future (parallel); chained thenApply = sum
supplyAsync without executor	ForkJoinPool.commonPool — shared, unmanaged, no MDC
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| This doc | Same idea as | Where |
|---|---|---|
| @Async proxy rules | @Transactional proxy + self-invocation | `01-foundations/05` |
| pool exhaustion math | HikariCP sizing (it's a pool too) | `01-foundations/03`, `01-foundations/06` |
| bulkhead per concern | Resilience4j Bulkhead | `09-resilience` |
| CallerRuns backpressure | Kafka consumer pause/resume | `04-kafka/02` |
| what a thread IS underneath | lifecycle, interruption | `02-concurrency/01` |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
