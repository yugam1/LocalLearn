# Topic 6 — Shared Structures: `ConcurrentHashMap`, `CopyOnWriteArrayList`, `ThreadLocal`
**Demos:** `t06shared/D16`, `D17`, `D18` | **Exercises:** `Ex9EventCounts`, `Ex10Context`

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** A thread-safe collection makes **each call** atomic. It cannot
make **your sequence of calls** atomic, because it has no idea which of your
calls belong together. So `ConcurrentHashMap` fixes the corruption and leaves
the race — and the race is the bug you already met twice, in D8 and Ex6. There
are exactly three answers to "two threads touch this": **coordinate** it (topics
3–4), **copy** it (`CopyOnWriteArrayList`), or **confine** it (`ThreadLocal`).
The last two look free and are not.

```
HashMap, 8 threads x 5,000 distinct keys (expect 40,000):
    lost 5.3%–31.9%  ·  or a worker never returns from put()  ·  or ClassCastException
ConcurrentHashMap, map.put(k, map.get(k)+1):     still loses 0.7%–13.3%
ConcurrentHashMap, map.merge(k, 1L, Long::sum):  exact, every trial
                                                 ↑ the map never changed. The CALL did.
```

**The decision rule:** **if the new value depends on the old one, it must be one
call** — `merge`, `compute`, `computeIfAbsent`, `putIfAbsent`, `replace(k, old,
new)`. Two calls is a race no matter how thread-safe each one is.

**Five rules you must never get wrong:**
1. `containsKey`-then-`put` and `get`-then-`put` are **check-then-act**. Same bug as D8's overselling and Ex6's stampede, third outfit. Measured: 11–15% of 200 trials elected more than one "first" leader.
2. The mapping function runs **under the bin lock**. Keep it short, never touch the same map inside it, and for a slow load cache a `CompletableFuture` instead of the value.
3. `size()` on a busy `ConcurrentHashMap` is an **estimate** (a sharded count, summed one cell at a time). Fine for a dashboard; `if (map.size() < CAP) put(...)` is D8 with a lie in the check.
4. `CopyOnWriteArrayList` costs **O(n) per write**, so building one is O(n²) — measured at 19.6 µs *per element* at 64,000 elements. Right for listener lists, catastrophic for anything that accumulates.
5. A `ThreadLocal` value lives as long as the **thread**, and a pooled thread outlives your request on purpose. `remove()` in a `finally` — and `remove()`, never `set(null)`, which leaves the entry behind.

*Run first: D16 (three different ways a HashMap breaks), D17 (the same old bug on
a thread-safe map, and per-bin locking measured), D18 (copy-on-write's real bill,
and a tenant id leaking into the next request).*

---

## 🔮 PREDICT FIRST

*Answer aloud before opening. A wrong prediction you then correct is worth more
than three right ones — that is the whole point of this zone.*

<details>
<summary><b>P1.</b> A per-key counter does <code>map.put(k, map.get(k) + 1)</code> from 16 threads. You change the field from <code>HashMap</code> to <code>ConcurrentHashMap</code>. How many of the 32,000 increments are lost now?</summary>

**Between 0.7% and 13.3% of them — the change fixed nothing.** Measured (D17,
five runs of five trials each):

| Implementation | Counted, of 32,000 |
|---|---|
| `HashMap` + `get`/`put` | 29,976 – 31,908 |
| **`ConcurrentHashMap` + `get`/`put`** | **27,735 – 31,787** |
| `ConcurrentHashMap.merge(k, 1L, Long::sum)` | 32,000, every trial |
| `ConcurrentHashMap.compute(k, (k,v) -> …)` | 32,000, every trial |

Stare at the middle row, the way topic 3 had you stare at its `volatile` row. The
map is genuinely thread-safe. `get` is atomic. `put` is atomic. And
`put(k, get(k) + 1)` is `count++` from D7 with extra ceremony: two threads read
7, both compute 8, both write 8.

