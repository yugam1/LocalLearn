# Topic 4 — Locks, Deadlock, Read/Write Locks & Conditions
**Demos:** `t04locks/D10`, `D11`, `D12` | **Exercises:** `Ex5Bank`, `Ex6Cache`, `Ex7Queue`

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** A lock buys you two things at once — exclusion AND a memory
edge — and costs you a new failure mode: **deadlock, which is never "two locks"
but "two locks in two different orders."** Every fix breaks one of Coffman's
four conditions; lock ordering (acquire by ascending id) breaks circular wait
and is the fix you reach for first.

```
deadlock: T1 holds A wants B │ T2 holds B wants A     → dump shows it (jcmd Thread.print)
livelock: both RUNNABLE, retrying in lockstep forever → dump looks FINE (needs jitter)
```

**Five rules you must never get wrong:**
1. Default to `synchronized` (can't leak the lock); reach for `ReentrantLock` only for tryLock / lockInterruptibly / fairness / multiple Conditions — and then `unlock()` in `finally`, always.
2. Fairness costs ~92× (measured) — every handoff becomes a park/unpark syscall. Only for demonstrated starvation.
3. `ReadWriteLock` pays off only when reads dominate **and** hold the lock >~1µs (at 0ns it LOSES even at 100% reads). Map-shaped data → `ConcurrentHashMap` instead.
4. Always `while (!condition) cond.await()` — never `if` (spurious wakeups, signalAll, signal-steal: three independent reasons).
5. Cache stampede → `computeIfAbsent` (per-bin lock: one loader per key, parallel across keys); slow loaders → cache a `CompletableFuture`.

*Run first: D10 (fair vs non-fair), D11 (real deadlock, detected), D12 (RWLock economics + stampede).*

---

## `synchronized` vs `ReentrantLock`

Both give mutual exclusion **and** a happens-before edge — a lock is not just
exclusion, it is also a memory barrier. Both are **reentrant**: the holding
thread can re-acquire without deadlocking itself, which is what lets one
synchronized method call another.

| Capability | `synchronized` | `ReentrantLock` |
|---|---|---|
| Give up after a timeout | ✗ waits forever | ✓ `tryLock(5, SECONDS)` |
| Give up immediately if busy | ✗ | ✓ `tryLock()` |
| Be cancelled while waiting | ✗ `BLOCKED` ignores `interrupt()` | ✓ `lockInterruptibly()` |
| Fair (FIFO) acquisition | ✗ | ✓ `new ReentrantLock(true)` |
| Multiple wait-sets | one, via `wait`/`notify` | many, via `newCondition()` |
| Acquire in A, release in B | impossible | possible (usually a mistake) |
| **Released automatically on exception** | **✓** | ✗ — you must use `try/finally` |

### Default to `synchronized`

Since JDK 15 the JVM's thin-lock machinery makes uncontended `synchronized`
cost about the same as `ReentrantLock`, and the keyword **cannot leak a lock** —
the monitor is released by the JVM even if the body throws. Reach for
`ReentrantLock` when you need a row from that table, most often `tryLock`
(deadlock avoidance) or a `Condition`.

When you do:

```java
lock.lock();
try {
    // ...
} finally {
    lock.unlock();      // ALWAYS. This is what synchronized does for you free.
}
```

### Fairness is expensive

**Measured (D10, 8 threads × 50,000 acquisitions):** non-fair **53 ms**, fair
**4,857 ms** — **92× slower**.

Fairness forbids barging, so every handoff becomes a park/unpark pair — a
syscall — instead of letting an already-running thread take the lock
immediately. Use it only when starvation is a *demonstrated* problem.

### Reentrancy is a count, not a bit

```java
lock.lock(); lock.lock(); lock.lock();
lock.getHoldCount();   // 3
lock.unlock(); lock.unlock();
lock.getHoldCount();   // 1 — still held, still excluding others
```

---

## Deadlock

### Coffman's four conditions

All four must hold simultaneously. Break any one and deadlock is impossible —
which is exactly how each fix works.

1. **Mutual exclusion** — the resource can't be shared
2. **Hold and wait** — a thread holds one lock while requesting another
3. **No preemption** — a lock can't be taken from its holder
4. **Circular wait** — A waits on B, B waits on A

### The classic trigger

```java
void transfer(Account from, Account to, long amount) {
    synchronized (from.lock) {        // thread 1: locks Alice, wants Bob
        synchronized (to.lock) {      // thread 2: locks Bob, wants Alice
            ...
        }
    }
}
```

`transfer(alice, bob)` racing `transfer(bob, alice)`. Neither will ever let go.

> **The bug is never "two locks". It is "two locks in two different orders".**

### Diagnosing it

```bash
jcmd <pid> Thread.print          # or jstack <pid>
```

The JVM finds monitor deadlocks itself and prints `Found one Java-level
deadlock:` with both stacks. Programmatically:

```java
long[] ids = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
```

Worth wiring into an Actuator health indicator.

**Two caveats:**

- **State word differs by lock type.** A `synchronized` deadlock shows
  `BLOCKED` (`- waiting to lock <0x…>`); a `ReentrantLock` deadlock shows
  `WAITING` (`- parking to wait for <0x…ReentrantLock$NonfairSync>`), because
  AQS parks via `LockSupport`. Same deadlock, different state. D11 demonstrates
  this — the output says `WAITING`.
- **Detection isn't total.** It covers monitors and AQS locks. A deadlock via
  `Semaphore` permits, or two threads each awaiting the other's
  `CountDownLatch`, is **invisible** to it — you get a hang with no diagnosis.

### The fixes

| Fix | Breaks | Notes |
|---|---|---|
| **Lock ordering** — always acquire by ascending id | circular wait | **Reach for this first.** No retries, no timeouts, nothing to tune |
| **`tryLock` + backoff** | hold-and-wait | Needs *randomised jitter* — a fixed delay lets threads retry in lockstep forever, which is **livelock**: both stay `RUNNABLE`, make no progress, and a thread dump shows nothing wrong |
| **Don't hold two locks** | at the root | One coarser lock, or make the operation atomic in the database |

```java
// Lock ordering. Any total order works, as long as EVERY path uses the same one.
int first  = Math.min(fromAccount, toAccount);
int second = Math.max(fromAccount, toAccount);
synchronized (locks[first]) {
    synchronized (locks[second]) { ... }
}
```

**That is Exercise 5.** Note the test asserts both *no deadlock* **and** *money
conserved* — deleting the locks fixes the first and breaks the second.

---

## `ReadWriteLock` — and when it actually helps

Many readers may hold the read lock at once; a writer excludes everybody. The
obvious rule — "use it when reads dominate" — is **wrong on its own**.

### Measured (D12, 8 threads)

| Read ratio | 0 ns reads | 2 µs reads | 20 µs reads |
|---|---|---|---|
| 50% | 0.94× | 2.50× | 4.66× |
| 90% | 0.41× | 3.31× | 5.82× |
| 99% | 0.34× | 3.32× | 6.16× |
| 100% | **0.34×** | 6.44× | **8.20×** |

*(speedup vs an exclusive lock; >1 means the RWLock wins)*

At 0 ns — a plain `HashMap.get` — the RWLock **loses at every ratio, even 100%
reads**. There is no overlap to win back; you just pay for tracking a reader
count. At 20 µs, the same code at the same ratios is 8× faster.

> The real rule: use a `ReadWriteLock` when reads dominate **and** each read
> holds the lock long enough for the sharing to pay for itself. Short critical
> section → plain lock. Map-shaped data → `ConcurrentHashMap`, which *shards*
> the locking instead of sharing it.

### Two traps

- **Writer starvation.** With the default non-fair policy a steady stream of
  readers can keep a writer waiting indefinitely. Use
  `new ReentrantReadWriteLock(true)` if writes must land.
- **No upgrade.** You cannot go read→write while holding the read lock — it
  deadlocks instantly. Release the read lock, take the write lock, and
  **re-check your condition**, because the state may have changed in the gap.
  Downgrade (write→read) *is* legal.

---

## The cache stampede

```java
String value = cache.get(key);   // CHECK
if (value == null) {
    value = loader.apply(key);   // ...expensive call in the gap...
    cache.put(key, value);       // ACT
}
```

32 threads missing on the same cold key → 32 calls to the service you were
trying to protect, precisely when it's already slow. (And the `HashMap` isn't
thread-safe either.)

