package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.Pipeline;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 8 contract — the producer–consumer pipeline.
 *
 * <p>Three tests, one per planted defect, and each one fails for a *different*
 * reason. That separation is deliberate: a single "does it work" test would go
 * green on an implementation that silently drops items under load, which is
 * precisely the production bug this exercise is about.
 *
 * <ul>
 *   <li>{@link #appliesBackpressureInsteadOfGrowingTheBacklog()} — D13. A fast
 *       producer against a slow processor. An unbounded backlog passes every
 *       correctness assertion while growing without limit, so the only way to
 *       catch it is to assert on the peak.</li>
 *   <li>{@link #workersParkWhileIdleInsteadOfSpinning()} — D14's verb grid. A
 *       {@code poll()} loop is functionally correct and burns a core per idle
 *       worker; only CPU time distinguishes it from {@code take()}.</li>
 *   <li>{@link #shutdownDrainsEveryAcceptedItem()} — D15. Stop-flag shutdown
 *       abandons the backlog. Counting is the only witness.</li>
 * </ul>
 */
public abstract class PipelineContract {

    protected abstract Pipeline newPipeline(int capacity, int workers, Consumer<String> processor);

    @Test
    @Timeout(60)
    @DisplayName("submit() blocks at capacity instead of growing the backlog")
    void appliesBackpressureInsteadOfGrowingTheBacklog() throws Exception {
        int capacity = 16;
        int items = 2_000;

        // One slow worker against an unthrottled producer: the backlog is under
        // constant pressure for the whole run, so an unbounded queue cannot
        // stay small by luck.
        Pipeline pipeline = newPipeline(capacity, 1, item -> sleepMicros(200));
        try {
            for (int i = 0; i < items; i++) {
                pipeline.submit("item-" + i);
            }
            pipeline.shutdownAndDrain();

            assertThat(pipeline.backlogPeak())
                    .as("the backlog reached %d items but capacity is %d. submit() must "
                        + "BLOCK while full (put), not accept without limit — an unbounded "
                        + "backlog is not a buffer, it is a deferred OutOfMemoryError (D13)",
                        pipeline.backlogPeak(), capacity)
                    .isLessThanOrEqualTo(capacity);
        } finally {
            drainQuietly(pipeline);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("idle workers park instead of spinning on poll()")
    void workersParkWhileIdleInsteadOfSpinning() throws Exception {
        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        assertThat(threadMx.isThreadCpuTimeSupported())
                .as("this JVM cannot measure per-thread CPU time").isTrue();
        threadMx.setThreadCpuTimeEnabled(true);

        int workers = 4;
        Pipeline pipeline = newPipeline(8, workers, item -> { });
        try {
            pipeline.submit("warm-up");             // let the workers start and go idle
            TimeUnit.MILLISECONDS.sleep(200);

            List<Long> workerIds = pipelineWorkerThreadIds(threadMx);
            assertThat(workerIds)
                    .as("expected %d live worker threads named 'pipeline-worker-*'", workers)
                    .hasSizeGreaterThanOrEqualTo(1);

            long cpuBefore = totalCpuNanos(threadMx, workerIds);
            TimeUnit.MILLISECONDS.sleep(1_000);     // a full second with nothing to do
            long cpuAfter = totalCpuNanos(threadMx, workerIds);

            long idleCpuMillis = TimeUnit.NANOSECONDS.toMillis(cpuAfter - cpuBefore);
            assertThat(idleCpuMillis)
                    .as("the %d idle workers burned %d ms of CPU across 1000 ms of wall clock. "
                        + "Parked threads use almost none; this much means a poll() spin loop. "
                        + "Block in take() instead (D14's verb grid)",
                        workerIds.size(), idleCpuMillis)
                    .isLessThan(200L * workerIds.size());
        } finally {
            drainQuietly(pipeline);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("shutdownAndDrain() processes every accepted item, then stops every worker")
    void shutdownDrainsEveryAcceptedItem() throws Exception {
        int items = 5_000;
        int workers = 3;
        AtomicLong seen = new AtomicLong();

        Pipeline pipeline = newPipeline(32, workers, item -> {
            seen.incrementAndGet();
            sleepMicros(50);                        // slow enough that a backlog exists at shutdown
        });
        try {
            for (int i = 0; i < items; i++) {
                pipeline.submit("item-" + i);
            }
            pipeline.shutdownAndDrain();            // must not return until the backlog is empty

            assertThat(pipeline.processed())
                    .as("submitted %d items, processed %d — shutdown abandoned %d items that had "
                        + "already been accepted. A stop flag stops workers wherever they are; "
                        + "to DRAIN, send one poison pill per worker through the same FIFO so it "
                        + "arrives after every real item (D15)",
                        items, pipeline.processed(), items - pipeline.processed())
                    .isEqualTo(items);

            assertThat(seen.get())
                    .as("the processor ran %d times for %d items — items were dropped or "
                        + "double-processed", seen.get(), items)
                    .isEqualTo(items);

            assertThat(pipeline.liveWorkers())
                    .as("%d worker threads were still alive after shutdownAndDrain() returned — "
                        + "it must join every worker, not just ask them to stop",
                        pipeline.liveWorkers())
                    .isZero();
        } finally {
            drainQuietly(pipeline);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static List<Long> pipelineWorkerThreadIds(ThreadMXBean threadMx) {
        List<Long> ids = new ArrayList<>();
        for (ThreadInfo info : threadMx.getThreadInfo(threadMx.getAllThreadIds())) {
            if (info != null && info.getThreadName().startsWith("pipeline-worker-")) {
                ids.add(info.getThreadId());
            }
        }
        return ids;
    }

    private static long totalCpuNanos(ThreadMXBean threadMx, List<Long> threadIds) {
        long total = 0;
        for (long id : threadIds) {
            long cpu = threadMx.getThreadCpuTime(id);
            if (cpu > 0) {                          // -1 once the thread is gone
                total += cpu;
            }
        }
        return total;
    }

    /**
     * Best-effort cleanup. A failed assertion must not leave spinning daemon
     * workers behind to skew the CPU measurements of every later test.
     */
    private static void drainQuietly(Pipeline pipeline) {
        try {
            if (pipeline.liveWorkers() > 0) {
                pipeline.shutdownAndDrain();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // the implementation under test is broken; that is what the assertions are for
        }
    }

    private static void sleepMicros(long micros) {
        long deadline = System.nanoTime() + micros * 1_000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
