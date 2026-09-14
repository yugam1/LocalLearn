# Topic 9 — ForkJoin, Parallel Streams, Virtual Threads & Structured Concurrency
**Demos:** `t09parallel/D25`, `D26`, `D27`, `D28` | **Exercise:** `Ex14WorkloadRunner`

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** This entire topic is one question asked before you choose
anything: **what kind of work is this?** There are three answers, each with a
different right tool, and every mistake on this page is the answer to one
question being applied to another.

```
                                  what kind of work is this?
                                              |
   CPU-bound, splits recursively  ────────────┼──── ForkJoinPool / parallel stream
   (sum a tree, sort, scan)                   |     work-stealing deques, cores-1
                                              |
   CPU-bound, independent tasks   ────────────┼──── bounded platform pool (topic 8)
   (render N images)                          |     because cores are the scarce thing
                                              |
   IO-bound, lots of it           ────────────┴──── ONE VIRTUAL THREAD PER TASK
   (call 10,000 services)                           a blocked one holds no OS thread

measured (D27 §2, 10,000 tasks each blocked 100ms):
    platform pool of 12      115 tasks/sec        ← topic 8's whole sizing problem
    platform pool of 1000  8,482 tasks/sec
    virtual thread per task 63,694 tasks/sec      ← ~550x the cores-sized pool
```

**The decision rule:** **if it blocks, it is not CPU work — get it off the
ForkJoin pools entirely.** Blocking inside a parallel stream starves a JVM-wide
resource; blocking inside `synchronized` on a virtual thread pins a carrier.
Both are measured below, and both are invisible in the source code.

**Five rules you must never get wrong:**
1. `fork()` immediately followed by `join()` on the same task **runs the halves sequentially**. Measured: 0.5–0.7× — *slower than the plain loop it replaced*, in 6 of 6 runs. The rule is `right.fork(); left.compute(); right.join();`.
2. `parallelStream()` runs on `ForkJoinPool.commonPool()` — **one pool for the entire JVM**, `cores - 1` workers, shared with every library you depend on and with any `CompletableFuture` that omits an executor. There is no bulkhead.
3. Blocking in a parallel stream is a **process-wide** slowdown, not a local one. Measured: an unrelated CPU-only parallel stream went **7.5× slower** while 44 tasks blocked elsewhere in the JVM.
4. Virtual threads make **blocking** cheap, not computing fast. ~2.3 µs to create one versus ~120 µs for a platform thread, and a blocked one consumes no OS thread at all.
5. On Java 21, `synchronized` **pins** a virtual thread to its carrier; `ReentrantLock` does not. Measured with **zero contention**: 110 ms → 14,400–17,400 ms. **Never hold a monitor across a blocking call.**

*Run first: D27 (the 550× scale difference, then the pinning collapse — the most
important measurement on this page), D26 (common-pool starvation), D25 (the
ordering rule), D28 (what structured concurrency is for).*

---

## 🔮 PREDICT FIRST

*Answer aloud before opening. A wrong prediction you then correct is worth more
than three right ones — that is the whole point of this zone.*

<details>
<summary><b>P1.</b> A recursive sum, split in half at each level. Version A does <code>left.fork(); left.join(); right.fork(); right.join();</code>. Version B is a plain single-threaded <code>for</code> loop over the same range. Which is faster on a 12-core machine?</summary>

**B, the single-threaded loop — by roughly 2×.** Measured (D25 §1, 6 runs):

| | wall | vs plain loop |
|---|---|---|
| plain `for` loop | 83 ms | baseline |
| **A** — `fork(); join(); fork(); join();` | **115–180 ms** | **0.5–0.7×** |
| `fork(); fork(); join(); join();` | 12–18 ms | 4.6–7.0× |
| `right.fork(); left.compute(); right.join();` | 11–15 ms | 5.5–7.6× |

A was slower than the sequential loop in **all 6 runs**, never once faster.

The mechanism: `join()` blocks until that task is done. Putting it immediately
after `fork()` means the right half is *not even submitted* until the left half
has completely finished. The two halves therefore run strictly one after the
other — the program is sequential. You have paid for task allocation, deque
operations and the join tree, and bought nothing. It looks parallel because the
word `fork` is present, and the compiler has no opinion about it.

The fix is to keep the current thread working instead of waiting: fork *one*
half, compute the *other* half yourself, then join. Note the honest detail — the
gap between "fork both" and the rule was small and noisy here (12–18 ms vs
11–15 ms). The rule is still right, because forking both leaves the current
thread doing nothing but waiting at every node in the tree, and that cost grows
with the tree. But the result you should carry away with confidence is the first
one: **`fork(); join();` per half is catastrophic, and the other two are both
fine.**
</details>

