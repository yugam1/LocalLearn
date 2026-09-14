# Topic 10 — The Diagnostic Incident (capstone)
**Demos:** `t10diagnostics/D29`, `D30`, `D31` | **Exercise:** `Ex15IncidentService`

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Every other page in this curriculum announces its mechanism in
the title, so you always know which chapter the fix comes from. Production never
does that. It hands you a **symptom** — "it's hung", "a core is pinned", "the
numbers are wrong" — and the entire skill is getting from there to a mechanism.
That skill is not a fifth thing after the four you already know; it is the
ability to look at *where a thread stopped* and recognise which of topics 1–9
puts a thread there.

The instruments are few, they are all built into the JDK, and each has a blind
spot you must memorise, because the blind spots are where the expensive
incidents live.

```
                    finds it?   findDeadlockedThreads()  findMonitorDeadlocked()
synchronized cycle      yes              2 threads              2 threads
ReentrantLock cycle     yes              4 threads              2 threads   <- diverges
Semaphore permit cycle  NO               4 threads              2 threads   <- unchanged
                                         ^ two threads parked forever, and the
                                           count did not move. Measured in D29.
```

**The decision rule:** **`findDeadlockedThreads()` returning `null` narrows the
cause; it never clears it.** It means "not a cycle of lock *ownership*", which
rules out one family and leaves three — exhausted pool, uncounted latch, unfed
queue — each of which is diagnosed from thread *stacks* and your own gauges
instead.

**Five rules you must never get wrong:**
1. `RUNNABLE` is a liar in both directions: it includes threads blocked in a native socket read, and it is also what a thread pinning a whole core looks like. Read the frames and the `cpu=` field, never the state word.
2. `BLOCKED` means an intrinsic `synchronized` monitor and nothing else. A `ReentrantLock` deadlock reports `WAITING`. Grep a dump for `BLOCKED`, find none, and you have ruled out nothing.
3. Automatic detection follows lock **ownership**. A `Semaphore` permit, a `CountDownLatch`, a `Future`, and an empty queue have no owner, so a cycle built from them hangs silently and forever.
4. Never block a pool thread on work that can only be performed by that same pool. With an unbounded queue nothing is rejected and nothing throws — the service just stops, permanently, and reports nothing.
5. A thread dump cannot see wrong data. A `ThreadLocal` left on a pooled thread produces a perfectly healthy JVM that answers for the wrong customer, and the only instrument that finds it is an assertion you wrote in advance.

*Run first: D29 (the instruments, calibrated against threads whose state you
chose), then D30 (four symptoms, no labels — diagnose them yourself), and only
then D31 (the answer key).*

---

## 🔮 PREDICT FIRST

*Answer aloud before opening. A wrong prediction you then correct is worth more
than three right ones — that is the whole point of this zone.*

<details>
<summary><b>P1.</b> A service is completely frozen. You run <code>jcmd &lt;pid&gt; Thread.print</code> and it ends with <b>"Found 1 deadlock"</b>, naming two threads. You fix that lock ordering and redeploy. Does the freeze go away?</summary>

**No — and this is the most expensive wrong assumption on the page.** The
deadlock the JVM found and the freeze you are being paged about are two
different bugs, and the tool cannot tell you that.

Here is the dump from D30, trimmed. The JVM found exactly one deadlock:

```
Found one Java-level deadlock:
=============================
"request-ledger-fwd":
  waiting for ownable synchronizer 0x000000070fce5eb8, (a java.util.concurrent.locks.ReentrantLock$NonfairSync),
  which is held by "request-ledger-rev"
"request-ledger-rev":
  waiting for ownable synchronizer 0x000000070fce5e88, (a java.util.concurrent.locks.ReentrantLock$NonfairSync),
  which is held by "request-ledger-fwd"
...
Found 1 deadlock.
```

Two threads. Meanwhile, in the *same dump*, all four of the request pool's
worker threads were also wedged — permanently — and the deadlock section says
nothing about them, because their hang is not a lock cycle:

```
"desk-b-worker-0" #27 daemon prio=5 cpu=0.64ms elapsed=19.15s nid=34307 waiting on condition
   java.lang.Thread.State: WAITING (parking)
	at jdk.internal.misc.Unsafe.park(java.base@21.0.11/Native Method)
	- parking to wait for  <0x000000070f870dc0> (a java.util.concurrent.FutureTask)
	at java.util.concurrent.FutureTask.awaitDone(java.base@21.0.11/FutureTask.java:500)
	at java.util.concurrent.FutureTask.get(java.base@21.0.11/FutureTask.java:190)
	at com.locallearn.concurrency.t10diagnostics.D30_TheIncident$BrokenService.lambda$handle$2(...)
```

That dump contained 30 Java threads, of which **15 were permanently stuck**: the
two named in the deadlock section, a third that had merely tried to *read* the
ledgers they were holding, four pool workers, and the eight callers waiting on
those workers. The JVM reported one deadlock, covering two of the fifteen.

The deadlock was real and fixing it was correct. The **outage** was the other
thirteen. A tool that finds one problem is not a tool that found *the* problem,
and "the JVM reported a deadlock" is where triage starts, not where it ends.
</details>

<details>
<summary><b>P2.</b> Your dump contains <b>no <code>BLOCKED</code> threads at all</b>, and <code>findMonitorDeadlockedThreads()</code> returns <code>null</code>. Have you ruled out a deadlock?</summary>

**No, and you have ruled out almost nothing — the two observations fail for the
same reason.**

