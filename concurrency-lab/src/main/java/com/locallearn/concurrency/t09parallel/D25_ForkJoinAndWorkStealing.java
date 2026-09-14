package com.locallearn.concurrency.t09parallel;

import com.locallearn.concurrency.support.Log;

import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RecursiveTask;
import java.util.concurrent.TimeUnit;

/**
 * DEMO 25 — {@link ForkJoinPool}: the pool for <b>CPU-bound divide-and-conquer</b>,
 * and the one ordering rule that decides whether it parallelises at all.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t09parallel.D25_ForkJoinAndWorkStealing}
 *
 * <h2>Why this pool is a different shape from topic 8's</h2>
 * A {@code ThreadPoolExecutor} has <em>one</em> queue that every worker takes
 * from, which is correct when tasks are independent and arrive from outside. A
 * divide-and-conquer computation is neither: tasks are created <em>by other
 * tasks</em>, mostly consumed by the thread that made them, and there are
 * enormous numbers of them. One shared queue would turn every split into a
 * contended lock acquisition.
 *
 * <p>So {@link ForkJoinPool} gives every worker its <b>own double-ended queue</b>.
 * A worker pushes and pops its own sub-tasks at the <em>head</em>, with no lock
 * and no contention in the common case — the newest sub-task is also the one
 * whose data is still warm in cache. Only when a worker runs dry does it
 * <b>steal</b> from the <em>tail</em> of another worker's deque: the oldest task
 * there, which is the biggest remaining chunk, so one steal buys a lot of work
 * and steals stay rare. That is the whole design, and it is why an
 * unbalanced tree still keeps every core busy without anyone scheduling it.
 *
 * <h2>The ordering rule — the one thing to get right</h2>
 * <pre>
 *   right.fork();                 // hand the RIGHT half to the pool
 *   long l = left.compute();      // do the LEFT half on THIS thread, right now
 *   long r = right.join();        // now collect the right half
 * </pre>
 * Two common alternatives, and what is actually wrong with each:
 * <ul>
 *   <li>{@code left.fork(); left.join(); right.fork(); right.join();} —
 *       <b>no parallelism whatsoever.</b> {@code join()} immediately after
 *       {@code fork()} means the task is submitted and then waited for before
 *       anything else is submitted, so the two halves run strictly one after the
 *       other. You have paid the framework's full overhead to run sequentially.
 *       This is the mistake the ordering rule exists to prevent.</li>
 *   <li>{@code left.fork(); right.fork(); left.join() + right.join();} — this
 *       <em>is</em> parallel and is not a disaster, but it is wasteful: the
 *       current thread submits both halves and then does nothing but wait, so
 *       every node in the tree occupies a thread that contributes no work.
 *       Forking one half and computing the other keeps the current thread
 *       productive and halves the number of tasks the pool must hand around.</li>
 * </ul>
 *
 * <h2>Threshold: the other half of the tuning</h2>
 * Splitting is not free — each split allocates a task object and touches a
 * deque. Split all the way down to single elements and the bookkeeping costs
 * more than the arithmetic. The leaf threshold is where you stop; the sweep
 * below shows how brutally it matters.
 */
public final class D25_ForkJoinAndWorkStealing {

    private static final int RANGE = 1 << 25;               // ~33.5M leaf units

    /** Consumes every result so the JIT cannot delete arithmetic nobody reads. */
    private static final java.util.concurrent.atomic.AtomicLong SINK =
            new java.util.concurrent.atomic.AtomicLong();

    /** How a task splits itself — the entire point of the demo. */
    private enum Mode {
        /** fork() then join() immediately: sequential, with overhead. */
        FORK_JOIN_EACH,
        /** fork both, then join both: parallel, but this thread idles. */
        FORK_BOTH,
        /** fork one, compute the other, then join: the rule. */
        FORK_ONE_COMPUTE_ONE
    }

    public static void main(String[] args) throws Exception {
        Log.log("availableProcessors() = %d", Runtime.getRuntime().availableProcessors());
        Log.log("ForkJoinPool.commonPool() parallelism = %d   (= cores - 1: the submitting",
                ForkJoinPool.commonPool().getParallelism());
        Log.log("thread is expected to help, so the pool only creates cores-1 workers)");

        orderingRule();
        thresholdSweep();
        workStealing();

        Log.takeaway("""
                ForkJoinPool is not "a faster thread pool". It is a pool for one shape
                of problem: CPU-bound work that recursively splits into independent
                sub-problems and then combines their results. Its per-worker deques
                and steal-from-the-tail policy exist to make that shape cheap.

                The ordering rule is not style. fork() followed immediately by join()
                on the same task runs the two halves one after the other, so you get
                the framework's overhead with none of its benefit -- measured above as
                the slowest of the three, despite being the one that "looks parallel".

                And nothing here helps if the work blocks. A ForkJoinPool worker that
                parks on IO is a worker that is not stealing, and the common pool is
                JVM-wide -- that is D26.""");
    }

    // ── 1. The three orderings, measured ───────────────────────────────────
    private static void orderingRule() {
        Log.section("1. THE ORDERING RULE — same computation, three ways to split it");
        Log.log("%-34s %10s %14s", "how the task splits", "wall", "result");

        long sequentialBaseline = time(() -> SINK.addAndGet(leafWork(0, RANGE)));
        Log.log("%-34s %8dms %14s", "(plain loop, no fork/join)", sequentialBaseline, "baseline");

        for (Mode mode : Mode.values()) {
            long millis = time(() -> SINK.addAndGet(new RangeTask(0, RANGE, 4_096, mode).invoke()));
            Log.log("%-34s %8dms %11.1fx vs loop", label(mode), millis,
                    sequentialBaseline / (double) millis);
        }
        Log.log("FORK_JOIN_EACH is the trap: it compiles, it is correct, it uses the");
        Log.log("pool — and it runs the halves strictly in sequence, so it is SLOWER");
        Log.log("than the plain loop it replaced. Parallel-looking code is not parallel.");
    }