<details>
<summary><b>P2.</b> Somewhere in your JVM — in a library you did not write, on data you have never seen — a <code>parallelStream()</code> does a blocking HTTP call per element. Elsewhere, your own parallel stream does nothing but arithmetic on its own private array. What happens to <i>your</i> stream?</summary>

**It slows down by about 7.5×, and nothing in your code or your data is
involved.** Measured (D26 §4, 6 runs):

| | wall | |
|---|---|---|
| your stream, quiet JVM | ~32,000 µs | baseline |
| your stream, while 44 tasks block elsewhere | ~242,000 µs | **7.0–9.8× slower** |
| after those tasks finish | ~33,000 µs | fully recovered |

The ratio landed between 7.0× and 9.8× in all 6 runs, and recovery was complete
every time.

The mechanism is that `.parallel()` does not create a pool — it **submits to
`ForkJoinPool.commonPool()`**, which is a single JVM-wide pool with `cores - 1`
workers. A worker parked on a socket is a worker that is not stealing. Your
stream is queued behind blocking tasks it shares nothing with except a scheduler.

Topic 8 taught bulkheads — one pool per concern, so a hung dependency saturates
only its own pool. `commonPool` is the one pool in the JVM you **cannot**
bulkhead, and `.parallel()` opts you into it in eight characters.

Note also what it does *not* do: it does not deadlock. `ForkJoinPool` compensates
by starting extra workers, so this is a gradual degradation you will debug at 3am
rather than a clean failure staging would have caught.
</details>

<details>
<summary><b>P3.</b> 2,000 tasks, each blocking for 100 ms, on virtual threads. Each task takes a lock — <b>its own private lock object</b>, so no two tasks ever contend. Version A wraps the blocking call in <code>synchronized (myLock)</code>; version B uses <code>myLock.lock()</code> on a <code>ReentrantLock</code>. Same logic, zero contention. How much slower is A?</summary>

**Between 105× and 156× slower.** Measured (D27 §3):

| blocking call wrapped in | wall | vs unwrapped |
|---|---|---|
| nothing | 112–139 ms | baseline |
| `ReentrantLock` (uncontended) | 107–139 ms | **0.8–1.0×** — free |
| **`synchronized` (uncontended)** | **14,400–17,400 ms** | **105–156×** |

There is no contention anywhere in this experiment. Every task locks an object
only it can see, so mutual exclusion is not the explanation — and that is the
whole point of constructing it this way.

The mechanism is **pinning**. A virtual thread blocks cheaply because the JVM can
*unmount* it: copy its stack frames to the heap and release the carrier (a real
platform thread) to run something else. In Java 21 it cannot do that while a
`synchronized` monitor is held, because the monitor is owned by the *carrier*.
So the virtual thread is pinned, and when it blocks it blocks a real OS thread
for the full 100 ms. With ~12 carriers, 2,000 tasks queue up behind them and
throughput falls back to roughly what a 12-thread platform pool would give you.

`ReentrantLock` is written in Java on top of `LockSupport.park`, which the JVM
understands, so it unmounts cleanly and costs nothing.

**And now the part a clean table would have hidden.** Across 11 runs this was
**bimodal**: 9 runs landed at 14.4–17.4 s (105–156×), and 2 runs landed at
~1.04 s (~9.5×). The JVM *may* compensate for a blocked carrier by starting
another, up to `jdk.virtualThreadScheduler.maxPoolSize` (default 256) — and
whether it manages to is not under your control. That variability is itself the
lesson: a pinning bug is 10× slow today and 150× slow tomorrow, on the same
build, which is exactly why it is so hard to diagnose from a latency graph.

*Find them with `-Djdk.tracePinnedThreads=full`. Audit every blocking call for a
surrounding `synchronized` before you run virtual threads in production.*
</details>

---

## 📖 THE STORY

### 1. `ForkJoinPool`: a different shape of pool, for a different shape of work

Topic 8's `ThreadPoolExecutor` has **one** queue that every worker takes from.
That is right when tasks are independent and arrive from outside the system. A
divide-and-conquer computation is neither: tasks are created *by other tasks*,
mostly consumed by the thread that made them, and there are a great many of them.
One shared queue would make every split a contended lock acquisition.

So `ForkJoinPool` gives each worker **its own double-ended queue**:

- A worker pushes and pops its own sub-tasks at the **head** — no lock, no
  contention in the common case, and the newest sub-task is the one whose data is
  still warm in cache.