`BLOCKED` means *queued for an intrinsic `synchronized` monitor*, and that is
the whole of its meaning. A `ReentrantLock` deadlock parks through
`LockSupport` inside `AbstractQueuedSynchronizer`, so both of its threads report
`WAITING`. `findMonitorDeadlockedThreads()` is the narrow detector that sees
only monitors, so it misses the same deadlock for the same reason.

D29 plants three deadlocks one after another and runs both detectors after each:

| after planting | `findDeadlockedThreads()` | `findMonitorDeadlockedThreads()` |
|---|---|---|
| a `synchronized` cycle | 2 threads | 2 threads |
| **plus** a `ReentrantLock` cycle | **4 threads** | 2 threads |
| **plus** a `Semaphore` permit cycle | 4 threads | 2 threads |

Identical in all seven runs. Two things to take from that table. First, the
**divergence** between the columns is itself a diagnosis: it tells you the
second cycle is on an AQS lock and its threads will say `WAITING`. Second, and
much more important, the third row **did not move**. Two threads are parked
forever on semaphore permits and the JVM believes everything is fine:

```
"sem-deadlock-A" WAITING - parking to wait for <java.util.concurrent.Semaphore$NonfairSync@5f2108b5>
"sem-deadlock-B" WAITING - parking to wait for <java.util.concurrent.Semaphore$NonfairSync@31a5c39e>
```

Detection follows lock **ownership**, and a semaphore permit has no owner.
Neither does a latch, a future, or an empty queue. Topic 4 told you this in one
sentence; here it is with a number attached.
</details>

<details>
<summary><b>P3.</b> During an outage you take a dump and almost every thread says <code>RUNNABLE</code>. Are your CPUs saturated?</summary>

**Not necessarily, and worse: the state word cannot answer the question in
either direction.**

`RUNNABLE` means "not parked on something the JVM knows about". It includes a
thread blocked in a native socket read, which is doing nothing at all — the JVM
cannot see an OS-level I/O wait, only that a native call has not returned. Every
JDBC query and every outbound HTTP call in your service looks like this:

```
"state-runnable-socket" RUNNABLE
        at java.base@21.0.11/sun.nio.ch.SocketDispatcher.read0(Native Method)
        at java.base@21.0.11/sun.nio.ch.NioSocketImpl.tryRead(NioSocketImpl.java:256)
```

And a thread genuinely burning a whole core is *also* just `RUNNABLE`. Here are
two threads from the same real dump, side by side:

```
"desk-b-worker-0" ... cpu=0.64ms     elapsed=19.15s ... waiting on condition
"desk-a-reaper"   ... cpu=23796.49ms elapsed=25.27s ... runnable
```

One consumed 0.64 milliseconds of CPU in nineteen seconds. The other consumed
**23.8 seconds of CPU in 25.3 seconds of wall clock** — 94% of a core, doing
nothing useful. Nothing in the word `RUNNABLE` distinguishes them.

The useful surprise: **the dump already tells you.** `jcmd Thread.print` prints
`cpu=` and `elapsed=` in every thread's header line, so you can find your
spinner without any extra tooling. Most people never notice that field, reach
for `top`, and get a process-level number that cannot name a thread.
</details>

---

## 📖 THE STORY

### 1. The instruments

There are four, they all ship with the JDK, and between them they answer four
different questions. D29 runs each one against threads whose state was chosen in
advance, so you can see exactly where each instrument agrees with the truth and
where it does not.

#### 1a. The thread dump — *where did every thread stop?*

```bash
jcmd <pid> Thread.print        # the modern tool
jcmd <pid> Thread.print -l     # adds ownable synchronizers: who holds which ReentrantLock
jstack <pid>                   # the same thing, older
```

A dump is a list of threads, each with a name, a state, the lock it is waiting
on, who owns that lock, and a stack. Four things are worth knowing before you
read one in anger.

**The state column lies twice.** `RUNNABLE` includes threads blocked on I/O
(P3), and `BLOCKED` means an intrinsic monitor and nothing else (P2). The
vocabulary also changes with the lock type, which matters if you grep:

| Lock type | State | The line in the dump |
|---|---|---|
| `synchronized` monitor | `BLOCKED` | `- waiting to lock <0x…> (a java.lang.Object)` |
| `ReentrantLock` / any AQS lock | `WAITING` | `- parking to wait for <0x…ReentrantLock$NonfairSync>` |
| `CountDownLatch`, `Semaphore` | `WAITING` | `- parking to wait for <0x…CountDownLatch$Sync>` |
| `Future.get()` | `WAITING` | `- parking to wait for <0x…FutureTask>` |
| `BlockingQueue.take()` on empty | `WAITING` | `- parking to wait for <0x…ConditionObject>` |
| `Thread.sleep` / `poll(timeout)` | `TIMED_WAITING` | `TIMED_WAITING (sleeping)` |

**The frame you want is never the top one.** Every parked thread's top three
frames are `Unsafe.park`, `LockSupport.park`, `AbstractQueuedSynchronizer.…`.
Those tell you nothing; they are the same for every blocked thread in the JVM.
Scroll down to the first frame in **your** package. That is the line of your
code that decided to block, and it is the whole diagnosis.

**The header line carries `cpu=` and `elapsed=`.** Per thread, for free. Divide
one by the other and you have found your spinner (P3).

**Take two dumps, thirty seconds apart.** One dump is a photograph and cannot
distinguish "stuck" from "busy". Two dumps showing the same thread on the same
frame is evidence. This is the cheapest diagnostic habit on the page and almost
nobody does it.

#### 1b. `ThreadMXBean` — *the same information, programmatically*

Everything above is available from inside the JVM, which means you can put it in
a health endpoint and have it page you instead of a customer. `Dump.java` in
`t10diagnostics` is the whole toolkit in about thirty lines; the calls that
matter are these.

