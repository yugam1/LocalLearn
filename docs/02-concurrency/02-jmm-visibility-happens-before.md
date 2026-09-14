# Topic 2 — The Java Memory Model: Visibility, Ordering & Safe Publication
**Demos:** `t02visibility/D4`, `D5`, `D6` | **Exercise:** `Ex2StopSignal`

> This is the topic that separates people who have *used* threads from people
> who *understand* them. It is also the one most often taught wrongly.

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** The JMM is a **contract of edges, not a promise of freshness**.
Threads see each other's writes ONLY across a *happens-before* edge (volatile,
monitor, start/join, final-freeze). No edge → the compiler may hoist your read
out of the loop and the CPU may reorder your stores — "guaranteed nothing," not
"usually fine." And the villain is the **JIT**, not CPU caches.

```
writer:  config = load();  ready = true;   ← volatile write = RELEASE
reader:  if (ready)        use(config);    ← volatile read  = ACQUIRE
         everything before the release is visible after the acquire
```

**Five rules you must never get wrong:**
1. `volatile` = access atomicity + visibility (no hoisting) + **ordering** (release/acquire). It never makes `x++` atomic.
2. The stale-flag hang is the JIT hoisting the read (`while(!stop)` → `if(!stop) while(true)`); `-Xint` proves it.
3. A lock is a memory barrier, not just mutual exclusion — **readers need the edge too**.
4. `ref = new Config(42)` is allocate→construct→publish, and construct/publish can reorder → half-built object. Hence volatile DCL, and why `final` fields publish safely for free.
5. "Works on my x86" ≠ correct: x86 is TSO-strong; ARM will collect on missing edges. **You cannot test your way to memory-model correctness.**

*Run first: D4 (flag never stops), D5 (92% reordering anomalies), D6 (safe publication + the HashMap that hangs forever).*

---

## The one-word bug

```java
private boolean stop = false;          // no volatile

// worker thread
while (!stop) { n++; }                 // may spin FOREVER

// another thread
stop = true;                           // this definitely executed
```

Measured in D4: the plain reader was **still spinning after 3 seconds** and
never stopped. Adding `volatile` — one keyword — made it exit in microseconds.

### Why — and it is *not* "CPU caches"

The folk explanation is "the value is stuck in the other core's cache". That is
essentially wrong: cache coherence (MESI) propagates writes in nanoseconds on
x86 and ARM alike.

**The real culprit is the compiler.** With no synchronisation between the two
threads there is no *happens-before* edge, so the JIT may assume nothing else
mutates the field, and performs a completely legal optimisation called
**hoisting**:

```java
while (!stop) { n++; }        // what you wrote

if (!stop) {                  // what C2 compiles it to
    while (true) { n++; }     // the read is hoisted out of the loop
}
```

The field is never re-read, so no amount of cache coherence can help. This is
why the bug is all-or-nothing rather than "eventually noticing".

> **Proof it's the JIT:** run D4 with `-Xint` (interpreter only) and the plain
> version stops every time. The bug appears exactly when C2 compiles the loop.

---

## What `volatile` actually guarantees

Three things. People remember one.

| # | Guarantee | What it buys you |
|---|---|---|
| 1 | **Atomicity of the access** | no torn reads (matters for `long`/`double` on 32-bit VMs) |
| 2 | **Visibility** | the read genuinely happens, every time — hoisting forbidden |
| 3 | **Ordering** | a volatile write *happens-before* every later volatile read of that field, **and everything the writer did before it becomes visible to the reader after it** |

Guarantee 3 is the forgotten one, and it is what makes the "publish with a
volatile flag" idiom safe:

```java
config = loadConfig();      // plain writes...
ready = true;               // volatile write — a RELEASE

// other thread
if (ready) {                // volatile read — an ACQUIRE
    use(config);            // guaranteed to see the fully-loaded config
}
```

### What `volatile` does **not** give you

**Atomicity of compound actions.** `volatile int n; n++;` is still a lost-update
race. See Topic 3 — D7 measured a `volatile int` counter losing **72%** of its
increments.

---

## Happens-before: the actual contract

