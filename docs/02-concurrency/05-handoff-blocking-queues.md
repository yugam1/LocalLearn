# Topic 5 — Hand-off: Blocking Queues, Backpressure & Draining Shutdown
**Demos:** `t05handoff/D13`, `D14`, `D15` | **Exercise:** `Ex8Pipeline`

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** A queue between a producer and a consumer does not make them
the same speed — it only decides **what happens when they aren't**. There are
exactly three possible answers and no fourth: **block** the producer
(backpressure), **drop** something (load shedding), or **grow** the backlog
(crash later, and call it "no decision"). An unbounded queue is not a buffer.
It is an `OutOfMemoryError` with a delay fuse.

```
UNBOUNDED  produced 1,007,114 | consumed 7,116 | backlog 1,000,000 in 419ms (~140 MB)
BOUNDED    produced    52,958 | consumed 51,934 | backlog     1,024        (~144 KB)
           ↑ bounded produced 19x less — because it told the truth about the consumer
```

**The decision rule:** **bounded unless you can prove the producer is
rate-limited** — and "our traffic is low" is not a proof, it is a hope with a
retry storm in its future.

**Five rules you must never get wrong:**
1. Capacity is not a tuning knob. It is **where your system fails**, chosen on purpose.
2. `poll()` returns `null` now; `take()` parks. A loop around `poll()` is a busy-wait that burns a core per idle worker and only shows up on the cloud bill.
3. A `volatile` flag **cannot stop a thread parked in `take()`**. Visible ≠ awake. A parked thread executes nothing, so it re-checks nothing.
4. Shutdown is a design decision with two answers: **drain** (poison pill / `shutdown()`) or **abandon** (interrupt / `shutdownNow()`). Pick deliberately; the default is data loss.
5. `SynchronousQueue` has capacity **zero** — a rendezvous, not a queue. Its whole job is turning "no idle consumer" into an instant signal. That is how `newCachedThreadPool` decides to spawn a thread.

*Run first: D13 (the backlog that eats 140MB in 0.4s), D14 (the family, and a
benchmark that contradicts your intuition), D15 (three shutdowns, two broken).*

---

## 🔮 PREDICT FIRST

*Answer aloud before opening. A wrong prediction you then correct is worth more
than three right ones — that is the whole point of this zone.*

<details>
<summary><b>P1.</b> A fast producer feeds a slow consumer for 1.5 seconds. Version A uses an unbounded <code>LinkedBlockingQueue</code>; version B uses <code>ArrayBlockingQueue(1024)</code>. Which one <i>loses</i> data, and which one processes more items?</summary>

**Neither loses data, and B processes more.** The intuitive answer is "B, because
it blocks" — but blocking is not losing. Measured (D13):