```java
ThreadMXBean threads = ManagementFactory.getThreadMXBean();

long[] any          = threads.findDeadlockedThreads();          // monitors AND AQS locks
long[] monitorsOnly = threads.findMonitorDeadlockedThreads();   // monitors only

// lockedMonitors/lockedSynchronizers = true is what makes the report name the
// OWNER of each contended lock, which is the actual diagnosis.
ThreadInfo[] info = threads.getThreadInfo(any, true, true);

threads.setThreadCpuTimeEnabled(true);
long cpuNanos = threads.getThreadCpuTime(threadId);             // -1 once the thread is dead

threads.getThreadCount();               // live now
threads.getPeakThreadCount();           // high-water mark, never goes down
threads.getTotalStartedThreadCount();   // ever started
```

**The difference between the two detectors is information, not trivia.**
`findMonitorDeadlockedThreads()` sees only `synchronized`. `findDeadlockedThreads()`
sees monitors *and* `AbstractQueuedSynchronizer` locks. When the first returns
two threads and the second returns four, you have learned that there is a second
cycle and that it is on a `ReentrantLock` — see the table in P2.

**The three counters answer three different questions.** Live climbing says
there is a leak. Total-started climbing at the same rate says it is *still
happening* rather than having happened once at boot. Peak is what you compare
against after the fix. D29 spawns fifty threads that never exit:

```
before:            live=24 peak=24 totalStarted=24 daemon=23
after 50 requests: live=74 peak=74 totalStarted=74 daemon=73
```

**CPU time is the only instrument that separates busy from spinning.** Three
threads, one spinning, one sleeping, one parked, measured over 500 ms of wall
clock and reproduced in all seven runs:

```
cpu-spinner    476–492 ms CPU  (95–98% of one core)   <-- SPINNING
cpu-sleeper            0 ms CPU  (0%)
cpu-parked             0 ms CPU  (0%)
```

In that specific demo you could have guessed from the state word, because
`cpu-sleeper` is `TIMED_WAITING`. In production you cannot: a busy-wait on
`poll()` is `RUNNABLE` inside library code that looks entirely reasonable, and
so is a thread doing real work. This is exactly what Ex7, Ex8 and Ex15 assert
on, and it is the only assertion that catches a spin loop, because a spin loop
is *functionally correct*.

#### 1c. The gauges only you can provide

No dump computes your queue depth. `ThreadPoolExecutor` exposes
`getActiveCount()`, `getQueue().size()`, `getCompletedTaskCount()` and
`getPoolSize()`, and those four numbers sampled twice are the difference between
"the pool is busy" and "the pool is dead". Export them as metrics; more on the
internals in [`08-threadpool-internals.md`](08-threadpool-internals.md).

Pair them with a **progress counter** — requests completed, items processed,
anything monotonic. High CPU alone means a busy service; a flat counter alone
means an idle one; **high CPU and a flat counter** means a thread working hard
at nothing, and neither number alone would have told you.

#### 1d. A short, honest note on JFR

Java Flight Recorder profiles a running JVM with low overhead:

```bash
jcmd <pid> JFR.start name=incident settings=profile duration=10s filename=incident.jfr
jfr summary incident.jfr
jfr print --events ExecutionSample incident.jfr
```

I ran exactly that against the wedged incident JVM and here is what it produced,
which is both less and more useful than the marketing suggests. Of 1,652
`jdk.ExecutionSample` events in ten seconds, **every single one** landed on the
two spinning threads:

```
 827  sampledThread = "desk-a-reaper"
 825  sampledThread = "desk-b-reaper"
```

That is a perfect diagnosis of symptom D and it took one command. Meanwhile the
recording contained exactly **one** `jdk.JavaMonitorEnter` event and **one**
`jdk.JavaMonitorWait` event across the whole ten seconds — that is, essentially
nothing — despite fifteen threads being permanently wedged in that JVM at the
time.

The reason is worth internalising: `ExecutionSample` is a **CPU sampler**. It
samples threads that are *running*. Threads that are parked are, by definition,
not running, so a hung service produces an empty profile. So:

- **JFR is excellent at symptom D** (a pinned core) and at real lock contention,
  where threads repeatedly enter and leave monitors.
- **JFR is close to useless for a hang**, where nothing is executing and nothing
  is contending. For that you want a thread dump, which is a snapshot of
  *everything*, running or not.

An empty JFR profile during an outage is not a failed investigation. It is a
positive result: nothing is executing, so your problem is that threads are
stopped, and the dump is the right next command.

### 2. The symptom table

This is the most useful artifact on the page. Start at the left column, which is
all production ever gives you.

