# 🧵 Java Multithreading — Hands-On Lab
## Foundations Block (Topics 1–4)

> **Why this exists.** `../03-async-and-scheduling/01-thread-pools-completablefuture.md` covers Spring's async abstraction —
> `ThreadPoolTaskExecutor`, `@Async`, `CompletableFuture`. That is how to *use*
> a thread pool. This lab is the layer underneath: what a thread actually is,
> what the JVM guarantees about memory, and why the bugs happen. You need both,
> and interviews ask about this layer far more than people expect.

---

## 📦 Where the code lives

Everything runnable is in `concurrency-lab/` — a standalone Maven module,
**Java 21**, no Spring, no parent POM. `order-service` is untouched and stays
on Java 17.

```
concurrency-lab/
├── src/main/java/com/locallearn/concurrency/
│   ├── support/       Stress.java, Log.java — the harness that makes races reproduce
│   ├── t01threads/    D1–D3   lifecycle, join/daemon/exceptions, interruption
│   ├── t02visibility/ D4–D6   stale flags, reordering, safe publication
│   ├── t03atomicity/  D7–D9   lost updates, check-then-act, CAS & LongAdder
│   ├── t04locks/      D10–D12 synchronized vs Lock, deadlock, RWLock & Condition
│   ├── t05handoff/    D13–D15 unbounded backlog, the queue family, draining shutdown
│   ├── t06shared/     D16–D18 HashMap corruption, atomic map updates, copy vs confine
│   ├── t07coordination/ D19–D21 latch vs barrier vs semaphore, permit leaks, silent hangs
│   ├── t08pools/      D22–D24 pool growth order, rejection policies, sizing & lost exceptions
│   ├── t09parallel/   D25–D28 fork/join, parallel streams, virtual threads, structured concurrency
│   ├── t10diagnostics/ D29–D31 reading thread dumps, the incident, diagnosing it
│   ├── api/           Contracts.java — the 15 interfaces you implement
│   ├── exercises/     Exercises.java  ← YOUR WORK GOES HERE (all 15 are broken)
│   └── solutions/     Solutions.java  ← reference, with the reasoning
└── src/test/java/com/locallearn/concurrency/
    ├── contract/      one abstract test per exercise
    ├── ExerciseTests  runs every contract against YOUR code
    └── SolutionTests  runs the SAME contracts against the reference
```

---

## ▶️ How to run

```bash
cd concurrency-lab

# Run any demo (they print their own explanation as they go)
./mvnw -q compile
java -cp target/classes com.locallearn.concurrency.t03atomicity.D7_LostUpdates

# Work the exercises
./mvnw test -Dtest=SolutionTests        # sanity: all 15 must pass
./mvnw test -Dtest=ExerciseTests        # your work: all 15 fail on a fresh checkout
./mvnw test -Dtest='ExerciseTests$Ex1'  # one exercise at a time
```

---

## 📖 How to study a topic

Each topic doc now has the standard learning structure (see `docs/02-concurrency/README.md`):
**⚡ Core Card** (60-second essence — every revision pass starts here), the
teaching narrative with measured demo results, **🎯 Retrieval Gym** (questions
with hidden answers — answer aloud *before* opening), **🃏 Flashcards**, and a
**🗓 Revision Log** (1/3/7/21-day spaced passes). First learn: run the demo,
predict what it will print, *then* read. Revision: Core Card + Gym only (~6 min).

---

## 🗺️ The curriculum

