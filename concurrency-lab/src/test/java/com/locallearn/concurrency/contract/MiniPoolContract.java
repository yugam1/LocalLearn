package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.MiniPool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EXERCISE 13 contract — a thread pool built from scratch.
 *
 * <p>Six tests, and the important thing about them is that <b>a pool can pass
 * "does it run my tasks" while being completely wrong</b>. The submission order
 * (core → queue → max → reject) is invisible to any test that only counts
 * completions, so most of these assertions are about the pool's <em>shape</em>
 * under load rather than its output.
 *
 * <ul>
 *   <li>{@link #queuesBeforeGrowingPastCore()} — D22. The headline. A pool with
 *       a roomy queue must stay at {@code corePoolSize} no matter how much work
 *       arrives.</li>
 *   <li>{@link #growsToMaxOnlyWhenTheQueueIsFull()} — D22's staircase, asserted
 *       one step at a time.</li>
 *   <li>{@link #rejectsWhenAtMaxWithAFullQueue()} — D23's AbortPolicy.</li>
 *   <li>{@link #gracefulShutdownDrainsEveryAcceptedTask()} — D15/D24. Drain.</li>
 *   <li>{@link #shutdownNowAbandonsAndReturnsTheUndoneTasks()} — D24. Abandon,
 *       and hand back the evidence.</li>
 *   <li>{@link #idleWorkersParkInsteadOfSpinning()} — D14's verb grid. The only
 *       witness to a {@code poll()} spin is CPU time.</li>
 * </ul>
 */
public abstract class MiniPoolContract {

    protected abstract MiniPool newPool(int corePoolSize, int maxPoolSize, int queueCapacity);

    @Test
    @Timeout(60)
    @DisplayName("a roomy queue means the pool NEVER grows past core")
    void queuesBeforeGrowingPastCore() throws Exception {
        int core = 2;
        int max = 8;
        CountDownLatch release = new CountDownLatch(1);
        MiniPool pool = newPool(core, max, 100);
        try {
            // 50 tasks that all block. Every one of them could have a thread:
            // the pool is allowed 8 and is using 2. It must still use only 2,
            // because the queue has room and the queue comes first.
            for (int i = 0; i < 50; i++) {
                pool.execute(() -> awaitQuietly(release));
            }
            TimeUnit.MILLISECONDS.sleep(300);       // let every worker actually start

            assertThat(pool.poolSize())
                    .as("the pool grew to %d threads with %d tasks queued and a queue "
                        + "capacity of 100. The submission rule is core -> QUEUE -> max "
                        + "-> reject: a task may only create a thread beyond corePoolSize "
                        + "when the queue REFUSES it. With room in the queue this pool "
                        + "must sit at exactly %d threads (D22, section 1)",
                        pool.poolSize(), pool.queueSize(), core)
                    .isEqualTo(core);
        } finally {
            release.countDown();
            quietShutdown(pool);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("the staircase: core, then queue, then grow to max")
    void growsToMaxOnlyWhenTheQueueIsFull() throws Exception {
        int core = 2;
        int max = 6;
        int queueCapacity = 4;
        CountDownLatch release = new CountDownLatch(1);
        MiniPool pool = newPool(core, max, queueCapacity);
        try {
            // Phase 1: core + queueCapacity tasks fit without any growth.
            for (int i = 0; i < core + queueCapacity; i++) {
                pool.execute(() -> awaitQuietly(release));
            }
            TimeUnit.MILLISECONDS.sleep(250);
            assertThat(pool.poolSize())
                    .as("after submitting core(%d) + queueCapacity(%d) = %d blocking tasks "
                        + "the pool had %d threads. The first %d fill the core, the next "
                        + "%d go to the QUEUE — none of them may create a thread (D22)",
                        core, queueCapacity, core + queueCapacity, pool.poolSize(),
                        core, queueCapacity)
                    .isEqualTo(core);

            // Phase 2: the queue is full, so now — and only now — the pool grows.
            for (int i = 0; i < max - core; i++) {
                pool.execute(() -> awaitQuietly(release));
            }
            TimeUnit.MILLISECONDS.sleep(250);
            assertThat(pool.poolSize())
                    .as("with the queue full, the next %d tasks must each create a thread, "
                        + "taking the pool to maxPoolSize(%d) — it had %d",
                        max - core, max, pool.poolSize())
                    .isEqualTo(max);
        } finally {
            release.countDown();
            quietShutdown(pool);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("at max with a full queue, execute() throws RejectedExecutionException")
    void rejectsWhenAtMaxWithAFullQueue() throws Exception {
        int core = 1;
        int max = 3;
        int queueCapacity = 2;
        CountDownLatch release = new CountDownLatch(1);
        MiniPool pool = newPool(core, max, queueCapacity);
        try {
            for (int i = 0; i < max + queueCapacity; i++) {   // exactly saturates it
                pool.execute(() -> awaitQuietly(release));
            }
            TimeUnit.MILLISECONDS.sleep(250);

            assertThatThrownBy(() -> pool.execute(() -> { }))
                    .as("the pool holds max(%d) + queue(%d) = %d tasks and must refuse the "
                        + "next one. A pool that silently accepts work beyond its declared "
                        + "capacity has an unbounded queue wearing a bounded one's "
                        + "configuration (D22, D23)", max, queueCapacity, max + queueCapacity)
                    .isInstanceOf(RejectedExecutionException.class);
        } finally {
            release.countDown();
            quietShutdown(pool);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("shutdownAndAwait() runs every accepted task, then stops every worker")
    void gracefulShutdownDrainsEveryAcceptedTask() throws Exception {
        int tasks = 2_000;
        AtomicInteger ran = new AtomicInteger();
        MiniPool pool = newPool(4, 4, tasks + 16);
        try {
            for (int i = 0; i < tasks; i++) {
                pool.execute(() -> {
                    busyMicros(50);                 // slow enough to leave a real backlog
                    ran.incrementAndGet();
                });
            }
            pool.shutdownAndAwait();

            assertThat(ran.get())
                    .as("submitted %d tasks, only %d ran — shutdownAndAwait() abandoned %d "
                        + "tasks the pool had already ACCEPTED. A stop flag stops workers "
                        + "wherever they are; to drain, send one poison pill per worker "
                        + "through the same FIFO so it arrives after every real task "
                        + "(D15, and shutdown() in D24)", tasks, ran.get(), tasks - ran.get())
                    .isEqualTo(tasks);

            assertThat(pool.completed())
                    .as("completed() reported %d for %d tasks", pool.completed(), tasks)
                    .isEqualTo(tasks);

            assertThat(pool.poolSize())
                    .as("%d workers were still alive after shutdownAndAwait() returned — it "
                        + "must JOIN every worker, not merely ask them to stop", pool.poolSize())
                    .isZero();
        } finally {
            quietShutdown(pool);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("shutdownNow() abandons the backlog AND returns the tasks it never started")
    void shutdownNowAbandonsAndReturnsTheUndoneTasks() throws Exception {
        int tasks = 500;
        CountDownLatch release = new CountDownLatch(1);
        MiniPool pool = newPool(1, 1, tasks + 16);
        try {
            for (int i = 0; i < tasks; i++) {
                pool.execute(() -> awaitQuietly(release));
            }
            TimeUnit.MILLISECONDS.sleep(200);       // worker 1 is blocked on task 1

            List<Runnable> neverStarted = pool.shutdownNow();

            assertThat(neverStarted)
                    .as("shutdownNow() returned %d tasks, but ~%d were still queued and will "
                        + "now never run. Returning them is the entire difference between "
                        + "losing work and REPORTING lost work: the caller needs the list to "
                        + "requeue it, log it, or at minimum count it (D24)",
                        neverStarted.size(), tasks - 1)
                    .hasSizeGreaterThan(tasks / 2);

            assertThat(pool.completed())
                    .as("shutdownNow() must not drain the backlog — it abandons it. "
                        + "completed()=%d of %d", pool.completed(), tasks)
                    .isLessThan(tasks);
        } finally {
            release.countDown();
            quietShutdown(pool);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("idle workers park instead of spinning on poll()")
    void idleWorkersParkInsteadOfSpinning() throws Exception {
        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        assertThat(threadMx.isThreadCpuTimeSupported())
                .as("this JVM cannot measure per-thread CPU time").isTrue();
        threadMx.setThreadCpuTimeEnabled(true);

        int core = 4;
        MiniPool pool = newPool(core, core, 64);
        try {
            for (int i = 0; i < core; i++) {        // force all core workers into existence
                pool.execute(() -> { });
            }
            TimeUnit.MILLISECONDS.sleep(300);       // ...and then let them all go idle

            List<Long> workerIds = workerThreadIds(threadMx);
            assertThat(workerIds)
                    .as("expected live worker threads named 'minipool-worker-*'")
                    .hasSizeGreaterThanOrEqualTo(1);

            long before = totalCpuNanos(threadMx, workerIds);
            TimeUnit.MILLISECONDS.sleep(1_000);     // a full second with nothing to do
            long after = totalCpuNanos(threadMx, workerIds);

            long idleCpuMillis = TimeUnit.NANOSECONDS.toMillis(after - before);
            assertThat(idleCpuMillis)
                    .as("%d idle workers burned %d ms of CPU across 1000 ms of wall clock. A "
                        + "parked thread uses essentially none; this much means the workers "
                        + "are spinning on poll(). Block in take() instead — it is the same "
                        + "bug Ex7 and Ex8 already rejected, and it costs a core per idle "
                        + "worker in production (D14's verb grid)",
                        workerIds.size(), idleCpuMillis)
                    .isLessThan(200L * workerIds.size());
        } finally {
            quietShutdown(pool);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static List<Long> workerThreadIds(ThreadMXBean threadMx) {
        List<Long> ids = new ArrayList<>();
        for (ThreadInfo info : threadMx.getThreadInfo(threadMx.getAllThreadIds())) {
            if (info != null && info.getThreadName().startsWith("minipool-worker-")) {
                ids.add(info.getThreadId());
            }
        }
        return ids;
    }

    private static long totalCpuNanos(ThreadMXBean threadMx, List<Long> ids) {
        long total = 0;
        for (long id : ids) {
            long cpu = threadMx.getThreadCpuTime(id);
            if (cpu > 0) {
                total += cpu;
            }
        }
        return total;
    }

    /**
     * Best-effort cleanup. A failed assertion must not leave spinning daemon
     * workers behind to skew the CPU measurement of every later test.
     */
    private static void quietShutdown(MiniPool pool) {
        try {
            pool.shutdownNow();
        } catch (RuntimeException e) {
            // the implementation under test is broken; that is what assertions are for
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void busyMicros(long micros) {
        long deadline = System.nanoTime() + micros * 1_000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
