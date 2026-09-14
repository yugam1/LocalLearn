package com.locallearn.concurrency.t08pools;

import com.locallearn.concurrency.support.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * DEMO 22 — The task-flow order, which is the single most counter-intuitive
 * fact about {@link ThreadPoolExecutor}: <b>core → queue → max → reject</b>.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t08pools.D22_PoolGrowthOrder}
 *
 * <h2>The order, and why everyone guesses it wrong</h2>
 * Almost every engineer reads {@code core=2, max=10} and concludes "the pool
 * runs 2 threads normally and up to 10 when busy". That is not what happens.
 * The real sequence for every submitted task is:
 * <pre>
 *   1. fewer than corePoolSize threads?      → start a NEW thread, run it there
 *   2. else, can the QUEUE accept the task?  → queue it        ← the surprise
 *   3. else, fewer than maximumPoolSize?     → start a NEW thread
 *   4. else                                  → RejectedExecutionHandler
 * </pre>
 * Step 2 sits <em>before</em> step 3. The pool therefore grows past core
 * <b>only when the queue is full</b> — so with a large or unbounded queue the
 * queue never fills, step 3 never fires, and {@code maximumPoolSize} is
 * decoration. It is configuration that reads like a safety limit and is in fact
 * unreachable code.
 *
 * <h2>Why it was designed this way</h2>
 * This is not an accident or a historical wart. A thread is expensive
 * (~1 MB of reserved stack, a scheduler entity, a context-switch participant);
 * a queue slot is a pointer. {@code ThreadPoolExecutor} therefore prefers the
 * cheap resource and treats thread creation as the last resort before outright
 * refusal. The queue is not a waiting room the pool uses when it gives up on
 * threads — it is the pool's <em>first</em> choice once the core is busy.
 *
 * <p>The consequence you must internalise: <b>the queue length is what sets
 * your latency, and the pool size is what sets your throughput</b>, and the two
 * knobs do not interact the way the names suggest.
 *
 * <h2>The Executors factory methods hide exactly these numbers</h2>
 * Every {@code Executors.newXxx} shortcut is a {@code ThreadPoolExecutor} with
 * two of the four numbers pinned at an extreme:
 * <pre>
 *   newFixedThreadPool(n)   core=max=n,  queue = LinkedBlockingQueue()  ← UNBOUNDED
 *   newSingleThreadExecutor core=max=1,  queue = LinkedBlockingQueue()  ← UNBOUNDED
 *   newCachedThreadPool()   core=0, max=Integer.MAX_VALUE,
 *                           queue = SynchronousQueue                    ← UNBOUNDED THREADS
 * </pre>
 * {@code newFixedThreadPool} can never reject and never grows, so its failure
 * mode is the D13 backlog: memory, later, somewhere else.
 * {@code newCachedThreadPool} uses a zero-capacity {@code SynchronousQueue}
 * precisely so that step 2 <em>always fails instantly</em> — which forces step 3
 * on every task that finds no idle worker. Topic 5 built that mechanism; this is
 * what it was for.
 */
public final class D22_PoolGrowthOrder {

    public static void main(String[] args) throws Exception {
        bigQueueNeverGrows();
        smallQueueGrowsThenRejects();
        theStaircase();
        fixedPoolBacklog();
        cachedPoolThreadExplosion();

        Log.takeaway("""
                core -> QUEUE -> max -> reject. The queue is tried BEFORE the pool
                grows, because a queue slot is a pointer and a thread is a megabyte.

                Therefore: maxPoolSize is dead configuration whenever the queue is
                large. If you want a pool that actually reaches its maximum, you
                must give it a queue small enough to fill. If you want a pool that
                absorbs bursts in memory instead, say so on purpose and size the
                queue as the memory budget it really is.

                The queue length sets your LATENCY; the pool size sets your
                THROUGHPUT. The names do not tell you that.""");
    }