| Symptom | Likely cause | Confirming evidence |
|---|---|---|
| **Total hang**, deadlock reported | lock ordering (topic 4) | `Found one Java-level deadlock`; both threads and both owners named |
| **Total hang**, *no* deadlock reported, threads parked on `FutureTask` | pool blocked on its own pool (topics 5, 8) | every worker `WAITING` in `FutureTask.awaitDone`; `getActiveCount()` == pool size and `getQueue().size()` > 0, **both frozen across two samples** |
| **Total hang**, *no* deadlock reported, threads parked on `CountDownLatch$Sync` | a latch nobody will count down (topic 7) | the thread that owed the `countDown()` is `TERMINATED` or never started |
| **Total hang**, *no* deadlock reported, threads parked on `ConditionObject` | consumers on a queue nobody feeds; producer died (topic 5) | queue depth 0 and flat; producer thread absent from the dump |
| **Partial hang** — a few threads stuck, service otherwise healthy | lock ordering, again | deadlock reported for *those* threads only; health check still passes, which is why it was missed |
| **Partial hang**, *no* deadlock reported | `Semaphore` permit cycle, mutual latch (topics 4, 7) | parked on `Semaphore$NonfairSync`; permits never returned; **no tool will ever name this** |
| **100% CPU, no progress**, one thread | busy-wait on `poll()` (topic 5) | dump header `cpu=` ≈ `elapsed=`; `RUNNABLE`; progress counter flat; all JFR samples on that thread |
| **100% CPU, no progress**, several threads in lockstep | livelock — `tryLock` retry with no jitter (topic 4) | all `RUNNABLE`, all in the retry loop, dump looks *healthy* |
| **100% CPU, progress continues** | genuinely busy, or GC thrash | check GC time before touching any of this |
| **Slow creep** — latency rising over hours | thread leak, or an unbounded queue backlog (topic 5) | `getTotalStartedThreadCount()` climbing; queue depth trending up; heap trending up |
| **No hang, wrong answers** | `ThreadLocal` not removed on a pooled thread (topic 6); shared mutable state (topics 2, 3) | **nothing in any dump**. Compare request input with response output |
| **Sporadically wrong numbers under load only** | lost update / check-then-act (topic 3) | an invariant assertion under a start gate; see `Stress` |

Two rows deserve to be read twice. The `Semaphore` row, because no instrument
will ever help you and you have to recognise it from the stack alone. And the
last two rows, because they do not hang at all — and a failure that does not
hang is the one that runs for six weeks before anybody notices.

### 3. The incident

`D30_TheIncident` is a request-processing service of the sort every backend
contains: a fixed worker pool, a per-request tenant context, a few internal
ledgers guarded by locks, and a background thread draining an audit queue. It is
about eighty lines. Four of them are wrong, each drawn from a different topic,
and **the demo shows you only symptoms**.

Run it, then diagnose all four before reading any further or running D31.

```bash
cd concurrency-lab
./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t10diagnostics.D30_TheIncident

# or, to hold the wedged JVM open so you can dump it yourself:
java -cp target/classes com.locallearn.concurrency.t10diagnostics.D30_TheIncident hold 60
```

#### Act A — two requests never return; the rest of the service is fine

```
3 seconds after the two transfers started:
  request-ledger-fwd alive=true state=WAITING
  request-ledger-rev alive=true state=WAITING
meanwhile an unrelated request still succeeds: tenant=acme|req=unrelated-request|accepted
a third, unrelated request that merely READS the ledgers: alive=true state=WAITING
```

Note the third line: **the health check passes**, which is exactly why this
survives to production. And note the fourth: the damage is not contained to the
two guilty threads. Anything that later touches the resources they are holding
joins the queue of the doomed, which is how two stuck threads at 09:00 are an
outage by 09:20.

<details>
<summary><b>Diagnose Act A before opening.</b> Which mechanism, and which single command proves it?</summary>

A **lock-ordering deadlock** (topic 4), and `jcmd <pid> Thread.print` proves it
outright — the JVM finds this class of bug itself. The excerpt is in P1.

The tell that separates it from Act B is that the deadlock detector *fires*, and
it fires because both stuck threads are waiting on locks that have **owners**.
The thing that catches people is the state word: both threads say `WAITING`, not
`BLOCKED`, because these are `ReentrantLock`s and AQS parks rather than queueing
on a monitor. `findMonitorDeadlockedThreads()` returns `null` for this deadlock.
If your triage habit is to grep for `BLOCKED`, you will conclude there is no
deadlock while staring at one.
</details>

#### Act B — under concurrent load the service stops completely

Three requests one at a time succeed perfectly. Then eight arrive at once
against four workers:

```
warm-up: one request at a time works perfectly
  tenant=acme|req=warmup-0|accepted
now 8 concurrent requests against 4 workers:
  t+1s: completed 0 of 8, pool active=4 queued=8
  t+2s: completed 0 of 8, pool active=4 queued=8
  t+3s: completed 0 of 8, pool active=4 queued=8
0 of 8 caller threads have returned. The counter has not moved since t+0.
```

<details>
<summary><b>Diagnose Act B.</b> The deadlock detector returns <code>null</code>. What is happening, and what is your next command?</summary>

**Pool exhaustion** (topics 5 and 8). Every worker submitted a sub-task to *the
pool it was running on* and then blocked on the resulting `Future`. With four
workers and four such requests in flight, all four threads are waiting for tasks
that can only be run by a thread that is already waiting.

Your next command is still the dump, because the stacks know even when the
detector does not:

```
"desk-b-worker-0" WAITING - parking to wait for <java.util.concurrent.FutureTask@5442a311>
        at java.base@21.0.11/jdk.internal.misc.Unsafe.park(Native Method)
        at java.base@21.0.11/java.util.concurrent.FutureTask.awaitDone(FutureTask.java:500)
        at …BrokenService.lambda$handle$2(D30_TheIncident.java:338)
   (and desk-b-worker-1, -2, -3, identically)
```

Four of four workers, all parked on a `FutureTask`, all from the same line of
`handle()`. Add the pool's own gauges sampled a second apart —
`active=4 queued=8 completed=0`, then `active=4 queued=8 completed=0` — and the
picture is complete: every thread is occupied, eight tasks are queued, nothing
moved.

Why no deadlock report? Because a `Future` has no owner. The detector looks for
a cycle in "thread A waits for a lock held by thread B"; here the workers are
waiting on *tasks*, and a task in a queue is not held by anybody. The queue is
unbounded, so nothing was rejected and nothing threw. The service did not crash.
It stopped.

The rule this comes from is worth more than the diagnosis: **never block a pool
thread on work that can only be performed by the same pool.** If the sub-task
genuinely must run elsewhere, give it its own pool, so the two cannot starve
each other.
</details>

