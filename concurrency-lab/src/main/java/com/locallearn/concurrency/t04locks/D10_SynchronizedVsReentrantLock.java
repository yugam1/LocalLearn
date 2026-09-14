package com.locallearn.concurrency.t04locks;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DEMO 10 — {@code synchronized} versus {@link ReentrantLock}: what the lock
 * gives you that the keyword cannot.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t04locks.D10_SynchronizedVsReentrantLock}
 *
 * <h2>What they have in common</h2>
 * Both give mutual exclusion <em>and</em> a happens-before edge: everything a
 * thread did before releasing is visible to the next thread that acquires.
 * A lock is not just exclusion — it is also a memory barrier. Both are
 * <b>reentrant</b>: the holding thread can re-acquire without deadlocking
 * itself, which is what makes one synchronized method able to call another.
 *
 * <h2>What only ReentrantLock can do</h2>
 * <table>
 *   <caption>Capabilities</caption>
 *   <tr><th>Capability</th><th>synchronized</th><th>ReentrantLock</th></tr>
 *   <tr><td>Give up after a timeout</td><td>no — waits forever</td><td>{@code tryLock(5, SECONDS)}</td></tr>
 *   <tr><td>Give up immediately if busy</td><td>no</td><td>{@code tryLock()}</td></tr>
 *   <tr><td>Be cancelled while waiting</td><td>no — BLOCKED is not interruptible</td><td>{@code lockInterruptibly()}</td></tr>
 *   <tr><td>Fair (FIFO) acquisition</td><td>no</td><td>{@code new ReentrantLock(true)}</td></tr>
 *   <tr><td>Multiple wait-sets</td><td>one, via wait/notify</td><td>many, via {@code newCondition()}</td></tr>
 *   <tr><td>Acquire in A, release in B</td><td>impossible</td><td>possible (and usually a mistake)</td></tr>
 *   <tr><td>Released automatically on exception</td><td><b>yes</b></td><td>no — you must use try/finally</td></tr>
 * </table>
 *
 * <p><b>Default to {@code synchronized}.</b> Since JDK 15 the JVM's biased/
 * thin-lock machinery means uncontended {@code synchronized} costs roughly the
 * same as {@code ReentrantLock}, and the keyword cannot leak a lock — the
 * monitor is released by the JVM even if the body throws. Reach for
 * {@code ReentrantLock} when you need a row from the table above, most often
 * {@code tryLock} (deadlock avoidance, see D11) or a {@code Condition}.
 *
 * <p><b>Fairness is not free.</b> The fair lock below is typically one to two
 * orders of magnitude slower: it must hand the lock to the longest-waiting thread, which
 * means parking and unparking on every handoff instead of letting a thread that
 * is already running on-core barge in. Use fairness only when starvation is a
 * demonstrated problem.
 */
public final class D10_SynchronizedVsReentrantLock {

    public static void main(String[] args) throws Exception {
        tryLockTimeout();
        interruptibleAcquire();
        reentrancy();
        fairnessCost();
    }

    private static void tryLockTimeout() throws Exception {
        Log.section("tryLock with a timeout — synchronized cannot do this");

        ReentrantLock lock = new ReentrantLock();
        Thread hog = new Thread(() -> {
            lock.lock();
            try {
                Stress.sleep(2_000);   // holds it far longer than the waiter will tolerate
            } finally {
                lock.unlock();
            }
        }, "hog");
        hog.start();
        Stress.sleep(100);

        boolean acquired = lock.tryLock(300, TimeUnit.MILLISECONDS);
        Log.log("tryLock(300ms) → %s — gave up instead of blocking for 2s", acquired);
        if (acquired) {
            lock.unlock();
        }
        hog.join();
    }

    private static void interruptibleAcquire() throws Exception {
        Log.section("lockInterruptibly — a waiter you can actually cancel");

        // With synchronized, a BLOCKED thread ignores interrupt() entirely.
        // There is no way to cancel it; your shutdown hook just waits.
        Object monitor = new Object();
        Thread blocked = new Thread(() -> {
            synchronized (monitor) {
                Log.log("finally got in — but only because main RELEASED the monitor, "
                        + "not because of interrupt(). There is no way to cancel this wait.");
            }
        }, "blocked-on-monitor");

        synchronized (monitor) {
            blocked.start();
            Stress.sleep(200);
            blocked.interrupt();
            Stress.sleep(200);
            Log.log("synchronized waiter after interrupt(): state=%s, flag=%s — still stuck",
                    blocked.getState(), blocked.isInterrupted());
        }
        blocked.join();

        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        Thread waiter = new Thread(() -> {
            try {
                lock.lockInterruptibly();
                try {
                    Log.log("acquired");
                } finally {
                    lock.unlock();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.log("lockInterruptibly waiter was cancelled cleanly");
            }
        }, "interruptible-waiter");
        waiter.start();
        Stress.sleep(200);
        waiter.interrupt();
        waiter.join();
        lock.unlock();
    }

    private static void reentrancy() {
        Log.section("Reentrancy — a lock counts, it does not just flip a bit");

        ReentrantLock lock = new ReentrantLock();
        lock.lock();
        lock.lock();
        lock.lock();
        Log.log("acquired 3 times; holdCount = %d, isHeldByCurrentThread = %s",
                lock.getHoldCount(), lock.isHeldByCurrentThread());
        lock.unlock();
        lock.unlock();
        Log.log("after 2 unlocks holdCount = %d — still held, still excluding others",
                lock.getHoldCount());
        lock.unlock();
        Log.log("after the 3rd unlock holdCount = %d — now actually released", lock.getHoldCount());
        Log.log("this is why a synchronized method can call another synchronized "
                + "method on the same object without deadlocking itself");
    }

    private static void fairnessCost() {
        Log.section("The price of fairness");

        int threads = 8;
        int iterations = 50_000;

        for (boolean fair : new boolean[]{false, true}) {
            ReentrantLock lock = new ReentrantLock(fair);
            AtomicInteger counter = new AtomicInteger();
            long t0 = System.nanoTime();
            Stress.run(threads, iterations, i -> {
                lock.lock();
                try {
                    counter.incrementAndGet();
                } finally {
                    lock.unlock();   // ALWAYS in finally — this is the one thing
                                     // synchronized does for you for free
                }
            });
            Log.log("fair=%-5s → %,d acquisitions in %,5d ms",
                    fair, counter.get(), (System.nanoTime() - t0) / 1_000_000L);
        }

        Log.takeaway("""
                Fair locks are typically 10-100x slower (this run: see above).
                Fairness forbids barging,
                so every handoff becomes a park/unpark pair — a syscall — instead
                of letting an already-running thread take the lock immediately.

                Default to `synchronized`. Reach for ReentrantLock when you need
                tryLock, lockInterruptibly, or a Condition — and when you do,
                the unlock() goes in a finally block, always.""");
    }
}