`ConcurrentHashMap` was never able to help here, because it cannot see that your
`get` and your `put` were meant to be one operation. The only way to tell it is
to say so, in one call.
</details>

<details>
<summary><b>P2.</b> A 1,000-element list shared by 8 threads, 90% reads and 10% writes. <code>CopyOnWriteArrayList</code> or <code>Collections.synchronizedList</code>?</summary>

**It is a coin flip, and that is the interesting answer.** Measured (D18, best of
three runs per cell after warm-up, repeated 5 times — speedup of COW over
`synchronizedList`, so above 1.0 means COW wins):

| write ratio | speedup across 5 runs |
|---|---|
| 0% | 20.0× – 31.2× |
| 0.1% | 19.8× – 32.3× |
| 1% | 8.5× – 12.0× |
| 5% | 1.5× – 2.8× |
| **10%** | **0.91× – 1.39× — it landed on both sides of 1.0** |

**The 10% row did not replicate and I am not going to pretend it did.** Three of
five runs had COW ahead, two had it behind. If your decision depends on that
row, your decision is being made by whatever else the machine was doing.

What *did* replicate, 5 runs out of 5, is the shape: a huge advantage with no
writes, decaying monotonically as writes arrive, gone by 10%. And the useful
conclusion is not on the table at all — it is that **"reads dominate" is not the
rule**, exactly as it was not the rule for `ReadWriteLock` in D12. The rule is
*writes are rare **events***: a listener list written at startup, a handler chain
written when a module registers. A list that grows once per request has no rare
writes, whatever its read ratio.
</details>

<details>
<summary><b>P3.</b> A servlet filter binds a request id into a <code>ThreadLocal</code> and clears it with <code>ID.set(null)</code> inside a <code>finally</code>. The <code>finally</code> is definitely there. Is anything leaked?</summary>

**Yes — the map entry.** `set(null)` stores a null *into the existing entry*. The
entry, and its reference to the `ThreadLocal` key, stay in the thread's
`ThreadLocalMap`. On a pool thread that map is never collected, so the slot is
never reclaimed.

D18 proves this without touching reflection, using a `ThreadLocal` that has an
`initialValue()`:

| call | `get()` returns | what it tells you |
|---|---|---|
| first `get()` | `fresh-1` | entry created, `initialValue()` ran |
| `set("explicit")`, `get()` | `explicit` | — |
| `set(null)`, `get()` | **`null`** | `initialValue()` did **not** run — the entry is still there |
| `remove()`, `get()` | **`fresh-2`** | `initialValue()` ran again — the entry was genuinely gone |

`initialValue()` ran exactly twice across that sequence. If `set(null)` had
removed the entry, the third row would have said `fresh-2` as well.

So the correctness bug is invisible (both spellings make `get()` return null) and
only the leak differs. **`remove()`, never `set(null)`.**
</details>

---

## 📖 THE STORY

### 1. What a `HashMap` actually does when two threads write to it

A `HashMap` is an array of bins. `put` computes a bin index from the key's hash,
walks that bin's list, and links a new node into it. Two things in that sentence
are not atomic and nobody made them so.

**Linking into a bin.** Two threads whose keys land in the same bin both read the
same `tab[i]` head pointer and both write a new node into it. The second write
overwrites the first. One entry, silently gone.

**Resizing.** Past the load factor the map allocates a bigger table and rehashes
everything into it — a long, multi-step operation on the shared `table` field. A
thread putting during someone else's resize may write into the *old* table, which
is about to be discarded, so the entries written during that window vanish
together. That is why the loss is lumpy and the percentage swings so wildly.

> **It is not a CPU-cache visibility problem.** Marking the map field `volatile`
> fixes exactly nothing — the reference was never the thing being raced on. The
> race is on the map's internal table array, two objects further down, and
> `volatile` on a field says nothing whatsoever about the object it points at.
> This is the same distinction D4 and D15 forced: a visibility bug and a
> structural race look identical from the outside and have nothing in common
> underneath.

