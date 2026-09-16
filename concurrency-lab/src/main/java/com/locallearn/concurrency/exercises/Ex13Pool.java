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

/*
 * EXERCISE 13 — build a thread pool
 *
 * THE SCENARIO
 *   This is the executor sitting behind a service: callers hand it Runnables
 *   from many threads, it runs them on a small set of worker threads, and it
 *   must be shut down cleanly at the end. It is a hand-written
 *   ThreadPoolExecutor with the same three knobs: core size, max size, and a
 *   bounded queue.
 *
 * WHAT IS WRONG RIGHT NOW
 *   The pool has four independent defects, and each one fails its own check:
 *   1. execute applies the submission rule upside down. It starts a new worker
 *      whenever workers.size() < maxPoolSize, so the pool runs to max threads
 *      before a single task ever reaches the queue. The queue only gets used
 *      once the pool is already at max — the queue is dead weight, and the pool
 *      makes threads for load one queue slot would have absorbed.
 *   2. The worker loop calls queue.poll(), which returns null immediately when
 *      the queue is empty. An idle worker therefore loops as fast as the CPU
 *      allows and burns a whole core doing nothing.
 *   3. shutdownAndAwait sets stopped = true and joins. A worker checks that
 *      flag between tasks, so tasks the pool had already accepted and queued
 *      are abandoned instead of run. Worse, a worker parked in a blocking take
 *      never re-reads the flag at all, so this shape also hangs.
 *   4. shutdownNow returns List.of(). The tasks still sitting in the queue are
 *      dropped without a trace, so the caller cannot count, log or requeue the
 *      work that was lost.
 *
 * YOUR TASK
 *   1. execute(Runnable) — apply the submission rule in this order: (1) fewer
 *      than corePoolSize workers, start one for this task; (2) otherwise try
 *      the queue; (3) only if the queue REFUSES it and there are fewer than
 *      maxPoolSize workers, start one; (4) otherwise throw
 *      RejectedExecutionException.
 *   2. addWorker(Runnable) — make the worker loop block waiting for the next
 *      task instead of polling, and make it exit on whatever signal the two
 *      shutdowns send.
 *   3. shutdownAndAwait() — drain: run every task already accepted, then stop
 *      each worker, then join them all before returning.
 *   4. shutdownNow() — abandon: stop the workers and return the tasks that were
 *      never started.
 *
 * RULES
 *   1. No busy-wait. An idle worker must consume essentially no CPU; a check
 *      measures per-thread CPU time over an idle second.
 *   2. A task that throws must not kill its worker — the pool keeps serving.
 *   3. The two shutdowns are different verbs and must stay different:
 *      shutdownAndAwait finishes the backlog, shutdownNow does not.
 *   4. Keep worker threads named minipool-worker-*; the CPU check finds them by
 *      that prefix.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The six checks are:
 *   1. with a roomy queue the pool never grows past corePoolSize;
 *   2. the staircase: core fills, then the queue fills, then the pool grows to
 *      max;
 *   3. at max with a full queue, execute throws RejectedExecutionException;
 *   4. shutdownAndAwait runs all 2,000 accepted tasks and leaves 0 workers
 *      alive;
 *   5. shutdownNow returns the hundreds of tasks it abandoned;
 *   6. four idle workers burn near-zero CPU across a full second.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex13Pool
 *
 * HINT
 *   You cannot use a poison pill AND honour shutdownNow's interrupt in one
 *   worker loop without deciding what each one means: a pill travels through
 *   the FIFO and therefore arrives after every real task (that is the drain),
 *   while an interrupt reaches a parked worker immediately (that is the
 *   abandon).
 *   Most of the checks measure the pool's SHAPE under load, not its output — a
 *   pool that runs every task you hand it can still be wrong in all four ways.
 *
 * SEE ALSO
 *   Docs — read this first: docs/02-concurrency/08-threadpool-internals.md,
 *     section "EXERCISE 13".
 *   Demo t08pools.D22_PoolGrowthOrder shows the submission rule live, and
 *   t08pools.D24_SizingLifecycleAndLostExceptions shows the two shutdowns. You
 *   already built the parts: parking workers and a bounded queue in Ex7, the
 *   poison-pill drain in Ex8. Reference solution: solutions/Solutions.java.
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

    /*
     * Accepts a task, or refuses it. Must guarantee the submission rule: core
     * -> QUEUE -> max -> reject. A task may only create a thread beyond
     * corePoolSize when the queue has REFUSED it. Get the order wrong and the
     * pool spends threads on load a queue slot would have absorbed, which is
     * how a service ends up with hundreds of threads under a burst it could
     * have buffered.
     */
    @Override
    public void execute(Runnable task) {
        if (stopped) {
            throw new java.util.concurrent.RejectedExecutionException("pool is shut down");
        }
        // WRONG: this grows the pool all the way to maxPoolSize before it ever offers
        // the task to the queue, so the queue is only reached once the pool is already
        // at max — the submission rule upside down (D22).
        // TODO rewrite these two blocks in the right order: start a worker only while
        // workers.size() < corePoolSize; then offer to the queue; only when offer()
        // returns false may you start a worker up to maxPoolSize; otherwise reject.
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

    /*
     * Starts one worker. The loop must guarantee two things: an idle worker
     * consumes no CPU (it waits for a task rather than asking for one over and
     * over), and it leaves the loop only when a shutdown says so. A worker
     * that polls costs a core each while idle; a worker that only tests a flag
     * cannot notice a shutdown while it is parked, and the join in
     * shutdownAndAwait then waits forever.
     */
    private void addWorker(Runnable firstTask) {
        Thread worker = new Thread(() -> {
            Runnable task = firstTask;
            while (!stopped) {                  // WRONG: a volatile flag ends the loop
                if (task != null) {             // wherever the worker is, so tasks still
                    try {                       // queued are abandoned — and a parked
                        task.run();             // worker never re-reads it at all.
                    } catch (RuntimeException e) {
                        // a pool must survive a failing task
                    }
                    completed.incrementAndGet();
                }
                // TODO block here until a task arrives (take()) instead of polling, and
                // decide the exit condition with it: a poison pill read off the queue
                // ends the drain, an interrupt ends the abandon.
                task = queue.poll();            // WRONG: poll() returns null the instant
                                                // the queue is empty, so an idle worker
                                                // spins at 100% CPU (D14's verb grid).
            }
        }, "minipool-worker-" + workers.size());
        workers.add(worker);
        largest.accumulateAndGet(workers.size(), Math::max);
        worker.start();
    }

    /*
     * The graceful shutdown: every task the pool ACCEPTED must still run, and
     * every worker must be dead before this returns. If it merely asks workers
     * to stop, queued work is silently lost — the caller was told the task was
     * accepted.
     */
    @Override
    public void shutdownAndAwait() throws InterruptedException {
        // WRONG: this says "stop now", not "finish the queue, then stop". Workers see
        // the flag between tasks and exit with the backlog still queued; a worker that
        // is parked waiting for work never sees it at all, so the join below hangs.
        // TODO stop accepting new tasks, then send a signal that travels through the
        // SAME FIFO as the tasks — one poison pill per worker — so each worker reaches
        // it only after every real task, and only then join them.
        stopped = true;
        for (Thread worker : workers) {
            worker.join();
        }
    }

    /*
     * The abrupt shutdown: abandon the backlog, but REPORT it. Interrupting
     * the workers is only half the contract; the tasks that will now never run
     * have to come back to the caller, who is the only one who can requeue,
     * log or count them. Returning an empty list turns lost work into
     * invisible lost work.
     */
    @Override
    public java.util.List<Runnable> shutdownNow() {
        stopped = true;
        for (Thread worker : workers) {
            worker.interrupt();
        }
        // WRONG: the tasks still sitting in the queue are dropped silently (D24).
        // TODO drain the queue into a list and return that list — the tasks removed
        // here are exactly the ones this shutdown is abandoning.
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

    /*
     * Only ever reached if a shutdown has hung, so it can afford to be
     * generous.
     */
    private static final long PATIENCE_MILLIS = 15_000;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 13 — a thread pool from scratch", "ExerciseTests$Ex13")
                .reading("docs/02-concurrency/08-threadpool-internals.md § \"EXERCISE 13\"");

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

    /*
     * Drains on a daemon thread and refuses to wait forever. A graceful
     * shutdown is exactly the place a pool hangs: a worker parked in take()
     * never re-reads a volatile flag (topic 5, P3), so "ask them to stop, then
     * join" waits for something that will never happen.
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

    /*
     * Best-effort cleanup. A failed check must not leave spinning workers
     * behind to skew the CPU measurement of every check after it.
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
