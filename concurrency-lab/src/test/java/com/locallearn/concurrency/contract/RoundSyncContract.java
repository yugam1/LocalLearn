package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.RoundSync;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.IntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 11 contract — a reusable rendezvous.
 *
 * <p>The first two tests catch the planted defect (a one-shot latch used as a
 * repeating barrier). The third catches the tempting wrong <em>fix</em>: a
 * spin loop on an {@code AtomicInteger}, which satisfies every correctness
 * assertion here and burns a core per waiting worker. That is the same measuring
 * trick {@code PipelineContract} uses, and for the same reason — some defects
 * are only visible as resource use.
 */
public abstract class RoundSyncContract {

    protected abstract RoundSync newRoundSync(int workers, IntConsumer roundWork);

    @Test
    @Timeout(60)
    @DisplayName("every one of 20 rounds waits for all 6 workers")
    void everyRoundWaitsForEveryWorker() throws Exception {
        int workers = 6;
        int rounds = 20;

        RoundSync sync = newRoundSync(workers, id -> Thread.onSpinWait());
        sync.runAll(rounds);
        List<Integer> tallies = sync.tallies();

        assertThat(tallies)
                .as("expected exactly one tally per round (%d), got %d. The round tally "
                    + "must run exactly once per round, and it must run while every "
                    + "worker is still waiting — a CyclicBarrier's barrier ACTION does "
                    + "both, because it runs on the last party to arrive while the "
                    + "others are still parked (D19)",
                    rounds, tallies.size())
                .hasSize(rounds);

        assertThat(tallies)
                .as("every round must be tallied with all %d workers present, but the "
                    + "tallies were %s. A CountDownLatch is ONE-SHOT: once its count "
                    + "reaches zero, countDown() is a no-op and await() returns "
                    + "immediately, so from round 2 onward there is no rendezvous at all "
                    + "— the workers run free while the code still looks synchronised. "
                    + "Nothing throws and nothing hangs. Use a CyclicBarrier, which "
                    + "re-arms itself the instant the last party arrives (D19)",
                    workers, tallies)
                .containsOnly(workers);
    }

    @Test
    @Timeout(60)
    @DisplayName("the barrier still holds when workers arrive at very different times")
    void holdsWhenWorkersArriveAtDifferentTimes() throws Exception {
        int workers = 6;
        int rounds = 10;

        // Worker 0 is always first and worker 5 is always last by ~25ms. A
        // rendezvous that has quietly stopped rendezvousing cannot survive this.
        RoundSync sync = newRoundSync(workers, id -> sleepQuietly(5L * id));
        sync.runAll(rounds);
        List<Integer> tallies = sync.tallies();

        assertThat(tallies)
                .as("with staggered workers the tallies were %s; every entry must be %d. "
                    + "Each round must not be tallied, and no worker may start the next "
                    + "round, until the slowest worker has arrived (D19)",
                    tallies, workers)
                .hasSize(rounds)
                .containsOnly(workers);
    }

    @Test
    @Timeout(60)
    @DisplayName("workers waiting at the barrier park instead of spinning")
    void workersParkAtTheBarrierInsteadOfSpinning() throws Exception {
        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        assertThat(threadMx.isThreadCpuTimeSupported())
                .as("this JVM cannot measure per-thread CPU time").isTrue();
        threadMx.setThreadCpuTimeEnabled(true);

        int workers = 4;
        long slowWorkerMillis = 1_000;

        // One round. Worker 0 takes a full second; workers 1..3 arrive at once
        // and then have nothing to do but wait for it.
        RoundSync sync = newRoundSync(workers, id -> {
            if (id == 0) {
                sleepQuietly(slowWorkerMillis);
            }
        });

        Thread runner = new Thread(() -> {
            try {
                sync.runAll(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "round-sync-runner");
        runner.setDaemon(true);
        runner.start();

        TimeUnit.MILLISECONDS.sleep(200);          // let the workers start and park
        List<Long> waitingWorkerIds = waitingWorkerThreadIds(threadMx);
        assertThat(waitingWorkerIds)
                .as("expected live worker threads named 'round-worker-*' (worker 0 is "
                    + "excluded because it is the one doing the slow work)")
                .hasSizeGreaterThanOrEqualTo(1);

        long cpuBefore = totalCpuNanos(threadMx, waitingWorkerIds);
        TimeUnit.MILLISECONDS.sleep(600);          // 600ms with nothing to do but wait
        long cpuAfter = totalCpuNanos(threadMx, waitingWorkerIds);
        runner.join(TimeUnit.SECONDS.toMillis(30));

        long waitingCpuMillis = TimeUnit.NANOSECONDS.toMillis(cpuAfter - cpuBefore);
        assertThat(waitingCpuMillis)
                .as("the %d workers waiting at the barrier burned %d ms of CPU across "
                    + "600 ms of wall clock. A parked thread uses almost none; this much "
                    + "means a spin loop such as `while (arrived.get() < workers) { }`. "
                    + "That passes every correctness assertion above and costs a core per "
                    + "waiting worker, which shows up only on the infrastructure bill — "
                    + "the same defect Ex7 and Ex8 measured. CyclicBarrier.await() parks",
                    waitingWorkerIds.size(), waitingCpuMillis)
                .isLessThan(120L * waitingWorkerIds.size());
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** Worker threads other than worker 0, which is busy doing the slow work. */
    private static List<Long> waitingWorkerThreadIds(ThreadMXBean threadMx) {
        List<Long> ids = new ArrayList<>();
        for (ThreadInfo info : threadMx.getThreadInfo(threadMx.getAllThreadIds())) {
            if (info != null
                    && info.getThreadName().startsWith("round-worker-")
                    && !info.getThreadName().equals("round-worker-0")) {
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

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