- A worker that runs dry **steals** from the **tail** of another worker's deque:
  the *oldest* task there, which is the *biggest* remaining chunk. One steal buys
  a lot of work, so steals stay rare.

Measured (D25 §3): an unbalanced tree across 8 workers completed with **43–61
steals** out of ~511 tasks created. Rare, exactly as designed — and enough to
keep every worker busy on a tree where the early leaves are 256× heavier than the
late ones. Nobody scheduled that; it fell out of the deque discipline.

**The ordering rule** (see P1) and **the leaf threshold** are the two things you
tune. Measured (D25 §2, ~33.5 M leaf units):

| leaf threshold | 16 | 256 | 4,096 | 65,536 | 4.2 M | 33.5 M (one leaf) |
|---|---|---|---|---|---|---|
| wall | 67 ms | 13 ms | 13 ms | 12 ms | 13 ms | 80 ms |
| tasks created | 4,194,303 | 262,143 | 16,383 | 1,023 | 15 | 1 |

Both ends are bad and the middle is a broad plateau. Too small and you spend the
run allocating task objects and touching deques (67 ms for 4.2 M tasks). Too
large and there is nothing to steal, so one core does everything (80 ms — the
sequential time). Anywhere across four orders of magnitude in between is fine,
which is the useful news: **the threshold needs to be roughly right, not
precisely right.**

### 2. Parallel streams are a `ForkJoinPool` with the fork/join hidden

Which means every rule above still applies, plus one hazard that is invisible in
the source. `.parallel()` splits the source with a `Spliterator` and submits the
pieces to `commonPool()`. Three consequences:

**It must have enough total work.** Measured (D26 §1, 6 runs):

| elements | work/element | sequential | parallel | speed-up |
|---|---|---|---|---|
| 100 | trivial | ~85 µs | ~300 µs | **0.18–0.40× (always loses)** |
| 100 | 1,000 iters | ~650 µs | — | **0.13–2.46× — pure noise** |
| 10,000 | trivial | ~400 µs | ~1,000 µs | 0.30–1.03× (lost in 5 of 6) |
| 10,000 | 1,000 iters | ~26,000 µs | ~4,000 µs | **5.7–7.1×** |
| 1,000,000 | trivial | ~5,200 µs | ~1,500 µs | 2.9–4.5× |
| 1,000,000 | 1,000 iters | ~2,400,000 µs | ~320,000 µs | **7.2–8.8×** |

Two honest notes. The **100 elements × 1,000 iterations** row is *noise* — it
ranged from 0.13× to 2.46× across six runs and should not be used to conclude
anything; at that size the measurement is dominated by whatever else the JVM was
doing. And the "10,000 elements" rule of thumb is not a threshold on *element
count* at all: 1,000,000 trivial elements won, and 100 expensive ones could not
be measured. What matters is **N × cost-per-element**, total.

**The source must split cheaply.** Measured (D26 §3, 1 M elements):

| source | sequential | parallel | speed-up |
|---|---|---|---|
| `ArrayList` | ~71,000 µs | ~11,500 µs | **5.4–9.2×** |
| `LinkedList` | ~69,000 µs | ~14,900 µs | 3.9–5.5× |

`ArrayList` splits by halving an index range: O(1) and perfectly balanced.
`LinkedList` has no index, so its spliterator must *walk* the list to split it —
the splitting traverses the same nodes the computation does.

Honest calibration: `ArrayList` won all 6 runs, but by a **modest** margin
(roughly 1.15–1.9×), and the parallel `LinkedList` still managed ~4.7×. The
textbook implies catastrophe; the measurement says consistent-but-moderate
penalty. Part of the reason is that this `LinkedList` was built in one pass from
an `ArrayList`, so its nodes happen to sit contiguously in memory and traverse
far better than a list assembled over an application's lifetime would.

**Boxing costs more than the parallelism saves.** Measured (D26 §2, 5 M elements):

| | sequential | parallel |
|---|---|---|
| `LongStream` (primitives) | ~2,100 µs | ~700 µs |
| `Stream<Long>` (boxed) | ~74,000 µs | ~13,000 µs |

Roughly **35× slower sequentially and 19× in parallel**. A boxed stream carries
pointers to heap objects, so every element is an allocation and a pointer chase —
and the pointer chase destroys exactly the cache locality that made splitting
worth doing. Parallelising a boxed stream is applying a 7× win on top of a 35×
loss. Use `IntStream` / `LongStream` / `DoubleStream`.

