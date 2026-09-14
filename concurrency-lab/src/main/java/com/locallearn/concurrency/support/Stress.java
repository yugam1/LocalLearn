package com.locallearn.concurrency.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;

/**
 * The single most important class in this lab.
 *
 * <p>A data race is <em>probabilistic</em>. Broken code very often produces the
 * right answer, because the threads happen not to overlap. If you write a test
 * that starts two threads and checks a counter, it will usually pass — and you
 * will conclude, wrongly, that your unsynchronised code is fine.
 *
 * <p>Two tricks make races show up reliably, and this class does both:
 *
 * <ol>
 *   <li><b>A start gate.</b> Every worker thread is created, started, and then
 *       parks on the same {@link CountDownLatch}. Thread creation costs ~1ms,
 *       so without a gate, thread 1 has usually finished its whole loop before
 *       thread 8 exists — no overlap, no race. The gate releases all of them
 *       within microseconds of each other, so they collide in the hot loop.</li>
 *   <li><b>Repeated trials.</b> One trial that happens to interleave nicely
 *       proves nothing. {@link #untilFailure} reruns a whole trial many times
 *       and reports the first one that misbehaves.</li>
 * </ol>
 *
 * <p>These are also exactly the tricks the exercise tests use, which is why a
 * broken {@code Exercise} implementation fails here but might survive a naive
 * hand-written test.
 */
public final class Stress {

    private Stress() {
    }

    /**
     * Runs {@code threads} workers concurrently, each invoking {@code work}
     * {@code iterationsPerThread} times, all released from a start gate at the
     * same instant. Blocks until every worker is done.
     *
     * <p>{@code work} receives the iteration index, so a worker can vary its
     * behaviour per iteration (useful for "half the threads read, half write").
     *
     * @throws IllegalStateException if any worker threw, wrapping the first
     *                               exception seen — a worker that dies silently
     *                               is the classic way a concurrency bug hides.
     */
    public static void run(int threads, int iterationsPerThread, IntConsumer work) {
        if (!run(threads, iterationsPerThread, work, 60)) {
            throw new IllegalStateException(
                    "Stress run did not finish within 60s — likely a deadlock, a livelock, "
                    + "or a thread spinning inside a non-thread-safe data structure. "
                    + "Run `jcmd <pid> Thread.print` to see where.");
        }
    }

    /**
     * Timeout-bounded variant. Returns {@code false} instead of throwing if the
     * workers do not finish in time, so a demo can <em>report</em> a hang rather
     * than become one.
     *
     * <p>Workers are daemon threads on purpose. A thread spinning inside a
     * corrupted {@code HashMap} ignores {@link Thread#interrupt()} — there is no
     * blocking call for it to throw from — so a non-daemon worker would keep the
     * JVM alive forever after we have given up on it.
     */
    public static boolean run(int threads, int iterationsPerThread, IntConsumer work,
                              int timeoutSeconds) {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicReference<Throwable> firstFailure = new AtomicReference<>();
        List<Thread> workers = new ArrayList<>(threads);

        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    gate.await();                       // park until everyone is ready
                    for (int i = 0; i < iterationsPerThread; i++) {
                        work.accept(i);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt(); // restore the flag; see t01threads
                } catch (Throwable e) {
                    firstFailure.compareAndSet(null, e);
                } finally {
                    done.countDown();
                }
            }, "stress-" + t);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }

        gate.countDown();                               // release them all at once
        boolean finished;
        try {
            finished = done.await(timeoutSeconds, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for stress run", e);
        }

        if (!finished) {
            workers.forEach(Thread::interrupt);         // best effort; may be ignored
            return false;
        }

        Throwable failure = firstFailure.get();
        if (failure != null) {
            throw new IllegalStateException("A stress worker threw: " + failure, failure);
        }
        return true;
    }

    /**
     * Repeats a whole trial up to {@code maxTrials} times and returns the
     * 1-based number of the first trial whose {@code check} returned false,
     * or {@code -1} if all trials passed.
     *
     * <p>Use this to demonstrate "this code is broken" honestly: a single
     * passing run is not evidence of correctness, but a single failing run
     * <em>is</em> evidence of a bug.
     */
    public static int untilFailure(int maxTrials, Trial trial) {
        for (int i = 1; i <= maxTrials; i++) {
            if (!trial.runAndCheck()) {
                return i;
            }
        }
        return -1;
    }

    /** One self-contained attempt: set up fresh state, hammer it, verify it. */
    @FunctionalInterface
    public interface Trial {
        /** @return true if this trial produced the correct result. */
        boolean runAndCheck();
    }

    /** Uninterruptible-ish sleep helper, so demo code stays readable. */
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted during sleep", e);
        }
    }

    /** Burns CPU for roughly {@code millis} without parking — unlike sleep, this keeps the core busy. */
    public static void burnCpu(long millis) {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        long sink = 0;
        while (System.nanoTime() < deadline) {
            sink += System.nanoTime();
        }
        if (sink == Long.MIN_VALUE) {
            System.out.print(""); // never happens; stops JIT from eliding the loop
        }
    }
}
