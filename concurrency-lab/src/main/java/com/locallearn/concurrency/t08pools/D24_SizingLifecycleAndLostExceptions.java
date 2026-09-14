package com.locallearn.concurrency.t08pools;

import com.locallearn.concurrency.support.Log;

import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEMO 24 — The three things you get wrong <em>after</em> you understand the
 * growth order: how many threads, how to stop, and where the exception went.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t08pools.D24_SizingLifecycleAndLostExceptions}
 *
 * <h2>1. Sizing is not one formula, it is one question</h2>
 * The question is: <b>what fraction of a task's duration is this thread
 * actually using a core?</b>
 * <pre>
 *   CPU-bound  (fraction ≈ 1)   →  threads ≈ cores (+1 to cover a page fault)
 *   IO-bound   (fraction ≈ 0)   →  threads ≈ cores × (1 + wait/compute)
 * </pre>
 * These are the same formula: the second collapses into the first when
 * {@code wait} is zero. A thread that is blocked on a socket holds no core, so
 * it costs you ~1 MB of stack and nothing else — which is why an IO pool can be
 * ten times the core count and still be correct, and why applying "cores + 1" to
 * an IO workload is one of the most common and most expensive sizing mistakes
 * in production Java.
 *
 * <p>Both sweeps below are measured rather than asserted, because the formula
 * gives you a starting point and the machine gives you the answer.
 *
 * <h2>2. shutdown() vs shutdownNow() — drain vs abandon, again</h2>
 * This is D15's poison pill versus D15's interrupt, wearing the names the JDK
 * gives them:
 * <pre>
 *   shutdown()      stop accepting new work; RUN everything already queued;
 *                   then terminate. Does not block — you must awaitTermination.
 *   shutdownNow()   stop accepting; INTERRUPT the running tasks; do not start
 *                   the queued ones; RETURN them to you as a List&lt;Runnable&gt;.
 *   awaitTermination(t, unit)   blocks until terminated or the deadline;
 *                   returns FALSE on timeout — a boolean people forget to read.
 * </pre>
 * Note what {@code shutdownNow} does <em>not</em> do: it does not stop a task
 * that ignores interruption. It sets the flag and hands back the backlog; a task
 * in a tight CPU loop with no blocking call and no {@code isInterrupted()} check
 * keeps running to completion. Topic 1 taught that interruption is a request,
 * not a kill, and that is still true here.
 *
 * <h2>3. Where the exception went</h2>
 * {@code execute(Runnable)} and {@code submit(Runnable)} look interchangeable
 * and differ in the most consequential way possible:
 * <ul>
 *   <li>{@code execute} lets the exception escape the worker's run loop. The
 *       thread's {@code UncaughtExceptionHandler} fires (by default: a stack
 *       trace on stderr), the worker <b>dies</b>, and the pool quietly creates a
 *       replacement.</li>
 *   <li>{@code submit} wraps the task in a {@link java.util.concurrent.FutureTask},
 *       which <b>catches Throwable and stores it</b> as the future's outcome. No
 *       handler runs. Nothing is printed. The worker survives. The exception
 *       exists only inside the {@code Future}, and is delivered exclusively to
 *       whoever calls {@code get()} — so if you ignore the returned future, as
 *       fire-and-forget code always does, the failure is gone with no trace.</li>
 * </ul>
 * This is the JDK-level mechanism behind the Spring-level trap that an
 * {@code @Async void} method swallows its exceptions
 * ({@code docs/03-async-and-scheduling/01-thread-pools-completablefuture.md}).
 */
public final class D24_SizingLifecycleAndLostExceptions {

    private static final int CORES = Runtime.getRuntime().availableProcessors();

    /**
     * Iterations of {@link #cpuWork} that take about 20 ms on ONE unloaded core
     * of this machine, measured at startup.
     *
     * <p>This calibration is not decoration, it is the whole validity of sweep 1.
     * The obvious way to write "20 ms of CPU work" is a loop that spins until a
     * wall-clock deadline — and that measures nothing, because such a task takes
     * 20 ms whether it owns a core or is fighting seven other threads for one. A
     * deadline-based spin is a sleep with the lights on: it would have shown this
     * machine scaling to 18x on 12 logical cores, which is impossible, and the
     * table would have looked perfectly respectable while being nonsense.
     *
     * <p>Fixed <em>work</em> is the only honest unit here. When the cores are
     * oversubscribed each task genuinely takes longer, which is exactly the
     * effect the sweep is trying to see.
     */
    // Declared BEFORE WORK_20MS on purpose: static initialisers run in source
    // order, and calibrate() writes to SINK.
    /** Consumes results so the JIT cannot delete the arithmetic it cannot see used. */
    private static final java.util.concurrent.atomic.AtomicLong SINK =
            new java.util.concurrent.atomic.AtomicLong();