**And the trap: `commonPool` is JVM-wide** (see P2, measured 7.0–9.8× starvation).
If the work blocks, it is not divide-and-conquer CPU work at all — it is IO work,
and IO work belongs on virtual threads.

### 3. Virtual threads: what they actually are

A platform thread is a thin wrapper over an OS thread: ~1 MB of reserved stack,
scheduled by the kernel, expensive enough that pooling it was the entire point of
topic 8. A **virtual thread** is a continuation plus a scheduler, both inside the
JVM. Its stack lives on the heap and starts at a few hundred bytes.

The mechanism that matters: when a virtual thread makes a blocking JDK call, the
JVM **unmounts** it — copies its stack frames to the heap and frees the
**carrier** (a real platform thread from a `ForkJoinPool` sized to the core count)
to run a different virtual thread. When the call returns, the continuation is
**mounted** again, possibly on a different carrier. **A blocked virtual thread
consumes no OS thread.**

That single fact inverts topic 8's economics. Pools exist because threads are
scarce; virtual threads are not scarce, so **you do not pool them**.
`Executors.newVirtualThreadPerTaskExecutor()` is not a pool despite the name — it
is a thread factory wearing an `ExecutorService` interface, creating one new
virtual thread per task.

**Creation cost (D27 §1):** 100,000 virtual threads created, run and finished in
~230 ms — about **2.3 µs each**, against ~120 µs for a platform thread. Roughly
**50× cheaper**, and that is before counting the megabyte of stack you did not
reserve.

**Scale under blocking (D27 §2)** — the headline table:

| executor | tasks | wall | tasks/sec |
|---|---|---|---|
| platform pool of 12 | 1,200 | ~10,450 ms | **115** |
| platform pool of 200 | 10,000 | ~5,225 ms | 1,915 |
| platform pool of 1,000 | 10,000 | ~1,150 ms | 8,700 |
| **virtual thread per task** | **10,000** | **~157 ms** | **63,700** |

All four rows replicated within a few percent across 6 runs.

Read the platform rows as a group: the **only** way to raise a blocking pool's
throughput is to add threads, and threads are precisely the resource you cannot
add many of. That ceiling is what topic 8's entire sizing exercise was
negotiating with. Virtual threads remove the ceiling rather than raising it.

**And what they do not do (D27 §4).** Pure CPU work, no blocking: across 5 runs
the virtual executor was equal or faster (platform 186–860 ms, virtual
101–268 ms). That is **not** an unmounting benefit — nothing blocks, so nothing
unmounts. It is the scheduler shape: a fixed pool funnels every task through one
shared queue, while the virtual scheduler is a `ForkJoinPool` with per-worker
deques (§1). The negative conclusion is the one that survives: **virtualisation
itself buys CPU-bound work nothing, because there is no blocking to make cheap.**
Choose a bounded pool for CPU work because you want concurrency bounded on
purpose — not because it is faster, since on this machine it was not.

### 4. Pinning — the highest-value thing on this page

Covered in P3 with the measurement (105–156× with zero contention, bimodal across
11 runs). The practical rules:

```java
// PINS the carrier for the whole blocking call — Java 21
synchronized (lock) { socket.read(); }

// Unmounts cleanly. Same mutual exclusion, no pinning.
lock.lock();
try { socket.read(); } finally { lock.unlock(); }

// Better still: do not hold any lock across a blocking call.
byte[] data = socket.read();
lock.lock();
try { cache.put(key, data); } finally { lock.unlock(); }
```

The third form was always the right advice — holding a lock across IO has ruined
throughput since long before virtual threads existed. Virtual threads simply make
the penalty an order of magnitude larger and much harder to spot, because the
lock may have no contention at all.

This is a **Java 21 limitation, not a permanent property**. JEP 491 (Java 24)
lets `synchronized` unmount. On 21 — this lab's target, and most production
deployments today — it pins.

### 5. Structured concurrency: giving a thread of control a scope

**These are preview APIs in Java 21.** `StructuredTaskScope` (JEP 453) and
`ScopedValue` (JEP 446) do not compile without `--enable-preview`, and that flag
marks *every* class file in a module as preview — which would then require the
flag at runtime for every demo and test in topics 1–9 and pin this lab to exactly
JDK 21. **This module therefore does not enable preview**, and D28 is honest
about the split: sections 1–2 run and are measured; sections 3–4 are a code
walkthrough whose output was captured from really running the snippet with
`--enable-preview`, with the reproduction commands printed alongside.