The JMM does not promise that threads see each other's writes. It promises a
*partial order*. If action A **happens-before** action B, then A's effects are
visible to B. Otherwise **nothing is guaranteed** — not "usually works",
guaranteed *nothing*.

### The edges you get for free

| Edge | Rule |
|---|---|
| Program order | within one thread, earlier statements happen-before later ones |
| Monitor | unlocking a monitor happens-before any later lock of the same monitor |
| Volatile | a write happens-before every later read of that field |
| Thread start | `t.start()` happens-before everything in `t` |
| Thread join | everything in `t` happens-before `t.join()` returning |
| Final fields | the end of a constructor happens-before a correctly-published read |
| Interrupt | `t.interrupt()` happens-before `t` detecting it |
| Transitivity | if A hb B and B hb C, then A hb C |

Everything else — `Atomic*`, `CountDownLatch`, `BlockingQueue`, `Future`,
`ExecutorService.submit` — is built from those and documents its own edge.

### Practical reading

> **A lock is not just mutual exclusion. It is also a memory barrier.**

Everything a thread did before releasing a lock is visible to the next thread
that acquires it. This is why "I only ever write from one thread, so I don't
need to synchronise the readers" is wrong: the *readers* need the edge too.

---

## Reordering is real, and you can watch it

The classic store-buffer litmus test. Two threads, both fields start at 0:

```
Thread A          Thread B
x = 1;            y = 1;
r1 = y;           r2 = x;
```

Enumerate every interleaving by hand: in each one, at least one thread stored
before the other loaded, so you always get `r1 == 1 || r2 == 1`.
`r1 == 0 && r2 == 0` is **unreachable** under sequential consistency.

**D5 produced it in 92% of 1,000,000 trials.** Adding `volatile` to `x` and `y`
took it to **0**.

### Two independent causes

- **Compiler reordering** — `x = 1` and `r1 = y` touch different fields with no
  data dependency, so the JIT may emit them in either order.
- **Store buffering in hardware** — even x86, otherwise strongly ordered,
  buffers stores and lets later loads bypass them. StoreLoad is precisely the
  one reordering x86 permits.

A volatile store followed by a volatile load emits a StoreLoad barrier (a
locked instruction or `mfence` on x86), and the anomaly disappears.

> **"It works on my machine" is not evidence.** The JMM is the contract; your
> CPU is one implementation of it. Anything you did not establish with a
> happens-before edge is up for grabs, and the hardware will collect on that
> eventually — usually on a different architecture, under load, at 3am.

---

## Safe publication

`holder = new Config(42);` looks atomic. It is three steps:

1. allocate memory (all fields zero/null)
2. run the constructor, writing `value = 42`
3. publish the reference into `holder`

Steps 2 and 3 have no dependency the compiler or CPU must respect, so they may
be **reordered**. Another thread can observe `holder != null` while
`holder.value` is still `0` — a reference to a **partially-constructed object**.

This is the famous **broken double-checked locking** bug, and the reason the DCL
singleton is only correct when the instance field is `volatile`.

### Safe ways to publish

| Mechanism | Why it works |
|---|---|
| `volatile` reference | the volatile write is a release; the constructor's writes happen-before any read of the reference |
| **`final` fields** | JLS 17.5: if published after the constructor returns, any thread seeing the reference sees correct final fields — **with no synchronisation at all** |
| `static` initialiser | the JVM guarantees class-init ordering |
| `ConcurrentMap` / `AtomicReference` | documented happens-before edges |
| A lock held by writer **and every reader** | monitor edge |

The `final` guarantee is why immutable objects are the cheapest thread safety
you will ever get, and why `String` can be shared freely.

### An honest note about D6

**D6 reports zero torn objects on x86, and that is expected.** x86/x86-64 is
**TSO** (Total Store Order): the hardware never reorders a store past another
store, and HotSpot doesn't reorder them here either. On this machine the broken
code is *accidentally* correct.

That is exactly the trap. The same class on an ARM server (Graviton, Ampere, an
Apple-silicon laptop) runs on a weakly-ordered CPU where store-store reordering
*is* permitted. **You cannot test your way to memory-model correctness.**