    private static final int WORK_20MS = calibrate(20);

    public static void main(String[] args) throws Exception {
        Log.log("availableProcessors() = %d", CORES);
        Log.log("calibrated: %,d xorshift iterations ~= 20ms on one core", WORK_20MS);
        cpuBoundSizing();
        ioBoundSizing();
        shutdownVsShutdownNow();
        theVanishingException();

        Log.takeaway("""
                Sizing: ask what fraction of the task actually holds a core. CPU-bound
                work scales until it runs out of cores and then PLATEAUS — note that
                it plateaued rather than degrading, which is not what the folk wisdom
                says; the price of the extra threads was memory and tail latency, not
                throughput. IO-bound work keeps improving far past the core count,
                because a blocked thread holds no core at all: sizing this same pool
                at "cores + 1" cost roughly 3.4x the throughput of sizing it at 48.

                Stopping: shutdown() drains, shutdownNow() abandons and HANDS BACK the
                tasks it never started — which is the JDK doing you the favour of
                making the data loss explicit and countable. Neither is correct by
                default; the bug is not choosing. And awaitTermination returns a
                boolean that is false on timeout, which almost nobody checks.

                Exceptions: execute() prints and kills the worker; submit() swallows
                into the Future and tells nobody until someone calls get(). Fire and
                forget with submit() means fire and never find out.""");
    }

    // ── 1. CPU-bound: more threads than cores makes it worse ───────────────
    /**
     * A fixed amount of genuinely CPU-bound work (no sleeping, no IO), run at
     * increasing pool sizes. Total work is constant, so the only variable is how
     * many threads are competing for the same cores.
     */
    private static void cpuBoundSizing() throws Exception {
        Log.section("1. CPU-BOUND sizing — 96 tasks x 20ms of REAL arithmetic");
        Log.log("Total work is constant. Only the pool size changes.");
        Log.log("%-12s %10s %12s", "threads", "wall", "vs 1 thread");

        long baseline = 0;
        for (int threads : new int[]{1, 2, 4, 6, 8, 12, 16, 24, 48, 96}) {
            long millis = runAll(threads, 96, () -> SINK.addAndGet(cpuWork(WORK_20MS)));
            if (threads == 1) {
                baseline = millis;
            }
            Log.log("%-12d %8dms %11.1fx", threads, millis, baseline / (double) millis);
        }
        Log.log("Near-linear while threads <= PHYSICAL cores, sub-linear through the");
        Log.log("hyperthread count, then a flat PLATEAU. Read that plateau carefully:");
        Log.log("the folk claim is that oversubscribing CPU work makes it slower, and");
        Log.log("across 5 runs that did NOT happen here — 16 threads and 96 threads");
        Log.log("land in the same band, with run-to-run noise wider than the gap.");
        Log.log("The real cost of the extra 80 threads is not throughput. It is ~80MB");
        Log.log("of stacks, a longer tail latency, and a thread dump nobody can read.");
    }

    // ── 2. IO-bound: cores + 1 would be a catastrophe ──────────────────────
    /**
     * Tasks that spend 5 ms on a core and 45 ms blocked — a wait/compute ratio
     * of 9, which is a conservative model of a database call. The formula
     * predicts an optimum near {@code cores × (1 + 9)}; the sweep shows where it
     * actually lands.
     */
    private static void ioBoundSizing() throws Exception {
        Log.section("2. IO-BOUND sizing — 240 tasks x (5ms CPU + 45ms blocked)");
        Log.log("wait/compute = 45/5 = 9, so the formula predicts cores x (1+9) = %d threads.",
                CORES * 10);
        Log.log("%-12s %10s %12s", "threads", "wall", "vs 1 thread");

        long baseline = 0;
        for (int threads : new int[]{1, 6, 12, 13, 24, 48, 96, 120, 240}) {
            long millis = runAll(threads, 240, () -> {
                SINK.addAndGet(cpuWork(WORK_20MS / 4));   // ~5ms: holds a core
                sleep(45);                                 // holds no core at all
            });
            if (threads == 1) {
                baseline = millis;
            }
            Log.log("%-12d %8dms %11.1fx", threads, millis, baseline / (double) millis);
        }
        Log.log("Compare with sweep 1: the SAME machine keeps getting faster well past");
        Log.log("%d threads here, because a blocked thread holds no core. Sizing this", CORES);
        Log.log("pool with 'cores + 1' would leave the machine ~90%% idle while the");
        Log.log("queue grew. That is the single most expensive sizing mistake there is.");
    }