**"Not thread-safe" is a family of failures, not one.** D16 runs 8 threads × 5,000
*distinct* keys — not one duplicate in the whole run, so the answer must be
exactly 40,000. Across five runs I saw all three of these:

| Outcome | Frequency | Severity |
|---|---|---|
| Entries lost | every run, **5.3% – 31.9%** | wrong data, healthy process |
| A worker **never returns from `put()`** | **5 runs out of 5** | a core burns forever; `interrupt()` is ignored (D3 — it is not blocked, it is looping) |
| `ClassCastException: HashMap$Node cannot be cast to HashMap$TreeNode` | seen once | the bin's own record of its structure is false |

`Collections.synchronizedMap` and `ConcurrentHashMap` returned 40,000 every
single time.

People repeat "the infinite loop was fixed in Java 8" as though the structure
became safe. JDK 8 did rewrite resize to kill JDK 7's specific cycle, and a
worker still failed to come back out of `put` in every run I did. Outcomes 2 and
3 are strictly worse than outcome 1, because outcome 1 at least leaves the
process healthy — and which one you get is a coin flip, which is why "we tested
it and it worked" carries no information here.

### 2. The two things `ConcurrentHashMap` deliberately does not promise

**`size()` is an estimate.** There is no single counter to read, because one
would be precisely the contended cache line the whole design exists to avoid —
the count is sharded across striped cells, the `LongAdder` mechanism from D9,
built in. `size()` sums those cells one at a time, so the number belongs to no
single instant.

Measured (D16, six writers hammering the map, ~1–2 million samples per run):

| | across 5 runs |
|---|---|
| worst **shortfall** vs puts that had already returned | **0**, every run |
| worst **surplus** — puts that landed mid-sum | 21,051 – 80,805 |
| final `size()` once quiescent | exact, every run |

The surplus column is the honest one. `size()` never undercounted work that had
already finished; it happily *included* puts that completed after the sum began.
It is a recent plausible number, not a reading at time T, and for a settled map
it is exact.

**Iterators are weakly consistent.** They never throw
`ConcurrentModificationException`, they walk the table as it exists while they
walk it, and they may or may not reflect writes made after they started. A
`HashMap` iterator instead fails fast — D16 got a `ConcurrentModificationException`
from it in 5 runs out of 5. Fail-fast is **not a thread-safety mechanism**; it is
a best-effort bug detector that is allowed to miss, and by the time it fires the
damage is done.

In the same run, `ConcurrentHashMap`'s iterator completed every walk without
throwing, and its own element count disagreed with a `size()` taken immediately
beforehand in 4–6 of the ~40 passes. Neither number is wrong. There is simply no
instant that both of them describe.

### 3. The same bug, third outfit

Neither caveat above is a bug. Treating `size()` as a fact you may then act on
**is** a bug, and so is every other check-then-act on a thread-safe map:

```java
if (map.size() < CAP) {          // CHECK — atomic, correct, and instantly stale
    map.put(key, value);         // ACT   — atomic, correct, and too late
}
```

D16 runs exactly that with `CAP = 1,000` and 8 threads. It overshot by **0 to 5
entries** per trial, in most trials of most runs.

Five is a small number, and that is the trap, exactly as D8 taught it: the
"atomic decrement only" half-fix oversold by 3 units instead of 38 and was *worse
for it*, because rare enough to survive a test suite is rare enough to ship.

You have now met this shape three times:

| Where | The code | What it cost |
|---|---|---|
| **D8** | `if (stock > 0) stock--` | sold 138 units of a 100-unit product |
| **Ex6** | `if (cache.get(k) == null) load(k)` | one loader ran 32 times, against the service it was protecting |
| **Here** | `if (!map.containsKey(k)) map.put(k, me)` | every caller told it was first |

That last one, measured (D17, 32 threads racing for one key, 200 trials):

| | elected more than one leader | worst case |
|---|---|---|
| `containsKey`-then-`put` | **11% – 15% of trials** | 2 – 7 leaders |
| `putIfAbsent` | never, 200/200 | — |

