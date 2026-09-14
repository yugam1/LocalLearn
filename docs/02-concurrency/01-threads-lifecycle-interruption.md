# Topic 1 — Threads: Lifecycle, Cancellation & Interruption
**Demos:** `t01threads/D1`, `D2`, `D3` | **Exercise:** `Ex4Worker`

---

## ⚡ CORE CARD — 60 seconds

**Mental model:** A thread is an expensive OS resource (~1MB, ~1ms) that you can
only ever **ask** to stop. Everything in this topic follows from two facts:
exceptions and interrupts don't cross thread boundaries by magic, and
cancellation is a *cooperative protocol* — flag set by one side, honored by the
other.

```java
// the canonical cancellable worker — all three parts, every time
while (!Thread.currentThread().isInterrupted()) { ... }   // 1. poll
catch (InterruptedException e) { Thread.currentThread().interrupt(); }  // 2. restore
finally { cleanup(); }                                     // 3. always
```

**Five rules you must never get wrong:**
1. `interrupt()` sets a flag — that's ALL. Blocking calls throw (and **clear the flag**); CPU loops must poll.
2. Every `catch (InterruptedException)` → rethrow **or** `Thread.currentThread().interrupt()`. No third option.
3. `RUNNABLE` in a dump is a liar — it includes threads blocked on I/O. `BLOCKED` means monitors ONLY; a ReentrantLock deadlock shows `WAITING`.
4. Daemon threads die mid-instruction at JVM exit — no finally, no flush. Never for cleanup/I/O.
5. A worker's exception goes to its `UncaughtExceptionHandler` — in a pool it hides in the Future until someone calls `get()`.

*Run the demos before reading: D1 (states), D2 (join/daemon/exceptions), D3 (interruption — the non-polling thread never stops).*

---

## What a thread costs

| | Platform thread (Java ≤20, and still the default) |
|---|---|
| Creation | ~1 ms |
| Stack | ~1 MB reserved (`-Xss`, default 512k–1M) |
| Kernel object | 1:1 with an OS thread |
| Practical ceiling | a few thousand per JVM |

That 1 ms is why every harness in this lab reuses threads instead of creating
them per trial — and it is the entire reason thread pools exist.

> Virtual threads (Java 21, JEP 444) change these numbers by three orders of
> magnitude. That is a later topic; this module already targets 21 so it will
> run here.

---

## The six states

```
NEW ──start()──> RUNNABLE ──run() returns──> TERMINATED
                  │  ▲
                  │  └── monitor acquired ───┐
       synchronized  │                       │
                  ▼  │                       │
               BLOCKED ──────────────────────┘
                  │
     wait()/join()/park()          sleep(ms)/wait(ms)
                  ▼                       ▼
               WAITING              TIMED_WAITING
```

| State | Means | In a thread dump |
|---|---|---|
| `NEW` | constructed, not started | never appears |
| `RUNNABLE` | running, queued for a core, **or blocked on I/O** | `RUNNABLE` |
| `BLOCKED` | waiting for a `synchronized` monitor | `- waiting to lock <0x…>` |
| `WAITING` | `wait()` / `join()` / `park()`, no timeout | `- parking to wait for <0x…>` |
| `TIMED_WAITING` | same, with a deadline | `TIMED_WAITING (sleeping)` |
| `TERMINATED` | `run()` returned or threw | never appears |

### Three traps in that table

1. **`RUNNABLE` is a liar.** A thread blocked on a socket read reports
   `RUNNABLE` — the JVM cannot see OS-level I/O waits. A dump full of
   `RUNNABLE` threads does *not* mean your CPUs are busy. Read the stack
   frames, not the state word.
2. **`BLOCKED` only ever means a monitor.** A `ReentrantLock` deadlock shows
   `WAITING`, because `AbstractQueuedSynchronizer` parks via
   `LockSupport.park` rather than queueing on a monitor. D11 demonstrates
   exactly this. If you grep a dump for `BLOCKED` and find none, you have
   **not** ruled out a deadlock.