**The problem, measured (D28 §1).** Two subtasks: one fails after ~5 ms, the
other takes 2,000 ms. Collect results with `slow.get(); failing.get();`:

| | measured |
|---|---|
| when the failure actually happened | ~5 ms |
| when the caller found out | **2,033 ms** |
| did the doomed sibling run to completion? | **yes** |

Two seconds of a thread, a connection and a database's attention were spent
producing a value discarded the instant it arrived — and the caller was blocked
the whole time in `get()` on a *different* future. Worse, nothing forced that
executor to be shut down: had the method returned early, both tasks would still
be running, owned by nobody and absent from every stack trace.

**The hand-rolled fix (D28 §2)** reacts in completion order and cancels siblings
explicitly: failure reported in **22 ms** instead of 2,033, sibling cancelled. It
is about twenty lines of polling, is-done scanning, explicit cancellation and a
`finally` — none of it hard, all of it required at *every* fan-out site, and
invisible by omission at the next one somebody adds.

**What `StructuredTaskScope` does instead:**

```java
try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
    Subtask<String> profile = scope.fork(() -> slow("profile", 2000));
    Subtask<String> pay     = scope.fork(() -> { throw new ISE("payments down"); });

    scope.join()            // wait for ALL forks, or the first failure
         .throwIfFailed();  // rethrow that failure here, in the caller

    return profile.get() + pay.get();
}   // close() GUARANTEES both forks have ended before we leave the block
```

Real captured output (`javac --release 21 --enable-preview` then
`java --enable-preview`):

```
slow-2000ms CANCELLED after sibling failure
scope threw: payments down after 6ms  <- NOT 2000ms
```

Three properties, none of them optional: `join()` returns on the **first
failure** rather than in submission order; a failure **cancels the siblings**;
and `close()` cannot be skipped, so **a fork cannot outlive the block**.

That last one is the actual definition. Ordinary code has always had this
property — a method returns before its caller continues, and its locals die with
it. Concurrency broke it: submitting to an executor creates a thread of control
whose lifetime has no relationship to the block that created it. Structured
concurrency restores the scope, which is why you also get a stack trace showing
the real parent.

### 6. `ScopedValue` versus `ThreadLocal`

Topic 6 covered `ThreadLocal` and the three ways it hurts: it is mutable from
anywhere, it must be cleaned up by hand or it leaks on a pooled thread, and
inheriting it into child threads copies the whole map. Virtual threads make the
last two much worse — `ThreadLocal` was tolerable partly *because* threads were
few, and a million virtual threads means a million maps.

```java
static final ScopedValue<String> USER = ScopedValue.newInstance();

ScopedValue.where(USER, "alice").run(() -> {
    //  USER.get() == "alice" here, in every method called from here, and in
    //  every task forked from a StructuredTaskScope inside here -- no copying.
});
//  USER.isBound() == false again. No cleanup. No leak. No finally.
```

Real captured output: `profile finished (user=alice)` — the binding was inherited
by the forked subtask — and `USER bound outside scope? false`.

| | `ThreadLocal` | `ScopedValue` |
|---|---|---|
| mutability | `set()` from anywhere | immutable once bound |
| lifetime | until `remove()` — or a leak | the dynamic scope, always |
| cleanup | your `finally` block | automatic, cannot be skipped |
| inheritance | copies the map | shares immutable bindings |
| cost at 1 M threads | 1 M maps | a few words per binding |

Every row follows from one design choice: a `ScopedValue` binding is
**immutable** and lives on the stack of the block that established it. Because it
cannot change, children can share it instead of copying it; because it is tied to
a block, it is unbound when the block exits no matter how the block exits.
`ThreadLocal` is a mutable map keyed by thread, and every difference above is a
consequence of that.

---

## 🧪 EXERCISE 14 — `Ex14WorkloadRunner`

```bash
cd concurrency-lab
./mvnw test -Dtest='ExerciseTests$Ex14'
```

One runner, two workloads, **and no single strategy fits both.** The starting
code is written the way a reasonable person writes it the first time: one
executor sized to the core count, shared by both methods, with a lock to keep a
shared result list safe. Every individual decision is defensible; together they
are wrong.

| Test | Fails because | Demo |
|---|---|---|
| `cpuBoundWorkRunsInParallel` | 48 tasks took the same wall time in parallel as sequentially — **1.0× speed-up** (352 vs 353 ms, and 459 vs 462 ms on a second run) | D25 |
| `ioBoundWorkAchievesHighConcurrency` | **at most 1** of 400 blocking tasks was ever in flight at once | D27 |

