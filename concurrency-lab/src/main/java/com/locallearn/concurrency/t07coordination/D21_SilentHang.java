package com.locallearn.concurrency.t07coordination;

import com.locallearn.concurrency.support.Log;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DEMO 21 — The anchor failure of this topic: a coordination deadlock is
 * <b>invisible</b>. No exception, no log line, no "Found one Java-level
 * deadlock" in the thread dump. Just a service that stopped serving while every
 * health check stays green.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t07coordination.D21_SilentHang}
 *
 * <p>Topic 4 told you this was coming. Its flashcard reads <i>"Detection blind
 * spot — Semaphore/latch deadlocks invisible, plain hang, no report"</i>, and
 * its deadlock-diagnosis section says detection "only covers monitors and AQS
 * locks". This demo is that sentence, executed, with the numbers to prove it:
 * three deadlocks side by side, one of which the JVM diagnoses for you and two
 * of which it cannot see at all.
 *
 * <h2>Why the JVM can find one and not the others</h2>
 * Deadlock detection is cycle detection on a <b>wait-for graph</b>, and to draw
 * an edge the JVM needs to answer one question: <i>this thread is blocked — who
 * is holding the thing it wants?</i>
 *
 * <ul>
 *   <li>A <b>monitor</b> ({@code synchronized}) records its owner in the object
 *       header. The edge exists. → detected.</li>
 *   <li>A <b>{@link ReentrantLock}</b> is an AQS "ownable synchronizer" and
 *       records {@code exclusiveOwnerThread}. The edge exists. → detected.</li>
 *   <li>A <b>{@link Semaphore}</b> permit has <em>no owner</em>. That is not an
 *       oversight, it is the defining property from D20: any thread may release
 *       a permit any other thread acquired. There is nobody to draw an edge
 *       to. → <b>undetectable, by construction.</b></li>
 *   <li>A <b>{@link CountDownLatch}</b> is worse still: the thread you are
 *       waiting on may not exist yet, may have died, or may be a thread that
 *       never intended to count down. There is no owner and no candidate for
 *       one. → <b>undetectable, by construction.</b></li>
 * </ul>
 *
 * <p>So this is not a missing feature that a future JDK will add. Ownerless
 * coordination is the whole point of a semaphore and a latch, and ownerless
 * means undiagnosable. The tooling cannot be fixed; your habits have to absorb
 * the difference.
 *
 * <h2>What you can actually do about it</h2>
 * <ol>
 *   <li><b>Read the dump anyway.</b> There is no deadlock banner, but the
 *       parked threads are right there, and the frame they are parked in names
 *       the mechanism: {@code CountDownLatch$Sync}, {@code Semaphore$NonfairSync},
 *       {@code CyclicBarrier}. A pile of threads in {@code WAITING} on
 *       {@code LockSupport.park} with no owner named is the signature. This demo
 *       prints exactly what you would see.</li>
 *   <li><b>Instrument the counts.</b> {@code latch.getCount()} and
 *       {@code semaphore.availablePermits()} are cheap, and a gauge that reads
 *       "permits available: 0" for ten minutes diagnoses in one glance what a
 *       thread dump cannot express at all. This is the single highest-value
 *       thing in this demo.</li>
 *   <li><b>Use the timed forms.</b> {@code await(timeout)},
 *       {@code tryAcquire(timeout)}, {@code barrier.await(timeout)}. An
 *       untimed wait in production converts a transient fault into a permanent
 *       one — and unlike a lock, nothing will ever come along and tell you.</li>
 * </ol>
 */
public final class D21_SilentHang {

    private static final ThreadMXBean THREAD_MX = ManagementFactory.getThreadMXBean();

