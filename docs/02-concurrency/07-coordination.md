# Topic 7 — Coordination: Latch, Barrier, Semaphore, Phaser
**Demos:** `t07coordination/D19`, `D20`, `D21` | **Exercises:** `Ex11Rounds`, `Ex12Pool`

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** These are not four APIs. They are **one 2×2**, and the two
questions are *is it reusable?* and *am I waiting for events to happen, or for
parties to arrive?* Get the axes and you never have to memorise a method list
again.

```
                 │ waiting for EVENTS to happen  │ waiting for PARTIES to arrive
 ────────────────┼───────────────────────────────┼──────────────────────────────
  ONE-SHOT       │ CountDownLatch                │ (a latch used as a start gate)
  REUSABLE       │ Semaphore — permits, D20      │ CyclicBarrier · Phaser
```

**The decision rule:** **does it have to happen more than once?** If yes, you
cannot use a latch — no amount of care makes a one-shot counter reusable, and the
failure is silent.

**Five rules you must never get wrong:**
1. A latch **counts down to zero and stays there**. `countDown()` on zero is a no-op; `await()` on zero returns instantly. Reuse it across rounds and round 2 has **no barrier at all** — measured: 6 workers in round 1, then 1, 1, 1, 1.
2. `countDown()` and `release()` go in a **`finally`**. The commonest cause of a production hang is not a cycle; it is one worker that threw on the way to its `countDown()`.
3. A `Semaphore` counts **permits to use a resource**, not events. Permits are anonymous: any thread may release, `acquire()` is **not reentrant**, and `release()` without `acquire()` mints a permit out of nothing.
4. A leaked permit is **permanent and cumulative**. Measured: 4 exceptions destroyed a 4-permit pool, and it never recovered. The system does not fall over at the first error; it slides.
5. **A latch or semaphore deadlock produces no deadlock report.** Not a tooling gap — detection needs an *owner* to draw an edge to, and permits and latch counts deliberately have none. Use `await(timeout)` / `tryAcquire(timeout)`, and gauge `getCount()` / `availablePermits()`.

*Run first: D19 (the 2×2, and a latch silently ceasing to synchronise), D20
(permits, and a pool dying one exception at a time), D21 (three deadlocks, one
diagnosed).*

---

## 🔮 PREDICT FIRST

*Answer aloud before opening. A wrong prediction you then correct is worth more
than three right ones — that is the whole point of this zone.*

<details>
<summary><b>P1.</b> Six workers run five rounds. Somebody uses a single <code>CountDownLatch(6)</code>, created once, as the per-round barrier: each worker calls <code>countDown()</code> then <code>await()</code>. What breaks, and when do you find out?</summary>