#### Act C — every request succeeds, and some answers belong to someone else

Two hundred ordinary tenant requests, then two hundred unauthenticated system
requests — health probes, scheduled sweeps — which belong to nobody:

```
200 tenant requests, then 200 requests that belong to NO tenant.
every one of the 400 returned successfully and quickly.
of the 200 that should have said tenant=anonymous, 200 did not.
first wrong answer: tenant=tenant-1|req=system-sweep-0|accepted
```

<details>
<summary><b>Diagnose Act C.</b> Nothing is stuck, no lock is contended, no CPU is pinned. Which instrument finds this?</summary>

**None of them.** That is the lesson, and it is why this act exists.

The cause is a **`ThreadLocal` left on a pooled thread** (topic 6). The request
context is installed when a request has a tenant and never removed, so the
worker goes back into the pool still wearing the last identity it served, and
the next task to land on that thread inherits an identity that is not its own.
The thread is reused; the `ThreadLocal` is per *thread*, not per *task*.

Every instrument on this page reports a healthy service, because by every
definition a runtime instrument can express, it **is** a healthy service: no
deadlock, all four workers idle and parked in the pool's own `take()`, zero CPU.
The only thing that finds it is comparing what you sent with what came back.

Now look at *which* tenant leaked: whichever one happened to use that thread
last. `tenant-1`, `tenant-2`, `tenant-3`, `tenant-4` in turn as the sweeps
rotate across the four workers. So the wrong answer differs on every run, on
every thread, and with every traffic pattern — which is precisely why this
reproduces beautifully in production and never in staging.

This is the failure mode to be frightened of. A hang pages you in ten minutes. A
`ThreadLocal` leak invoices the wrong customer for six weeks and nobody notices.
That is also why the usual advice — "call `remove()` to avoid a memory leak" —
undersells it. In a pool, `remove()` is not hygiene. It is correctness.
</details>

#### Act D — idle service, busy machine

```
the service has nothing to do for the next second. Watch the CPU:
  desk-d-reaper                 1001 ms CPU  (100% of one core)  <-- SPINNING
  desk-d-worker-0                  0 ms CPU  (  0% of one core)
  desk-d-worker-1                  0 ms CPU  (  0% of one core)
and the progress counter over that same second: 1 -> 1 audit entries
```

<details>
<summary><b>Diagnose Act D.</b> The guilty thread is <code>RUNNABLE</code>. So is every healthy thread doing real work. How do you tell them apart?</summary>

A **busy-wait** (topic 5). `poll()` returns `null` immediately on an empty
queue, so a loop around it spins. A full core consumed and zero work done.

You tell them apart with **two** instruments, and neither is sufficient alone:
per-thread CPU time says this thread is using a whole core, and the progress
counter says the work it is supposed to be doing has not advanced. High CPU
alone is a busy service. A flat counter alone is an idle one. High CPU *and* a
flat counter is a thread working hard at nothing.

The fix is a verb, not an algorithm: `poll(timeout, unit)` parks like `take()`
but surfaces periodically, which is what a consumer loop that must also notice a
shutdown flag actually wants. That is D14's verb grid, and it is the third time
this curriculum has asked you for it, because it passes every correctness test
ever written and shows up only on the infrastructure bill.
</details>

#### Reproducibility, honestly

Every figure above comes from a run on this machine, and I repeated the whole
demo seven times before publishing any of them.

- Acts **A**, **C** and **D** were identical in all seven runs. Act C was
  200 wrong out of 200 every time; only the *identity* of the leaked tenant
  varied, which is itself the point.
- Act **D**'s CPU figure varied between **79% and 100%** of one core across the
  seven runs, always above 70%, and never confusable with the parked threads at
  0%.
- Act **B** was **not** deterministic at first, and this is worth your
  attention. The original version reproduced in only four of seven runs: if a
  worker happened to submit its sub-task while another worker was still idle,
  that idle worker ran the sub-task and the request completed. So the defect is
  real, but whether you *see* it depends on the interleaving.

  Rather than publish a probabilistic demo, I forced the state: the service now
  holds the first `workers` requests until all of them are provably occupying a
  worker thread, then releases them. That is a state a busy production pool
  reaches by itself many times a second. With it, Act B reproduced in **seven of
  seven** runs — `completed 0 of 8, active=4, queued=8`, identical every time.
  The technique is D15's: wait until the system is provably in the state you
  want to demonstrate, rather than hoping the scheduler obliges.

This distinction matters more than the numbers. A concurrency defect that
reproduces four times in seven on an idle laptop reproduces *constantly* on a
loaded production box, and intermittency is a property of your observation, not
of the bug.

### 4. The scoreboard

D31 runs all four instruments against all four symptoms. Sixteen answers; six
are useful.

| | symptom A | symptom B | symptom C | symptom D |
|---|---|---|---|---|
| dump census | names it | names it | clean | **misleads** |
| deadlock detectors | **names it** | silent | clean | n/a |
| per-thread CPU time | ~0 ms | ~0 ms | clean | **names it** |
| pool gauges | n/a | **names it** | clean | n/a |
| comparing input to output | n/a | n/a | **names it** | confirms |

Only symptom A is solved by a tool. B needs a tool *plus* a stack *plus* a
gauge. C is invisible to every runtime instrument that exists and is found only
by an assertion somebody wrote in advance. D needs CPU time *and* a progress
counter, because either one alone is ambiguous.

### 5. One more thing triage teaches you

A real JVM has more than one problem at a time. In D31, symptom A's two threads
are parked in `ReentrantLock.lock()`, which is **not interruptible**, so they
stay deadlocked for the life of the process — and they show up in every deadlock
report you run afterwards while chasing symptoms B, C and D.

