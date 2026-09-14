# Topic 3 — Races, Atomicity & Compare-And-Swap
**Demos:** `t03atomicity/D7`, `D8`, `D9` | **Exercises:** `Ex1Counter`, `Ex3Inventory`

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** Atomicity bugs live **in the gap between steps** of things
that look like one step. `count++` is read-modify-write (three bytecodes);
`if (stock > 0) stock--` is check-then-act. Making the *pieces* atomic doesn't
close the gap — it only makes the bug rarer, which is worse. Either the whole
compound action is one indivisible step (CAS loop, lock, or a DB
`UPDATE...WHERE`), or it's broken.

```
volatile int counter, 8×100k increments:  lost 72%   ← volatile is NOT a fix
"atomic decrement only" half-fix:         oversells 2% of trials — rarer, still broken
CAS retry loop / synchronized:            correct
```

**Five rules you must never get wrong:**
1. `volatile` = visibility + ordering. **Never** atomicity of `x++`.
2. "It happens less often now" is not a fix — rare bugs survive test suites and ship.
3. CAS = "set only if still equals expected," one CPU instruction; every `Atomic*` mutator is a retry loop over it. Lock-free, deadlock-free, retry-storms under heavy contention.
4. ABA: CAS compares values, not history — fix with `AtomicStampedReference` (a version stamp), which is exactly JPA `@Version`.
5. Invariant spans >1 variable or >1 JVM? CAS can't help — lock (in-process) or push the invariant into the database.

*Run first: D7 (lost updates), D8 (overselling + the half-fix), D9 (LongAdder vs AtomicLong).*

---

## `count++` is three operations

```
getfield  count     // 1. READ
iconst_1
iadd                // 2. MODIFY
putfield  count     // 3. WRITE
```

Two threads can both read `7`, both compute `8`, both write `8`. Two
increments, one net effect — a **lost update**. This is a *read-modify-write*
race and it is the most common concurrency bug in business code.

### Measured (D7, 8 threads × 100,000 increments, expecting 800,000)

| Implementation | Result | Lost |
|---|---|---|
| `int` | 118,908 | **85%** |
| `volatile int` | 227,116 | **72%** |
| `AtomicInteger` | 800,000 | 0 |
| `synchronized` | 800,000 | 0 |

**Stare at the `volatile` row.** It loses on the same catastrophic scale. Which
of the two loses more shifts run to run, because all `volatile` changed was the
*timing* — it forces a real memory access each iteration, so the loop is slower
and the overlap window moves. The semantics are untouched: both threads still
read the same value and both still write it back.

> `volatile` = visibility + ordering.
> Atomicity of read-modify-write = `Atomic*` or a lock.
> Different problems. Volatile solves only the first.

---

## Check-then-act: how 100 units of stock sell 138

```java
if (stock > 0) {     // CHECK — true for thread A and thread B simultaneously
    stock--;         // ACT   — both decrement
    return true;     // both callers believe they reserved a unit
}
```

The check and the act are individually fine. The bug is in the gap: the
condition A verified is no longer true by the time A acts on it. This is a
**compound action**, and making the individual steps atomic does not fix it.

Not academic — this is overselling inventory, double refunds, two users
claiming the same username, duplicate order numbers.

### The tempting half-fix that isn't

```java
if (stock.get() > 0) {          // atomic read...
    stock.decrementAndGet();    // ...atomic write, but a SEPARATE step
    return true;
}
```

**D8, 500 trials each:**

| Implementation | Oversold in | Worst case |
|---|---|---|
| plain check-then-act | **13%** of trials | 38 units |
| "atomic decrement only" | **2%** of trials | 3 units |
| CAS retry loop | never | — |
| `synchronized` | never | — |

The half-fix doesn't look harmless — it looks *better*. Rarer, smaller. That is
the worst possible property for a bug: rare enough to survive your test suite,
rare enough in production to look like a data-entry mistake, and permanent.

**"It happens less often now" is not a fix.** Either the check and the act are
inside one indivisible step, or they are not.

### Three real fixes

| Fix | When |
|---|---|
| **CAS retry loop** | state is a single variable; lock-free |
| **`synchronized` / `ReentrantLock`** | the invariant spans several fields |
| **Push it into the database** — `UPDATE … WHERE stock >= ?`, or `@Version` | more than one JVM is involved |

That last row matters: an in-process lock protects exactly one process. Two
pods behind a load balancer need the invariant in the database. See
[../01-foundations/05-transactions-isolation-locking.md](../01-foundations/05-transactions-isolation-locking.md) — optimistic locking is this same
compare-and-set idea one layer down.

---

## Compare-And-Swap

`compareAndSet(expected, next)` atomically says: *"if this field is still
`expected`, set it to `next` and return true; otherwise change nothing and
return false."* One CPU instruction (`lock cmpxchg` on x86, `LDREX/STREX` on
ARM). No OS involvement, no thread ever parks.

Every `Atomic*` mutator is a retry loop over it:

```java
int incrementAndGet() {
    while (true) {
        int current = get();
        if (compareAndSet(current, current + 1)) {
            return current + 1;
        }
        // lost the race — somebody wrote first. Re-read and retry.
    }
}
```

This is **lock-free** (the system always makes progress) but not **wait-free**
(this particular thread could in theory retry forever).

### Optimistic vs pessimistic — the same split as your database

| | Lock (pessimistic) | CAS (optimistic) |
|---|---|---|
| Assumes | conflict is likely | conflict is rare |
| On conflict | park the thread (~1–10 µs context switch) | retry the loop (nanoseconds) |
| Fails badly when | contention is low — pays for nothing | contention is high — retry storm |
| Deadlock possible | yes | **no** — nothing is held |