```java
return cache.computeIfAbsent(key, k -> loader.apply(k));
```

`ConcurrentHashMap.computeIfAbsent` holds the **bin lock** for that key's hash
bucket while the mapping function runs, so exactly one thread loads a given key
and the rest receive its result. Crucially the lock is **per bin**, not global:
two threads loading two *different* cold keys still run in parallel. A
`synchronized get()` also achieves compute-once but serialises every unrelated
load behind one lock — the bottleneck you were removing.

**Two caveats:** the mapping function must not touch the same map (recursive
update throws or hangs), and a slow loader holds the bin lock. For genuinely
slow loads, cache a `CompletableFuture` instead of the value — the future is
inserted immediately and the bin lock released while the work proceeds.

**That is Exercise 6.**

---

## `Condition` — wait until the state is right

A `Condition` is `wait`/`notify` for a `ReentrantLock`, with one decisive
advantage: **one lock can have several wait-sets**.

A bounded buffer needs "not full" and "not empty" as *separate* queues, so
`signal()` wakes a thread that can actually proceed. With a single monitor you
must use `notifyAll()` and let the wrong threads wake, re-check, and go back to
sleep — the **thundering herd**.

```java
private final ReentrantLock lock = new ReentrantLock();
private final Condition notFull  = lock.newCondition();
private final Condition notEmpty = lock.newCondition();

void put(T item) throws InterruptedException {
    lock.lock();
    try {
        while (count == items.length) {   // while, NEVER if
            notFull.await();              // releases the lock while parked
        }
        items[tail] = item;
        tail = (tail + 1) % items.length;
        count++;
        notEmpty.signal();                // wake ONE consumer
    } finally {
        lock.unlock();
    }
}
```