    public static void main(String[] args) throws Exception {
        monitorDeadlockIsDiagnosed();
        latchDeadlockIsInvisible();
        semaphoreDeadlockIsInvisible();
        whatToDoInstead();

        Log.takeaway("""
                Three deadlocks, identical from the outside: threads stop, CPU is
                idle, the process stays up, the health check stays green. The JVM
                diagnosed exactly one of them.

                The mechanism is not a tooling gap. Deadlock detection is cycle
                detection on a wait-for graph, and an edge needs an OWNER —
                "thread A waits for X, which thread B holds". A monitor has an
                owner. A ReentrantLock has an owner. A semaphore permit and a latch
                count have none, deliberately: being ownerless is exactly what
                makes them useful (D20 — acquire in one thread, release in
                another). Ownerless therefore means undiagnosable, permanently.

                So the habits have to carry the weight instead:
                  - gauge latch.getCount() and semaphore.availablePermits(); a
                    gauge pinned at 0 says in one glance what no thread dump can;
                  - prefer await(timeout) / tryAcquire(timeout) / barrier.await(
                    timeout) — a timeout turns a permanent hang into an error you
                    can see, retry, and alert on;
                  - countDown() and release() belong in a FINALLY, because the
                    commonest cause of this hang is not a cycle at all, it is one
                    worker that threw on the way to its countDown().""");
    }

    // ── 1. The one the JVM does diagnose ───────────────────────────────────
    private static void monitorDeadlockIsDiagnosed() throws InterruptedException {
        Log.section("A — synchronized deadlock (D11's bug): DIAGNOSED");

        Object lockA = new Object();
        Object lockB = new Object();
        CountDownLatch bothHoldOne = new CountDownLatch(2);

        park("monitor-1", () -> {
            synchronized (lockA) {
                bothHoldOne.countDown();
                awaitQuietly(bothHoldOne);
                synchronized (lockB) {
                    throw new AssertionError("unreachable");
                }
            }
        });
        park("monitor-2", () -> {
            synchronized (lockB) {
                bothHoldOne.countDown();
                awaitQuietly(bothHoldOne);
                synchronized (lockA) {
                    throw new AssertionError("unreachable");
                }
            }
        });

        TimeUnit.MILLISECONDS.sleep(300);
        report("monitor-");
    }

    // ── 2. The one it cannot ───────────────────────────────────────────────
    private static void latchDeadlockIsInvisible() throws InterruptedException {
        Log.section("B — two threads each awaiting the other's latch: INVISIBLE");

        CountDownLatch latchA = new CountDownLatch(1);
        CountDownLatch latchB = new CountDownLatch(1);

        // Each thread waits for the other to signal, and neither ever will.
        // Structurally this is identical to case A. To the JVM it is not a
        // deadlock at all — it is two threads that simply decided to wait.
        park("latch-1", () -> {
            awaitQuietly(latchB);
            latchA.countDown();
        });
        park("latch-2", () -> {
            awaitQuietly(latchA);
            latchB.countDown();
        });

        TimeUnit.MILLISECONDS.sleep(300);
        report("latch-");
        Log.log("latchA.getCount() = %d, latchB.getCount() = %d — both stuck at 1.",
                latchA.getCount(), latchB.getCount());
        Log.log("Those two numbers are the entire diagnosis, and the JVM will never");
        Log.log("print them for you. You have to have exported them yourself.");
    }

    private static void semaphoreDeadlockIsInvisible() throws InterruptedException {
        Log.section("C — two semaphores acquired in opposite orders: INVISIBLE");

        Semaphore semA = new Semaphore(1);
        Semaphore semB = new Semaphore(1);
        CountDownLatch bothHoldOne = new CountDownLatch(2);

        // Exactly D11's lock-ordering bug, expressed with permits instead of
        // monitors. Same cycle, same circular wait, same fix (a consistent
        // acquisition order) — and no report whatsoever.
        park("semaphore-1", () -> {
            acquireQuietly(semA);
            bothHoldOne.countDown();
            awaitQuietly(bothHoldOne);
            acquireQuietly(semB);
        });
        park("semaphore-2", () -> {
            acquireQuietly(semB);
            bothHoldOne.countDown();
            awaitQuietly(bothHoldOne);
            acquireQuietly(semA);
        });

        TimeUnit.MILLISECONDS.sleep(300);
        report("semaphore-");
        Log.log("semA.availablePermits() = %d (queue length %d), semB = %d (queue length %d)",
                semA.availablePermits(), semA.getQueueLength(),
                semB.availablePermits(), semB.getQueueLength());
        Log.log("Coffman's four conditions all hold here, exactly as in case A, and");
        Log.log("the fix is D11's: acquire in one globally consistent order. The");
        Log.log("only thing that changed is that nothing will tell you to.");
    }