The IO test also takes **41 seconds to fail**, which is not an accident worth
fixing — 400 tasks × 100 ms, fully serialised, is what the bug *is*. Sit through
it once.

Two defects to find:

1. **One strategy for two kinds of work.** A cores-sized platform pool is right
   for CPU work and catastrophic for IO work. D27 measured that exact shape:
   115 tasks/sec versus 63,700.
2. **The lock is held across the task itself.** `synchronized` around
   `task.call()` serialises everything, so neither workload gets any parallelism.
   And once you move the IO path to virtual threads it earns a *second*, subtler
   penalty — a virtual thread that blocks inside `synchronized` is **pinned**.

The hint worth taking: that lock exists only to protect a list. There are ways to
collect results in order that need no lock at all — and then nothing is held
across a blocking call, which was the right answer before virtual threads existed
and is now the difference between 100 ms and 17 seconds.

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, THEN open. Miss one → reread that section.*

<details><summary><b>Q.</b> State the fork/join ordering rule, and say what actually goes wrong with <code>fork(); join();</code> on each half.</summary>

The rule: `right.fork(); long l = left.compute(); long r = right.join();` — give
away one half, do the other half on the current thread, then collect.

`left.fork(); left.join(); right.fork(); right.join();` **has no parallelism at
all.** `join()` blocks until that task completes, so the right half is not even
submitted until the left has finished; the halves run strictly in sequence. You
pay for task allocation, deque operations and the join tree and get nothing.
Measured: **0.5–0.7× versus a plain `for` loop** — slower than no parallelism —
in 6 of 6 runs.

`left.fork(); right.fork(); left.join() + right.join();` is genuinely parallel
and acceptable, but the current thread does nothing but wait at every node.
Measured, the difference from the rule was small and noisy (12–18 ms vs
11–15 ms); the catastrophic case is the one to remember.
</details>

<details><summary><b>Q.</b> Why does a <code>ForkJoinPool</code> worker steal from the TAIL of another deque rather than the head?</summary>

Each worker pushes and pops its **own** tasks at the head — lock-free in the
common case, and the newest task is the one whose data is still cache-warm. The
tail holds the **oldest** task, which in a divide-and-conquer tree is the
**biggest** remaining chunk. Stealing it means one steal buys a lot of work, so
steals stay rare and the cache-friendly local path stays the common case.

Measured: an unbalanced tree on 8 workers needed only **43–61 steals** across
~511 tasks, and still kept every worker busy on a tree whose early leaves were
256× heavier than its late ones.
</details>

<details><summary><b>Q.</b> What does <code>.parallel()</code> actually do, and what is the JVM-wide consequence?</summary>

It splits the source with a `Spliterator` and submits the pieces to
`ForkJoinPool.commonPool()` — **one pool for the whole process**, `cores - 1`
workers, shared with every parallel stream, every `CompletableFuture` that omits
an executor, and any library that uses either.

Consequence: blocking inside a parallel stream is a **process-wide** degradation.
Measured — a CPU-only parallel stream sharing no data, no lock and no code with
the offender ran **7.0–9.8× slower** while 44 tasks blocked elsewhere, and fully
recovered afterwards. Topic 8 taught one pool per concern; `commonPool` is the
one pool you cannot bulkhead, and `.parallel()` opts you into it in eight
characters.
</details>

<details><summary><b>Q.</b> When is a parallel stream worth it? Give the real criterion, not the rule of thumb.</summary>

When **N × cost-per-element** is large, the source splits cheaply (array or
`ArrayList`, not `LinkedList`), the elements are primitives rather than boxed, and
the operation neither blocks nor touches shared mutable state.

The "10,000 elements" rule of thumb is not a threshold on element count:
measured, 1,000,000 *trivial* elements won ~3.6×, while 10,000 *expensive* ones
won ~6.4× and 100 expensive ones produced pure noise (0.13×–2.46× across six
runs). Total work is the criterion; element count alone predicts nothing.
</details>

<details><summary><b>Q.</b> What is a virtual thread, mechanically, and what is the one thing it makes cheap?</summary>

A continuation plus a JVM-level scheduler, with its stack on the heap starting at
a few hundred bytes. On a blocking JDK call the JVM **unmounts** it — copies its
frames to the heap and releases the **carrier** (a real platform thread from a
core-sized `ForkJoinPool`) to run another virtual thread; it is **mounted** again
when the call returns, possibly on a different carrier.

It makes **blocking** cheap, not computing fast. A blocked virtual thread
consumes no OS thread, so "how many can block at once" stops being a resource
question. Measured: ~2.3 µs to create versus ~120 µs for a platform thread, and
63,700 blocking tasks/sec versus 115 for a cores-sized platform pool.

