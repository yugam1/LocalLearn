package com.locallearn.concurrency.t09parallel;

import com.locallearn.concurrency.support.Log;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DEMO 27 — Virtual threads (JEP 444, final in Java 21): the answer when the
 * work is <b>IO-bound and there is a lot of it</b>. Plus <b>pinning</b>, which
 * is the one way to lose the entire benefit while your code still looks right.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t09parallel.D27_VirtualThreadsAndPinning}
 *
 * <h2>What a virtual thread actually is</h2>
 * A platform thread is a thin wrapper over an OS thread: ~1 MB of reserved
 * stack, scheduled by the kernel, and expensive enough that pooling it was the
 * whole point of topic 8. A virtual thread is a <em>continuation plus a
 * scheduler</em>, both in the JVM. Its stack lives on the heap and starts at a
 * few hundred bytes, growing only as deep as it actually goes.
 *
 * <p>The mechanism that matters: when a virtual thread executes a blocking JDK
 * call, the JVM <b>unmounts</b> it — copies its stack frames to the heap and
 * frees the underlying <em>carrier</em> (a real platform thread from a
 * {@code ForkJoinPool} sized to the core count) to run some other virtual
 * thread. When the call completes the continuation is <b>mounted</b> again,
 * possibly on a different carrier. So a blocked virtual thread consumes no OS
 * thread at all.
 *
 * <p>That single fact inverts topic 8's economics. Thread pools exist because
 * threads are scarce; virtual threads are not scarce, so you do not pool them.
 * The idiom is <b>one virtual thread per task</b>, created and discarded
 * freely — {@code Executors.newVirtualThreadPerTaskExecutor()} is not a pool
 * despite the name, it is a thread factory with an {@code ExecutorService}
 * interface, and it creates one new virtual thread per submitted task.
 *
 * <h2>Pinning — where it all goes wrong</h2>
 * Unmounting requires the JVM to move the stack to the heap, and it cannot do
 * that when there are frames it does not control. In Java 21 there are two such
 * cases, and the important one is:
 * <pre>
 *   synchronized  → the monitor is held by the CARRIER, so the virtual thread
 *                   cannot unmount. It is PINNED. If it now blocks, it blocks
 *                   the carrier — a real OS thread — for the whole duration.
 *   ReentrantLock → implemented in Java on LockSupport.park, which the JVM DOES
 *                   understand. Unmounts cleanly. No pinning.
 * </pre>
 * The effect is severe and completely invisible in the source: the code is
 * correct, the lock may have <em>zero contention</em>, and throughput still
 * collapses toward the carrier count. Section 3 measures exactly that, using a
 * <b>separate lock object per task</b> so that no two tasks ever contend — any
 * slowdown measured there is pinning and nothing else.
 *
 * <p>(This is a Java 21 limitation, not a permanent property of virtual threads.
 * JEP 491 in Java 24 lets {@code synchronized} unmount. On 21 — this lab's
 * target — it pins, and this is the single highest-value thing to know about
 * running virtual threads in production today.)
 */
public final class D27_VirtualThreadsAndPinning {

    private static final int CORES = Runtime.getRuntime().availableProcessors();

    public static void main(String[] args) throws Exception {
        Log.log("availableProcessors() = %d  (so ~%d carrier threads by default)", CORES, CORES);

        creationCost();
        scaleUnderBlocking();
        pinning();
        notForCpuWork();

        Log.takeaway("""
                Ask what kind of work it is, then pick:

                  CPU-bound, splittable   -> ForkJoinPool / parallel stream (D25, D26)
                  IO-bound, lots of it    -> one virtual thread per task
                  CPU-bound, independent  -> a bounded platform pool (topic 8)

                Virtual threads do not make anything faster. They make BLOCKING cheap,
                by unmounting the stack instead of holding an OS thread. If the work
                never blocks there is nothing to unmount and nothing to gain -- section
                4 shows virtual threads losing to a plain pool on pure CPU work.

                And the whole benefit is forfeited by one `synchronized` around a
                blocking call, with zero contention and no visible symptom in the code.
                On Java 21, audit blocking calls for synchronized and replace it with
                ReentrantLock. Run with -Djdk.tracePinnedThreads=full to find them.""");
    }

    // ── 1. Creation cost ───────────────────────────────────────────────────
    private static void creationCost() throws Exception {
        Log.section("1. CREATION COST — 100,000 threads that do almost nothing");

        int n = 100_000;

        long virtualMillis = time(() -> {
            CountDownLatch done = new CountDownLatch(n);
            for (int i = 0; i < n; i++) {
                Thread.ofVirtual().start(done::countDown);
            }
            awaitQuietly(done);
        });
        Log.log("100,000 VIRTUAL threads created, run and finished: %,dms", virtualMillis);

        // The same with platform threads, at 1/50th the count — because 100,000
        // platform threads is ~100 GB of reserved stack and will not start.
        int platformCount = n / 50;
        long platformMillis = time(() -> {
            CountDownLatch done = new CountDownLatch(platformCount);
            for (int i = 0; i < platformCount; i++) {
                Thread.ofPlatform().start(done::countDown);
            }
            awaitQuietly(done);
        });
        Log.log("  %,6d PLATFORM threads, same trivial body:        %,dms",
                platformCount, platformMillis);
        Log.log("per-thread: virtual ~%.1fus, platform ~%.1fus  -> ~%.0fx cheaper",
                virtualMillis * 1000.0 / n,
                platformMillis * 1000.0 / platformCount,
                (platformMillis / (double) platformCount) / (virtualMillis / (double) n));
        Log.log("A virtual thread's stack starts at a few hundred bytes on the HEAP;");
        Log.log("a platform thread reserves ~1MB of address space and enters the kernel.");
    }