### Always wait in a `while` loop

Three independent reasons, any one sufficient:

1. A **spurious wakeup** is permitted by the JLS and needs no cause.
2. `signalAll` wakes threads whose condition is still false.
3. Between being signalled and re-acquiring the lock, another thread may have
   taken the very item you were woken for.

`if` is a bug in all three cases.

### Why write one

That code is `ArrayBlockingQueue` in miniature. Writing it once is how you
learn to read every bounded-queue-shaped thing afterwards — including the
`queueCapacity` on Spring's `ThreadPoolTaskExecutor`
([../03-async-and-scheduling/01-thread-pools-completablefuture.md](../03-async-and-scheduling/01-thread-pools-completablefuture.md)). Blocked producers are **parked**,
costing zero CPU, and woken only when a slot actually frees. That is
backpressure.

**That is Exercise 7** — and its test measures per-thread CPU time to reject a
busy-wait solution, which would pass every other assertion while burning a core
per blocked thread.

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, THEN open. Miss one → reread that section above.*


<details><summary><b>Q.</b> <code>synchronized</code> or <code>ReentrantLock</code>?</summary>

Default to `synchronized`: since JDK 15 it's about as fast uncontended, and the
JVM releases the monitor even if the body throws, so you cannot leak a lock.
Use `ReentrantLock` when you need something the keyword can't do — `tryLock`
with a timeout, `lockInterruptibly`, fairness, or multiple `Condition`
wait-sets. And then the `unlock()` goes in a `finally`, always.
</details>

<details><summary><b>Q.</b> What is a deadlock and how do you prevent it?</summary>

Four conditions must hold at once: mutual exclusion, hold-and-wait, no
preemption, circular wait. Break any one. The standard fix is **lock ordering**
— acquire in a globally consistent order, e.g. ascending account id — which
breaks circular wait with no retries or timeouts. Alternatively `tryLock` with
randomised backoff breaks hold-and-wait, but needs jitter or you get livelock.
The bug is never "two locks", it's "two locks in two different orders".
</details>

<details><summary><b>Q.</b> How do you diagnose a deadlock in production?</summary>

