package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.MiniPool;
import com.locallearn.concurrency.support.Check;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>EXERCISE 13 — build a thread pool.</b> See {@code t08pools.D22_PoolGrowthOrder}
 * and {@code t08pools.D24_SizingLifecycleAndLostExceptions}.
 *
 * <p>This is the exercise the whole curriculum has been building toward, and
 * you already own every part of it: worker threads that park rather than
 * spin (Ex7), a bounded queue that applies backpressure (Ex7), and a
 * shutdown that drains instead of abandoning (Ex8's poison pill). What is
 * new is the <b>submission rule</b>, and it is the one thing almost everyone
 * gets backwards:
 *
 * <pre>
 *   1. workers &lt; core?        -> start a worker for this task
 *   2. queue accepts the task? -> queue it            &lt;-- BEFORE growing
 *   3. workers &lt; max?         -> start a worker for this task
 *   4. otherwise               -> RejectedExecutionException
 * </pre>
 *
 * <p>Four planted defects, each its own failing test:
 * <ol>
 *   <li><b>The order is inverted.</b> {@link Ex13Pool#execute} grows the pool
 *       to {@code maxPoolSize} before it ever offers to the queue, so the
 *       queue is dead weight and the pool creates threads for load one queue
 *       slot would have absorbed (D22).</li>
 *   <li><b>Busy-wait.</b> Workers spin on {@code poll()} instead of parking
 *       in {@code take()}, burning a core each while idle (D14, Ex7, Ex8).</li>
 *   <li><b>Lossy graceful shutdown.</b> {@code shutdownAndAwait} flips a flag,
 *       so queued tasks are abandoned rather than drained (D15, D24).</li>
 *   <li><b>{@code shutdownNow} hides the damage.</b> It returns an empty list
 *       instead of the tasks it never started, so the caller cannot see,
 *       count or requeue what was lost (D24).</li>
 * </ol>
 *
 * <p>Hint for defect 3: you cannot use a poison pill <em>and</em> honour
 * {@code shutdownNow}'s interrupt in the same worker loop without deciding
 * what each one means. Write the two shutdowns as the two different verbs
 * they are — drain versus abandon.
 *
 * <p>Notice as you go how little of this is about running tasks. A pool that
 * runs every task you hand it can still be wrong in all four of these ways,
 * which is why most of the checks below measure the pool's <em>shape</em>
 * under load rather than its output.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex13Pool   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex13'                                 # the grade
 * </pre>
 */
public final class Ex13Pool implements com.locallearn.concurrency.api.Contracts.MiniPool {
    private final int corePoolSize;
    private final int maxPoolSize;
    private final java.util.concurrent.BlockingQueue<Runnable> queue;
    private final java.util.List<Thread> workers =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.concurrent.atomic.AtomicLong completed =
            new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicInteger largest =
            new java.util.concurrent.atomic.AtomicInteger();
    private volatile boolean stopped;

    public Ex13Pool(int corePoolSize, int maxPoolSize, int queueCapacity) {
        this.corePoolSize = corePoolSize;
        this.maxPoolSize = maxPoolSize;
        this.queue = new java.util.concurrent.ArrayBlockingQueue<>(queueCapacity);
    }

    @Override
    public void execute(Runnable task) {
        if (stopped) {
            throw new java.util.concurrent.RejectedExecutionException("pool is shut down");
        }
        // TODO broken (defect 1): this grows the pool all the way to
        // maxPoolSize BEFORE it ever tries the queue — the submission rule
        // upside down. Result: the queue is never used while threads remain
        // available, which is exactly backwards from ThreadPoolExecutor (D22).
        synchronized (workers) {
            if (workers.size() < maxPoolSize) {
                addWorker(task);
                return;
            }
        }
        if (!queue.offer(task)) {
            throw new java.util.concurrent.RejectedExecutionException("queue full");
        }
    }

    private void addWorker(Runnable firstTask) {
        Thread worker = new Thread(() -> {
            Runnable task = firstTask;
            while (!stopped) {                  // TODO broken (defect 3): a flag stops
                if (task != null) {             // workers wherever they are, abandoning
                    try {                       // whatever is still queued
                        task.run();
                    } catch (RuntimeException e) {
                        // a pool must survive a failing task
                    }
                    completed.incrementAndGet();
                }
                task = queue.poll();            // TODO broken (defect 2): poll() returns
                                                // null immediately, so an idle worker
                                                // spins at 100% CPU (D14's verb grid)
            }
        }, "minipool-worker-" + workers.size());
        workers.add(worker);
        largest.accumulateAndGet(workers.size(), Math::max);
        worker.start();
    }

    @Override
    public void shutdownAndAwait() throws InterruptedException {
        stopped = true;                         // TODO broken (defect 3): "stop now",
        for (Thread worker : workers) {         // not "finish the queue, then stop"
            worker.join();
        }
    }

    @Override
    public java.util.List<Runnable> shutdownNow() {
        stopped = true;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        // TODO broken (defect 4): the tasks still sitting in the queue are
        // silently dropped. shutdownNow()'s whole contract is that it HANDS
        // THEM BACK so the caller can count or requeue them (D24).
        return java.util.List.of();
    }

    @Override
    public int poolSize() {
        int alive = 0;
        synchronized (workers) {
            for (Thread worker : workers) {
                if (worker.isAlive()) {
                    alive++;
                }
            }
        }
        return alive;
    }

    @Override
    public int largestPoolSize() {
        return largest.get();
    }

    @Override
    public int queueSize() {
        return queue.size();
    }

    @Override
    public long completed() {
        return completed.get();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    /** Only ever reached if a shutdown has hung, so it can afford to be generous. */
    private static final long PATIENCE_MILLIS = 15_000;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 13 — a thread pool from scratch", "ExerciseTests$Ex13");

        check.that("a roomy queue means the pool NEVER grows past core", () -> {
            int core = 2;
            int max = 8;
            CountDownLatch release = new CountDownLatch(1);
            MiniPool pool = new Ex13Pool(core, max, 100);
            try {
                // 50 tasks that all block. Every one of them could have a thread:
                // the pool is allowed 8 and is using 2. It must still use only 2,
                // because the queue has room and the queue comes first.
                for (int i = 0; i < 50; i++) {
                    pool.execute(() -> awaitQuietly(release));
                }
                TimeUnit.MILLISECONDS.sleep(300);   // let every worker actually start

                Check.equal(pool.poolSize(), core,
                        "the pool grew with %d tasks queued and a queue capacity of 100. "
                        + "The submission rule is core -> QUEUE -> max -> reject: a task "
                        + "may only create a thread beyond corePoolSize when the queue "
                        + "REFUSES it. With room in the queue this pool must sit at "
                        + "exactly %d threads (D22, section 1)", pool.queueSize(), core);
            } finally {
                release.countDown();
                quietShutdown(pool);
            }
        });

        check.that("the staircase: core, then queue, then grow to max", () -> {
            int core = 2;
            int max = 6;
            int queueCapacity = 4;
            CountDownLatch release = new CountDownLatch(1);
            MiniPool pool = new Ex13Pool(core, max, queueCapacity);
            try {
                // Phase 1: core + queueCapacity tasks fit without any growth.
                for (int i = 0; i < core + queueCapacity; i++) {
                    pool.execute(() -> awaitQuietly(release));
                }
                TimeUnit.MILLISECONDS.sleep(250);
                Check.equal(pool.poolSize(), core,
                        "after submitting core(%d) + queueCapacity(%d) = %d blocking "
                        + "tasks the pool should still be at core. The first %d fill the "
                        + "core, the next %d go to the QUEUE — none of them may create a "
                        + "thread (D22)",
                        core, queueCapacity, core + queueCapacity, core, queueCapacity);

                // Phase 2: the queue is full, so now — and only now — the pool grows.
                for (int i = 0; i < max - core; i++) {
                    pool.execute(() -> awaitQuietly(release));
                }
                TimeUnit.MILLISECONDS.sleep(250);
                Check.equal(pool.poolSize(), max,
                        "with the queue full, the next %d tasks must each create a "
                        + "thread, taking the pool to maxPoolSize(%d)", max - core, max);
            } finally {
                release.countDown();
                quietShutdown(pool);
            }
        });

        check.that("at max with a full queue, execute() throws RejectedExecutionException", () -> {
            int core = 1;
            int max = 3;
            int queueCapacity = 2;
            CountDownLatch release = new CountDownLatch(1);
            MiniPool pool = new Ex13Pool(core, max, queueCapacity);
            try {
                for (int i = 0; i < max + queueCapacity; i++) {   // exactly saturates it
                    pool.execute(() -> awaitQuietly(release));
                }
                TimeUnit.MILLISECONDS.sleep(250);

                boolean rejected = false;
                try {
                    pool.execute(() -> { });
                } catch (RejectedExecutionException expected) {
                    rejected = true;
                }
                Check.require(rejected,
                        "the pool holds max(%d) + queue(%d) = %d tasks and must refuse the "
                        + "next one. A pool that silently accepts work beyond its declared "
                        + "capacity has an unbounded queue wearing a bounded one's "
                        + "configuration (D22, D23)", max, queueCapacity, max + queueCapacity);
            } finally {
                release.countDown();
                quietShutdown(pool);
            }
        });

        check.that("shutdownAndAwait() runs every accepted task, then stops every worker", () -> {
            int tasks = 2_000;
            AtomicInteger ran = new AtomicInteger();
            MiniPool pool = new Ex13Pool(4, 4, tasks + 16);
            try {
                for (int i = 0; i < tasks; i++) {
                    pool.execute(() -> {
                        busyMicros(50);             // slow enough to leave a real backlog
                        ran.incrementAndGet();
                    });
                }
                shutdownAndAwaitWithin(pool);

                Check.equal(ran.get(), tasks,
                        "shutdownAndAwait() abandoned tasks the pool had already ACCEPTED. "
                        + "A stop flag stops workers wherever they are; to drain, send one "
                        + "poison pill per worker through the same FIFO so it arrives "
                        + "after every real task (D15, and shutdown() in D24)");

                Check.equal(pool.completed(), tasks,
                        "completed() disagrees with the %d tasks that ran", tasks);

                Check.equal(pool.poolSize(), 0,
                        "workers were still alive after shutdownAndAwait() returned — it "
                        + "must JOIN every worker, not merely ask them to stop");
            } finally {
                quietShutdown(pool);
            }
        });

        check.that("shutdownNow() abandons the backlog AND returns the undone tasks", () -> {
            int tasks = 500;
            CountDownLatch release = new CountDownLatch(1);
            MiniPool pool = new Ex13Pool(1, 1, tasks + 16);
            try {
                for (int i = 0; i < tasks; i++) {
                    pool.execute(() -> awaitQuietly(release));
                }
                TimeUnit.MILLISECONDS.sleep(200);   // worker 1 is blocked on task 1

                List<Runnable> neverStarted = pool.shutdownNow();

                Check.require(neverStarted.size() > tasks / 2,
                        "shutdownNow() returned %d tasks, but ~%d were still queued and "
                        + "will now never run. Returning them is the entire difference "
                        + "between losing work and REPORTING lost work: the caller needs "
                        + "the list to requeue it, log it, or at minimum count it (D24)",
                        neverStarted.size(), tasks - 1);

                Check.require(pool.completed() < tasks,
                        "shutdownNow() must not drain the backlog — it abandons it. "
                        + "completed()=%d of %d", pool.completed(), tasks);
            } finally {
                release.countDown();
                quietShutdown(pool);
            }
        });

        check.that("idle workers park instead of spinning on poll()", () -> {
            ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
            Check.require(threadMx.isThreadCpuTimeSupported(),
                    "this JVM cannot measure per-thread CPU time");
            threadMx.setThreadCpuTimeEnabled(true);

            int core = 4;
            MiniPool pool = new Ex13Pool(core, core, 64);
            try {
                for (int i = 0; i < core; i++) {    // force all core workers into existence
                    pool.execute(() -> { });
                }
                TimeUnit.MILLISECONDS.sleep(300);   // ...and then let them all go idle

                List<Long> workerIds = workerThreadIds(threadMx);
                Check.require(!workerIds.isEmpty(),
                        "expected live worker threads named 'minipool-worker-*'");

                long before = totalCpuNanos(threadMx, workerIds);
                TimeUnit.MILLISECONDS.sleep(1_000); // a full second with nothing to do
                long after = totalCpuNanos(threadMx, workerIds);

                long idleCpuMillis = TimeUnit.NANOSECONDS.toMillis(after - before);
                Check.require(idleCpuMillis < 200L * workerIds.size(),
                        "%d idle workers burned %,d ms of CPU across 1000 ms of wall "
                        + "clock. A parked thread uses essentially none; this much means "
                        + "the workers are spinning on poll(). Block in take() instead — "
                        + "it is the same bug Ex7 and Ex8 already rejected, and it costs a "
                        + "core per idle worker in production (D14's verb grid)",
                        workerIds.size(), idleCpuMillis);
            } finally {
                quietShutdown(pool);
            }
        });

        System.exit(check.finish());
    }

    // ── checker helpers ────────────────────────────────────────────────────

    /**
     * Drains on a daemon thread and refuses to wait forever. A graceful shutdown
     * is exactly the place a pool hangs: a worker parked in {@code take()} never
     * re-reads a volatile flag (topic 5, P3), so "ask them to stop, then join"
     * waits for something that will never happen.
     */
    private static void shutdownAndAwaitWithin(MiniPool pool) throws InterruptedException {
        Thread closer = new Thread(() -> {
            try {
                pool.shutdownAndAwait();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "minipool-closer");
        closer.setDaemon(true);
        closer.start();
        closer.join(PATIENCE_MILLIS);
        Check.require(!closer.isAlive(),
                "shutdownAndAwait() had not returned after %,d ms. Either the workers "
                + "were never told to stop, or they were told in a way they cannot hear "
                + "while parked in take() (D15, D24)", PATIENCE_MILLIS);
    }

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
            if (cpu > 0) {                          // -1 once the thread is gone
                total += cpu;
            }
        }
        return total;
    }

    /**
     * Best-effort cleanup. A failed check must not leave spinning workers behind
     * to skew the CPU measurement of every check after it.
     */
    private static void quietShutdown(MiniPool pool) {
        try {
            pool.shutdownNow();
        } catch (RuntimeException e) {
            // the implementation under test is broken; that is what the checks are for
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