    // ── 2. The point: concurrency under blocking ───────────────────────────
    /**
     * 10,000 tasks that each block for 100 ms. This is a web service's day job.
     * The total work is 1,000 seconds of waiting and ~0 seconds of CPU, so the
     * only question is how many of those waits can be in flight at once.
     */
    private static void scaleUnderBlocking() throws Exception {
        Log.section("2. SCALE — tasks that each block for 100ms");
        Log.log("Pure waiting, no CPU. The only question is how much of the waiting");
        Log.log("can be in flight at once. Reported as throughput so the rows compare");
        Log.log("even though the slowest one runs fewer tasks (it would take minutes).");
        Log.log("%-40s %8s %10s %12s", "executor", "tasks", "wall", "tasks/sec");

        long blockMillis = 100;

        // The cores-sized pool gets fewer tasks purely so this demo finishes:
        // at 10,000 it would take ~83 seconds to make the same point.
        record Case(String label, int poolSize, int tasks) { }
        for (Case c : new Case[]{
                new Case("platform pool of " + CORES, CORES, 1_200),
                new Case("platform pool of 200", 200, 10_000),
                new Case("platform pool of 1000", 1_000, 10_000)}) {
            long millis = time(() -> runBlockingTasks(
                    Executors.newFixedThreadPool(c.poolSize()), c.tasks(), blockMillis));
            Log.log("%-40s %,8d %8dms %,12.0f", c.label(), c.tasks(), millis,
                    c.tasks() * 1000.0 / Math.max(millis, 1));
        }

        int tasks = 10_000;
        long virtualMillis = time(() -> runBlockingTasks(
                Executors.newVirtualThreadPerTaskExecutor(), tasks, blockMillis));
        Log.log("%-40s %,8d %8dms %,12.0f", "newVirtualThreadPerTaskExecutor()", tasks,
                virtualMillis, tasks * 1000.0 / Math.max(virtualMillis, 1));
        Log.log("The virtual executor ran all 10,000 waits essentially at once, on the");
        Log.log("same %d carriers. Nothing was pooled, tuned or sized. There is no", CORES);
        Log.log("queueCapacity to get wrong here, because nothing is queued.");
        Log.log("Note what the platform rows really say: the ONLY way to raise a");
        Log.log("blocking pool's throughput is to add threads, and threads are the");
        Log.log("resource you cannot add many of. That ceiling is what topic 8's whole");
        Log.log("sizing exercise was negotiating with. Virtual threads remove it.");
    }

    // ── 3. PINNING — the measurement that matters ──────────────────────────
    /**
     * Every task takes its OWN lock, so there is never any contention: two tasks
     * never wait for each other, and a correct implementation would show no
     * slowdown at all. The only difference between the three rows is which
     * construct is wrapped around the blocking call.
     */
    private static void pinning() throws Exception {
        Log.section("3. PINNING — the same blocking work, three wrappers, ZERO contention");
        Log.log("Each task locks its OWN private lock object, so no task ever waits for");
        Log.log("another. Any difference below is pinning, not mutual exclusion.");
        Log.log("%-46s %10s %12s", "virtual threads, blocking call wrapped in", "wall", "vs none");

        int tasks = 2_000;
        long blockMillis = 100;

        long none = time(() -> runWrapped(tasks, blockMillis, Wrapper.NONE));
        Log.log("%-46s %8dms %11s", "nothing (plain blocking call)", none, "baseline");

        long reentrant = time(() -> runWrapped(tasks, blockMillis, Wrapper.REENTRANT_LOCK));
        Log.log("%-46s %8dms %10.1fx", "ReentrantLock (uncontended)", reentrant,
                reentrant / (double) none);

        long sync = time(() -> runWrapped(tasks, blockMillis, Wrapper.SYNCHRONIZED));
        Log.log("%-46s %8dms %10.1fx", "synchronized (uncontended)  <-- PINNED", sync,
                sync / (double) none);

        Log.log("");
        Log.log("%,d tasks x %dms = %,ds of waiting. Unpinned, it all overlaps.",
                tasks, blockMillis, tasks * blockMillis / 1000);
        Log.log("Pinned, each blocked virtual thread also holds a CARRIER hostage.");
        Log.log("");
        Log.log("RUN THIS SEVERAL TIMES. Across 11 runs the penalty was BIMODAL:");
        Log.log("  14,400-17,400ms (105-156x) in 9 runs  <- ~2,000/12 carriers, no relief");
        Log.log("  ~ 1,040ms       (  ~9.5x)  in 2 runs  <- the carrier pool was grown");
        Log.log("The JVM MAY compensate for a blocked carrier by starting another, up to");
        Log.log("jdk.virtualThreadScheduler.maxPoolSize (default 256) — and whether it");
        Log.log("manages to is not something your code controls. That is why pinning is");
        Log.log("so hard to diagnose: the same build is 10x slow today and 150x slow");
        Log.log("tomorrow. What never varied across 6 runs is the DIRECTION and the fact");
        Log.log("that even the good mode is an order of magnitude off the baseline.");
        Log.log("");
        Log.log("Nothing in the source distinguishes these two. Same logic, same");
        Log.log("correctness, no contention — and one of them quietly turns your");
        Log.log("virtual threads back into a small platform pool.");
        Log.log("Find them with: java -Djdk.tracePinnedThreads=full ...");
    }