Read the first row the way D8 taught you: **it is mostly fine.** Mostly fine is
the property that gets a bug past code review, past the test suite, and into the
release — and in a real service this one is two schedulers both believing they
own the nightly job.

### 4. The fix: make the map do the whole thing

Every one of these performs a *whole* read-modify-write while holding the bin
lock, so nothing can interleave:

| Method | Use it for |
|---|---|
| `putIfAbsent(k, v)` | insert only if absent; **returns the incumbent**, so `null` means you won |
| `computeIfAbsent(k, fn)` | compute-and-insert only if absent — Ex6's answer |
| `computeIfPresent(k, fn)` | update only if present |
| `compute(k, (k,v) -> …)` | the general form; **return `null` to remove the entry** |
| `merge(k, v, (old,new) -> …)` | insert `v` if absent, else combine — the counter idiom |
| `replace(k, expected, new)` | compare-and-swap on a map entry — D9's CAS, keyed, ABA caveat and all |

`compute` returning `null` to delete is worth memorising. It is how you satisfy
"a key whose count reaches zero must disappear" *inside the same atomic step*,
rather than with a follow-up `remove()` that would reopen the gap you just
closed. That is the crux of Exercise 9.

### 5. Why this beats a `synchronized` block — measured

Both a `synchronized` block and `computeIfAbsent` give you compute-once. Only one
of them lets two threads load two *different* keys at the same time, because
`ConcurrentHashMap` locks the **bin** — the single table slot the key hashes to —
not the map.

**Measured (D17, 8 threads, 8 different cold keys, a 200 ms loader each, 5 runs):**

| | wall time |
|---|---|
| `synchronized (map) { get / load / put }` | **1,621 – 1,652 ms** |
| `map.computeIfAbsent(key, loader)` | **202 – 211 ms** |

Eight sequential loads versus eight parallel ones, ~8×, and it held in all five
runs with barely any spread — the numbers are dominated by a 200 ms sleep, so
there is very little for noise to move. The global lock serialises eight
*unrelated* loads behind each other, which is the exact bottleneck the cache
existed to remove. This is also why D12's `ReadWriteLock` advice ended with
"map-shaped data → `ConcurrentHashMap`": it **shards** the locking instead of
sharing it.

**Two rules for the mapping function**, both consequences of the same fact — it
runs while the bin lock is held:

1. **Keep it short.** Everything hashing to that bin is waiting on it. For a
   genuinely slow load, cache a `CompletableFuture` instead of the value: the
   future is inserted immediately and the lock is released while the work runs.
2. **Do not touch the same map inside it.** D17 measures both halves of this:

   | inner call | result, 5 runs out of 5 |
   |---|---|
   | same bin (keys 0 and 16 in a 16-slot table) | `IllegalStateException: Recursive update` |
   | different bin (keys 0 and 7) | no exception at all |

   `computeIfAbsent` parks a reservation node in the empty bin before running the
   function, and a nested call that finds that reservation refuses — because the
   only alternative is waiting on a bin this very thread is in the middle of
   writing. **The absence of an exception is not evidence that your mapping
   function is safe**; it only means the keys did not collide this time.

### 6. Copy, or confine — the two ways of not sharing at all

Everything so far answered "two threads touch the same mutable state" with
coordination. There are two other answers, and neither takes a lock on the read
path.

#### `CopyOnWriteArrayList` — copy it

Readers walk an immutable array that nobody will ever modify, so reads need no
lock and no per-element volatile read. Writers take a lock, clone the entire
array, modify the clone, and publish it to the `volatile` array field.

"Reads are lock-free" is the half of the sentence people repeat. The other half
is that **every write is O(n)**, so building a list one `add` at a time copies
1 + 2 + … + n elements. Measured (D18, best of three after warm-up, 3 runs):