| | produced | consumed | backlog |
|---|---|---|---|
| unbounded | 1,007,114 | 7,116 | **1,000,000** (~140 MB, hit the demo's safety valve in 419 ms) |
| bounded(1024) | 52,958 | 51,934 | 1,024 (~144 KB) |

A "produced" a million items it had no intention of processing and turned them
into heap. It didn't go faster; it went *into debt*. Production has no safety
valve — the heap is the valve, and it fires as an `OutOfMemoryError` in
whichever thread happened to allocate last, usually nowhere near the queue that
caused it.

B produced 19× less because that is genuinely how much work the consumer could
absorb. Backpressure doesn't slow your system down — it **reveals** how slow it
already was, at the edge, where you can still shed load or time out.
</details>

<details>
<summary><b>P2.</b> Push 1,000,000 items through an <code>ArrayBlockingQueue(1024)</code>: first with 1 producer + 1 consumer, then with 4 + 4. Which is faster?</summary>

**4+4, by roughly 2×** — 134 ms vs 289 ms (D14), and it held in all 7 runs I
measured. Almost everyone predicts 1+1, reasoning that four producers contend on
the same lock.

Lock contention isn't the dominant cost here; **park/unpark is**. With one
thread per side the queue keeps hitting empty and full, so nearly every hand-off
costs a context switch (~1–10 µs). With four per side there is always work in
flight, the queue sits off both limits, and threads stay on the fast path
without parking at all.

"More contention" and "slower" are not synonyms. This is why you measure.
</details>

<details>
<summary><b>P3.</b> A consumer runs <code>while (!done) { process(queue.take()); }</code> with <code>done</code> declared <b>volatile</b>. The producer finishes, sets <code>done = true</code>, and exits. Does the consumer stop?</summary>

**No — it hangs forever.** Measured (D15): 2 seconds after the flag flips, the
thread is still `WAITING`, `alive=true`.

This is the trap, and you were set up for it: in D4/Ex2 the fix for a stuck stop
flag was exactly `volatile`. Here `volatile` is already there and is working
perfectly — the flag is completely visible. **Visibility was never the problem.**
The consumer is *parked inside `take()`* on an empty queue, and a parked thread
executes nothing, so it cannot re-check any flag no matter how visible. Nothing
will ever wake it, because the only thread that could have was the producer that
just left.

Same symptom as D4 (a loop that never exits), completely different mechanism
(D4: the read was optimised away; here: the reader is asleep and the alarm clock
left the building). Fixes: interrupt (abandons the backlog) or a poison pill
(drains it).

*If you predicted "yes" with confidence — good. Confidently-wrong predictions,
once corrected, are the best-retained facts you will take from this page.*
</details>

---

## 📖 THE STORY

### 1. The three-way choice, and why "unbounded" is not neutral

When a producer outruns a consumer, exactly one of three things happens:

| Choice | Mechanism | Failure mode you are buying |
|---|---|---|
| **Block** the producer | bounded queue, `put()` | latency at the edge — callers wait, then time out |
| **Drop** something | `offer()` → false (drop newest); take-then-offer (drop oldest) | known, countable data loss |
| **Grow** the backlog | unbounded queue | `OutOfMemoryError`, later, somewhere else |

Choosing an unbounded queue *feels* like declining to choose. It isn't: it is
picking row three. And row three is the only one whose failure arrives with no
warning, in an unrelated thread, at an unrelated time.

You will meet this same trichotomy at every layer of the stack — it is
`ThreadPoolExecutor`'s rejection policies (topic 8), Kafka producer
`buffer.memory` + `max.block.ms`, TCP's receive window. Learn it once here.

> **The rule:** bounded unless you can *prove* the producer is rate-limited.

### 2. The family is one decision wearing five class names

How much slack do you allow between producer and consumer?

| Capacity | Class | Notes |
|---|---|---|
| **0** | `SynchronousQueue` | rendezvous — every `put` waits for a `take` |
| **N** | `ArrayBlockingQueue` | one lock, pre-allocated array, zero per-item allocation |
| **N** | `LinkedBlockingQueue(N)` | **two** locks (put-lock + take-lock) so the two sides don't contend |
| **∞** | `LinkedBlockingQueue()` | see D13. "Infinite" means "until OOM" |
| special | `PriorityBlockingQueue` | unbounded, heap-ordered, **not FIFO** |
| special | `DelayQueue` | elements invisible until their delay expires — this is how `ScheduledThreadPoolExecutor` works underneath |

**Why `SynchronousQueue` isn't useless.** A queue that can't hold anything looks
absurd until you see who uses it: `Executors.newCachedThreadPool()`. The pool
offers each task to a `SynchronousQueue`; if an idle worker is already parked in
`poll()`, the hand-off succeeds instantly. If not, `offer()` returns `false`
**immediately** and the pool spawns a thread. Zero capacity is the mechanism
that converts "nobody is free" into a signal, with no latency and no buffer to
hide it.

**Measured (D14, 1,000,000 hand-offs, one representative run on a quiet machine):**

| Queue | 1P/1C | 4P/4C |
|---|---|---|
| `ArrayBlockingQueue(1024)` | 289 ms | **134 ms** |
| `LinkedBlockingQueue(1024)` | **253 ms** | 180 ms |
| `SynchronousQueue` | 290 ms | 367 ms |

**Now the part most benchmark tables leave out.** I ran this 7 times. Only two
findings replicated *every* time:

1. `ArrayBlockingQueue` is **faster at 4P/4C than at 1P/1C**, often 2× (see P2).
2. `SynchronousQueue` is the **worst** at 4P/4C. Zero capacity means zero slack
   to absorb a scheduling hiccup — every hand-off is a rendezvous.

The `ArrayBlockingQueue`-vs-`LinkedBlockingQueue` comparison went the way theory
predicts — Linked's split put/take locks win at 1P/1C where the two sides never
contend; Array's pre-allocated array wins at 4P/4C where producers contend with
each other anyway and per-item allocation is what's left to pay for — **but only
in 5 of 7 runs each**. Background load flips it.

That is the real lesson, and it is worth more than the table: **a 10% difference
between two queues is not a design input, it is noise you will re-measure next
Tuesday.** Pick by *semantics* (bounded? priority? rendezvous?) and let a
benchmark settle only the genuine ties — on your own hardware, several times.

### 3. The verb grid — the other half of the API

Every queue offers three verbs per direction. Choosing the wrong one is a bug,
not a style preference:

| | rejects immediately | blocks | blocks with deadline |
|---|---|---|---|
| **insert** | `offer(e)` → false | `put(e)` | `offer(e, time, unit)` |
| **remove** | `poll()` → null | `take()` | `poll(time, unit)` |

- `poll()` on an empty queue returns `null` **right now**. A `while` loop around
  it is a busy-wait: functionally correct, and it burns a core per idle worker.
  This is what Ex7's and Ex8's CPU-time assertions exist to catch — it passes
  every correctness test ever written and shows up only as an infrastructure bill.
- `take()` parks. Correct, but uninterruptible by flags (see P3).
- **Production consumer loops almost always want `poll(timeout)`**: it parks like
  `take`, but surfaces periodically so a shutdown check gets a look-in.

### 4. Shutdown is part of the protocol

Three attempts, measured in D15 on a 10,000-item backlog:

| Attempt | Result | Verdict |
|---|---|---|
| `volatile done` + `take()` | **hangs forever** — `WAITING`, `alive=true` after 2s | broken (P3) |
| `interrupt()` | processed 8,744 / 10,000 — **1,256 abandoned** | correct *only* if dropping work is acceptable |
| **poison pill** | processed **10,000 / 10,000**, worker exited on its own | exact |

**The poison pill** makes shutdown an *in-band message*: a sentinel sent through
the same queue as the data, one per consumer. Because the queue is FIFO, the
pill necessarily arrives **after** every item submitted before it — so "saw the
pill" *proves* "drained everything". No race, no timeout to tune, no lost items.

```java
private static final String PILL = new String("POISON");  // new: identity matters

while (true) {
    String item = queue.take();
    if (item == PILL) return;        // == not equals()
    process(item);
}
```

Compare with `==`, never `equals()`. It must be *that exact object* — otherwise a
user who legitimately submits the string `"POISON"` kills a worker early. This
is why the pill is a deliberately unique instance.

**N consumers need N pills** — each pill stops exactly one taker.

And this is precisely the distinction one layer up:

| Pipeline | `ExecutorService` |
|---|---|
| poison pill → drain, then stop | `shutdown()` → finish the queue, then stop |
| `interrupt()` → stop now, drop the backlog | `shutdownNow()` → interrupt, **return** the undone tasks |

---

## 🧪 EXERCISE 8 — `Ex8Pipeline`

```bash
cd concurrency-lab
./mvnw test -Dtest='ExerciseTests$Ex8'
```

The first exercise where the broken version **mostly works** — items flow
through and get processed. It is broken the way production systems are broken:
three latent defects that each surface under a different condition, and each one
was a demo. One test per defect, so you fix them one at a time:

| Test | Fails because | Demo |
|---|---|---|
| `appliesBackpressureInsteadOfGrowingTheBacklog` | backlog reached 1,992 with capacity 16 | D13 |
| `workersParkWhileIdleInsteadOfSpinning` | 4 idle workers burned 3,836 ms of CPU in 1,000 ms of wall clock | D14 |
| `shutdownDrainsEveryAcceptedItem` | submitted 5,000, processed 668 — 4,332 abandoned | D15 |

You already own both halves of the fix: the bounded blocking queue is Ex7 (here
you may use `ArrayBlockingQueue` — you've earned it), and the shutdown is D15's
poison pill.

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, THEN open. Miss one → reread that section.*

<details><summary><b>Q.</b> Why is an unbounded queue a production hazard rather than a convenience?</summary>

It doesn't remove the producer/consumer speed mismatch; it hides the deficit in
the heap. Measured: an unbounded queue accumulated 1,000,000 items (~140 MB) in
419 ms while consuming 7,116, versus a bounded one that stayed flat at 1,024
items. The failure moves from "slow now" (visible, recoverable, at the edge) to
`OutOfMemoryError` later, in an unrelated thread, nowhere near the cause. When a
producer outruns a consumer you may block, drop, or grow the backlog — unbounded
is choosing "grow" while feeling like you chose nothing.
</details>

<details><summary><b>Q.</b> Name the three verbs for removing from a <code>BlockingQueue</code> and when each is right.</summary>

`poll()` returns null immediately — right for "check and move on", and a
**busy-wait** if you loop on it (burns a core per idle worker). `take()` parks
until an item arrives — correct, but a parked thread can't observe a shutdown
flag. `poll(timeout, unit)` parks *with* a deadline — what production consumer
loops almost always want, because it blocks efficiently yet wakes periodically
so shutdown checks get a look-in.
</details>

<details><summary><b>Q.</b> A stop flag is <code>volatile</code> and the consumer still never exits. But in D4 <code>volatile</code> was the fix. What's different?</summary>

In D4 the bug was **visibility**: the JIT hoisted a non-volatile read out of the
loop, so the read never happened. Here the flag is fully visible and the read
would happen — except the thread is **parked inside `take()`** on an empty queue,
and a parked thread executes nothing, so it never reaches the check. Nothing will
wake it, because the producer that would have is the one that set the flag and
left. Visible ≠ awake. Fix with an interrupt (abandons the backlog) or a poison
pill (drains it).
</details>

<details><summary><b>Q.</b> Explain the poison pill, and why it's compared with <code>==</code>.</summary>

A sentinel object sent through the *same queue* as the data, turning shutdown
into an in-band message. Because the queue is FIFO, the pill arrives after every
item submitted before it, so "saw the pill" proves "drained everything" — no
race, no timeout to tune, no loss. N consumers need N pills; each stops exactly
one taker. Compared with `==` because it must be *that exact instance*:
`equals()` would let a user who legitimately submits the string `"POISON"` kill a
worker early.
</details>

<details><summary><b>Q.</b> <code>SynchronousQueue</code> holds nothing. What is it for?</summary>

Direct hand-off. `offer()` succeeds only if a consumer is *already parked* in
`take()`, and fails instantly otherwise — which makes "nobody is free" an
immediate signal rather than something buried in a buffer.
`Executors.newCachedThreadPool()` is built on exactly this: offer the task; if it
fails, spawn a thread. Zero capacity is the feature.
</details>

<details><summary><b>Q.</b> Why did <code>ArrayBlockingQueue</code> beat <code>LinkedBlockingQueue</code> at 4P/4C but lose at 1P/1C — and how much should you trust that?</summary>

The mechanism: `LinkedBlockingQueue` has two locks (put and take), so at 1P/1C
the single producer and single consumer never contend — 253 ms vs 289 ms. At
4P/4C the producers contend with each other on the put-lock anyway, so the split
buys nothing, and what's left decides it: `ArrayBlockingQueue` holds a
pre-allocated array while `LinkedBlockingQueue` allocates a node per item and
hands the GC a million corpses — 134 ms vs 180 ms.

**Trust it barely.** Each of those two results held in only 5 of 7 runs;
background load flips them. A ~10% gap between two queues is noise, not a design
input — choose by semantics, not by this table.

What *did* replicate every time: `ArrayBlockingQueue` is faster at 4P/4C than at
1P/1C (134 vs 289), because park/unpark rather than lock contention is the
dominant cost; and `SynchronousQueue` is worst at 4P/4C, because zero capacity
means zero slack to absorb a scheduling hiccup.
</details>

<details><summary><b>Q.</b> Map pipeline shutdown onto <code>ExecutorService</code>.</summary>

Poison pill ≡ `shutdown()`: stop accepting, finish what's queued, then exit.
`interrupt()` ≡ `shutdownNow()`: interrupt the workers, abandon the backlog (and
`shutdownNow` at least *returns* the undone tasks so you can decide what to do
with them). Same two semantics — drain vs abandon — one layer up. Neither is
"correct"; the bug is failing to choose.
</details>

---

## 🃏 FLASHCARDS

```
Producer outruns consumer — options	Block (backpressure) · Drop (shed) · Grow (OOM later). No fourth.
Unbounded queue, measured	1,000,000 items / ~140 MB in 419ms vs bounded flat at 1,024
Capacity is	WHERE YOUR SYSTEM FAILS, chosen on purpose — not a tuning knob
The decision rule	Bounded unless you can PROVE the producer is rate-limited
poll() vs take() vs poll(timeout)	null now (loop = busy-wait) · parks (deaf to flags) · parks but wakes — use this
volatile flag + take()	HANGS. Parked thread executes nothing → re-checks nothing. Visible ≠ awake
D4 hang vs D15 hang	Read optimised away  vs  reader is parked. Same symptom, opposite cause
interrupt() shutdown	Prompt AND lossy — measured 1,256 of 10,000 abandoned
Poison pill	In-band sentinel, FIFO ⇒ arrives after every real item ⇒ "saw pill" proves "drained"
Pill compared with	== never equals() — must be THAT instance; N consumers need N pills
Pill : interrupt ::	shutdown() : shutdownNow()  — drain vs abandon
SynchronousQueue	Capacity ZERO, rendezvous. offer() fails unless a taker is parked → newCachedThreadPool
ArrayBQ vs LinkedBQ	1 lock + prealloc array  vs  2 locks (put/take) + node per item
Measured 1P/1C vs 4P/4C	ArrayBQ 289/134 — FASTER with more threads (park/unpark dominates, held 7/7 runs)
DelayQueue	Items invisible until their delay expires — how ScheduledThreadPoolExecutor works
PriorityBlockingQueue	Unbounded and NOT FIFO — both facts bite
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| Layer | The same three-way choice |
|---|---|
| `ThreadPoolExecutor` (topic 8) | `CallerRunsPolicy` (block-ish) · `AbortPolicy`/`DiscardPolicy` (drop) · unbounded queue (grow) |
| Kafka producer | `max.block.ms` (block) · `buffer.memory` exhausted → throw (drop) |
| Spring `ThreadPoolTaskExecutor` | `queueCapacity` — [`../03-async-and-scheduling/01-thread-pools-completablefuture.md`](../03-async-and-scheduling/01-thread-pools-completablefuture.md) |
| TCP | receive window = backpressure, in the protocol itself |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)

**Interleaved pass (R4) — pull these forward from earlier topics:**
1. *(Topic 2)* A plain `boolean` stop flag never stops a worker loop. Now contrast: a **volatile** flag never stops a worker blocked in `take()`. Explain both mechanisms without using the word "cache".
2. *(Topic 4)* Ex7 built a bounded queue from one lock and two `Condition`s. Why two rather than one `notifyAll()`? Tie that to why `ArrayBlockingQueue` beat `LinkedBlockingQueue` at 4P/4C here.