| # | Doc | Demos | Exercise | The one thing to take away |
|---|---|---|---|---|
| 1 | [Threads, lifecycle & interruption](01-threads-lifecycle-interruption.md) | D1–D3 | Ex4 | Interruption is a **request**, not a kill |
| 2 | [The Java Memory Model](02-jmm-visibility-happens-before.md) | D4–D6 | Ex2 | Without a happens-before edge, **nothing** is guaranteed |
| 3 | [Races, atomicity & CAS](03-atomicity-races-cas.md) | D7–D9 | Ex1, Ex3 | `volatile` ≠ atomic. `count++` is three operations |
| 4 | [Locks, deadlock & conditions](04-locks-deadlock-conditions.md) | D10–D12 | Ex5, Ex6, Ex7 | Deadlock is never "two locks" — it's **two orders** |
| 5 | [Hand-off: blocking queues & backpressure](05-handoff-blocking-queues.md) | D13–D15 | Ex8 | A queue doesn't equalise speeds — it picks **block / drop / grow** |
| 6 | [Shared structures: ConcurrentHashMap, CopyOnWrite, ThreadLocal](06-shared-structures.md) | D16–D18 | Ex9, Ex10 | If the new value depends on the old one, it must be **one call** — `merge`, `compute`, `putIfAbsent` |
| 7 | [Coordination: latch, barrier, semaphore, phaser](07-coordination.md) | D19–D21 | Ex11, Ex12 | They're one **2×2** — *events or parties?* × *one-shot or reusable?* |
| 8 | [ThreadPoolExecutor internals](08-threadpool-internals.md) | D22–D24 | Ex13 | The **queue is tried before the pool grows** — that one inversion explains everything |
| 9 | [ForkJoin, parallel streams & virtual threads](09-forkjoin-parallel-virtual-threads.md) | D25–D28 | Ex14 | Ask **"what kind of work is this?"** before choosing anything |
| 10 | [Diagnostics & the capstone incident](10-diagnostics-incident.md) | D29–D31 | Ex15 | Every instrument has a **blind spot**, and that's where the expensive incidents live |

**Topic 10 is the final, and it is a simulation rather than a topic.** Every
other page names its mechanism in the title, so you always know which chapter
the fix comes from. Production hands you a symptom instead. D30 gives you a
broken service with four planted defects and no labels; D31 is the answer key.
Do not open it early.

---

## 🎯 The exercises

Each one starts broken, and each is a bug you watched happen in a demo.

| # | Class | Broken because | Demo | Read first |
|---|---|---|---|---|
| 1 | `Ex1Counter` | `value++` is read-modify-write | D7 | [T3 §`count++` is three operations](03-atomicity-races-cas.md) |
| 2 | `Ex2StopSignal` | plain `boolean` flag — the JIT hoists the read | D4 | [T2 §The one-word bug](02-jmm-visibility-happens-before.md) |
| 3 | `Ex3Inventory` | check-then-act with a quantity (no `decrementAndGet` shortcut) | D8 | [T3 §Check-then-act](03-atomicity-races-cas.md) |
| 4 | `Ex4Worker` | never polls the flag; swallows `InterruptedException` | D3 | [T1 §Interruption](01-threads-lifecycle-interruption.md) |
| 5 | `Ex5Bank` | lock order depends on the arguments → circular wait | D11 | [T4 §Deadlock](04-locks-deadlock-conditions.md) |
| 6 | `Ex6Cache` | cache stampede + a `HashMap` shared across threads | D12 | [T4 §The cache stampede](04-locks-deadlock-conditions.md) |
| 7 | `Ex7Queue` | no locking, no blocking, overwrites when full | D12 | [T4 §`Condition`](04-locks-deadlock-conditions.md) |
| 8 | `Ex8Pipeline` | unbounded backlog · `poll()` spin · stop-flag shutdown drops the queue | D13–D15 | [T5 §Exercise 8](05-handoff-blocking-queues.md) |
| 9 | `Ex9EventCounts` | `get`-then-`put` / `containsKey`-then-act on a `ConcurrentHashMap` — each call is atomic, the pair isn't | D17 | [T6 §Exercise 9](06-shared-structures.md) |
| 10 | `Ex10Context` | a plain `static` field shared by every thread, not thread-confined at all — and not restored on the exceptional path | D18 | [T6 §Exercise 10](06-shared-structures.md) |
| 11 | `Ex11Rounds` | reuses a `CountDownLatch` across rounds — round 2 has no barrier at all | D19 | [T7 §Exercise 11](07-coordination.md) |
| 12 | `Ex12Pool` | `tryAcquire()` never waits and the task runs regardless — and `release` isn't in a `finally`, so a throwing task leaks the permit | D20 | [T7 §Exercise 12](07-coordination.md) |
| 13 | `Ex13Pool` | submission rule inverted — grows to max *before* trying the queue | D22, D24 | [T8 §Exercise 13](08-threadpool-internals.md) |
| 14 | `Ex14Runner` | one executor strategy applied to both CPU-bound and IO-bound work | D25–D27 | [T9 §Exercise 14](09-forkjoin-parallel-virtual-threads.md) |
| 15 | `Ex15IncidentService` | **four** planted defects, one each from topics 4, 5+8, 6 and 5 — and you are not told which | D29–D31 | [T10 §Exercise 15](10-diagnostics-incident.md) |

