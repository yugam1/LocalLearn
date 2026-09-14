package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.WorkloadRunner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 14 contract — "what kind of work is this?"
 *
 * <p>The two workloads are deliberately chosen so that <b>no single executor
 * passes both</b>. A cores-sized platform pool sails through the CPU test and
 * fails the IO test by an order of magnitude; and anything that serialises the
 * tasks — a lock held across the call, for instance — fails both.
 *
 * <p>The IO test asserts <b>measured concurrency</b> rather than only wall time,
 * because wall time alone can be met by a fast machine while the design is still
 * wrong. Counting how many tasks are simultaneously in flight names the actual
 * property being demanded.
 */
public abstract class WorkloadRunnerContract {

    protected abstract WorkloadRunner newRunner();

    /** Enough blocking tasks that only a non-thread-per-request design can hold them. */
    private static final int IO_TASKS = 400;
    private static final long IO_BLOCK_MILLIS = 100;

    @Test
    @Timeout(120)
    @DisplayName("both workloads return every result, in order")
    void bothWorkloadsReturnCorrectResultsInOrder() throws Exception {
        try (WorkloadRunner runner = newRunner()) {
            List<Callable<Long>> cpu = new ArrayList<>();
            List<Callable<Long>> io = new ArrayList<>();
            for (int i = 0; i < 64; i++) {
                final long n = i;
                cpu.add(() -> n * n);
                io.add(() -> {
                    Thread.sleep(Duration.ofMillis(5));
                    return n * n;
                });
            }

            assertThat(runner.runCpuBound(cpu))
                    .as("runCpuBound must return one result per task, in submission order")
                    .hasSize(64)
                    .containsExactlyElementsOf(expectedSquares(64));

            assertThat(runner.runIoBound(io))
                    .as("runIoBound must return one result per task, in submission order")
                    .hasSize(64)
                    .containsExactlyElementsOf(expectedSquares(64));
        }
    }

    @Test
    @Timeout(120)
    @DisplayName("CPU-bound work actually runs in parallel across the cores")
    void cpuBoundWorkRunsInParallel() throws Exception {
        int cores = Runtime.getRuntime().availableProcessors();
        int tasks = Math.max(16, cores * 4);
        int iterations = 3_000_000;

        // Measure the same work sequentially on this machine, right now, so the
        // comparison cannot be invalidated by a slow or busy CI box.
        long sequentialStart = System.nanoTime();
        for (int i = 0; i < tasks; i++) {
            SINK.addAndGet(spin(i, iterations));
        }
        long sequentialMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sequentialStart);

        try (WorkloadRunner runner = newRunner()) {
            List<Callable<Long>> cpu = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                final int seed = i;
                cpu.add(() -> spin(seed, iterations));
            }

            long start = System.nanoTime();
            runner.runCpuBound(cpu);
            long parallelMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            // A deliberately loose bar: 3x on a machine with >= 4 cores. The
            // point is to catch "no parallelism at all", not to grade the
            // scheduler.
            assertThat(parallelMillis)
                    .as("%d CPU-bound tasks took %d ms in parallel versus %d ms sequentially "
                        + "on this %d-core machine — a speed-up of %.1fx. That is not "
                        + "parallel execution. The usual cause is a lock held across the "
                        + "whole task, which serialises every one of them no matter how many "
                        + "threads are available",
                        tasks, parallelMillis, sequentialMillis, cores,
                        sequentialMillis / (double) Math.max(parallelMillis, 1))
                    .isLessThan(Math.max(sequentialMillis / 3, 50));
        }
    }

    @Test
    @Timeout(180)
    @DisplayName("IO-bound work runs hundreds of tasks concurrently, not cores-at-a-time")
    void ioBoundWorkAchievesHighConcurrency() throws Exception {
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();

        try (WorkloadRunner runner = newRunner()) {
            List<Callable<Long>> io = new ArrayList<>();
            for (int i = 0; i < IO_TASKS; i++) {
                final long n = i;
                io.add(() -> {
                    int now = inFlight.incrementAndGet();
                    peakInFlight.accumulateAndGet(now, Math::max);
                    try {
                        Thread.sleep(Duration.ofMillis(IO_BLOCK_MILLIS));
                        return n;
                    } finally {
                        inFlight.decrementAndGet();
                    }
                });
            }

            long start = System.nanoTime();
            List<Long> results = runner.runIoBound(io);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(results).hasSize(IO_TASKS);

            int cores = Runtime.getRuntime().availableProcessors();
            assertThat(peakInFlight.get())
                    .as("at most %d of the %d blocking tasks were ever in flight at once (on a "
                        + "%d-core machine). These tasks do nothing but BLOCK — they need no "
                        + "core, so nothing about this machine justifies running them a few "
                        + "at a time. Two things cause this: running IO work on a "
                        + "platform-thread pool sized for CPU work, and holding a lock across "
                        + "the blocking call. The second also PINS a virtual thread to its "
                        + "carrier, which D27 measured as a 108x collapse with zero "
                        + "contention", peakInFlight.get(), IO_TASKS, cores)
                    .isGreaterThan(IO_TASKS / 4);

            // The concurrency assertion above is the real one; this is its
            // consequence, stated in the units an SLA is written in.
            long serialisedFloor = IO_TASKS * IO_BLOCK_MILLIS / Math.max(cores, 1);
            assertThat(millis)
                    .as("%d tasks x %d ms of pure blocking took %d ms. A thread-per-core pool "
                        + "would need about %d ms; one virtual thread per task needs about "
                        + "%d ms, because a blocked virtual thread unmounts and holds no OS "
                        + "thread at all (D27, section 2)",
                        IO_TASKS, IO_BLOCK_MILLIS, millis, serialisedFloor, IO_BLOCK_MILLIS)
                    .isLessThan(Math.max(serialisedFloor / 2, IO_BLOCK_MILLIS * 4));
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static final java.util.concurrent.atomic.AtomicLong SINK =
            new java.util.concurrent.atomic.AtomicLong();

    private static List<Long> expectedSquares(int n) {
        List<Long> expected = new ArrayList<>(n);
        for (long i = 0; i < n; i++) {
            expected.add(i * i);
        }
        return expected;
    }

    /** Genuine CPU work: a serial dependency chain the JIT cannot elide. */
    private static long spin(int seed, int iterations) {
        long x = 0x9E3779B97F4A7C15L ^ seed;
        for (int i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }
}