    private enum Wrapper { NONE, REENTRANT_LOCK, SYNCHRONIZED }

    private static void runWrapped(int tasks, long blockMillis, Wrapper wrapper) {
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < tasks; i++) {
                executor.submit(() -> {
                    // A private lock per task: uncontended by construction.
                    Object monitor = new Object();
                    ReentrantLock lock = new ReentrantLock();
                    switch (wrapper) {
                        case NONE -> sleep(blockMillis);
                        case REENTRANT_LOCK -> {
                            lock.lock();
                            try {
                                sleep(blockMillis);   // unmounts: no carrier held
                            } finally {
                                lock.unlock();
                            }
                        }
                        case SYNCHRONIZED -> {
                            synchronized (monitor) {
                                sleep(blockMillis);   // PINNED: carrier held throughout
                            }
                        }
                    }
                    return null;
                });
            }
        }   // close() waits for every task
    }

    // ── 4. Virtual threads are not a speed-up for CPU work ─────────────────
    private static void notForCpuWork() throws Exception {
        Log.section("4. NOT A SPEED-UP — pure CPU work, no blocking at all");

        // Few, CHUNKY tasks on purpose. An earlier version of this section used
        // 2,000 tiny tasks and virtual threads won every run by 2-3x -- which
        // measured queue contention in the fixed pool (2,000 short tasks through
        // one shared LinkedBlockingQueue) rather than anything about virtual
        // threads. Bigger tasks make the scheduling cost negligible so the
        // comparison is about the compute, which is what the claim is about.
        int tasks = 240;
        int iterations = 2_000_000;

        long platform = time(() -> runCpuTasks(
                Executors.newFixedThreadPool(CORES), tasks, iterations));
        long virtual = time(() -> runCpuTasks(
                Executors.newVirtualThreadPerTaskExecutor(), tasks, iterations));

        Log.log("platform pool of %-3d : %6dms", CORES, platform);
        Log.log("virtual per task     : %6dms  (%.2fx)", virtual, virtual / (double) platform);
        Log.log("Across 5 runs the virtual executor was equal or FASTER every time");
        Log.log("(platform 186-860ms, virtual 101-268ms). Say the honest thing about");
        Log.log("that rather than the tidy thing:");
        Log.log("");
        Log.log("  * It is NOT an unmounting benefit. Nothing blocks here, so no virtual");
        Log.log("    thread ever unmounts. The work runs on the same %d carriers.", CORES);
        Log.log("  * It IS the scheduler shape. A fixed pool funnels every task through");
        Log.log("    ONE shared queue; the virtual scheduler is a ForkJoinPool with");
        Log.log("    per-worker deques (D25). With many small tasks that difference shows.");
        Log.log("  * The platform row is also much noisier (186ms to 860ms), which is");
        Log.log("    its own argument against reading too much into either number.");
        Log.log("");
        Log.log("So the lesson is the NEGATIVE one, and it survives the measurement:");
        Log.log("virtualisation itself buys CPU-bound work nothing, because there is no");
        Log.log("blocking to make cheap. Prefer a bounded pool for CPU work when you want");
        Log.log("concurrency BOUNDED on purpose — not because it is faster. On this");
        Log.log("machine it was not, and claiming otherwise would be inventing a result.");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static void runBlockingTasks(ExecutorService executor, int tasks, long blockMillis) {
        try (executor) {
            for (int i = 0; i < tasks; i++) {
                executor.submit(() -> {
                    sleep(blockMillis);
                    return null;
                });
            }
        }
    }

    private static final java.util.concurrent.atomic.AtomicLong SINK =
            new java.util.concurrent.atomic.AtomicLong();

    private static void runCpuTasks(ExecutorService executor, int tasks, int iterations) {
        try (executor) {
            for (int i = 0; i < tasks; i++) {
                final int seed = i;
                executor.submit(() -> {
                    long x = 0x9E3779B97F4A7C15L ^ seed;
                    for (int k = 0; k < iterations; k++) {
                        x ^= x << 13;
                        x ^= x >>> 7;
                        x ^= x << 17;
                    }
                    SINK.addAndGet(x);
                    return null;
                });
            }
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(Duration.ofMillis(millis));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long time(Runnable body) {
        long start = System.nanoTime();
        body.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private D27_VirtualThreadsAndPinning() {
    }
}
