# Topic 8 — ThreadPoolExecutor Internals: Growth Order, Rejection, Sizing & Shutdown
**Demos:** `t08pools/D22`, `D23`, `D24` | **Exercise:** `Ex13MiniPool`

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** A thread pool has four numbers and they are **not** consulted
in the order you read them. For every submitted task the pool asks: *below core?
→ start a thread. Otherwise, **will the queue take it?** → queue it. Only if the
queue **refuses** → grow toward max. Only if that fails too → reject.* The queue
is tried **before** the pool is allowed to grow, because a queue slot is a
pointer and a thread is a megabyte. Every surprising thing a pool does follows
from that one inversion.

```
task → workers < core?     → NEW THREAD
     → queue.offer(task)?  → QUEUED            ← tried BEFORE growing
     → workers < max?      → NEW THREAD
     → else                → RejectedExecutionHandler

measured (D22, core=2 max=10 queue=100, 60 simultaneous tasks):
    poolSize=2   active=2   queued=58     ← 8 permitted threads never created
same pool, queue=4, the ONLY change:
    poolSize=10  queued=4   accepted=14   rejected=46
```

**The decision rule:** **size the queue first, because the queue is what decides
whether `maxPoolSize` exists at all.** A large queue buys latency tolerance and
makes max unreachable; a small queue buys concurrency and makes rejection real.
You cannot have both, and picking neither gives you the first one by accident.

**Five rules you must never get wrong:**
1. **Queue before growth.** With a large or unbounded queue, `maxPoolSize` is not a safety limit — it is unreachable code. You would need 103 concurrent tasks to create thread #3 above.
2. `newFixedThreadPool` and `newSingleThreadExecutor` have an **unbounded** queue: they can never reject, so they fail as heap instead. Measured: 1,000,000 tasks queued in 104 ms behind 2 workers, nothing refused.
3. `newCachedThreadPool` has **unbounded threads** — a `SynchronousQueue` (capacity zero, topic 5) guarantees the queue always refuses, so every task with no idle worker creates one. Measured: 1,000 tasks → 1,000 threads.
4. Sizing is one question, not two formulas: **what fraction of the task actually holds a core?** CPU-bound ≈ cores; IO-bound ≈ cores × (1 + wait/compute). Measured cost of confusing them: **12.5× vs 42×**.
5. `submit()` **swallows** the exception into a `Future` and tells nobody; `execute()` lets it reach the `UncaughtExceptionHandler` and kills the worker. Fire-and-forget `submit()` is a failure detector with the detector removed.

*Run first: D22 (the staircase, and two `Executors` factories failing), D23 (four
rejection policies, and the fastest producer is the one that lost everything),
D24 (two sizing sweeps, three shutdowns, one vanishing exception).*

---

## 🔮 PREDICT FIRST

*Answer aloud before opening. A wrong prediction you then correct is worth more
than three right ones — that is the whole point of this zone.*

<details>
<summary><b>P1.</b> Two pools, <code>core=2, max=10</code>, differing in one number: pool A has <code>queue=100</code>, pool B has <code>queue=4</code>. Both get the same burst of 60 simultaneous long tasks. Which pool <i>refuses</i> work, and which one runs more threads at once?</summary>

**B refuses 46 of the 60 — and B is also the one running 10 threads. A accepts
every task while running 2.** Almost everyone predicts the opposite, because a
bigger queue sounds like the more cautious setting.

Measured (D22, sections 1 and 2):

| | poolSize | queued | accepted | rejected |
|---|---|---|---|---|
| A — `queue=100` | **2** | 58 | 60 | 0 |
| B — `queue=4` | **10** | 4 | 14 | **46** |

The mechanism is the inverted order. In A the queue always has room, so step 2
always succeeds and step 3 — "grow toward max" — is never reached. Eight threads
the pool is explicitly permitted to create are never created, and `maxPoolSize=10`
is unreachable configuration: you would need 103 concurrent tasks to see thread
number three.