    // ── 3. The habits that replace the missing tool ────────────────────────
    private static void whatToDoInstead() throws InterruptedException {
        Log.section("WHAT ACTUALLY WORKS — timeouts and gauges");

        Semaphore exhausted = new Semaphore(0);
        long start = System.nanoTime();
        boolean acquired = exhausted.tryAcquire(200, TimeUnit.MILLISECONDS);
        Log.log("tryAcquire(200ms) on an exhausted semaphore -> %b after %d ms",
                acquired, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

        CountDownLatch never = new CountDownLatch(1);
        start = System.nanoTime();
        boolean counted = never.await(200, TimeUnit.MILLISECONDS);
        Log.log("await(200ms) on a latch nobody will count down -> %b after %d ms",
                counted, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));

        Log.log("Both returned false instead of parking forever. That boolean is");
        Log.log("the difference between an incident you can see and an incident you");
        Log.log("cannot: it is a branch you can log, count, alert on, and retry.");
        Log.log("");
        Log.log("And the gauges, which cost nothing:");
        Log.log("  latch.getCount()               how many signals are still owed");
        Log.log("  semaphore.availablePermits()   0 for minutes = a leak (D20)");
        Log.log("  semaphore.getQueueLength()     how many callers are parked");
        Log.log("  barrier.getNumberWaiting()     parties present but not released");
        Log.log("Export those four and this entire class of outage becomes a");
        Log.log("dashboard line instead of an afternoon with jstack.");
    }

    // ── the reporting that stands in for a thread dump ─────────────────────

    /**
     * Prints what {@code jcmd <pid> Thread.print} would show you for these
     * threads, plus the JVM's own verdict.
     */
    private static void report(String namePrefix) {
        // Filter the JVM's verdict down to THIS scenario's threads. The monitor
        // deadlock from case A is still deadlocked while cases B and C run, and
        // without this filter its detection would be misread as theirs.
        long[] allDeadlocked = THREAD_MX.findDeadlockedThreads();
        StringBuilder deadlockedHere = new StringBuilder();
        if (allDeadlocked != null) {
            for (ThreadInfo info : THREAD_MX.getThreadInfo(allDeadlocked)) {
                if (info != null && info.getThreadName().startsWith(namePrefix)) {
                    deadlockedHere.append(info.getThreadName()).append(' ');
                }
            }
        }

        for (ThreadInfo info : THREAD_MX.getThreadInfo(THREAD_MX.getAllThreadIds(), 4)) {
            if (info == null || !info.getThreadName().startsWith(namePrefix)) {
                continue;
            }
            Log.log("  %-12s state=%-9s blocked-on=%s owned-by=%s",
                    info.getThreadName(), info.getThreadState(),
                    info.getLockName() == null ? "-" : info.getLockName(),
                    info.getLockOwnerName() == null ? "nobody" : info.getLockOwnerName());
            for (StackTraceElement frame : info.getStackTrace()) {
                if (frame.getClassName().startsWith("java.util.concurrent")
                        || frame.getClassName().startsWith("jdk.internal.misc")) {
                    Log.log("        at %s.%s", frame.getClassName(), frame.getMethodName());
                }
            }
        }

        if (deadlockedHere.isEmpty()) {
            Log.log("  >>> findDeadlockedThreads() names NONE of these threads");
            Log.log("  >>> jcmd Thread.print would print NO \"Found one Java-level deadlock\"");
            Log.log("  >>> owned-by says \"nobody\" above: there is no edge to draw,");
            Log.log("  >>> so there is no cycle to find. Nothing is broken in the");
            Log.log("  >>> detector — the information it needs does not exist.");
        } else {
            Log.log("  >>> findDeadlockedThreads() names: %s", deadlockedHere.toString().trim());
            Log.log("  >>> jcmd Thread.print would print \"Found one Java-level deadlock\"");
        }
    }

    // ── helpers: every one of these threads is a daemon and stays parked ───

    private static void park(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);     // these never finish; do not hold the JVM open
        thread.start();
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void acquireQuietly(Semaphore semaphore) {
        try {
            semaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private D21_SilentHang() {
    }
}