So D6 Part 2 shows a related bug that *is* reproducible everywhere: sharing a
plain `HashMap` across 8 threads. On this machine it doesn't just lose entries
— it **hangs forever at 100% CPU**, spinning inside `HashMap.put` on a bucket
chain that concurrent modification spliced into a cycle. It ignores
`interrupt()`, because there is no blocking call for the interrupt to surface
at. `ConcurrentHashMap` handled the identical workload with zero losses.

---

## Decision table

| You need | Use |
|---|---|
| A flag one thread sets and others poll | `volatile boolean` |
| A flag where only the first setter should act | `AtomicBoolean.compareAndSet` |
| A counter | `AtomicLong` / `LongAdder` — **not** `volatile` |
| An immutable value object shared widely | `final` fields, publish however you like |
| A lazily-initialised singleton | holder-class idiom, or `volatile` + DCL |
| Anything with an invariant across ≥2 fields | a lock. Not volatile, not atomics |

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, THEN open. Miss one → reread that section above.*


<details><summary><b>Q.</b> What does <code>volatile</code> guarantee?</summary>

Three things: the access is atomic (no torn `long`/`double`); the read is
genuinely performed rather than hoisted; and it creates a happens-before edge —
a volatile write is visible to any later volatile read, along with everything
the writer did beforehand. What it does *not* give is atomicity of compound
actions, so `volatile int n; n++` still loses updates.
</details>

<details><summary><b>Q.</b> Then why does a non-volatile stop flag hang forever?</summary>

Not caches — the JIT. With no happens-before edge it may hoist the field read
out of the loop, turning `while (!stop)` into `if (!stop) while (true)`. The
field is then never re-read. Run with `-Xint` and it stops every time, which
proves it's a compiler optimisation, not a coherence delay.
</details>

<details><summary><b>Q.</b> What is happens-before?</summary>

The JMM's partial order. If A happens-before B, A's effects are visible to B;
without such an edge, *nothing* is guaranteed. You get edges from program
order, monitor lock/unlock, volatile write/read, `Thread.start`,
`Thread.join`, final-field freeze, and transitivity. Everything higher-level is
built from those.
</details>

<details><summary><b>Q.</b> Why does double-checked locking need <code>volatile</code>?</summary>

`instance = new Singleton()` is allocate → construct → publish, and the last
two may be reordered. Without `volatile`, a second thread can see a non-null
reference to a half-constructed object and skip the lock entirely. The volatile
write is a release that forbids the reordering. Better still, use the holder-class
idiom and let class-initialisation semantics do it for you.
</details>

<details><summary><b>Q.</b> Are <code>final</code> fields thread-safe?</summary>

Yes, specially so. JLS 17.5 guarantees that if a reference is published after
its constructor returns, any thread seeing the reference sees correctly
initialised final fields — no synchronisation needed. That's why immutable
objects are the easy path, and why `String` is safe to share. It does *not*
extend to mutable objects a final field points to.
</details>

<details><summary><b>Q.</b> Is a lock only about mutual exclusion?</summary>

No — it's also a memory barrier. Releasing a monitor happens-before the next
acquisition of it, so everything done inside the critical section is visible to
the next holder. That's why readers must take the lock too, not just writers.
</details>

---

## 🃏 FLASHCARDS

```
Stale stop-flag culprit	The JIT hoists the read out of the loop (not CPU caches) — -Xint proves it
volatile's three guarantees	Access atomicity · visibility (no hoisting) · ordering (release/acquire edge)
volatile does NOT give	Atomicity of compound actions — volatile n++ still loses updates
Happens-before contract	A hb B → A's effects visible to B; no edge → guaranteed NOTHING
Free hb edges	Program order · monitor unlock→lock · volatile write→read · start · join · final freeze · transitivity
A lock is also	A memory barrier — readers need the edge too, not just writers
new Config(42) hidden steps	allocate → construct → publish; construct/publish may reorder
Broken DCL fix	volatile instance field (or holder-class idiom)
final field guarantee	Published after constructor → correct values, zero synchronization (JLS 17.5)
x86 false safety	TSO hides store-store reordering — ARM will expose it; can't test to correctness
Shared plain HashMap	Can splice a bucket cycle → spins forever at 100% CPU, ignores interrupt
```

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