| elements | `ArrayList` total | COW total | **COW ns per element** |
|---|---|---|---|
| 4,000 | 0–1 ms | 4 ms | 1,117 – 1,208 |
| 8,000 | 1 ms | 20–21 ms | 2,597 – 2,746 |
| 16,000 | 2 ms | 78–86 ms | 4,905 – 5,421 |
| 32,000 | 1 ms | 294–295 ms | 9,215 – 9,243 |
| 64,000 | 1–2 ms | **1,252 – 1,264 ms** | **19,572 – 19,763** |

Read the last column, not the middle one. `ArrayList`'s amortised cost per add is
flat; the COW list's **doubles every time the list doubles**. That is what O(n²)
looks like when you meet it in a profiler: not a slow function, a function whose
cost depends on how much you have already done. The trend is textbook and it
replicated tightly in all three runs.

Its iterator is a **true snapshot**, taken at `iterator()` and immune to
everything afterwards:

```
list is now      [audit, metrics, slack]
the iterator saw [audit, metrics, email]
iterator.remove() -> UnsupportedOperationException
```

That is stronger than `ConcurrentHashMap`'s weak consistency and it is exactly
what you want for dispatching an event to listeners: a listener registering
mid-dispatch cannot corrupt the walk and cannot half-receive the event. You pay
for it with the copy.

| Right for | Wrong for |
|---|---|
| listener lists, handler chains, feature-flag sets | caches, session sets, request accumulators |
| small, written at startup or on registration | anything that grows with traffic |
| writes are rare **events** | writes are merely a small **percentage** |

#### `ThreadLocal` — confine it

A `ThreadLocal` is not a magic per-call variable. It is a `ThreadLocalMap`
hanging off `Thread` itself, keyed by the `ThreadLocal` object. **The value lives
exactly as long as the thread does** — and a pool thread is designed to outlive
your request.

D18, on a single-thread pool:

```
request 1 (tenant acme-corp) ran on pool-worker and did NOT clean up
request 2 (no tenant of its own) ran on pool-worker and sees tenant = acme-corp
```

Request 2 read a value it never set, belonging to a customer it has nothing to do
with, and nothing anywhere threw. With `remove()` in a `finally`, request 3 threw
*and* request 4 still saw `null`.

This **is** the MDC rule from
[../01-foundations/07-logging-mdc-correlation-ids.md](../01-foundations/07-logging-mdc-correlation-ids.md). MDC is a `ThreadLocal`
map, and `MDC.clear()` in a `finally` is non-negotiable for exactly this reason.
A correlation id bleeding into the next request's log lines is the mild version;
the same mechanism carrying a tenant id or a security principal is the version
that becomes an incident.

Three consequences to carry:

1. **`remove()` in a `finally`.** After the work is not good enough — an
   exception skips it.
2. **`remove()`, not `set(null)`.** See P3: `set(null)` leaves the entry, and on
   a pool thread that lives forever, so does the entry. That is a memory leak on
   top of the correctness bug.
3. **It does not follow your work to another thread.** Hand a task to an executor
   and the value is gone, which is why `@Async` and `CompletableFuture` lose MDC
   unless you copy it across with a task decorator.

---

## 🧪 EXERCISE 9 — `Ex9EventCounts`

```bash
cd concurrency-lab
./mvnw test -Dtest='ExerciseTests$Ex9'
```

**Read the field declaration before anything else.** The map is already a
`ConcurrentHashMap`, and the class is still broken. That is not a trick; it is
the topic.

| Test | Fails because |
|---|---|
| `countsEveryRecordedEvent` | 32,000 recorded, 30,378 counted — `get`-then-`put` is D7's `count++` |
| `consumeSucceedsExactlyAsManyTimesAsRecordDid` | 8,000 occurrences, 8,146 successful consumes — two callers both claimed the same one |
| `worksSingleThreaded` | passes — the logic is fine, the *atomicity* is not |

Both fixes are one call: `merge` for the increment, `compute` for the decrement.
The interesting part is the second one, because `compute` has to do three things
at once — decide, decrement, and **remove the entry at zero by returning null** —
and still report back whether it fired. It cannot return two values, so capture
the flag from inside the mapping function.