In B the queue fills after four tasks, so step 2 starts failing and the pool
finally grows. Its total capacity is `max + queue = 14`, and everything beyond
that is refused.

Now the part worth sitting with: **A did not handle the burst better, it just
failed to tell you about it.** A is holding 58 tasks in memory and running them
two at a time; B is running ten at a time and reporting the overload immediately.
"Accepted without complaint" and "coping" are different things, and only one of
them is visible in a metric.
</details>

<details>
<summary><b>P2.</b> You size a pool for tasks that spend 5 ms computing and 45 ms waiting on a database. The machine has 12 logical cores. You apply the CPU rule and use <code>cores + 1 = 13</code> threads. How much throughput did that cost you versus 48 threads?</summary>

**About 3.4×** — and the machine was ~90% idle the entire time.

Measured (D24, sweep 2 — 240 tasks × (5 ms CPU + 45 ms blocked), 5 runs):

| threads | wall | speed-up vs 1 thread |
|---|---|---|
| 12 | 1,073 ms | 11.8× |
| **13** (`cores + 1`) | **1,009 ms** | **12.5×** |
| 24 | 549 ms | 22.7× |
| **48** | **~300 ms** | **~42×** |
| 96 | 180–225 ms | 57–70× |

Those first four rows replicated in all 5 runs within ±3%.

The reasoning error is treating "thread" as "core". A thread blocked on a socket
**holds no core** — it costs about a megabyte of stack and nothing else. So the
question is never "how many threads can this machine run", it is "how many can
be *waiting* at once", and the answer is: far more than you have cores.

`cores × (1 + wait/compute)` is not a different formula from `cores + 1`; it is
the same formula, and `cores + 1` is the special case where `wait` is zero.
Here wait/compute is 45/5 = 9, predicting ~120 threads — and note the measurement
only confirms the *order of magnitude*: past about 96 threads the rows stop being
distinguishable from noise (see THE STORY §4). The formula tells you whether the
answer is 13 or 130. It does not tell you whether it is 96 or 120, and nothing
will, because that difference is not stable.
</details>

<details>
<summary><b>P3.</b> Two identical tasks that throw <code>IllegalStateException</code>. One goes to <code>pool.execute(task)</code>, the other to <code>pool.submit(task)</code> with the returned <code>Future</code> ignored, as fire-and-forget code always does. What appears in your logs for each?</summary>

**`execute` reports it. `submit` reports nothing, ever.** The intuition is
backwards because `submit` is the richer API — it returns a handle, so it feels
like the more careful choice.

Measured (D24, section 4):

| | what you see | the worker thread |
|---|---|---|
| `execute` | `UncaughtExceptionHandler` fires (default: stack trace on stderr) | **dies**, pool silently creates a replacement — observed `exc-worker-1` → `exc-worker-2` |
| `submit` | **absolutely nothing** | survives |

The mechanism is one class. `submit` wraps your task in a `FutureTask`, whose
`run()` **catches `Throwable` and stores it** as the future's outcome. Because it
was caught, it never reaches the thread's uncaught-exception handler, so no
handler runs and nothing is printed. The exception is not lost — it is *held*,
addressed exclusively to whoever calls `get()`. Ignore the future and the failure
is never delivered to anyone.

This is the JDK-level mechanism behind the Spring-level trap that an
`@Async void` method swallows its exceptions
([`../03-async-and-scheduling/01-thread-pools-completablefuture.md`](../03-async-and-scheduling/01-thread-pools-completablefuture.md) P2).
It is the same `FutureTask`, one layer down.

*If you predicted that `submit` is safer — good. That is the belief this page
exists to correct, and correcting it here is why you will remember it.*
</details>

---

## 📖 THE STORY

### 1. The four-step rule, and why it is in that order

Every task submitted to a `ThreadPoolExecutor` goes through exactly these steps,
in exactly this sequence:

| Step | Condition | Action |
|---|---|---|
| 1 | `workers < corePoolSize` | start a new worker for this task |
| 2 | `queue.offer(task)` succeeds | **queue it** |
| 3 | `workers < maximumPoolSize` | start a new worker for this task |
| 4 | otherwise | hand it to the `RejectedExecutionHandler` |