The habit that fixes this is to **scope the question to the threads you are
actually investigating**, which is why the service's threads are all named
`incident-*` and the callers `stress-*`. Name your threads. A pool whose threads
are called `pool-3-thread-7` will cost you twenty minutes at exactly the moment
you have none, and a `ThreadFactory` that names them properly is four lines.

---

## 🧪 EXERCISE 15 — `Ex15IncidentService`

```bash
cd concurrency-lab
./mvnw test -Dtest='ExerciseTests$Ex15'
```

Repair the service. Four tests, one per defect, and — unlike every other
exercise in this lab — **the failure messages name the symptom and the topic,
never the fix.** Working that out is the exercise; a message that gave it away
would turn a diagnosis into a transcription.

| Test | The symptom it reports | Drawn from |
|---|---|---|
| `servesConcurrentRequestsWithoutStalling` | `0 of 320 finished in 25s, and it has not moved since` | topics 5, 8 |
| `bidirectionalTransfersDoNotHang` | the stuck threads are parked on a lock another stuck thread holds | topics 1, 4 |
| `oneRequestsTenantNeverLeaksIntoAnother` | `200 of 200 requests that carried NO tenant came back attributed to somebody else's tenant` | topic 6 |
| `idleServiceBurnsNoCpu` | `its 3 threads burned 990 ms of CPU` across 1,000 ms of wall clock | topic 5 |

The first three are identical on every run. The CPU figure varied between
**898 ms and 1,007 ms** across six runs of the broken version, against a
threshold of 600 — never close enough to the limit to be in doubt.

Each failure also prints a live **evidence block** built from the same
`ThreadMXBean` calls D29 teaches — the two detectors, a state census, and the
frame in *your* code where each thread stopped. That is deliberate: a failing
test here should read like the first thing you would have typed at a real
incident, so the habit transfers. A real one looks like this:

```
--- evidence ---------------------------------
(scoped to threads named incident-*)
findDeadlockedThreads()        -> null (nothing found)
findMonitorDeadlockedThreads() -> null (nothing found)
No lock cycle. That does NOT mean 'no hang': a pool waiting on its own queue, a
latch nobody counts down, or a semaphore cycle are all invisible here.
(2 further deadlocked thread(s) exist outside this scope.)
incident-* states: {RUNNABLE=1, WAITING=4}
  "incident-reaper"   stopped at …Ex15IncidentService.reap(Exercises.java:599)
  "incident-worker-0" stopped at …Ex15IncidentService.lambda$handle$2(Exercises.java:555)
  "incident-worker-1" stopped at …Ex15IncidentService.lambda$handle$2(Exercises.java:555)
  "incident-worker-2" stopped at …Ex15IncidentService.lambda$handle$2(Exercises.java:555)
  "incident-worker-3" stopped at …Ex15IncidentService.lambda$handle$2(Exercises.java:555)
----------------------------------------------
```

Three rules for this exercise:

1. **Diagnose before you edit.** Run D30, take a real dump with
   `jcmd <pid> Thread.print`, and write down your four answers. Then run D31 and
   mark yourself. Editing first and seeing what goes green teaches you nothing
   that transfers.
2. **Each defect comes from a different topic.** If you find yourself fixing two
   of them the same way, one of your diagnoses is wrong.
3. **Fix the stall first.** Two of the tests cannot even reach their real
   assertion while the service is wedged.

Reference implementation in `solutions/Solutions.java` — `Sol15IncidentService`.
Every other exercise in this lab is worth attempting before you open the
solution. This one is worth attempting *twice*.

---

## 🎯 RETRIEVAL GYM

*Closed book, and this is the final — the questions deliberately reach back
through the whole module. Answer aloud, THEN open. Miss one and reread that
topic, not just this page.*

<details><summary><b>Q.</b> A service is hung. <code>findDeadlockedThreads()</code> returns <code>null</code>. Name three different causes consistent with that, and the evidence that distinguishes them.</summary>

The detector follows cycles of lock **ownership**, so a `null` means "not that",
not "nothing wrong". Three families remain, and the stacks tell them apart.

**Pool exhaustion** (topics 5, 8): every worker `WAITING` in
`FutureTask.awaitDone`, because tasks are blocking on other tasks from the same
pool. Confirm with the pool's own gauges — `getActiveCount()` at the maximum and
`getQueue().size()` above zero, both frozen across two samples a second apart.

**A latch or barrier nobody will release** (topic 7): threads parked on
`CountDownLatch$Sync` or `CyclicBarrier`. Confirm by finding the thread that
owed the `countDown()` — it has terminated, or threw before reaching it, or was
never started.

**A queue nobody feeds** (topic 5): consumers parked on a `ConditionObject`
inside `take()`, queue depth zero and flat, and the producer thread is missing
from the dump entirely. That is D15's hang, one layer up.

A fourth, rarer: a `Semaphore` permit cycle — parked on `Semaphore$NonfairSync`
with permits never returned. Genuinely a deadlock, and no tool will ever say so.
</details>

<details><summary><b>Q.</b> <i>(Topics 1 and 4.)</i> You grep a dump for <code>BLOCKED</code> and find none. What have you actually ruled out?</summary>

Almost nothing. You have ruled out threads queued on **intrinsic
`synchronized` monitors**, which is the entirety of what `BLOCKED` means. Every
`java.util.concurrent` lock — `ReentrantLock`, `ReentrantReadWriteLock`,
`Semaphore`, `CountDownLatch` — parks through `LockSupport` inside
`AbstractQueuedSynchronizer` and reports `WAITING`.