## 🧪 EXERCISE 10 — `Ex10Context`

```bash
./mvnw test -Dtest='ExerciseTests$Ex10'
```

Three defects, one test each, and all three have shipped in real filters:

| Test | Fails because | Only exposed by |
|---|---|---|
| `confinesTheIdToTheThreadThatBoundIt` | a `static String` is shared by the whole JVM — 7,908 wrong reads | concurrency |
| `doesNotLeakOntoTheNextRequestOnAPooledThread` | cleanup is not in a `finally`, so the next request inherited `tenant-acme` | an exception **and** a pool |
| `restoresTheEnclosingContextWhenScopesNest` | cleanup clears instead of restoring, so the outer id became `null` | nesting |

The third one is the one people have never thought about. Unbinding must put back
what was there, not wipe the slot — otherwise a correlation id disappears halfway
through a trace the moment anything nests. And the restore has a branch in it
that matters: `remove()` when there was no previous value, `set(previous)` when
there was. Storing a null instead would be P3's leak.

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, THEN open. Miss one → reread that section.*

<details><summary><b>Q.</b> You switched a shared map to <code>ConcurrentHashMap</code> and a counter still loses updates. Why?</summary>

Because thread-safe means *each call* is atomic, not that your *sequence* of
calls is. `map.put(k, map.get(k) + 1)` is two atomic calls with a race-shaped hole
between them: two threads both read 7, both compute 8, both write 8. It is D7's
`count++` with extra ceremony, and it lost 0.7–13.3% of 32,000 increments in my
measurements. The map cannot help, because it has no way to know the `get` and
the `put` were meant to be one operation. Tell it, with one call:
`merge(k, 1L, Long::sum)`.
</details>

<details><summary><b>Q.</b> Name the atomic <code>ConcurrentHashMap</code> methods and what each is for.</summary>

`putIfAbsent` — insert only if absent, returning the incumbent so `null` means you
won the race. `computeIfAbsent` — compute-and-insert only if absent, the
compute-once/stampede fix. `computeIfPresent` — update only if present.
`compute` — the general read-modify-write, where **returning null removes the
entry**. `merge` — insert-or-combine, the counter idiom. `replace(k, expected,
new)` — compare-and-swap on one entry, which is D9's CAS keyed by map key, ABA
caveat included. All of them hold the bin lock across the read *and* the write.
</details>

<details><summary><b>Q.</b> Three ways a plain <code>HashMap</code> breaks under concurrent writes.</summary>

**Lost entries** (5.3–31.9% of 40,000 distinct keys in my runs): two threads link
a node into the same bin head, or a put lands inside someone else's resize and
writes into the table about to be discarded — which is why loss is lumpy rather
than smooth. **A worker that never returns from `put`**, burning a core; it
ignores `interrupt()` because it is not blocked on anything, it is looping. And
**`ClassCastException: Node cannot be cast to TreeNode`**, where the bin's own
record of whether it has been treeified is false. I saw all three. The second and
third are strictly worse, because the first at least leaves the process healthy.
</details>

<details><summary><b>Q.</b> Why is <code>ConcurrentHashMap.size()</code> only an estimate, and when does that matter?</summary>

Because there is no single counter — one would be exactly the contended cache
line the design exists to avoid. The count is sharded across striped cells (D9's
`LongAdder`, built in) and `size()` sums them one at a time, so the result belongs
to no single instant. Measured: it never undercounted puts that had already
returned, but it *overcounted* by up to 80,805 by including puts that landed
mid-sum. It is exact for a quiescent map. It matters the moment you branch on it:
`if (map.size() < CAP) map.put(...)` overshot its cap by up to 5 in my runs —
D8's check-then-act with a lie in the check.
</details>

<details><summary><b>Q.</b> Weakly consistent vs fail-fast vs snapshot iterators.</summary>