**Nothing throws, nothing hangs, and you find out from wrong results much later.**
Measured (D19, "how many of the 6 workers had arrived when the round was
tallied") — identical in 5 runs out of 5:

| round | with the reused latch | with a `CyclicBarrier` |
|---|---|---|
| 1 | **6** of 6 | 6 of 6 |
| 2 | **1** of 6 | 6 of 6 |
| 3 | **1** of 6 | 6 of 6 |
| 4 | **1** of 6 | 6 of 6 |
| 5 | **1** of 6 | 6 of 6 |

Most people predict a hang, because "the latch is used up" sounds like *blocked
forever*. It is the opposite. Once the count reaches zero, `countDown()` does
nothing and `await()` **returns immediately** — so from round 2 onward there is no
rendezvous at all. Six threads run free while the code still reads as though it
synchronises.

That is the worst possible failure mode. A hang is a bug report; this is a
wrong number, produced quickly and confidently, in a program that looks correct.
There is no `reset()` on `CountDownLatch` and its absence is deliberate: a
resettable latch has an unanswerable race — who resets it, and what about the
thread still inside `await()`?
</details>

<details>
<summary><b>P2.</b> Two threads each wait on the other's <code>CountDownLatch</code>. You run <code>jcmd &lt;pid&gt; Thread.print</code>. What does it tell you?</summary>

**Nothing. There is no deadlock section.** Measured (D21, the same JVM, three
deadlocks side by side):

| Case | Thread state | `blocked-on` | `owned-by` | `findDeadlockedThreads()` |
|---|---|---|---|---|
| `synchronized` × 2 | `BLOCKED` | `java.lang.Object@…` | **the other thread** | **names both threads** |
| latch × 2 | `WAITING` | `CountDownLatch$Sync@…` | **nobody** | **names none of them** |
| `Semaphore` × 2 | `WAITING` | `Semaphore$NonfairSync@…` | **nobody** | **names none of them** |

Topic 4 warned you this was coming — its flashcard reads *"Detection blind spot —
Semaphore/latch deadlocks invisible, plain hang, no report."* Here is the
mechanism, and it is not a missing feature.

Deadlock detection is cycle detection on a **wait-for graph**, and drawing an edge
requires answering one question: *this thread is blocked — who holds the thing it
wants?* A monitor records its owner in the object header. A `ReentrantLock`
records `exclusiveOwnerThread`. **A semaphore permit and a latch count have no
owner**, and that is not an oversight — being ownerless is precisely what makes
them useful (D20: acquire in one thread, release in another). No owner means no
edge, no edge means no cycle, and no cycle means nothing to report.

So the tooling cannot be fixed and your habits have to absorb the difference. The
`owned-by: nobody` column *is* the signature: a pile of `WAITING` threads parked
in `LockSupport.park` with no owner named anywhere.
</details>

<details>
<summary><b>P3.</b> An error path calls <code>semaphore.release()</code> without a matching <code>acquire()</code>. Does it throw?</summary>

**No — it creates a permit out of nothing.** Measured (D20): a `Semaphore`
constructed with 2 permits, after two bare `release()` calls, reports
`availablePermits() == 4`.

Almost everyone predicts `IllegalMonitorStateException`, by analogy with
unlocking a lock you do not hold. But a semaphore has **no owner and no
bookkeeping of who acquired what**, so it has nothing to object with. That is the
same property that lets a producer release a permit a consumer acquired.

The practical consequence: one double-release in an error path and your "limit of
4" is silently a limit of 5, then 6, and nothing ever tells you. It is the exact
mirror of the leak in D20's other half, where a *missing* release shrinks the pool
to zero — and the two bugs look identical in code review. Both come from the same
omission: `release()` that is not paired, once, in a `finally`.
</details>

---

## 📖 THE STORY

### 1. The 2×2, and why it is worth more than the four APIs

|  | waiting for **events** to happen | waiting for **parties** to arrive |
|---|---|---|
| **one-shot** | `CountDownLatch` | a latch used as a start gate |
| **reusable** | `Semaphore` (permits) | `CyclicBarrier`, `Phaser` |

Two axes decide everything:

**Is it reusable?** A latch counts down and stays at zero forever. A barrier
re-arms itself the instant the last party arrives, with nothing to call and
nothing to remember.

**Are you waiting for events, or for each other?** A latch's counters and its
waiters are *different threads*, in any numbers — one thread may count down five
times, or five threads once each, and a hundred threads may be waiting. A
barrier's parties **are** the waiters: arriving *is* waiting, and the count is
fixed at construction because the barrier has to know when everyone is present.

A `Semaphore` is the odd one out in the best way: it is the only member whose
count moves in **both** directions, which is exactly why it is the one that caps
concurrency.

### 2. You have been running a latch since topic 1

Open `concurrency-lab/src/main/java/com/locallearn/concurrency/support/Stress.java`.
Both latch idioms are in there, and every demo in this lab has been standing on
them:

```java
CountDownLatch gate = new CountDownLatch(1);        // ONE counter, MANY waiters
CountDownLatch done = new CountDownLatch(threads);  // MANY counters, ONE waiter
```

**The start gate.** Every worker parks on `gate.await()`; the main thread calls
`gate.countDown()` once and all of them are released within microseconds. Without
it, thread 1 finishes its whole loop before thread 8 exists and no race ever
reproduces. This is why `Stress` is described in its own Javadoc as the most
important class in the lab.

**Wait for N workers.** Every worker calls `done.countDown()` in a `finally`; the
main thread calls `done.await()`. Measured in D19: one `countDown()` released all
6 gated workers, and all 6 had finished 20–27 ms later.

Between them those two idioms cover most of what people reach for a barrier to
do. And note where `countDown()` lives: **in a `finally`**. A worker that throws
on the way to its `countDown()` leaves the awaiting thread parked forever — and
by P2, that hang has no diagnosis.

### 3. A latch does not reset, and the failure is silent

This is the mistake that matters, and P1 has the table: round 1 is perfect,
rounds 2 through 5 have no barrier at all, and the tally drops from 6 to 1.
Deterministic across every run.

`CyclicBarrier` is the reusable half of the row:

```java
CyclicBarrier barrier = new CyclicBarrier(workers, () -> tallies.add(arrived.getAndSet(0)));
...
barrier.await();     // parks until ALL parties arrive; then re-arms itself
```

There is no `reset()` call anywhere in that. The barrier trips when the last
party arrives and is immediately armed again.

**The barrier action is the part people underuse.** It runs on the *last thread
to arrive*, exactly once per round, **while every other party is still parked**.
That is the only instant in the whole round when no worker is running, which
makes it the only place a merge, a buffer swap, or a tally can be exact.
Tallying after `await()` returns — even with a perfectly good barrier — races with
the other workers starting the next round.

### 4. `BrokenBarrierException` is a feature, and the contrast is the point

A barrier's contract is all-or-nothing, so if a party never arrives, the others
must not wait forever on a rendezvous that can no longer happen. D19 sets up three
required parties and provides two:

```
2 of 3 parties arrive; the third never shows up.
party-1 timed out after 150ms — THIS is what marks the barrier broken
party-0 waited with NO deadline and was woken by BrokenBarrierException instead of hanging
barrier.isBroken() = true, waiting parties = 0
after reset(): isBroken() = false — back in service
```

One party's timeout marks the barrier broken, and **every** other party is woken
with an exception — including the one that asked for no deadline at all and would
otherwise have hung forever. `reset()` puts it back into service once you have
dealt with the missing party.

Hold that beside D21: a barrier fails **loudly, on purpose**; the same mistake
with a latch or a semaphore produces silence and an empty thread dump. When you
have a genuine choice between them, that difference is a real argument for the
barrier.

### 5. `Phaser` — know it exists

A `Phaser` is a barrier whose party count can change at runtime. Measured in D19:

```
start:   1 registered party (just main), phase 0
A and B joined: 3 registered parties
phase 0 done -> now phase 1, 3 parties
C joined late: 4 registered parties       ← a party appeared mid-run
phase 1 done -> now phase 2, 4 parties
phase 2 done -> now phase 3, 1 party remains (A, B and C left)
phaser terminated = true
```

`register()` and `arriveAndDeregister()` between phases are the entire
difference. A `CyclicBarrier` fixes its party count at construction and cannot
change it; a party leaving a `Phaser` simply lowers the bar for the next phase
instead of breaking it, and the phaser terminates when the last party leaves.

Genuinely useful when the number of participants is discovered at runtime — a
recursive decomposition, a crawl that spawns workers per level. Overkill
everywhere else. **Reach for `CyclicBarrier` first and for a `Phaser` only when
you can name the party that has to join or leave late.**

### 6. `Semaphore` — permits, not events, and not a lock

A semaphore counts **permits to use a resource**. That one sentence separates it
from a latch, and getting it backwards is the usual mistake.

Measured (D20, 32 callers against 4 permits, 640 calls, 6 runs): peak concurrency
observed **4**, all 640 calls completed, permits back to 4. Nothing was rejected
and nothing was lost — the other 28 callers were parked in `acquire()`.

**It is not a lock, and the difference is not pedantry.** A lock has an *owner*;
`synchronized` enforces that structurally and a reentrant lock lets the owner
re-enter. A semaphore has none of that:

| Property | Consequence |
|---|---|
| Thread A may `acquire`, thread B may `release` | a feature — it is how a producer hands a slot to a consumer (D20 demonstrates it) |
| **Not reentrant** | a thread holding the last permit that calls `acquire()` again blocks **against itself**, forever, with no deadlock report |
| `release()` without `acquire()` **mints a permit** | see P3: 2 permits became 4, silently |

**The rule that is worth more than the API:**

```java
semaphore.acquire();
try {
    return doWork();
} finally {
    semaphore.release();   // EVERY exit path, including the exceptional ones
}
```

A `release()` placed *after* the work is skipped whenever the work throws, and a
permit that is never released is gone for the lifetime of the process. Measured
(D20, 6 runs out of 6, identical every time):

| After N failures on the non-`finally` path | permits left (of 4) |
|---|---|
| 1 | 3 |
| 2 | 2 |
| 3 | 1 |
| 4 | **0** |
| then: a healthy `tryAcquire(500 ms)` | **false** — and it always will be |

With `release()` in a `finally`, the same four failures left **4** permits
untouched.

Read the shape of that failure, not just the endpoint. **The system does not
break at the first error — it shrinks, one exception at a time.** What you get
paged on is a slow slide over hours, not a cliff, and at the bottom every caller
is parked forever while the process stays up and the health check stays green.

### 7. Block or shed — topic 5's trichotomy, one layer up

`acquire()` and `tryAcquire()` are the same choice topic 5 made you face with
queue capacity. Measured (D20, 32 callers, 4 permits, `tryAcquire()` with no
wait): admitted 65–80, **shed 88–90%** of 640 calls.

| Verb | Topic 5's word | What you get |
|---|---|---|
| `acquire()` | **block** | nobody turned away; the queue of waiting callers is made of parked threads no dashboard shows you |
| `tryAcquire()` | **drop** | a countable, deliberate rejection you can log, alert on, and return as a 503 |
| `tryAcquire(timeout)` | both | wait a bounded time, then shed — usually the right default at a service boundary |

Neither is "correct". The bug is failing to choose — and note that `acquire()`
grows an *invisible* backlog, which is the same reason topic 5 called an
unbounded queue "choosing to crash later while feeling like you chose nothing".

### 8. Fairness costs, exactly as it did for a lock

**Measured (D20, 8 threads × 50,000 acquire/release on a 1-permit semaphore, 6
runs, warmed up):**

| | range across 6 runs |
|---|---|
| non-fair | 49 – 73 ms |
| fair | **4,813 – 5,284 ms** |
| ratio | 67× – 104× |

The **ratio** wobbles a lot and I am not going to publish a single number for it:
the non-fair figure is small enough that a few milliseconds of background noise
moves it by 50%. The fair figure is rock steady across all six runs, and the
conclusion — **roughly two orders of magnitude** — replicated every time.

Same mechanism as D10's 92× for `ReentrantLock`. Fairness forbids barging, so a
thread that is already running cannot take a free permit while someone is queued
ahead of it. Every hand-off therefore becomes a park/unpark pair — a syscall —
instead of a few nanoseconds of CAS. Use fair only when you have **measured**
starvation.

### 9. The anchor failure: a hang with no diagnosis

P2 has the table. The rest is what to do about it, since the tooling is not going
to improve.

**Read the dump anyway.** There is no deadlock banner, but the parked threads are
right there and the frame names the mechanism:

```
latch-1      state=WAITING   blocked-on=java.util.concurrent.CountDownLatch$Sync@…   owned-by=nobody
      at jdk.internal.misc.Unsafe.park
      at java.util.concurrent.locks.LockSupport.park
      at java.util.concurrent.locks.AbstractQueuedSynchronizer.acquireSharedInterruptibly
```

`CountDownLatch$Sync`, `Semaphore$NonfairSync`, `CyclicBarrier` — plus
`owned-by: nobody` — is the signature.

**Instrument the counts. This is the highest-value line on the page.** They are
free, and a gauge pinned at zero diagnoses in one glance what a thread dump cannot
express at all:

| Gauge | What it tells you |
|---|---|
| `latch.getCount()` | how many signals are still owed (D21: both latches stuck at 1) |
| `semaphore.availablePermits()` | 0 for minutes ⇒ a leak (§6) |
| `semaphore.getQueueLength()` | how many callers are parked waiting |
| `barrier.getNumberWaiting()` | parties present but not released |

**Use the timed forms.** Measured in D21: `tryAcquire(200 ms)` on an exhausted
semaphore returned `false` after 203 ms; `await(200 ms)` on a latch nobody would
count down returned `false` after 205 ms. That boolean is the difference between
an incident you can see and one you cannot — it is a branch you can log, count,
alert on, and retry. An untimed wait in production converts a transient fault into
a permanent one.

And note D21's case C: two semaphores acquired in opposite orders is **D11's
lock-ordering bug exactly**, Coffman's four conditions and all, with the same fix
— acquire in one globally consistent order. The only thing that changed is that
nothing will tell you to.

---

## 🧪 EXERCISE 11 — `Ex11Rounds`

```bash
cd concurrency-lab
./mvnw test -Dtest='ExerciseTests$Ex11'
```

Six workers, N rounds, and a single `CountDownLatch` created in the constructor
doing duty as a per-round barrier.

| Test | Fails because |
|---|---|
| `everyRoundWaitsForEveryWorker` | tallies were `[25, 1, 1, 1, 1, 1, 1, 1, 4, 3, …]` instead of twenty 6s |
| `holdsWhenWorkersArriveAtDifferentTimes` | tallies were `[6, 1, 1, 1, 1, 1, 1, 1, 1, 1]` — round 1 works, then nothing does |
| `workersParkAtTheBarrierInsteadOfSpinning` | **passes** — and it is not there for the broken version |

That third test exists to reject the tempting wrong *fix*:
`while (arrived.get() < workers) { }` satisfies every correctness assertion above
and burns a core per waiting worker. It is the same defect Ex7 and Ex8 measured
with CPU time, and the same reason those assertions exist. Note also the first
row: the broken version's round-1 tally is often **above** 6, because the other
workers race into round 2 before worker 0 gets to read the counter — a second,
independent reason the tally belongs in a barrier action.

## 🧪 EXERCISE 12 — `Ex12Pool`

```bash
./mvnw test -Dtest='ExerciseTests$Ex12'
```

Two defects, both real, one test each:

| Test | Fails because |
|---|---|
| `neverRunsMoreTasksAtOnceThanTheLimit` | 32 tasks ran at once against a pool of 4 — `tryAcquire()` returned false and the code carried on anyway |
| `doesNotLeakASlotWhenTheTaskThrows` | after 4 failed tasks the pool has **0 of 4** slots left |

The first is the one option that is never right: if callers should wait, the verb
is `acquire()`; if they should be turned away, you must actually turn them away.
Running unaccounted gives you concurrency that is unbounded *and* invisible.

The second is asserted on `availableSlots()` rather than by waiting for a hang, so
you get a message you can read instead of a timeout — which is the whole lesson of
§9 applied to the test suite itself.

When it passes, go back and ask §7's design question: at a real service boundary,
is `acquire()` or `tryAcquire(timeout)` the behaviour you actually want?

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, THEN open. Miss one → reread that section.*

<details><summary><b>Q.</b> Draw the 2×2 and place all four classes.</summary>

The axes are *one-shot vs reusable* and *waiting for events vs waiting for
parties*. `CountDownLatch` is one-shot and event-counting (and a latch of 1 used
as a start gate is the "parties" corner of that row). `Semaphore` is reusable and
resource-counting — the only one whose count moves in both directions, which is
why it caps concurrency. `CyclicBarrier` and `Phaser` are reusable rendezvous of
a fixed set of parties, `Phaser` differing only in that parties may register and
deregister at runtime.
</details>

<details><summary><b>Q.</b> What exactly happens if you reuse a <code>CountDownLatch</code> across rounds?</summary>

Round 1 works perfectly. After the count reaches zero, `countDown()` is a no-op
and `await()` returns immediately, so from round 2 there is **no rendezvous at
all** — the workers run free while the code still looks synchronised. Measured:
6 workers tallied in round 1, then 1, 1, 1, 1. Nothing throws and nothing hangs;
you find out from wrong results, later. There is no `reset()` and its absence is
deliberate — a resettable latch has an unanswerable race about who resets it and
what happens to the thread still inside `await()`. Use a `CyclicBarrier`.
</details>

<details><summary><b>Q.</b> The two <code>CountDownLatch</code> idioms — and where have you already been using them?</summary>

**The start gate**: `new CountDownLatch(1)`, every worker awaits, one thread
counts down once, all released within microseconds — one counter, many waiters.
**Wait for N**: `new CountDownLatch(threads)`, every worker counts down in a
`finally`, one thread awaits — many counters, one waiter. Both are in
`support/Stress.java`, which every demo in this lab has been running since topic
1; the start gate is why races reproduce here at all instead of thread 1
finishing before thread 8 exists.
</details>

<details><summary><b>Q.</b> What is a barrier action and why must the merge go in it?</summary>

The `Runnable` passed to `new CyclicBarrier(parties, action)`. It runs on the last
thread to arrive, exactly once per round, **while every other party is still
parked** — the only instant in the round when no worker is running. That makes it
the only place a tally, a merge, or a buffer swap can be exact. Doing it after
`await()` returns, even with a correct barrier, races with the other workers
starting the next round.
</details>

<details><summary><b>Q.</b> What is <code>BrokenBarrierException</code> for?</summary>

A barrier's contract is all-or-nothing, so a rendezvous that can no longer happen
must not leave everyone parked. When a waiting party is interrupted or times out,
the barrier marks itself **broken** and wakes every other party — present and
future — with this exception. Measured: with 2 of 3 parties present, one timing
out after 150 ms broke the barrier, and the party that had asked for *no* deadline
was woken with the exception rather than hanging. `reset()` returns it to service.
This is exactly the diagnosis a latch or semaphore cannot give you.
</details>

<details><summary><b>Q.</b> How is a <code>Semaphore</code> different from a lock?</summary>

A lock has an **owner**: the acquiring thread must release it, and it is
reentrant. A semaphore's permits are anonymous tokens. Thread A may acquire and
thread B release — that is the feature, and it is how a slot is handed over.
`acquire()` is **not reentrant**, so a thread holding the last permit that
acquires again blocks against itself forever. And `release()` without `acquire()`
mints a permit from nothing: measured, 2 permits became 4 with no exception, so
one double-release in an error path silently raises your limit. The ownerless
property is also precisely why a semaphore deadlock cannot be detected.
</details>

<details><summary><b>Q.</b> Where does <code>release()</code> go, and what happens if it does not go there?</summary>

In a `finally`, always. A `release()` after the work is skipped by every throw,
and a permit that is never released is gone for the lifetime of the process.
Measured: four exceptions took a 4-permit pool to 0, and a healthy
`tryAcquire(500 ms)` afterwards returned false — permanently. The same four
failures with `release()` in a `finally` left all 4 permits untouched. The shape
matters as much as the number: the damage is cumulative, so the system does not
fall over at the first error, it slides one permit at a time until every caller
parks forever with the health check still green.
</details>

<details><summary><b>Q.</b> Why is a latch or semaphore deadlock invisible to a thread dump?</summary>

Deadlock detection is cycle detection on a wait-for graph, and an edge needs an
**owner**: "thread A waits for X, which thread B holds". A monitor records its
owner in the object header; a `ReentrantLock` records `exclusiveOwnerThread`. A
semaphore permit and a latch count have no owner — deliberately, because being
ownerless is what lets one thread release what another acquired. No owner, no
edge, no cycle, nothing to report. Measured in one JVM: the `synchronized`
deadlock was named by `findDeadlockedThreads()`; the latch and semaphore ones were
not, and both showed `owned-by: nobody`. It is not a tooling gap and it will not
be fixed.
</details>

<details><summary><b>Q.</b> Your service has silently stopped serving. No deadlock in the dump, CPU idle. What do you look at?</summary>

Look for `WAITING` threads parked in `LockSupport.park` whose frame names
`CountDownLatch$Sync`, `Semaphore$NonfairSync` or `CyclicBarrier`, with
`owned-by: nobody` — that combination is the signature. Then read the gauges you
should already be exporting: `latch.getCount()`, `semaphore.availablePermits()`
(0 for minutes means a leak), `semaphore.getQueueLength()`,
`barrier.getNumberWaiting()`. And prevent the next one with the timed forms —
`await(timeout)`, `tryAcquire(timeout)` — which turn a permanent hang into a
`false` you can log, count, alert on and retry.
</details>

<details><summary><b>Q.</b> <code>acquire()</code> or <code>tryAcquire()</code> at a service boundary?</summary>

It is topic 5's block/drop/grow decision one layer up. `acquire()` blocks: nobody
is turned away, and the backlog is a pile of parked threads no dashboard shows
you. `tryAcquire()` drops: a countable, deliberate rejection you can log, alert on
and return as a 503 — measured at 88–90% shed with 32 callers against 4 permits.
`tryAcquire(timeout)` is the middle ground and usually the right default: wait a
bounded time, then shed. Neither is "correct"; the bug is failing to choose, and
`acquire()` is the one whose backlog is invisible.
</details>

---

## 🃏 FLASHCARDS

```
The whole topic	A 2x2: one-shot vs reusable  X  waiting for EVENTS vs waiting for PARTIES
The decision rule	Does it happen more than once? Then it cannot be a latch
CountDownLatch	One-way counter. Counters and waiters are DIFFERENT threads, any numbers. No reset, by design
Reused latch, measured	Round 1: 6 of 6. Rounds 2-5: 1 of 6. No exception, no hang — just silently no barrier
Why no reset() on a latch	Unanswerable race: who resets, and what about the thread still inside await()?
The two latch idioms	Start gate (1 counter, N waiters) · Wait-for-N (N counters, 1 waiter) — both in Stress.java
countDown()/release() go	In a FINALLY. A worker that throws on the way there hangs the waiter, undiagnosably
CyclicBarrier	N parties rendezvous; arriving IS waiting; fixed count; re-arms itself with no reset() call
Barrier action	Runs on the LAST arriver, once per round, everyone else still parked — the only exact place to merge
BrokenBarrierException	All-or-nothing announced: one party's timeout wakes EVERYONE, including the no-deadline waiter
Phaser	CyclicBarrier + register()/arriveAndDeregister() at runtime. Know it exists; reach for barrier first
Semaphore counts	PERMITS TO USE A RESOURCE — not events (latch), not ownership (lock)
Semaphore is not a lock	Any thread may release · NOT reentrant (blocks against itself) · release() with no acquire() MINTS a permit
release() minting, measured	2 permits + two bare release() = 4 permits, no exception. Your "limit of 4" is now 5, then 6
Permit leak, measured	4 exceptions destroyed a 4-permit pool -> 0 forever. With release() in a finally: 4, untouched
Shape of the leak failure	Cumulative and irreversible — a slow slide, not a cliff, ending in a green health check and no service
acquire vs tryAcquire	Topic 5's block vs drop. Blocking's backlog is parked threads NO dashboard shows you
tryAcquire shedding, measured	32 callers / 4 permits: shed 88-90% — countable rejections you can alert on
Semaphore fairness, measured	Non-fair 49-73ms vs fair 4,813-5,284ms (67-104x). Ratio is noisy; ~2 orders of magnitude is not
THE anchor failure	Latch/semaphore deadlock = WAITING threads, no deadlock report, health check green
Why it's undetectable	Cycle detection needs an OWNER to draw an edge to. Permits and latch counts have none, deliberately
Thread-dump signature	WAITING + LockSupport.park + CountDownLatch$Sync / Semaphore$NonfairSync + owned-by: NOBODY
The four gauges	latch.getCount() · sem.availablePermits() · sem.getQueueLength() · barrier.getNumberWaiting()
Prevention	await(timeout) / tryAcquire(timeout) — measured false at 203ms instead of parking forever
Two semaphores, opposite order	D11's lock-ordering bug exactly. Same Coffman conditions, same fix — and zero warning
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| Layer | The same idea |
|---|---|
| `CompletableFuture.allOf(...)` | wait-for-N, one-shot — a `CountDownLatch` with a nicer face ([`../03-async-and-scheduling/01-thread-pools-completablefuture.md`](../03-async-and-scheduling/01-thread-pools-completablefuture.md)) |
| HikariCP | `maximumPoolSize` is the permit count and `connectionTimeout` is the `tryAcquire` timeout — pool exhaustion *is* §6's leak ([`../01-foundations/06-n-plus-one-hikaricp-tuning.md`](../01-foundations/06-n-plus-one-hikaricp-tuning.md)) |
| Tomcat / `ThreadPoolExecutor` | `maxThreads` and pool size are semaphores by another name; rejection policy is `tryAcquire` returning false |
| Resilience4j | `Bulkhead` is a `Semaphore` with metrics attached — which is exactly §9's "export the gauges", productised |
| Kubernetes | init containers and readiness gates are start gates: nothing proceeds until the count reaches zero |
| Spark / MapReduce | a stage boundary is a barrier, and the shuffle that happens there is the barrier action |
| Distributed systems | a lease with a TTL is `tryAcquire(timeout)` — the timeout exists because the ownerless hang is even worse across a network |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)

**Interleaved pass (R4) — pull these forward from earlier topics:**
1. *(Topic 4)* D11's deadlock was found by the JVM and printed with both stacks.
   D21's semaphore deadlock has the identical structure — same four Coffman
   conditions, same lock-ordering fix — and is invisible. Explain what the
   detector needs that it does not have, without using the word "bug", and say
   what you would export instead.
2. *(Topic 5)* Topic 5 made you choose between block, drop and grow for a queue's
   capacity. Map `acquire()`, `tryAcquire()` and `tryAcquire(timeout)` onto that
   trichotomy, then explain why `acquire()`'s backlog is *worse* to operate than a
   bounded queue's even though neither loses data.