    private static String label(Mode mode) {
        return switch (mode) {
            case FORK_JOIN_EACH -> "fork();join(); fork();join()  WRONG";
            case FORK_BOTH -> "fork();fork(); join();join()  ok";
            case FORK_ONE_COMPUTE_ONE -> "fork(); compute(); join()     RULE";
        };
    }

    // ── 2. Threshold ───────────────────────────────────────────────────────
    private static void thresholdSweep() {
        Log.section("2. LEAF THRESHOLD — where you stop splitting");
        Log.log("Same correct ordering; only the leaf size changes.");
        Log.log("%-14s %10s %14s", "threshold", "wall", "tasks created");

        for (int threshold : new int[]{16, 256, 4_096, 65_536, 1 << 22, RANGE}) {
            // A fresh task per run: a ForkJoinTask may be executed only once.
            long millis = time(() -> SINK.addAndGet(new RangeTask(0, RANGE, threshold,
                    Mode.FORK_ONE_COMPUTE_ONE).invoke()));
            long tasks = Math.max(1, (RANGE / (long) threshold) * 2 - 1);
            Log.log("%-14d %8dms %,14d", threshold, millis, tasks);
        }
        Log.log("Too small and you spend the run allocating task objects and touching");
        Log.log("deques. Too large (the last row is one leaf = the whole range) and");
        Log.log("there is nothing to steal, so only one core ever works.");
    }

    // ── 3. Work stealing, observed ─────────────────────────────────────────
    /**
     * A deliberately unbalanced tree: the leaves near the start of the range do
     * far more work than the ones near the end. A pool that simply split the
     * range evenly across N threads would finish 7 of its 8 shards early and
     * then idle. Work stealing rebalances without anyone planning it.
     */
    private static void workStealing() {
        Log.section("3. WORK STEALING — an unbalanced tree still keeps every core busy");

        ForkJoinPool pool = new ForkJoinPool(8);
        try {
            long millis = time(() -> SINK.addAndGet(pool.invoke(new SkewedTask(0, 1 << 17, 512))));
            Log.log("unbalanced tree over %,d units on 8 workers: %dms", 1 << 17, millis);
            Log.log("steals=%,d  (a steal is a worker that ran dry taking the OLDEST,",
                    pool.getStealCount());
            Log.log("i.e. biggest, task from the TAIL of another worker's deque)");
            Log.log("Steals are rare relative to the ~%,d tasks created: the common case",
                    ((1 << 17) / 512) * 2 - 1);
            Log.log("is a worker popping its own newest task, lock-free and cache-warm.");
        } finally {
            pool.shutdown();
        }
    }

    // ── the tasks ──────────────────────────────────────────────────────────

    private static final class RangeTask extends RecursiveTask<Long> {
        private final int lo;
        private final int hi;
        private final int threshold;
        private final Mode mode;

        RangeTask(int lo, int hi, int threshold, Mode mode) {
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
            this.mode = mode;
        }

        @Override
        protected Long compute() {
            if (hi - lo <= threshold) {
                return leafWork(lo, hi);
            }
            int mid = (lo + hi) >>> 1;
            RangeTask left = new RangeTask(lo, mid, threshold, mode);
            RangeTask right = new RangeTask(mid, hi, threshold, mode);

            return switch (mode) {
                case FORK_JOIN_EACH -> {
                    // Submit, then immediately block waiting for it. The right
                    // half is not even submitted until the left has finished:
                    // this is a sequential program with extra steps.
                    left.fork();
                    long l = left.join();
                    right.fork();
                    long r = right.join();
                    yield l + r;
                }
                case FORK_BOTH -> {
                    // Genuinely parallel, but this thread now contributes
                    // nothing except waiting.
                    left.fork();
                    right.fork();
                    yield left.join() + right.join();
                }
                case FORK_ONE_COMPUTE_ONE -> {
                    // The rule: give away one half, do the other half yourself,
                    // and only then collect.
                    right.fork();
                    long l = left.compute();
                    yield l + right.join();
                }
            };
        }
    }

    /** Leaves near {@code lo == 0} are far more expensive: an unbalanced tree. */
    private static final class SkewedTask extends RecursiveTask<Long> {
        private final int lo;
        private final int hi;
        private final int threshold;

        SkewedTask(int lo, int hi, int threshold) {
            this.lo = lo;
            this.hi = hi;
            this.threshold = threshold;
        }

        @Override
        protected Long compute() {
            if (hi - lo <= threshold) {
                int weight = Math.max(1, 256 - (lo >>> 9));   // heavy at the start
                long acc = 0;
                for (int w = 0; w < weight; w++) {
                    acc += leafWork(lo, hi);
                }
                return acc;
            }
            int mid = (lo + hi) >>> 1;
            SkewedTask right = new SkewedTask(mid, hi, threshold);
            right.fork();
            long l = new SkewedTask(lo, mid, threshold).compute();
            return l + right.join();
        }
    }

    /**
     * Real arithmetic with a serial dependency chain, so the JIT cannot delete
     * it and the cost scales with the range rather than with memory bandwidth.
     */
    private static long leafWork(int lo, int hi) {
        long x = 0x9E3779B97F4A7C15L ^ lo;
        long acc = 0;
        for (int i = lo; i < hi; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
            acc += x;
        }
        return acc;
    }

    private static long time(Runnable body) {
        body.run();                                  // warm-up: let C2 compile it
        long start = System.nanoTime();
        body.run();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private D25_ForkJoinAndWorkStealing() {
    }
}