`ConcurrentHashMap` is **weakly consistent**: never throws, never blocks,
traverses the table as it exists while walking, and may or may not show writes
made after it started. `HashMap` is **fail-fast**: it notices `modCount` moved and
throws `ConcurrentModificationException` — which is a best-effort *bug detector*,
not a safety mechanism; it is allowed to miss, and when it fires the damage is
already done. `CopyOnWriteArrayList` is a **true snapshot**: the array it walks is
immutable, so it is immune to concurrent changes entirely (and `iterator.remove()`
throws `UnsupportedOperationException`, because the array is not the live one).
</details>

<details><summary><b>Q.</b> Why does <code>computeIfAbsent</code> beat a <code>synchronized</code> block that does the same thing?</summary>

Both give you compute-once. Only `computeIfAbsent` locks the *bin* rather than the
whole map, so two threads loading two different cold keys run in parallel.
Measured with 8 threads, 8 different cold keys and a 200 ms loader:
`synchronized (map)` took 1,621–1,652 ms, `computeIfAbsent` 202–211 ms — eight
sequential loads versus eight parallel ones. The global lock serialises unrelated
loads behind each other, which is the bottleneck the cache existed to remove.
Same reason D12 said "map-shaped data → `ConcurrentHashMap`": it shards the
locking instead of sharing it.
</details>

<details><summary><b>Q.</b> What are the rules for a <code>compute</code>/<code>merge</code> mapping function?</summary>

Both follow from one fact: it runs while the bin lock is held. **Keep it short** —
everything hashing to that bin waits on it, so for a slow load cache a
`CompletableFuture` instead of the value, which inserts a placeholder immediately
and releases the lock while the work runs. **Never touch the same map inside it**
— the JDK throws `IllegalStateException: Recursive update` rather than let you
wait on a bin you are writing. But it only detects that when the keys collide: in
my measurements keys 0 and 16 threw and keys 0 and 7 did not, so the absence of an
exception proves nothing.
</details>

<details><summary><b>Q.</b> When is <code>CopyOnWriteArrayList</code> right, and what does it cost?</summary>

Every write clones the whole backing array, so a write is O(n) and building the
list is O(n²) — measured at 1.1 µs per element at 4,000 elements and 19.6 µs per
element at 64,000, doubling as the list doubles. It is right when writes are rare
**events** and the list is small: listener lists, handler chains, feature flags.
It is wrong for anything that accumulates, whatever its read ratio — "reads
dominate" is not the rule, the same mistake D12 corrected for `ReadWriteLock`. In
my 8-thread benchmark it beat `synchronizedList` by 20–31× at zero writes and was
a coin flip at 10%.
</details>

<details><summary><b>Q.</b> What is the <code>ThreadLocal</code> pool-thread hazard, and what are the two rules?</summary>

A `ThreadLocal` value lives in a map hanging off the `Thread`, so it lives as long
as the thread — and a pooled thread outlives your request by design. A request
that sets a value and does not clear it returns the thread to the pool with the
value attached, and the next unrelated request reads it. Measured: request 2 saw
`tenant-acme`, and nothing threw. Rule one: `remove()` in a `finally`, so an
exception cannot skip it. Rule two: `remove()`, never `set(null)` — `set(null)`
leaves the entry in place, which on an immortal pool thread is a memory leak on
top of the bug. This is MDC's `MDC.clear()` rule, one layer down.
</details>

---

## 🃏 FLASHCARDS