Step 2 sits before step 3, and that is the whole topic. It is not a historical
accident or a wart: **a queue slot is a pointer and a thread is roughly a
megabyte** of reserved stack plus a scheduler entity plus a context-switch
participant. The pool spends the cheap resource first and treats thread creation
as the last thing it does before refusing work outright.

The consequence, stated as plainly as possible: **the queue length sets your
latency and the pool size sets your throughput**, and the parameter names tell
you none of that.

**The staircase, measured (D22 §3 — `core=2, max=4, queue=3`, one task at a time):**

| submitted | poolSize | queued | what happened |
|---|---|---|---|
| 1 | 1 | 0 | NEW THREAD (below core) |
| 2 | 2 | 0 | NEW THREAD (below core) |
| 3 | 2 | 1 | **queued** — though the pool may grow to 4 |
| 4 | 2 | 2 | **queued** |
| 5 | 2 | 3 | **queued** — queue now full |
| 6 | 3 | 3 | NEW THREAD (queue refused) |
| 7 | 4 | 3 | NEW THREAD (at max now) |
| 8 | 4 | 3 | **REJECTED** |
| 9 | 4 | 3 | **REJECTED** |

Rows 3–5 are the ones to stare at. The pool was *allowed* two more threads and
had three tasks waiting, and it created neither, because the queue said yes.

> **The rule:** choose the queue capacity first. It determines whether
> `maxPoolSize` is a real limit or decoration.

### 2. The `Executors` factories are this rule with two numbers pinned

Every `Executors.newXxx` shortcut is a `ThreadPoolExecutor` with two of the four
numbers set to an extreme, and in each case the extreme is the dangerous end:

| Factory | core / max | queue | Failure mode |
|---|---|---|---|
| `newFixedThreadPool(n)` | n / n | `LinkedBlockingQueue()` — **unbounded** | never rejects → backlog → `OutOfMemoryError` |
| `newSingleThreadExecutor()` | 1 / 1 | `LinkedBlockingQueue()` — **unbounded** | same, serialised |
| `newCachedThreadPool()` | 0 / **`Integer.MAX_VALUE`** | `SynchronousQueue` — capacity **0** | never queues → unbounded threads |

**`newFixedThreadPool` measured (D22 §4):** 1,000,002 tasks submitted in **104 ms**
behind a pool of 2, with 1,000,000 sitting in the queue. Not one `submit` blocked
and not one task was refused. This is D13's unbounded backlog wearing an
`ExecutorService` costume — and it is worse here than in topic 5, because each
queued `Runnable` is a closure that pins everything it captured. A backlog of
requests holds every one of those requests' data live in the heap.

**`newCachedThreadPool` measured (D22 §5):** 1,000 concurrent tasks → `poolSize=1,000`,
one OS thread each. There is no number in its configuration that prevents this.

The `SynchronousQueue` detail is worth pausing on, because topic 5 built exactly
this mechanism. A zero-capacity queue means step 2 **always fails instantly**
unless a worker is already parked in `poll()` waiting for a hand-off. So
`newCachedThreadPool` is not "a pool that grows when busy" — it is a pool that is
*structurally incapable of queueing*, and therefore reaches step 3 on every task
that finds no idle worker. Zero capacity is not a limitation here; it is the
entire design.

### 3. Rejection is topic 5's trichotomy with JDK class names on it

Topic 5 established that when a producer outruns a consumer there are exactly
three possible responses and no fourth: **block**, **drop**, or **grow**. A thread
pool is a producer/consumer system, so the same three are all that is on offer.
The rejection policy is simply where you write down which one you picked.

| Policy | Trichotomy | Mechanism |
|---|---|---|
| `CallerRunsPolicy` | **BLOCK** | the submitting thread runs the task itself, so it cannot submit while busy |
| `AbortPolicy` (default) | **DROP**, loudly | `RejectedExecutionException` — the caller decides |
| `DiscardPolicy` | **DROP**, silently | the newest task vanishes |
| `DiscardOldestPolicy` | **DROP**, silently | evicts the queue **head**, then retries this task |
| *(an unbounded queue)* | **GROW** | needs no policy, because rejection never happens |