3. **`WAITING` on `join()`** is extremely common and usually benign — it is
   just `main` waiting for a worker.

**Run it:** `D1_LifecycleAndStates` walks a thread through all six.

---

## `join()`, daemons, and where exceptions go

### `join()`
`t.join()` blocks the caller until `t` terminates. It is the primitive under
`Future.get()`, `CompletableFuture.join()`, and
`ExecutorService.awaitTermination()`. It throws `InterruptedException`, so it
is a cancellation point.

### Daemon threads
The JVM exits when the last **non-daemon** thread finishes. A daemon thread is
then killed where it stands — **no `finally` block, no flush, no cleanup**.

```java
t.setDaemon(true);   // must be set BEFORE start(), else IllegalThreadStateException
```

Never put cleanup or I/O on a daemon thread. This is why Spring's
`ThreadPoolTaskExecutor` uses non-daemon threads plus
`setWaitForTasksToCompleteOnShutdown(true)`.

*(The lab's `Stress` harness uses daemon workers deliberately: a worker
spinning inside a corrupted `HashMap` ignores `interrupt()`, and a non-daemon
one would keep the JVM alive forever after we gave up on it.)*

### Exceptions don't cross threads

```java
try {
    worker.start();
    worker.join();
} catch (IllegalStateException e) {
    // UNREACHABLE. There is no call-stack relationship between
    // main and worker — the throw happened on a different stack.
}
```

The exception goes to the thread's `UncaughtExceptionHandler`. With none set,
it prints to stderr and the thread dies. **In a pool it is worse**: the pool
captures it into the `Future`, so if nobody calls `get()`, it vanishes
completely.

That is precisely why `AsyncConfig` in order-service registers an
`AsyncUncaughtExceptionHandler` — see [../03-async-and-scheduling/01-thread-pools-completablefuture.md](../03-async-and-scheduling/01-thread-pools-completablefuture.md).

**Run it:** `D2_JoinDaemonAndExceptions`.

---

## Interruption — the part that matters most

**Interruption is a request, not a kill.** Almost every cancellation bug comes
from not believing that sentence.

### The mechanism

Every thread carries one boolean, the **interrupt flag**.

| Call | Effect |
|---|---|
| `t.interrupt()` | sets the flag. For a running thread, that is *all* it does |
| `Thread.currentThread().isInterrupted()` | reads it, **does not clear** |
| `Thread.interrupted()` | reads it **and clears it** — static, easy to misuse |

Blocking methods that declare `InterruptedException` — `sleep`, `wait`, `join`,
`BlockingQueue.take`, `Lock.lockInterruptibly`, `Future.get` — notice the flag,
**clear it**, and throw.

Pure-CPU code notices nothing. It must poll.

### The two rules

**1. Never swallow `InterruptedException`.**

```java
// BUG — the throw already cleared the flag, and this destroys the request.
// Nobody upstream can ever recover it.
try { Thread.sleep(100); }
catch (InterruptedException e) { /* ignored */ }
```

**2. If you can't propagate it, restore the flag.**

```java
try {
    Thread.sleep(100);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();   // restore what the throw cleared
    return;
}
```

There is no third option.

### CPU-bound loops must poll

```java
while (!Thread.currentThread().isInterrupted()) {
    doOneUnitOfWork();
}
```

In D3, the non-polling thread was still `RUNNABLE` after 46 million iterations
post-`interrupt()`. It would never have stopped.

### Why there is no forcible kill

`Thread.stop()` was removed for good reason: it threw an asynchronous exception
at an arbitrary bytecode, which could leave a `synchronized` block with a
half-updated object **and the lock released**. There is no safe forcible kill in
Java. Cooperative cancellation is the only mechanism, which is what makes these
rules load-bearing rather than stylistic.

**Run it:** `D3_InterruptionAndCancellation`.

---

## The canonical cancellable worker