```
Thread-safe collection guarantees	Each CALL is atomic. NOT your sequence of calls — it can't know which belong together
CHM + get-then-put, measured	Still lost 0.7–13.3% of 32,000 — it's D7's count++ with ceremony
The decision rule	New value depends on old value ⇒ it must be ONE call (merge/compute/putIfAbsent)
compute returning null	REMOVES the entry — how you delete-at-zero atomically instead of a second remove()
containsKey-then-put, measured	>1 "first" leader in 11–15% of 200 trials, worst 7. putIfAbsent: 200/200 correct
Same bug, three outfits	D8 stock-- · Ex6 cache stampede · CHM containsKey-then-put
HashMap concurrent writes	Three outcomes: lost 2–32% · a worker never returns from put() · ClassCastException Node→TreeNode
Why HashMap loses entries	Bin head overwritten + puts landing inside someone else's resize (lumpy, not smooth)
Is it a visibility bug?	NO. volatile on the map field fixes nothing — the race is on the internal table, 2 objects down
CHM size()	Estimate: sharded cells summed one at a time. Overcounted by up to 80,805 while busy, exact when quiescent
size() check-then-act	`if (map.size() < CAP) put()` overshot by up to 5 — D8 with a lie in the check
CHM iterator	Weakly consistent: never throws, never a snapshot. HashMap's is fail-fast (a bug DETECTOR, not safety)
Per-bin vs global lock, measured	8 cold keys, 200ms loader: synchronized 1,621ms vs computeIfAbsent 205ms (~8x)
Mapping function rules	Short (bin lock held) · never touch the same map · slow load ⇒ cache a CompletableFuture
Recursive update	IllegalStateException — but ONLY when keys share a bin (0&16 threw, 0&7 didn't). Absence proves nothing
COW write cost, measured	1.1µs/element at 4k → 19.6µs/element at 64k. O(n) per write, O(n^2) to build
COW decision rule	Writes are rare EVENTS and the list is small (listeners) — NOT "reads dominate"
COW iterator	True snapshot at iterator() · it.remove() throws UnsupportedOperationException
ThreadLocal is	A map on the Thread. Value lives as long as the THREAD — and pool threads outlive your request
ThreadLocal pool leak	Request 1 set tenant, request 2 read it. Nothing threw. remove() in a FINALLY
remove() vs set(null)	set(null) leaves the ENTRY (proved: initialValue doesn't re-run). remove() deletes it
ThreadLocal + another thread	Invisible. @Async/CompletableFuture lose MDC unless a task decorator copies it
```

---

## 🔗 SAME IDEA, DIFFERENT LAYER

| Layer | The same idea |
|---|---|
| SQL | `UPDATE t SET n = n + 1 WHERE id = ?` is `merge` — one statement, atomic. `SELECT` then `UPDATE` is `get`-then-`put` |
| SQL | `INSERT … ON CONFLICT DO UPDATE` (Postgres) / `MERGE` is `compute`, and `INSERT … ON CONFLICT DO NOTHING` is `putIfAbsent` |
| JPA | `@Version` optimistic locking is `replace(k, expected, new)` — [`../01-foundations/05-transactions-isolation-locking.md`](../01-foundations/05-transactions-isolation-locking.md) |
| Redis | `SETNX` is `putIfAbsent`; `INCR` is `merge`. Both exist because `GET` then `SET` is the same race over a network |
| Logging | MDC is a `ThreadLocal` map, with the same `finally` obligation — [`../01-foundations/07-logging-mdc-correlation-ids.md`](../01-foundations/07-logging-mdc-correlation-ids.md) |
| Spring | `RequestContextHolder` and request-scoped beans are `ThreadLocal`, and lose their value the same way across `@Async` — [`../03-async-and-scheduling/01-thread-pools-completablefuture.md`](../03-async-and-scheduling/01-thread-pools-completablefuture.md) |
| Functional | Persistent/immutable collections are copy-on-write with structural sharing — the same trade, with the O(n) filed down |

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)

**Interleaved pass (R4) — pull these forward from earlier topics:**
1. *(Topic 3)* D8's overselling, Ex6's cache stampede and this topic's
   `containsKey`-then-`put` are the same defect. State the defect in one sentence
   with no reference to inventory, caches or maps — then explain why making the
   individual steps atomic (`AtomicInteger`, `ConcurrentHashMap`) fixes none of
   the three, and say what the *rarity* of the half-fixed version costs you.
2. *(Topic 2)* D4's stale stop flag was fixed by `volatile`. Marking a `HashMap`
   field `volatile` fixes nothing at all. Explain the difference in terms of
   *which object the guarantee applies to*, without using the word "cache".