**Measured (D23 — `core=2, max=2, queue=8`, 2,000 tasks of ~1 ms each, one
producer submitting flat out):**

| Policy | ran on pool | ran on caller | rejected | **lost** | **producer wall** |
|---|---|---|---|---|---|
| `AbortPolicy` | 46 | 0 | 1,954 | 0 | 19 ms |
| **`CallerRunsPolicy`** | 1,336 | 664 | 0 | **0** | **668 ms** |
| `DiscardPolicy` | 10 | 0 | 0 | **1,990** | **0 ms** |
| `DiscardOldestPolicy` | 12 | 0 | 0 | **1,988** | 1 ms |

This is the most reproducible table on the page. Across 6 runs `DiscardPolicy`
ran exactly 10 tasks and lost exactly 1,990 **every time**; `DiscardOldestPolicy`
ran exactly 12 and lost 1,988 **every time**; `CallerRunsPolicy` landed within
±2 tasks and ±2 ms on every run. Only `AbortPolicy` moved at all (42–60 tasks
ran, 17–30 ms), because how many tasks squeeze through before the queue fills is
a genuine race.

Read the last two columns together, because that pairing is the entire lesson:
**the fastest producer is the one that lost everything.** `DiscardPolicy` finished
submitting 2,000 tasks in a single millisecond by throwing 1,990 of them away and
saying nothing. A dashboard measuring submission latency would show that
configuration as the healthiest in the fleet.

`CallerRunsPolicy` is the usual production default because it is the only row
that loses nothing *and* slows the source. The mechanism deserves stating
precisely, since "the caller runs it" sounds like a mere fallback: while the
submitting thread is executing a task, **it is not in its submit loop**, so the
arrival rate drops to zero for the duration. Saturation propagates backwards to
whoever generates the work. That is what backpressure means, and it is exactly
what a bounded queue's `put()` did one layer down in topic 5.

Its cost is equally concrete: in a web application the "caller" is a
request-handling thread, so pool saturation now blocks an HTTP worker. That is a
deliberate trade — a slow request instead of a lost one — and it is the wrong
trade when the caller is an event-loop thread that must never block.

And `DiscardOldestPolicy` drops the **oldest queued** task, which is right when
fresh data supersedes stale (a metrics tick, a price update, a repaint) and
catastrophic when queued items are distinct units of work. You will silently drop
the requests that have waited longest — the users who have been watching a
spinner the longest.

### 4. Sizing: one question, and a measurement that ends the argument

The question is **what fraction of this task's duration actually holds a core?**

- fraction ≈ 1 (CPU-bound) → threads ≈ cores (+1 to cover a page fault)
- fraction ≈ 0 (IO-bound) → threads ≈ cores × (1 + wait/compute)

**CPU-bound sweep (D24 §1 — 96 tasks × 20 ms of real arithmetic, 12 logical /
6 physical cores, median of 5 runs):**

| threads | 1 | 2 | 4 | 6 | 8 | 12 | 16 | 24 | 48 | 96 |
|---|---|---|---|---|---|---|---|---|---|---|
| speed-up | 1.0× | 2.0× | 3.9× | **5.3×** | 6.8× | 8.8× | 9.3× | 9.9× | 10.7× | 10.7× |

Near-linear up to the **physical** core count (5.3× at 6 threads), sub-linear
through the hyperthread count, then flat.

**Now the honest part, which is the reason this sweep is here at all.** The folk
claim is that oversubscribing CPU work makes it *slower* — that 96 threads on 12
cores should thrash. **Across 5 runs it did not happen.** 16 threads and 96
threads land in the same band, and the run-to-run spread (9.2×–12.1× at 96
threads) is wider than any gap between them. The measurement says *plateau*, not
*decline*, so that is what this page says.