```java
public void run() {
    try {
        while (!Thread.currentThread().isInterrupted()) {   // 1. poll
            Thread.sleep(10);
            unitsCompleted++;
        }
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();                 // 2. restore
    } finally {
        cleanup();                                          // 3. always
    }
}
```

All three parts, every time. **That is Exercise 4.**

---

## 🎯 RETRIEVAL GYM

*Closed book. Answer aloud, THEN open. Miss one → reread that section above.*


<details><summary><b>Q.</b> What's the difference between <code>BLOCKED</code> and <code>WAITING</code>?</summary>

`BLOCKED` = queued for a `synchronized` monitor; the JVM will hand it over
automatically when the owner releases. `WAITING` = parked until someone
explicitly signals — `notify`, `unpark`, or the target of a `join` terminating.
Practically: a `ReentrantLock` deadlock shows `WAITING`, a `synchronized`
deadlock shows `BLOCKED`. Both are found by `findDeadlockedThreads()`; only the
latter by `findMonitorDeadlockedThreads()`.
</details>

<details><summary><b>Q.</b> How do you stop a thread?</summary>

You ask it to stop. `interrupt()` sets a flag; blocking calls throw
`InterruptedException`, CPU loops must poll `isInterrupted()`. `Thread.stop()`
is removed — it could release a monitor mid-update and leave shared state
corrupt. So cancellation is always cooperative, and every long-running task
needs to be written to cooperate.
</details>

<details><summary><b>Q.</b> Why is swallowing <code>InterruptedException</code> a bug?</summary>

Throwing it *clears* the flag. If you catch it and neither rethrow nor call
`Thread.currentThread().interrupt()`, the cancellation request is destroyed and
unrecoverable — your shutdown hook will wait forever on a thread that was told
to stop and forgot.
</details>

<details><summary><b>Q.</b> Daemon vs user thread?</summary>

The JVM exits when the last non-daemon thread ends, killing daemons in place
with no `finally` and no flush. Use daemons only for work that is safe to lose
— monitoring, heartbeats. Never for cleanup or I/O. Must be set before
`start()`.
</details>

<details><summary><b>Q.</b> Where does an exception thrown on a thread go?</summary>

To that thread's `UncaughtExceptionHandler`, never to the thread that started
it — there's no call-stack relationship. Unset, it prints to stderr. In an
executor it's captured into the `Future` and disappears if nobody calls
`get()`. Always configure a handler (or a `ThreadFactory` that sets one) on
pooled threads.
</details>

<details><summary><b>Q.</b> Why don't we just create a thread per request?</summary>

~1ms and ~1MB each, 1:1 with an OS thread, so a few thousand is the ceiling —
and past the core count, more threads means more context switching, not more
throughput. Hence pools. Virtual threads (21+) change the arithmetic: they're
~1KB and scheduled by the JVM onto a small carrier pool, so thread-per-request
becomes viable again for I/O-bound work.
</details>

---

## 🃏 FLASHCARDS

```
interrupt() does exactly	Sets one boolean flag — nothing else for a running thread
InterruptedException side effect	The throw CLEARS the interrupt flag
Swallowed InterruptedException	Cancellation request destroyed forever — restore or rethrow
CPU-bound loop cancellation	Must poll isInterrupted() — blocking calls aren't there to throw
Thread.interrupted() trap	Static: reads AND CLEARS the flag
RUNNABLE state lie	Includes threads blocked on socket I/O — read stacks, not state words
BLOCKED means only	Waiting for a synchronized monitor (ReentrantLock waits show WAITING)
Daemon thread at JVM exit	Killed in place — no finally, no flush; set before start()
Worker exception destination	UncaughtExceptionHandler; in pools → captured in Future, lost without get()
Why no Thread.stop()	Async exception could release a monitor mid-update → corrupt shared state
Platform thread cost	~1ms create, ~1MB stack, 1:1 OS thread, ceiling ≈ few thousand
```

---

## 🗓 REVISION LOG

- [ ] **R1** — next day
- [ ] **R2** — +3 days
- [ ] **R3** — +1 week
- [ ] **R4** — +3 weeks (interleaved)