So a full `ReentrantLock` deadlock, detected by the JVM, printed under "Found
one Java-level deadlock", has both of its threads showing `WAITING`. Measured in
both D11 and D30. Search a dump for `parking to wait for`, not for `BLOCKED`,
and read the type in the angle brackets — it names the mechanism.
</details>

<details><summary><b>Q.</b> <i>(Topics 4 and 5.)</i> A core is pinned and nothing is progressing. Distinguish a busy-wait from a livelock from a genuinely busy service.</summary>

All three show `RUNNABLE` threads and high CPU, so the state word is useless and
you need two more facts: how many threads, and whether a progress counter moves.

**Busy-wait**: usually one thread, `RUNNABLE`, `cpu=` ≈ `elapsed=` in the dump
header, stack sitting on a `poll()` loop, progress counter flat. All JFR
execution samples land on it — D30's reaper took 1,652 of 1,652.

**Livelock**: *several* threads, all `RUNNABLE`, all inside a `tryLock`
retry loop, all making no progress because they keep retrying in lockstep. The
dump looks *healthy*, which is what makes it the nastiest of the three. The
cause is a fixed backoff with no randomised jitter (topic 4, D11).

**Genuinely busy**: high CPU and the progress counter climbing. Check GC time
before you conclude anything, because a GC-thrashing JVM also pins cores and
also makes no application progress.

The general rule: CPU time tells you a thread is *running*; only a progress
counter tells you it is *working*. You need both.
</details>

<details><summary><b>Q.</b> <i>(Topic 6.)</i> Why is a <code>ThreadLocal</code> that is not removed a correctness bug on a pooled thread, and not merely a memory leak?</summary>

Because the thread outlives the task. A pool exists precisely to reuse threads,
so a value left in a `ThreadLocal` at the end of one task is visible to the next,
unrelated task that lands on that thread — and that next task belongs to a
different user.

Measured in D30 Act C: 200 requests that carried no tenant at all, and 200 of
them came back attributed to a real tenant, rotating through `tenant-1` to
`tenant-4` as the sweeps landed on different workers. Not "some memory was
retained" — a system sweep read another customer's books.

Two things are needed, and neither alone is sufficient: set the value
**unconditionally**, so a request with no tenant *overwrites* rather than
inherits; and `remove()` it in a **`finally`**, so nothing survives the task even
if the body throws. And note what no instrument can do here: a thread dump,
deadlock report and CPU profile of that JVM are all completely clean.
</details>

<details><summary><b>Q.</b> <i>(Topics 5 and 8.)</i> Why does a pool blocking on its own pool produce a silent, permanent stop rather than an error?</summary>

Because every individual step succeeds. `submit()` succeeds — the queue is
unbounded, so nothing is rejected and no `RejectedExecutionException` is thrown.
`Future.get()` succeeds at blocking, which is what it is for. Every worker is
doing exactly what it was told. There is simply nobody left to run the tasks
they are waiting for, because every thread that could is already waiting.

There is also no cycle of lock *ownership*, so no detector fires. A task sitting
in a queue is not "held" by anyone.

The evidence is: every worker `WAITING` in `FutureTask.awaitDone` from the same
line of your code, `active` at the pool maximum, `queued` above zero, and all
three numbers identical across two samples. The rule: **never block a pool
thread on work that can only be performed by that same pool.** If it must run
elsewhere, give it its own pool.

A bounded queue would have converted this silent stop into a loud
`RejectedExecutionException` — which is topic 5's three-way choice
(block / drop / grow) arriving one layer up, exactly as promised.
</details>

<details><summary><b>Q.</b> You have sixty seconds and a hung production JVM. What do you type, in what order, and why?</summary>

1. `jcmd <pid> Thread.print -l` — twice, thirty seconds apart. Two dumps, because
   one is a photograph and cannot tell "stuck" from "busy". The `-l` adds
   ownable synchronizers, so `ReentrantLock` holders are named.
2. Read the **deadlock section at the bottom first**. If it names threads, you
   have a lock-ordering bug — and check whether those threads explain the whole
   outage or only part of it (P1: in D30's dump, they explained two threads out
   of six that were wedged).
3. Grep for `parking to wait for` and read the **type in the angle brackets**.
   `FutureTask` means a pool waiting on itself. `CountDownLatch$Sync` means an
   uncounted latch. `ConditionObject` means an empty or full queue.
   `Semaphore$NonfairSync` means a permit nobody returned.
4. For each stuck thread, skip the `Unsafe.park` / `LockSupport` frames and find
   the first frame in **your** package. That is the line that decided to block.
5. Scan the header lines for a thread whose `cpu=` is close to its `elapsed=`.
   That is your spinner, and you did not need a profiler.
6. Check your pool gauges and a progress counter across both dumps. Frozen
   numbers plus zero CPU is a stop; climbing CPU plus frozen numbers is a spin
   or a livelock.

And if nothing is stuck at all but the answers are wrong, stop reading dumps —
no instrument is going to help. Compare request input with response output.
</details>

<details><summary><b>Q.</b> <i>(Topics 2, 3 and 5, interleaved.)</i> Four hangs from this module share the symptom "a loop that never exits". Name them and give the mechanism for each.</summary>

**D4** — a plain `boolean` stop flag. The JIT hoisted the non-volatile read out
of the loop, so the read never happened. Fix: `volatile`. Mechanism: visibility.

**D15 attempt 1** — a **`volatile`** stop flag with a consumer parked in
`take()`. The flag is perfectly visible and the fix from D4 is already applied.
The consumer is asleep inside `take()` on an empty queue, and a parked thread
executes nothing, so it re-checks nothing. Visible ≠ awake. Mechanism: parking.