The extra 80 threads are still a bad idea, but for the reasons the measurement
actually supports: ~80 MB of stacks, a longer latency tail, and a thread dump
nobody can read. Do not oversubscribe — and do not justify it with a slowdown
you have not measured.

**IO-bound sweep (D24 §2 — 240 tasks × (5 ms CPU + 45 ms blocked), 5 runs):**

| threads | 1 | 6 | 12 | 13 | 24 | 48 | 96 | 120 | 240 |
|---|---|---|---|---|---|---|---|---|---|
| speed-up | 1.0× | 5.9× | 11.8× | 12.5× | 22.7× | ~42× | 57–70× | 60–80× | 54–77× |

Everything up to 48 threads replicated in all 5 runs within ±3%. **From 96
threads onward it does not replicate** — 240 threads scored 54× in one run and
77× in another. The plateau is real; its exact location is noise.

That is worth more than a cleaner table would be. The formula predicted ~120
threads here, and the measurement can confirm the *order of magnitude* while
being completely unable to distinguish 96 from 120 from 240. Use the formula to
learn whether your answer is 13 or 130, then measure on your own hardware, then
stop tuning — because past a point you are fitting noise.

### 5. Stopping: drain, abandon, and the boolean nobody reads

This is D15's poison pill versus D15's interrupt, wearing the JDK's names.

| Call | Semantics | Measured (D24 §3, 1 worker, 50 queued tasks) |
|---|---|---|
| `shutdown()` | stop accepting; **run** everything queued; then terminate | ran **50/50** |
| `shutdownNow()` | stop accepting; **interrupt** the running; **return** the unstarted | ran **6/50**, returned **44 tasks** |
| `awaitTermination(t, u)` | block until terminated or deadline; **returns `false` on timeout** | `false` after 200 ms on a 3 s task |

Two things people miss.

**First, `shutdownNow` hands the backlog back.** That `List<Runnable>` is the JDK
making your data loss *explicit and countable* — requeue it, log it, or at
minimum count it. Most code discards the return value and thereby converts a
reported loss into a silent one.

**Second, `shutdownNow` cannot stop a task that ignores interruption.** It sets
the flag; that is all it can do. Measured: a task doing 1.5 s of pure arithmetic
with no blocking call and no `isInterrupted()` check was still running 400 ms
after `shutdownNow()` and finished after the full 1,501 ms, entirely on its own
terms. Topic 1 taught that interruption is a **request**, not a kill, and nothing
about a thread pool changes that.

`shutdown()` does **not** block, which is why the idiom is always the pair:

```java
pool.shutdown();
if (!pool.awaitTermination(30, TimeUnit.SECONDS)) {   // ← read this boolean
    List<Runnable> abandoned = pool.shutdownNow();    // ← and this list
    log.warn("forced shutdown, {} tasks abandoned", abandoned.size());
}
```

### 6. Where the exception went

Covered in P3; the summary is that `submit` and `execute` differ in the most
consequential way two near-identical methods can:

```java
// FutureTask.run(), paraphrased — this is the whole mechanism
try   { result = callable.call(); set(result); }
catch (Throwable ex) { setException(ex); }      // caught, stored, NOT rethrown
```

Because it is caught, the thread's `UncaughtExceptionHandler` never runs. Nothing
prints. The worker survives. The failure waits inside the `Future` for a `get()`
that fire-and-forget code never makes.

If you want fire-and-forget *and* visibility, you must choose one deliberately:
use `execute` and install an `UncaughtExceptionHandler` on the thread factory, or
keep `submit` and make something actually consume the futures.

---

## 🧪 EXERCISE 13 — `Ex13MiniPool`

```bash
cd concurrency-lab
./mvnw test -Dtest='ExerciseTests$Ex13'
```

**Build a working thread pool from scratch.** This is the payoff of the whole
curriculum, and you already own every part: worker threads (topic 1), a bounded
blocking queue that parks rather than spins (Ex7), and a draining shutdown via
poison pill (Ex8). The only new thing is the four-step submission rule — and
getting its *order* right is the exercise.