**The tests are the point.** Each one runs 8–32 threads behind a start gate and
repeats the whole trial 20–200 times, because a single trial of broken code has
a real chance of passing. Ex7 additionally measures per-thread CPU time to
reject a busy-wait solution that would otherwise pass every other assertion.

Ex8 is the first one that **mostly works** when you open it — items really do
flow through and get processed. It carries three independent defects, each with
its own test, because that is how production systems are broken: not obviously,
but under a condition nobody tested.

Ex13 goes further still: one of its six tests **passes on the broken pool**, and
that is deliberate. A pool whose submission rule is inverted still runs every
task and still rejects when genuinely saturated — it just creates threads for
load a queue slot would have absorbed. Nothing about it looks wrong, which is
precisely how that bug reaches production.

`SolutionTests` runs the identical assertions against the reference code. If
those pass and yours don't, the harness is sound and the bug is yours.

---

## 📊 Measured on this machine (JDK 21, x86-64)

Real numbers from the demos, so you know what to expect:

| Demo | Result |
|---|---|
| D4 plain `boolean` stop flag | **never stops** — still spinning after 3s |
| D5 reordering (`r1==0 && r2==0`) | **92%** of 1M trials → **0%** with `volatile` |
| D7 `plain int` counter, 8×100k | lost **85%** of increments |
| D7 `volatile int` counter | lost **72%** — volatile is not a fix |
| D8 check-then-act | oversold in **13%** of trials, worst case **38 units** |
| D8 "atomic decrement only" half-fix | oversold in **2%** — *rarer, still broken* |
| D9 `LongAdder` vs `AtomicLong` @16 threads | 23ms vs 222ms (**~10×**) |
| D10 fair vs non-fair `ReentrantLock` | 4857ms vs 53ms (**~92× slower**) |
| D12 `ReadWriteLock` vs exclusive, 0ns reads | **0.34×** — RWLock *loses* |
| D12 same, 20µs reads @100% reads | **8.2×** — RWLock wins |
| D13 unbounded queue, fast producer | **1,000,000 items (~140 MB) backlogged in 419 ms** |
| D13 same load, `ArrayBlockingQueue(1024)` | backlog flat at 1,024 (~144 KB) |
| D14 `ArrayBlockingQueue` 1P/1C → 4P/4C | 289ms → **134ms** — *faster* with more threads (7/7 runs) |
| D15 `volatile` flag + `take()` shutdown | **never exits** — parked in `take()`, `alive=true` after 2s |
| D15 `interrupt()` shutdown | 8,744 of 10,000 processed — **1,256 abandoned** |
| D15 poison pill | **10,000 of 10,000** — exact |
| D16 `HashMap`, 8 threads × 5,000 distinct keys | lost **5.3%–31.9%** of 40,000 entries — or a worker never returns from `put()`, or `ClassCastException` |
| D17 `ConcurrentHashMap`, `get`-then-`put` | still loses **0.7%–13.3%** of 32,000 increments — the map didn't change, the call did |
| D17 `ConcurrentHashMap`, `merge(k, 1L, Long::sum)` | **exact, every trial** |
| D17 per-bin lock vs global lock, 8 cold keys, 200ms loader | `synchronized(map)` 1,621–1,652 ms vs `computeIfAbsent` 202–211 ms (**~8×**) |
| D18 `CopyOnWriteArrayList` write cost | 1.1 µs/element at 4,000 → **19.6 µs/element at 64,000** — O(n) per write, O(n²) to build |
| D18 `ThreadLocal` correlation id leaking onto pooled threads | tenant id from request N still visible at the start of request N+1 |
| D19 `CountDownLatch` reused across rounds | round 1: 6 workers arrived; rounds 2–5: **1 worker each** — no barrier at all |
| D19 one `countDown()` releasing 6 gated workers | all 6 finished **20–27 ms** later |
| D20 `tryAcquire()` shedding, 32 callers vs 4 permits | **88–90% shed** — countable rejections, not silent failure |
| D20 non-fair vs fair `Semaphore`, 8 threads × 50,000 acquire/release | 49–73 ms vs 4,813–5,284 ms (**67–104×**) |
| D21 4 exceptions against a 4-permit pool | pool driven to **0 available permits**, never recovers |
| D21 `tryAcquire(200ms)` on an exhausted semaphore | returns **false at ~203 ms** instead of parking forever |
| D22 pool core=2 max=10 queue=100, 60 tasks | **2 threads, 58 queued** — 8 permitted threads never created |
| D22 same pool, queue=4 (only change) | **10 threads**, 46 rejected |
| D22 `newFixedThreadPool(2)` | **1,000,002 tasks queued in 104 ms** — never rejects, fails as heap |
| D22 `newCachedThreadPool`, 1,000 tasks | **1,000 threads** |
| D23 Discard vs CallerRuns, 2,000 tasks | lost **1,990 in <1 ms** vs **0 in 668 ms** |
| D24 IO sizing: `cores+1` vs 48 threads | 12.5× vs **~42×** speedup |
| D25 `fork(); join();` per half | **0.5–0.7×** — *slower* than the plain loop (6/6 runs) |
| D26 blocking in the common pool | unrelated CPU stream **7.0–9.8× slower**, JVM-wide |
| D27 10,000 tasks × 100ms blocking | 12-thread pool **115/sec** vs virtual threads **63,700/sec** (~550×) |
| D27 virtual thread pinned by `synchronized` | **105–156× slower** than `ReentrantLock` (9 of 11 runs) |
| D29 deadlock detectors vs a `Semaphore` permit cycle | **neither detector sees it** — 2 threads parked forever, count unchanged |
| D30 pool blocked on its own pool | 0 of 8 requests completed, **no deadlock reported**, nothing thrown |
| D30 `ThreadLocal` left on pooled threads | **200 of 200** requests answered with another tenant's identity |
| D30 real dump of the wedged JVM | 30 threads, **15 permanently stuck**, JVM reported a deadlock covering **2** |
| D31 JFR during the outage | 1,652 of 1,652 CPU samples on the 2 spinners; **1** `JavaMonitorEnter` for 15 stuck threads |