    // ── 1. The surprise, isolated ──────────────────────────────────────────
    /**
     * core=2, max=10, queue=100, and 60 simultaneous long tasks. The intuitive
     * answer is "10 threads, it's busy". The real answer is 2, and it would
     * still be 2 with 101 tasks.
     */
    private static void bigQueueNeverGrows() throws Exception {
        Log.section("1. core=2, max=10, queue=100 — submit 60 long tasks");

        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 10, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100));

        for (int i = 0; i < 60; i++) {
            pool.execute(() -> await(release));
        }
        TimeUnit.MILLISECONDS.sleep(200);           // let the pool settle

        Log.log("poolSize=%d  active=%d  queued=%d  (max is 10 and we submitted 60)",
                pool.getPoolSize(), pool.getActiveCount(), pool.getQueue().size());
        Log.log("58 tasks are sitting in a queue with 40 free slots while 8 threads");
        Log.log("the pool is ALLOWED to create do not exist. maxPoolSize=10 is");
        Log.log("unreachable here: you would need 103 concurrent tasks to reach it.");

        release.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // ── 2. Shrink the queue and the same pool behaves completely differently ─
    private static void smallQueueGrowsThenRejects() throws Exception {
        Log.section("2. Same pool, queue=4 — the ONLY change");

        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 10, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4));

        int accepted = 0;
        int rejected = 0;
        for (int i = 0; i < 60; i++) {
            try {
                pool.execute(() -> await(release));
                accepted++;
            } catch (RejectedExecutionException e) {
                rejected++;                          // AbortPolicy is the default
            }
        }
        TimeUnit.MILLISECONDS.sleep(200);

        Log.log("poolSize=%d  active=%d  queued=%d  accepted=%d  rejected=%d",
                pool.getPoolSize(), pool.getActiveCount(), pool.getQueue().size(),
                accepted, rejected);
        Log.log("Capacity is max + queue = 10 + 4 = 14. Everything beyond that is");
        Log.log("refused. Identical pool, identical load — one number changed.");

        release.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // ── 3. Watch the four phases happen in order ───────────────────────────
    /**
     * Submits one task at a time and prints the pool's shape after each, so the
     * four phases appear as a staircase rather than as a rule you must trust.
     */
    private static void theStaircase() throws Exception {
        Log.section("3. The staircase — core=2, max=4, queue=3, one task at a time");

        CountDownLatch release = new CountDownLatch(1);
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                2, 4, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(3));

        Log.log("%-10s %10s %10s  %s", "submitted", "poolSize", "queued", "what just happened");
        for (int i = 1; i <= 9; i++) {
            String outcome;
            int sizeBefore = pool.getPoolSize();
            try {
                pool.execute(() -> await(release));
                TimeUnit.MILLISECONDS.sleep(60);     // let the worker actually start
                outcome = pool.getPoolSize() > sizeBefore
                        ? "NEW THREAD" : "queued";
            } catch (RejectedExecutionException e) {
                outcome = "REJECTED (max + queue full)";
            }
            Log.log("%-10d %10d %10d  %s",
                    i, pool.getPoolSize(), pool.getQueue().size(), outcome);
        }
        Log.log("Tasks 1-2 made threads (below core). Tasks 3-5 went to the QUEUE");
        Log.log("even though the pool was allowed to grow. Only once the queue was");
        Log.log("full did tasks 6-7 create threads 3 and 4. Then: refusal.");

        release.countDown();
        pool.shutdown();
        pool.awaitTermination(10, TimeUnit.SECONDS);
    }

    // ── 4. newFixedThreadPool: the backlog you cannot see ──────────────────
    /**
     * The D13 experiment, one layer up. A fixed pool's queue is an unbounded
     * {@code LinkedBlockingQueue}, so {@code submit} never blocks and never
     * refuses — the deficit between arrival rate and service rate becomes heap.
     */
    private static void fixedPoolBacklog() throws Exception {
        Log.section("4. Executors.newFixedThreadPool(2) — the invisible backlog");

        CountDownLatch release = new CountDownLatch(1);
        ExecutorService fixed = Executors.newFixedThreadPool(2);
        ThreadPoolExecutor asPool = (ThreadPoolExecutor) fixed;

        Log.log("queue class = %s  (no capacity argument exists)",
                asPool.getQueue().getClass().getSimpleName());

        long start = System.nanoTime();
        int submitted = 0;
        while (asPool.getQueue().size() < 1_000_000) {   // safety valve; production has none
            fixed.execute(() -> await(release));
            submitted++;
        }
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        Log.log("submitted %,d tasks in %,d ms with poolSize=%d — queue holds %,d",
                submitted, millis, asPool.getPoolSize(), asPool.getQueue().size());
        Log.log("Not one submit blocked and not one task was refused. Each queued");
        Log.log("Runnable is a live object graph: closures capture their arguments,");
        Log.log("so a backlog of requests pins every request's data in the heap.");
        Log.log("This is D13 wearing an ExecutorService costume.");

        release.countDown();
        fixed.shutdownNow();
    }

    // ── 5. newCachedThreadPool: the other extreme ──────────────────────────
    /**
     * {@code newCachedThreadPool} is core=0, max={@code Integer.MAX_VALUE},
     * queue={@code SynchronousQueue}. Zero queue capacity means step 2 always
     * fails, so every task that finds no idle worker creates a thread. There is
     * no number in the configuration that stops this.
     */
    private static void cachedPoolThreadExplosion() throws Exception {
        Log.section("5. Executors.newCachedThreadPool() — unbounded THREADS");

        CountDownLatch release = new CountDownLatch(1);
        ExecutorService cached = Executors.newCachedThreadPool();
        ThreadPoolExecutor asPool = (ThreadPoolExecutor) cached;

        Log.log("queue class = %s  (capacity ZERO — topic 5, D14)",
                asPool.getQueue().getClass().getSimpleName());

        int tasks = 1_000;
        for (int i = 0; i < tasks; i++) {
            cached.execute(() -> await(release));
        }
        TimeUnit.MILLISECONDS.sleep(300);

        Log.log("submitted %,d concurrent tasks -> poolSize=%,d, largestPoolSize=%,d",
                tasks, asPool.getPoolSize(), asPool.getLargestPoolSize());
        Log.log("One OS thread per in-flight task, ~1 MB of reserved stack each.");
        Log.log("A traffic spike does not queue here — it allocates. The failure is");
        Log.log("OutOfMemoryError: unable to create native thread, or a machine that");
        Log.log("spends its cores context-switching instead of working.");

        release.countDown();
        cached.shutdown();
        cached.awaitTermination(10, TimeUnit.SECONDS);
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private D22_PoolGrowthOrder() {
    }
}