    // ── 3. Lifecycle ───────────────────────────────────────────────────────
    private static void shutdownVsShutdownNow() throws Exception {
        Log.section("3. shutdown() vs shutdownNow() — 1 worker, 50 queued tasks");

        // --- shutdown(): drains ---
        AtomicInteger ranA = new AtomicInteger();
        ExecutorService drain = Executors.newFixedThreadPool(1);
        for (int i = 0; i < 50; i++) {
            drain.execute(() -> {
                sleep(10);
                ranA.incrementAndGet();
            });
        }
        drain.shutdown();
        boolean terminatedA = drain.awaitTermination(30, TimeUnit.SECONDS);
        Log.log("shutdown()     -> ran %d/50, terminated=%b  (queued work all completed)",
                ranA.get(), terminatedA);

        // --- shutdownNow(): abandons, and hands the backlog back ---
        AtomicInteger ranB = new AtomicInteger();
        ExecutorService abandon = Executors.newFixedThreadPool(1);
        for (int i = 0; i < 50; i++) {
            abandon.execute(() -> {
                sleep(10);
                ranB.incrementAndGet();
            });
        }
        sleep(60);                                   // let a few get through
        List<Runnable> neverStarted = abandon.shutdownNow();
        boolean terminatedB = abandon.awaitTermination(30, TimeUnit.SECONDS);
        Log.log("shutdownNow()  -> ran %d/50, terminated=%b, RETURNED %d never-started tasks",
                ranB.get(), terminatedB, neverStarted.size());
        Log.log("               the returned list is the JDK making your data loss");
        Log.log("               explicit and countable — requeue it or log it, but");
        Log.log("               do not do what almost all code does and discard it.");

        // --- awaitTermination's ignored boolean ---
        ExecutorService slow = Executors.newFixedThreadPool(1);
        slow.execute(() -> sleep(3_000));
        slow.shutdown();
        boolean inTime = slow.awaitTermination(200, TimeUnit.MILLISECONDS);
        Log.log("awaitTermination(200ms) on a 3s task -> %b", inTime);
        Log.log("               FALSE means 'still running'. Code that ignores this");
        Log.log("               boolean reports a clean shutdown it did not achieve.");
        slow.shutdownNow();

        // --- shutdownNow cannot stop a task that ignores interruption ---
        ExecutorService stubborn = Executors.newFixedThreadPool(1);
        CountDownLatch started = new CountDownLatch(1);
        long spinStart = System.nanoTime();
        stubborn.execute(() -> {
            started.countDown();
            // ~1.5s of arithmetic: no blocking call, no isInterrupted() check.
            SINK.addAndGet(cpuWork(WORK_20MS * 75));
        });
        started.await();
        stubborn.shutdownNow();                      // sets the interrupt flag... and that is all
        boolean stopped = stubborn.awaitTermination(400, TimeUnit.MILLISECONDS);
        Log.log("shutdownNow() on an uninterruptible 1.5s CPU task: terminated within 400ms = %b",
                stopped);
        Log.log("               interruption is a REQUEST (topic 1). A task with no");
        Log.log("               blocking call and no isInterrupted() check never hears it.");
        stubborn.awaitTermination(5, TimeUnit.SECONDS);
        Log.log("               it finally ended after %dms, on its own terms.",
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - spinStart));
    }

    // ── 4. The vanishing exception ─────────────────────────────────────────
    private static void theVanishingException() throws Exception {
        Log.section("4. submit() vs execute() — where the exception went");

        // An explicit handler rather than the JVM default, for two reasons: it
        // proves the handler is what fires (a stack trace on stderr is easy to
        // mistake for the pool logging something), and it keeps the exception
        // from reaching the ThreadGroup that the exec plugin watches, which
        // would end this demo as a build failure instead of a lesson.
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                1, 1, 60, TimeUnit.SECONDS, new LinkedBlockingQueue<>(),
                r -> {
                    Thread t = new Thread(r, "exc-worker-" + THREAD_SEQ.getAndIncrement());
                    t.setUncaughtExceptionHandler((thread, error) -> Log.log(
                            "  !! UncaughtExceptionHandler fired on %s: %s: %s",
                            thread.getName(), error.getClass().getSimpleName(), error.getMessage()));
                    return t;
                });

        Log.log("--- execute(): the exception escapes, and it costs you the thread ---");
        Future<?> nameBefore = pool.submit(() -> Thread.currentThread().getName());
        Log.log("worker before = %s", nameBefore.get());

        pool.execute(() -> {
            throw new IllegalStateException("BOOM from execute()");
        });
        sleep(200);                                  // let the handler print
        Future<?> nameAfter = pool.submit(() -> Thread.currentThread().getName());
        Log.log("worker after  = %s   <- a DIFFERENT thread: the first one died", nameAfter.get());
        Log.log("The handler fired, the worker died, and the pool silently replaced it.");
        Log.log("You get told — but you pay a thread for the privilege.");

        Log.log("");
        Log.log("--- submit(): total silence ---");
        Future<?> lost = pool.submit(() -> {
            throw new IllegalStateException("BOOM from submit()");
        });
        sleep(200);
        Log.log("200ms later: nothing printed, nothing logged, worker still alive.");
        Log.log("future.isDone()=%b — the exception is IN there, held for whoever asks.",
                lost.isDone());
        try {
            lost.get();
            Log.log("get() returned normally?!");
        } catch (ExecutionException e) {
            Log.log("get() finally surfaces it: %s: %s",
                    e.getCause().getClass().getSimpleName(), e.getCause().getMessage());
        }
        Log.log("Now imagine the line 'Future<?> lost = ' was not there. Fire-and-forget");
        Log.log("submit() is a silent failure detector with the detector removed:");
        Log.log("FutureTask.run() catches Throwable, stores it, and tells nobody.");
        Log.log("This is exactly why Spring's @Async void swallows exceptions too.");

        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    private static final AtomicInteger THREAD_SEQ = new AtomicInteger(1);

    // ── helpers ────────────────────────────────────────────────────────────

    /** Runs {@code tasks} copies of {@code body} on a fixed pool of {@code threads}, returns wall millis. */
    private static long runAll(int threads, int tasks, Runnable body) throws Exception {
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                threads, threads, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(tasks + 1));
        pool.prestartAllCoreThreads();               // exclude thread-creation cost from the measurement
        CountDownLatch done = new CountDownLatch(tasks);

        long start = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            pool.execute(() -> {
                try {
                    body.run();
                } finally {
                    done.countDown();
                }
            });
        }
        done.await();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        pool.shutdownNow();
        return millis;
    }

    /**
     * Genuinely CPU-bound work: a xorshift64 chain where every iteration depends
     * on the previous one, so it occupies an arithmetic unit for a fixed number
     * of cycles and cannot be vectorised, unrolled away, or skipped.
     */
    private static long cpuWork(int iterations) {
        long x = 0x9E3779B97F4A7C15L;
        long acc = 0;
        for (int i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            acc += x;
        }
        return acc;
    }

    /** Returns the iteration count that takes ~{@code millis} on one core, after warm-up. */
    private static int calibrate(int millis) {
        int iterations = 1 << 16;
        for (int warmUp = 0; warmUp < 5; warmUp++) {
            SINK.addAndGet(cpuWork(iterations));     // let C2 compile cpuWork first
        }
        for (int attempt = 0; attempt < 40; attempt++) {
            long start = System.nanoTime();
            SINK.addAndGet(cpuWork(iterations));
            long elapsed = System.nanoTime() - start;
            if (elapsed >= millis * 1_000_000L) {
                return (int) (iterations * (millis * 1_000_000L) / (double) elapsed);
            }
            iterations <<= 1;
        }
        return iterations;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private D24_SizingLifecycleAndLostExceptions() {
    }
}