Because they are not scarce, you **do not pool them** —
`newVirtualThreadPerTaskExecutor()` is a factory, not a pool.
</details>

<details><summary><b>Q.</b> Explain pinning: mechanism, measurement, and why the measurement was designed with per-task locks.</summary>

Unmounting requires the JVM to move stack frames to the heap, and in Java 21 it
cannot while a `synchronized` monitor is held, because the monitor is owned by the
**carrier**. So the virtual thread is **pinned**, and when it blocks it blocks a
real OS thread for the whole duration — throughput falls back toward the carrier
count. `ReentrantLock` is built on `LockSupport.park`, which the JVM understands,
so it unmounts cleanly.

Measured: 2,000 tasks × 100 ms blocking → 112–139 ms unwrapped, 107–139 ms with
`ReentrantLock`, **14,400–17,400 ms with `synchronized`** — 105–156×.

**Each task locked its own private object**, so no two tasks could ever contend.
That design is what makes the result mean *pinning* rather than mutual exclusion:
with a shared lock the slowdown would have been ordinary serialisation and would
have proved nothing about virtual threads.
</details>

<details><summary><b>Q.</b> The pinning result was bimodal. What were the two modes, why, and what should you take from that?</summary>

Across 11 runs: 9 landed at 14.4–17.4 s (105–156×), and 2 landed at ~1.04 s
(~9.5×). The fast mode is the JVM **compensating** — when a carrier blocks, the
scheduler may start another, up to `jdk.virtualThreadScheduler.maxPoolSize`
(default 256). Whether it manages to is not under your control.

The takeaway is diagnostic, not numeric: a pinning bug is **10× slow today and
150× slow tomorrow on the same build**, which is precisely why it is so hard to
identify from a latency graph. Both modes are catastrophic against a 110 ms
baseline. Find them with `-Djdk.tracePinnedThreads=full` rather than by staring
at percentiles.
</details>

<details><summary><b>Q.</b> Do virtual threads speed up CPU-bound work? Answer from the measurement.</summary>

**No — and be careful how you say it.** Measured on pure CPU work, the virtual
executor was equal or *faster* in 5 of 5 runs (platform 186–860 ms, virtual
101–268 ms). But that is not a virtual-thread benefit: nothing blocks, so nothing
unmounts. It is the scheduler shape — a fixed pool funnels every task through one
shared queue while the virtual scheduler uses per-worker deques.

The conclusion that survives is the negative one: **virtualisation buys CPU work
nothing, because there is no blocking to make cheap.** Prefer a bounded pool for
CPU work when you want concurrency bounded deliberately — not because it is
faster, because measured here it was not.
</details>

<details><summary><b>Q.</b> What does structured concurrency guarantee, and what did the unstructured version cost?</summary>

The guarantee is one sentence: **when control leaves the block, every thread of
control created inside it has already finished.** `close()` cannot be skipped, so
a fork cannot outlive its scope; `join()` returns on the first failure rather
than in submission order; and a failure cancels the siblings automatically.

Measured without it: a subtask failed at ~5 ms and the caller was not told until
**2,033 ms**, because it was blocked in `get()` on a *different* future — and the
doomed sibling ran to completion producing a value discarded on arrival. The
hand-rolled fix got it to 22 ms and took ~20 lines that must be repeated,
correctly, at every fan-out site.

On Java 21 these are **preview** APIs requiring `--enable-preview`; this lab
teaches them as a verified walkthrough rather than enabling preview module-wide.
</details>

<details><summary><b>Q.</b> Contrast <code>ScopedValue</code> with <code>ThreadLocal</code> — and name the single design choice all the differences follow from.</summary>

`ThreadLocal` is mutable from anywhere, lives until `remove()` (or leaks on a
pooled thread), needs a `finally` for cleanup, and copies its whole map when
inherited. `ScopedValue` is immutable once bound, lives exactly as long as the
block that bound it, needs no cleanup, and is *shared* rather than copied with
forked subtasks.

The single design choice: **a `ScopedValue` binding is immutable and lives on the
stack of the block that established it.** Because it cannot change, children can
share it; because it is tied to a block, it unbinds on every exit path.
`ThreadLocal` is a mutable map keyed by thread, and every difference follows from
that. It matters far more with virtual threads: a million threads means a million
`ThreadLocal` maps.
</details>

---

## 🃏 FLASHCARDS