`jcmd <pid> Thread.print` — the JVM reports "Found one Java-level deadlock"
with both stacks. Programmatically `ThreadMXBean.findDeadlockedThreads()`, which
is worth exposing as a health indicator. Two things to know: a `synchronized`
deadlock shows `BLOCKED` while a `ReentrantLock` one shows `WAITING` (AQS parks
rather than queueing on a monitor), and detection only covers monitors and AQS
locks — a `Semaphore` or latch deadlock is invisible and presents as a plain
hang.
</details>

<details><summary><b>Q.</b> Deadlock vs livelock vs starvation?</summary>

Deadlock: threads blocked forever in a cycle, no CPU used, visible in a dump.
Livelock: threads are `RUNNABLE` and actively doing work but making no
progress — e.g. retrying in lockstep — and a thread dump looks *fine*, which
makes it much harder to find. Starvation: a thread never gets scheduled or
never wins the lock; fair locks fix it at a large throughput cost.
</details>

<details><summary><b>Q.</b> When does a <code>ReadWriteLock</code> actually pay off?</summary>

Only when reads dominate *and* each read holds the lock long enough to overlap.
With nanosecond critical sections it loses at every read ratio — I measured
0.34× at 100% reads — because you're paying to track a reader count for no
sharing benefit. At 20 µs reads the same code was 8× faster. For map-shaped
data use `ConcurrentHashMap` instead; it shards locking per bin rather than
sharing one lock. Also watch for writer starvation and remember you can't
upgrade read→write.
</details>

<details><summary><b>Q.</b> Why <code>while</code> and not <code>if</code> around <code>await()</code>?</summary>

Three reasons: spurious wakeups are legal and need no cause; `signalAll` wakes
threads whose condition is still false; and between the signal and reacquiring
the lock, another thread can consume the state you were woken for. All three
mean the condition must be re-tested after waking, which is what `while` does.
</details>

<details><summary><b>Q.</b> <code>wait</code>/<code>notify</code> or <code>Condition</code>?</summary>

`Condition` when one lock needs more than one wait-set — a bounded buffer wants
separate "not full" and "not empty" queues so `signal()` wakes a thread that can
actually proceed. With a single monitor you're forced into `notifyAll()` and a
thundering herd. `Condition` also gives you `awaitUninterruptibly` and
`awaitNanos`. In practice, prefer an existing `BlockingQueue` over writing
either.
</details>

<details><summary><b>Q.</b> How do you prevent a cache stampede?</summary>

`ConcurrentHashMap.computeIfAbsent` — it holds the per-bin lock while the
mapping function runs, so one thread loads and the rest get its result, while
unrelated keys still load in parallel. For slow loaders cache a
`CompletableFuture` rather than the value, so the bin lock isn't held for the
duration of the work.
</details>

---

## 🃏 FLASHCARDS

```
synchronized's killer feature	JVM releases the monitor even on exception — cannot leak a lock
ReentrantLock's four reasons	tryLock(timeout) · lockInterruptibly · fairness · multiple Conditions
Fair lock cost (measured)	~92× slower — every handoff is a park/unpark syscall
Coffman's four	Mutual exclusion · hold-and-wait · no preemption · circular wait (break ANY one)
First-choice deadlock fix	Lock ordering (ascending id) — breaks circular wait, nothing to tune
tryLock+backoff without jitter	Livelock: RUNNABLE, busy, zero progress, clean-looking dump
Deadlock diagnosis	jcmd <pid> Thread.print → "Found one Java-level deadlock"; ThreadMXBean.findDeadlockedThreads()
Detection blind spot	Semaphore/latch deadlocks invisible — plain hang, no report
RWLock real rule	Reads dominate AND read holds lock >~1µs; 0ns reads → RWLock loses even at 100%
Read→write upgrade	Instant deadlock — release read, take write, RE-CHECK condition
while not if around await()	Spurious wakeups · signalAll wakes wrong threads · signal-steal in the gap
Cache stampede fix	computeIfAbsent (per-bin lock); slow loader → cache a CompletableFuture
```

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