Four planted defects, five failing tests:

| Test | Fails because | Demo |
|---|---|---|
| `queuesBeforeGrowingPastCore` | grew to 8 threads with 42 tasks queued and 100 slots free | D22 |
| `growsToMaxOnlyWhenTheQueueIsFull` | pool had 6 threads after core+queue tasks; expected 2 | D22 |
| `gracefulShutdownDrainsEveryAcceptedTask` | submitted 2,000, only a few hundred ran (208 and 430 in two runs) — the rest abandoned | D15, D24 |
| `shutdownNowAbandonsAndReturnsTheUndoneTasks` | returned 0 tasks when ~499 were queued | D24 |
| `idleWorkersParkInsteadOfSpinning` | 4 idle workers burned ~1,900 ms of CPU in 1,000 ms of wall clock | D14 |

The sixth test (`rejectsWhenAtMaxWithAFullQueue`) **passes on the broken
version** — and that is deliberate. The broken pool does refuse work eventually,
which is exactly how a pool with an inverted submission rule survives code review
and reaches production: it is not obviously broken, it is *quietly the wrong
shape* under load.

Two design notes worth thinking about before you open the solution:

- A new worker must be created **with its first task**, not merely pointed at the
  queue. If the queue is full, that task has nowhere else to go.
  `ThreadPoolExecutor.addWorker` takes a `firstTask` parameter for this reason.
- Your two shutdowns need genuinely different mechanisms, because they mean
  different things. A poison pill drains; an interrupt abandons. A single
  `volatile` flag expresses neither — and worse, cannot even be *observed* by a
  worker parked in `take()` (topic 5, P3).

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, THEN open. Miss one → reread that section.*

<details><summary><b>Q.</b> Recite the four-step submission rule and name the trap it creates.</summary>

Below core → new thread. Otherwise → **try the queue**. Queue refuses → grow
toward max. At max with a full queue → rejection handler.

The trap: step 2 precedes step 3, so the pool grows past core **only when the
queue is full**. With a large or unbounded queue the queue never fills,
`maxPoolSize` is never consulted, and it becomes unreachable configuration that
reads like a safety limit. Measured: `core=2, max=10, queue=100` under 60
simultaneous tasks ran **2** threads with 58 queued.

The reason for the order: a queue slot is a pointer, a thread is ~1 MB plus a
scheduler entity. The pool spends the cheap resource first.
</details>

<details><summary><b>Q.</b> Why is <code>newFixedThreadPool</code> unsafe in production, and how does <code>newCachedThreadPool</code> fail differently?</summary>

`newFixedThreadPool` pairs a fixed thread count with an **unbounded**
`LinkedBlockingQueue`. It can therefore never reject and never grow: the gap
between arrival and service rate becomes heap. Measured: 1,000,000 tasks queued
in 104 ms behind 2 workers, nothing refused, nothing blocked. Each queued
`Runnable` is a closure pinning everything it captured, so the backlog holds
whole request graphs live.

`newCachedThreadPool` fails at the opposite extreme: `max = Integer.MAX_VALUE`
with a `SynchronousQueue` of capacity **zero**, so step 2 always fails and every
task without an idle worker creates a thread. Measured: 1,000 tasks → 1,000 OS
threads. One dies of memory held by queued data; the other dies of memory held by
thread stacks, or of a machine that spends its cores context-switching.
</details>

<details><summary><b>Q.</b> Map the four rejection policies onto topic 5's block/drop/grow, and say which loses the most work.</summary>

`CallerRunsPolicy` = **block** (the submitter runs the task, so it cannot
submit — backpressure by conscription). `AbortPolicy` = **drop**, loudly, by
handing the decision back as an exception. `DiscardPolicy` and
`DiscardOldestPolicy` = **drop**, silently, from opposite ends of the queue. An
unbounded queue = **grow**, and needs no policy because it never rejects.

