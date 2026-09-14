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
│   ├── api/           Contracts.java — the 8 interfaces you implement
│   ├── exercises/     Exercises.java  ← YOUR WORK GOES HERE (all 8 are broken)
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
./mvnw test -Dtest=SolutionTests        # sanity: these must all pass (~11s)
./mvnw test -Dtest=ExerciseTests        # your work: all 7 fail on a fresh checkout
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
| 8 | [ThreadPoolExecutor internals](08-threadpool-internals.md) | D22–D24 | Ex13 | The **queue is tried before the pool grows** — that one inversion explains everything |
| 9 | [ForkJoin, parallel streams & virtual threads](09-forkjoin-parallel-virtual-threads.md) | D25–D28 | Ex14 | Ask **"what kind of work is this?"** before choosing anything |

*(Topics 6 and 7 — shared structures and coordination — are in progress and slot
in between 5 and 8.)*

---

## 🎯 The exercises

Each one starts broken, and each is a bug you watched happen in a demo.

| # | Class | Broken because | Demo |
|---|---|---|---|
| 1 | `Ex1Counter` | `value++` is read-modify-write | D7 |
| 2 | `Ex2StopSignal` | plain `boolean` flag — the JIT hoists the read | D4 |
| 3 | `Ex3Inventory` | check-then-act with a quantity (no `decrementAndGet` shortcut) | D8 |
| 4 | `Ex4Worker` | never polls the flag; swallows `InterruptedException` | D3 |
| 5 | `Ex5Bank` | lock order depends on the arguments → circular wait | D11 |
| 6 | `Ex6Cache` | cache stampede + a `HashMap` shared across threads | D12 |
| 7 | `Ex7Queue` | no locking, no blocking, overwrites when full | D12 |
| 8 | `Ex8Pipeline` | unbounded backlog · `poll()` spin · stop-flag shutdown drops the queue | D13–D15 |
| 13 | `Ex13Pool` | submission rule inverted — grows to max *before* trying the queue | D22, D24 |
| 14 | `Ex14Runner` | one executor strategy applied to both CPU-bound and IO-bound work | D25–D27 |

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
| 6 | Shared structures — `ConcurrentHashMap` (compute/merge), `CopyOnWriteArrayList`, `ThreadLocal` and its pool-leak hazard | check-then-act (D8) in new clothing | 🔄 in progress |
| 7 | Coordination — `CountDownLatch`, `CyclicBarrier`, `Semaphore`, `Phaser` as a 2×2, not four APIs | latch deadlocks foreshadowed in topic 4 | 🔄 in progress |
| 8 | `ThreadPoolExecutor` internals — core → **queue** → max → reject | Ex7's queue + Ex8's pipeline become a pool | ✅ |
| 9 | ForkJoin, parallel streams, **virtual threads**, structured concurrency | `ScopedValue` vs topic 6's `ThreadLocal` | ✅ |
| 10 | **Capstone incident** — diagnose a planted deadlock + pool exhaustion + ThreadLocal leak from thread dumps alone (`jstack`, `jcmd`, JFR) | everything | 🔄 in progress |

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