Exactly `@Version` vs `PESSIMISTIC_WRITE`. Same trade-off, different layer.

### The ABA problem

CAS compares **values, not history**. If the value goes A → B → A while your
thread was preparing, your `compareAndSet(A, …)` succeeds even though the world
changed underneath you.

Harmless for a counter. A genuine correctness bug for a lock-free stack, where
the node you're about to pop may have been freed and recycled.

Fix: `AtomicStampedReference`, which CASes a `(value, stamp)` pair. A database
`@Version` column is precisely the same trick.

---

## `LongAdder` vs `AtomicLong`

`AtomicLong` has every thread CAS **one** memory word. Under contention most
attempts fail and retry, and that word's cache line ping-pongs between cores.

`LongAdder` keeps an array of per-thread cells, each padded onto its own cache
line. Threads increment different cells and never contend. `sum()` adds them on
demand.

### Measured (D9, 500k increments per thread)

| Threads | `AtomicLong` | `LongAdder` | `synchronized` |
|---|---|---|---|
| 1 | 24 ms | 15 ms | 21 ms |
| 2 | 25 ms | 17 ms | 39 ms |
| 4 | 57 ms | 12 ms | 107 ms |
| 8 | 107 ms | 12 ms | 313 ms |
| 16 | 222 ms | **23 ms** | 706 ms |

Read the columns down, not across. `AtomicLong` degrades ~9× from 1 to 16
threads; `LongAdder` stays flat.

**Trade-off:** writes scale nearly linearly, but `sum()` is O(cells) and is
**not an atomic snapshot**.

| Use | Why |
|---|---|
| `LongAdder` | write-heavy metrics you read rarely — this is what Micrometer's `Counter` uses |
| `AtomicLong` | you need the value each operation *returns* (ID generation) |
| `synchronized` | the invariant spans more than one variable |

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, THEN open. Miss one → reread that section above.*


<details><summary><b>Q.</b> Why isn't <code>volatile int count; count++</code> thread-safe?</summary>

`count++` is read-modify-write — three bytecodes. `volatile` makes each
individual read and write fresh and ordered, but says nothing about the gap
between them. Two threads still both read 7, both compute 8, both write 8.
Measured: a volatile counter lost ~72% of 800,000 increments. Use
`AtomicInteger`, `LongAdder`, or a lock.
</details>

<details><summary><b>Q.</b> Explain compare-and-swap.</summary>

A single atomic CPU instruction: "if this word still equals X, replace it with
Y and report success, else report failure and change nothing." `Atomic*` wraps
it in a retry loop. Lock-free — no thread parks, so no deadlock — but it can
livelock-ish under heavy contention as retries pile up, which is where
`LongAdder` or a lock wins.
</details>

<details><summary><b>Q.</b> What is the ABA problem?</summary>

CAS compares values, not history. If a value goes A→B→A between your read and
your CAS, the CAS succeeds despite the intervening change. Irrelevant for
counters; a real bug for lock-free data structures where the recycled value is
a pointer to a different object. Fix with `AtomicStampedReference` — CAS the
(value, version) pair — which is exactly what a JPA `@Version` column does.
</details>

<details><summary><b>Q.</b> <code>AtomicLong</code> or <code>LongAdder</code>?</summary>

`AtomicLong` contends on one cache line; `LongAdder` shards across padded
per-thread cells, so writes scale nearly linearly — ~10× faster at 16 threads
in my benchmark. But `sum()` is O(cells) and isn't an atomic snapshot, and
`LongAdder` has no `getAndIncrement`. So: `LongAdder` for metrics counters,
`AtomicLong` when you need the returned value.
</details>

<details><summary><b>Q.</b> What's a check-then-act race, and how do you fix it?</summary>

A compound action where the condition you verified can change before you act on
it — `if (stock > 0) stock--` overselling inventory. Making just the mutation
atomic doesn't fix it; it only makes it rarer, which is worse because it then
survives testing. Real fixes: a CAS retry loop that re-reads *and re-checks*
inside the loop, a lock around the whole compound action, or push the invariant
into the database with `UPDATE … WHERE stock >= ?` or `@Version` — which is the
only option once more than one JVM is involved.
</details>

<details><summary><b>Q.</b> When is a lock better than an atomic?</summary>

When the invariant spans more than one variable. CAS operates on a single word,
so "decrement stock *and* append to the reservation list, atomically" cannot be
done with atomics. Also when contention is high enough that retry storms cost
more than parking.
</details>

---

## 🃏 FLASHCARDS

```
count++ is really	READ, MODIFY, WRITE — three bytecodes, racy gap between them
volatile counter, 8×100k	Lost ~72% — volatile changes timing, not semantics
Check-then-act bug location	The GAP: the checked condition is stale by the act
Half-fix (atomic pieces only)	Rarer bug = worse bug — survives tests, ships to prod
Three real fixes	CAS retry loop (1 variable) · lock (multi-field invariant) · DB UPDATE...WHERE / @Version (multi-JVM)
CAS in one sentence	"If still equals expected, set to next" — one CPU instruction, no parking
Lock-free vs wait-free	System always progresses vs THIS thread always progresses (CAS loop is only lock-free)
ABA problem	CAS compares values not history; A→B→A slips through → AtomicStampedReference / @Version
AtomicLong vs LongAdder @16 threads	222ms vs 23ms — adder shards per-thread cells, no shared cache line
LongAdder trade-off	sum() is O(cells) and NOT an atomic snapshot; no getAndIncrement
When lock beats atomic	Invariant spans >1 variable, or contention makes retry storms cost more than parking
```

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