```
The one question this topic asks	What KIND of work is this? CPU-splittable / CPU-independent / IO
ForkJoin ordering rule	right.fork(); left.compute(); right.join()
fork(); join(); per half	NO parallelism — measured 0.5-0.7x, SLOWER than a plain loop (6/6)
Why per-worker deques	Push/pop own at HEAD lock-free & cache-warm; steal OLDEST from TAIL
Why steal the oldest	It is the BIGGEST chunk — one steal buys lots of work, steals stay rare
Measured steals	43-61 steals across ~511 tasks on an unbalanced tree
Leaf threshold	Both ends bad, middle is a 4-orders-of-magnitude plateau. Roughly right is enough
.parallel() submits to	ForkJoinPool.commonPool() — JVM-wide, cores-1, shared, NOT bulkheadable
Common-pool starvation	Unrelated CPU stream 7.0-9.8x slower while 44 tasks blocked (6/6 runs)
Parallel stream criterion	N x cost-per-element, TOTAL. Element count alone predicts nothing
Boxing cost measured	Stream<Long> ~35x slower sequential, ~19x parallel, vs LongStream
ArrayList vs LinkedList	O(1) index halving vs WALKING to split. Consistent but modest: ~1.2-1.9x
Virtual thread =	A continuation + JVM scheduler; heap stack from a few hundred bytes
Mount / unmount	Blocking JDK call → frames to heap, carrier freed. Blocked ⇒ NO OS thread
Creation cost measured	~2.3us virtual vs ~120us platform — ~50x cheaper
Scale measured (10k x 100ms)	pool-of-12: 115/sec · pool-of-1000: 8,700/sec · virtual: 63,700/sec
Virtual threads are NOT	A pool. newVirtualThreadPerTaskExecutor is a FACTORY — never pool them
PINNING (Java 21)	synchronized holds the CARRIER's monitor ⇒ cannot unmount ⇒ blocks an OS thread
Pinning measured	110ms → 14,400-17,400ms. 105-156x. ZERO contention (per-task locks)
Pinning is bimodal	9/11 runs 105-156x; 2/11 ~9.5x when the scheduler grew the carrier pool
Pinning fix	ReentrantLock (0.8-1.0x) — better: hold NO lock across a blocking call
Find pinning with	-Djdk.tracePinnedThreads=full
Virtual threads for CPU work	Gain nothing — nothing blocks, so nothing unmounts
StructuredTaskScope guarantee	Control leaves the block ⇒ every fork inside has ALREADY finished
Unstructured cost measured	Failure at 5ms reported at 2,033ms; doomed sibling ran to completion
Java 21 status	StructuredTaskScope + ScopedValue are PREVIEW — need --enable-preview
ScopedValue vs ThreadLocal	Immutable + block-scoped vs mutable map per thread. All differences follow
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| Layer | The same idea |
|---|---|
| Topic 8 (pool sizing) | virtual threads remove the ceiling for **IO** work; CPU work still needs a bounded pool |
| Topic 8 (bulkheads) | `commonPool` is the anti-bulkhead — one pool for every concern in the process |
| Topic 5 (`SynchronousQueue`) | the same "no slack" idea; `ForkJoinPool` deques are the opposite design |
| Topic 4 (locks) | "never hold a lock across a blocking call" — always true, now 100× more expensive |
| Topic 6 (`ThreadLocal`) | `ScopedValue` is its immutable, block-scoped replacement |
| Topic 1 (interruption) | `StructuredTaskScope` cancels siblings *via* interruption — cooperative still |
| `CompletableFuture` | no-executor overloads use `commonPool` too — [`../03-async-and-scheduling/01`](../03-async-and-scheduling/01-thread-pools-completablefuture.md) |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)

**Interleaved pass (R4) — pull these forward from earlier topics:**
1. *(Topic 8)* You measured that a blocking pool's throughput can only be raised
   by adding threads (115 → 8,700 tasks/sec from 12 → 1,000 threads). Explain why
   virtual threads make that whole sizing exercise **unnecessary for IO work and
   still necessary for CPU work** — then say which of topic 8's four numbers
   (core, queue, max, rejection policy) still has meaning under
   `newVirtualThreadPerTaskExecutor()`, and why the honest answer is "none, and
   that is a cost as well as a benefit."
2. *(Topic 4)* Ex5's deadlock-free bank used lock ordering, and topic 4 argued
   that an uncontended lock is nearly free. Both claims are still true — yet an
   uncontended `synchronized` here cost **105–156×**. Reconcile them: explain
   precisely what got expensive, why contention was never involved, and why the
   same code on a platform thread would have shown none of it.