Measured on 2,000 tasks: `DiscardPolicy` lost **1,990** and finished submitting
in **under a millisecond**; `CallerRunsPolicy` lost **0** and took **668 ms**.
The fastest producer is the one that threw everything away — which is why
submission latency is a dangerous health metric on its own.
</details>

<details><summary><b>Q.</b> Why is <code>CallerRunsPolicy</code> the usual production default, and when is it wrong?</summary>

It is the only policy that both loses nothing and slows the source. Mechanism:
while the submitting thread executes a rejected task it is not in its submit
loop, so the arrival rate drops to zero for that duration and saturation
propagates backwards to the work's origin.

It is wrong when the caller must not block — an event loop, a Netty IO thread, or
any single thread whose stall is a whole-system stall. In a web app it means a
saturated pool blocks an HTTP worker: a slow request instead of a lost one, which
is usually right, but is a trade you should make knowingly.
</details>

<details><summary><b>Q.</b> Size a pool for tasks that are 5 ms CPU and 45 ms blocked on 12 cores — and say how much the "cores + 1" answer costs.</summary>

wait/compute = 9, so `cores × (1 + 9)` ≈ 120 threads. Measured: 13 threads gave
**12.5×**, 48 gave **~42×** — so applying the CPU rule to IO work cost about
**3.4×** the throughput while leaving the machine ~90% idle.

The reasoning: a thread blocked on a socket holds no core, costing ~1 MB of stack
and nothing else. `cores + 1` is not a different formula — it is this formula with
`wait = 0`.

Caveat that matters: the sweep replicated cleanly up to 48 threads and **not at
all** past 96 (54×–77× at 240 threads across runs). The formula picks the order of
magnitude; it cannot pick between 96 and 240, and neither can the benchmark.
</details>

<details><summary><b>Q.</b> Did oversubscribing the CPU-bound pool make it slower? Answer from the measurement, not from theory.</summary>

**No — it plateaued.** 96 tasks of real arithmetic on 12 logical cores: 5.3× at 6
threads, 8.8× at 12, then flat at ~10.7× from 16 through 96 threads, with
run-to-run spread (9.2×–12.1× at 96) wider than the gaps between those rows.

The common claim that extra threads make CPU work *slower* through context
switching did not reproduce across 5 runs. Oversubscription is still wrong, but
for the costs actually observable: ~80 MB of stacks, worse tail latency, and an
unreadable thread dump. Quoting a throughput collapse you have not measured is
how a plausible story survives being false.
</details>

<details><summary><b>Q.</b> Distinguish <code>shutdown()</code>, <code>shutdownNow()</code> and <code>awaitTermination()</code> — including the two things people miss.</summary>

`shutdown()` stops accepting and **runs the queued work** (measured 50/50), then
terminates; it does not block. `shutdownNow()` stops accepting, **interrupts**
the running tasks, does not start the queued ones, and **returns them**
(measured: ran 6/50, returned 44). `awaitTermination` blocks until terminated or
the deadline.

Missed #1: `awaitTermination` returns a **boolean that is `false` on timeout**
(measured `false` after 200 ms on a 3 s task). Code that ignores it reports a
clean shutdown it did not achieve.

Missed #2: `shutdownNow` **cannot stop a task that ignores interruption**. A 1.5 s
pure-arithmetic task with no blocking call was still running 400 ms after
`shutdownNow()` and ran its full 1,501 ms. Interruption is a request (topic 1).
</details>

<details><summary><b>Q.</b> Where does a <code>submit()</code>ted task's exception go, and why?</summary>

Into the `Future`, and nowhere else. `submit` wraps the task in a `FutureTask`
whose `run()` catches `Throwable` and stores it via `setException`. Because it is
caught, the thread's `UncaughtExceptionHandler` never fires — nothing is printed
or logged, and the worker survives. The failure is delivered only to a caller of
`get()`.

`execute` lets the exception escape instead: the handler fires (default: stack
trace on stderr), the worker **dies**, and the pool silently creates a
replacement (measured `exc-worker-1` → `exc-worker-2`). So `execute` costs you a
thread and tells you; `submit` keeps the thread and tells nobody. This is the
same `FutureTask` that makes Spring's `@Async void` swallow exceptions.
</details>