**D30 Act B** — a worker blocked on a `Future` whose task is in the queue of the
pool it is blocking. Nothing is visible or invisible, awake or asleep; there is
simply no thread left to do the work. Mechanism: exhaustion.

**D30 Act D** — the inverse: a loop that never *stops*, because `poll()` returns
immediately and the loop spins. It exits fine; it just burns a core until it
does. Mechanism: the wrong verb.

Same symptom, four unrelated causes, and each needs a different instrument. That
is the whole thesis of this page.
</details>

---

## 🃏 FLASHCARDS

```
Thread dump, the two commands	jcmd <pid> Thread.print -l  ·  jstack <pid>. Take TWO, 30s apart
The frame you want in a dump	NEVER the top one — skip Unsafe.park/LockSupport, find the first frame in YOUR package
Dump header hidden gem	cpu= and elapsed= per thread. cpu≈elapsed = your spinner, no profiler needed
findDeadlockedThreads() null means	"not a lock-OWNERSHIP cycle" — NOT "no hang". Narrows, never clears
The two detectors differ by	findMonitorDeadlocked = monitors only; findDeadlocked = monitors + AQS. Divergence names the lock type
Measured detector divergence	sync cycle 2/2 · +ReentrantLock cycle 4/2 · +Semaphore cycle 4/2 (unchanged — invisible)
Invisible to every deadlock tool	Semaphore permits · CountDownLatch · Future.get · empty queue — no owner, no cycle
RUNNABLE lies twice	Includes native socket reads (idle) AND a thread pinning a core. Read frames + cpu=
BLOCKED means only	Intrinsic synchronized monitor. ReentrantLock deadlock says WAITING
Angle brackets name the mechanism	FutureTask=pool on itself · CountDownLatch$Sync=uncounted latch · ConditionObject=queue · Semaphore$NonfairSync=permits
Pool blocked on its own pool	Every worker WAITING in FutureTask.awaitDone; active==max, queued>0, both FROZEN over 2 samples
Why pool exhaustion is silent	Unbounded queue ⇒ nothing rejected, nothing thrown; no lock owner ⇒ no detector. It just stops
The pool rule	Never block a pool thread on work only that same pool can do. Needs elsewhere? Give it its OWN pool
ThreadLocal on a pooled thread	Thread outlives the task ⇒ next task inherits the last tenant. Measured 200/200 wrong
ThreadLocal fix needs BOTH	set() unconditionally (absent overwrites) AND remove() in a finally. Correctness, not hygiene
Wrong data vs hang	No dump/detector/profiler sees wrong data. Only comparing input to output. Runs for weeks unnoticed
100% CPU + flat progress counter	Working hard at nothing. EITHER number alone is ambiguous — you need both
Busy-wait vs livelock	One thread in a poll() loop  vs  SEVERAL in a tryLock retry with no jitter (dump looks healthy)
Thread leak, the three counters	live=is there a leak · totalStarted=still happening? · peak=compare after the fix
JFR ExecutionSample is a CPU sampler	Nails a spinner (1652/1652 samples on 2 reapers). Near-USELESS for a hang — nothing is running
Empty JFR profile during an outage	A positive result: nothing is executing ⇒ threads are stopped ⇒ take a dump
Name your threads	A ThreadFactory is 4 lines. "pool-3-thread-7" costs you 20 minutes you do not have
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| Layer | The same diagnosis |
|---|---|
| `ThreadPoolExecutor` ([`08-threadpool-internals.md`](08-threadpool-internals.md)) | `getActiveCount` / queue depth / completed count — export all three as metrics |
| Coordination primitives ([`07-coordination.md`](07-coordination.md)) | Latches and barriers deadlock **invisibly**; always prefer the timed `await(timeout, unit)` overload |
| Shared structures ([`06-shared-structures.md`](06-shared-structures.md)) | `ThreadLocal.remove()` on any pooled thread is a correctness requirement |
| Virtual threads ([`09-forkjoin-parallel-virtual-threads.md`](09-forkjoin-parallel-virtual-threads.md)) | A million virtual threads make `Thread.print` unusable; JDK 21 ships `jcmd <pid> Thread.dump_to_file -format=json <path>`, which emits machine-readable `threadContainers` you can query instead of reading |
| HikariCP | Connection-pool exhaustion is Act B exactly — `leakDetectionThreshold` prints the stack of the borrower that never returned |
| Spring MVC / Tomcat | Request threads are the `stress-*` of Act A: the ones that hang are the callers, not your pool |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)

**Interleaved pass (R4) — this page is the final, so pull the whole module
through it:**

1. *(Topics 1, 2, 5)* Three hangs, three mechanisms: a plain `boolean` flag, a
   `volatile` flag with a consumer in `take()`, and a pool blocked on its own
   `Future`. Explain each without using the word "deadlock", and say which
   instrument finds which.
2. *(Topics 3, 6)* Two "wrong data" bugs: a lost update under `count++`, and a
   `ThreadLocal` surviving into the next task. Both are invisible in a thread
   dump. What kind of test catches each, and why does neither reproduce on a
   developer laptop?
3. *(Topics 4, 7)* Write out, from memory, the list of things
   `findDeadlockedThreads()` **cannot** see. For each one, name the stack frame
   or lock type in a dump that would give it away instead.
4. *(Topics 5, 8)* Act B's pool stopped silently because its queue was
   unbounded. Describe what the same defect would have looked like with a
   bounded queue, and connect that to topic 5's three-way choice.
5. *(All)* Take D30's real dump excerpt in P1. Without rerunning anything,
   list every distinct bug visible in it and say which line of the dump is your
   evidence for each.