Run them yourself; the numbers move but the conclusions don't.

---

## 🧱 How the modules are grouped

Not by API, but by **problem schema** — you index concurrency knowledge by "what
problem does this solve", and grouping by class name produces recognition without
recall. Each block's artifact feeds the next, so revision happens through *use*
rather than review.

| Block | Topics | The artifact it hands forward |
|---|---|---|
| **A — Foundations** | 1–4 | what a thread is, and why the bugs happen |
| **B — Moving work between threads** | 5–7 | Ex7's hand-built bounded queue, Ex8's pipeline |
| **C — Who runs the work** | 8–9 | Ex13 turns that pipeline into a real thread pool |
| **D — Capstone** | 10 | the incident, which needs all of it at once |

| # | Topic | Reuses | Status |
|---|---|---|---|
| 6 | Shared structures — `ConcurrentHashMap` (compute/merge), `CopyOnWriteArrayList`, `ThreadLocal` and its pool-leak hazard | check-then-act (D8) in new clothing | ✅ |
| 7 | Coordination — `CountDownLatch`, `CyclicBarrier`, `Semaphore`, `Phaser` as a 2×2, not four APIs | latch deadlocks foreshadowed in topic 4 | ✅ |
| 8 | `ThreadPoolExecutor` internals — core → **queue** → max → reject | Ex7's queue + Ex8's pipeline become a pool | ✅ |
| 9 | ForkJoin, parallel streams, **virtual threads**, structured concurrency | `ScopedValue` vs topic 6's `ThreadLocal` | ✅ |
| 10 | **Capstone incident** — diagnose a planted deadlock + pool exhaustion + ThreadLocal leak + a spinner from thread dumps alone (`jstack`, `jcmd`, JFR) | everything | ✅ |

Topic 10 is a simulation rather than a topic: you get a broken service and the
tools, and must work out *which* mechanism applies with no topic label to tell
you. That last step is the one interviews and production actually test, and the
one a topic-by-topic curriculum never trains.

---

## 🔗 Related existing docs

| Topic | Doc |
|---|---|
| `ThreadPoolTaskExecutor`, `@Async`, `CompletableFuture` | [../03-async-and-scheduling/01-thread-pools-completablefuture.md](../03-async-and-scheduling/01-thread-pools-completablefuture.md) |
| `@Scheduled`, scheduler pools, ShedLock | [../03-async-and-scheduling/02-scheduling-shedlock.md](../03-async-and-scheduling/02-scheduling-shedlock.md) |
| Optimistic vs pessimistic locking in JPA (`@Version`) | [../01-foundations/05-transactions-isolation-locking.md](../01-foundations/05-transactions-isolation-locking.md) |
| MDC propagation across threads | [../01-foundations/07-logging-mdc-correlation-ids.md](../01-foundations/07-logging-mdc-correlation-ids.md) |