---

## 🃏 FLASHCARDS

```
Submission rule	core → QUEUE → max → reject. Queue is tried BEFORE growth
Why queue before growth	A queue slot is a pointer; a thread is ~1MB + a scheduler entity
maxPoolSize is dead when	The queue is large — it never fills, so step 3 never runs
Measured: core=2 max=10 queue=100	60 tasks → poolSize 2, queued 58. 8 permitted threads never created
Same pool, queue=4	poolSize 10, accepted 14, REJECTED 46 — one number changed
Pool capacity	max + queueCapacity. Beyond that: the rejection handler
newFixedThreadPool danger	Unbounded LinkedBlockingQueue — 1,000,000 tasks queued in 104ms, no refusal
newCachedThreadPool danger	SynchronousQueue (cap 0) ⇒ always grows. 1,000 tasks → 1,000 threads
Rejection ≡ topic 5	CallerRuns=block · Abort/Discard*=drop · unbounded queue=grow
Measured rejection	Discard lost 1,990/2,000 in <1ms; CallerRuns lost 0 in 668ms (6/6 runs)
CallerRuns mechanism	Submitter runs the task ⇒ not in its submit loop ⇒ arrival rate → 0
DiscardOldest drops	The queue HEAD — the requests that already waited longest
Sizing, one question	What fraction of the task actually HOLDS a core?
CPU-bound measured	5.3x at 6 threads (6 physical cores), plateau ~10.7x. NOT slower — flat
IO-bound measured	13 threads 12.5x vs 48 threads ~42x — 'cores+1' on IO work costs 3.4x
Past ~96 threads	Did NOT replicate: 54x–77x at 240 across runs. Formula ⇒ magnitude only
shutdown() vs shutdownNow()	Drain (ran 50/50) vs abandon (ran 6/50, RETURNED 44 unstarted)
shutdownNow's return value	The abandoned tasks — data loss made countable. Do not discard it
awaitTermination returns	A boolean, FALSE on timeout. Almost nobody reads it
shutdownNow vs a CPU loop	Cannot stop it. 1.5s task ran all 1,501ms — interruption is a REQUEST
submit() exception	Caught by FutureTask, stored, NOT rethrown → silence until get()
execute() exception	UncaughtExceptionHandler fires, worker DIES, pool replaces it
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| Layer | The same idea |
|---|---|
| Topic 5 (queues) | block/drop/grow — the rejection policies are those three, named |
| Topic 5 (`SynchronousQueue`) | capacity 0 ⇒ `offer` always fails ⇒ `newCachedThreadPool` always grows |
| Topic 5 (poison pill) | `shutdown()` drains : `shutdownNow()` abandons |
| Topic 1 (interruption) | `shutdownNow` sets a flag; an uninterruptible task ignores it |
| Spring `ThreadPoolTaskExecutor` | the same four numbers, named `corePoolSize`/`queueCapacity`/`maxPoolSize` — [`../03-async-and-scheduling/01`](../03-async-and-scheduling/01-thread-pools-completablefuture.md) |
| Spring `@Async void` | the same `FutureTask` swallow, one layer up |
| HikariCP | a pool with the same saturation maths, for connections |
| Topic 9 | virtual threads remove the sizing problem for **IO** work — and only IO |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)

**Interleaved pass (R4) — pull these forward from earlier topics:**
1. *(Topic 5)* You proved there are exactly three answers when a producer outruns
   a consumer: block, drop, grow. Assign each of the four rejection policies to
   one of them, then explain why an **unbounded queue needs no rejection policy
   at all** — and why that is a symptom rather than a convenience.
2. *(Topic 1)* D3 taught that interruption is cooperative. Use that to explain why
   `shutdownNow()` failed to stop a 1.5 s arithmetic loop within 400 ms, and say
   what you would have to add to the task to make `shutdownNow()` work. Then
   contrast it with topic 5's parked consumer, where interruption works
   immediately but *loses the backlog*.
