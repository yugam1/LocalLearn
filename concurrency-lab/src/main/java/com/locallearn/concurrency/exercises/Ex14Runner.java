package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.WorkloadRunner;
import com.locallearn.concurrency.support.Check;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/*
 * EXERCISE 14 — one runner, two kinds of work
 *
 * THE SCENARIO
 *   A batch runner used by two very different callers. runCpuBound is handed
 *   tasks that compute — they need a core each and there is no point running
 *   more of them than the machine has cores. runIoBound is handed tasks that
 *   mostly wait on a network call or a database — hundreds should be in flight
 *   at once, because a waiting task needs no core at all. Both return their
 *   results in submission order.
 *
 * WHAT IS WRONG RIGHT NOW
 *   Both methods funnel into one private runAll, and that single path makes two
 *   decisions that suit neither caller:
 *   1. One ExecutorService, a fixed pool of CORES platform threads, serves
 *      both. That sizing is right for computing and catastrophic for waiting:
 *      200 tasks that each block for 50 ms can only be in flight CORES at a
 *      time, so the run takes 200 x 50 / CORES ms instead of about 50 ms.
 *   2. synchronized (lock) wraps task.call() itself, so only one task in the
 *      whole runner executes at a time. The CPU path gets no parallelism at
 *      all, and if you move the IO path to virtual threads it gets a second
 *      penalty: a virtual thread that blocks inside synchronized is PINNED to
 *      its carrier and cannot unmount, so the carriers run out anyway.
 *
 * YOUR TASK
 *   1. runCpuBound(List) — run on threads sized to the core count, all tasks
 *      genuinely in parallel.
 *   2. runIoBound(List) — run on an execution strategy where hundreds of
 *      blocked tasks cost nothing, so they are all in flight at once.
 *   3. runAll(List) — collect the results without holding anything across
 *      task.call(). If both methods keep sharing this helper, it has to take
 *      its executor from the caller rather than owning one.
 *   4. close() — must still shut down whatever executors you end up owning.
 *
 * RULES
 *   1. Both methods must return every result in submission order — a fast
 *      runner that shuffles results has not solved anything.
 *   2. Nothing may be held across task.call(). Not a monitor, not a lock.
 *   3. Do not change the WorkloadRunner signatures.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The three checks are:
 *   1. both methods return all 64 results, correct and in submission order;
 *   2. the CPU batch finishes at least 3x faster than the same work run
 *      sequentially on this machine, measured right now so a slow box cannot
 *      skew it;
 *   3. more than a quarter of the 200 blocking tasks are in flight
 *      simultaneously, and the whole IO batch fits inside a 2-second budget.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex14Runner
 *
 * HINT
 *   The lock exists only to protect a list. There are ways to collect results
 *   in order — index into a pre-sized array, or just read the futures back in
 *   submission order — that need no lock at all.
 *   The IO check measures how many tasks were in flight at once, not wall time:
 *   a fast machine can meet a deadline while the design is still wrong, and the
 *   in-flight count names the property you are actually being asked for.
 *
 * SEE ALSO
 *   Docs — read this first:
 *     docs/02-concurrency/09-forkjoin-parallel-virtual-threads.md, section
 *     "EXERCISE 14".
 *   Demo t09parallel.D27_VirtualThreadsAndPinning measured both effects: a
 *   cores-sized pool managed ~120 blocking tasks per second where virtual
 *   threads managed ~68,000, and pinning alone cost 108x with zero contention.
 *   Reference solution: solutions/Solutions.java.
 */
public final class Ex14Runner implements WorkloadRunner {

    private static final int CORES = Runtime.getRuntime().availableProcessors();

    // WRONG: one executor for two kinds of work. CORES platform threads is the right
    // shape for tasks that compute and the wrong shape for tasks that wait — a waiting
    // task needs no core, but here it occupies one of only CORES slots.
    // TODO give each workload its own execution strategy: a cores-sized pool for the
    // CPU path, and for the IO path something where a blocked task holds no OS thread
    // (one virtual thread per task). Whatever you create here, close() must shut down.
    private final ExecutorService executor = Executors.newFixedThreadPool(CORES);

    private final Object lock = new Object();

    /*
     * Compute-bound tasks: each one needs a core, so the useful number in
     * flight is about the core count. Must run them genuinely in parallel —
     * the check compares against the same work measured sequentially on this
     * machine.
     */
    @Override
    public List<Long> runCpuBound(List<Callable<Long>> tasks) throws Exception {
        // TODO run these on a pool sized to the core count. (This call is correct once
        // runAll no longer serialises tasks and no longer picks the executor itself.)
        return runAll(tasks);
    }

    /*
     * Blocking tasks: each one spends its life waiting, so hundreds should be
     * in flight at once. If this path is capped at the core count, 200 tasks x
     * 50 ms of pure waiting takes seconds instead of about 50 ms.
     */
    @Override
    public List<Long> runIoBound(List<Callable<Long>> tasks) throws Exception {
        // WRONG: same cores-sized platform pool as the CPU path, so only CORES of these
        // tasks can be waiting at any moment.
        // TODO route this workload to one virtual thread per task, so a blocked task
        // unmounts its carrier and costs nothing while it waits (D27).
        return runAll(tasks);
    }

    /*
     * The shared submit-and-collect loop. It must preserve submission order in
     * the returned list and must hold nothing while a task runs — anything
     * held across task.call() makes the whole runner single-threaded.
     */
    private List<Long> runAll(List<Callable<Long>> tasks) throws Exception {
        List<Future<Long>> futures = new ArrayList<>();
        for (Callable<Long> task : tasks) {
            futures.add(executor.submit(() -> {
                // WRONG: the monitor is held across task.call(), including whatever
                // blocking the task does, so exactly one task runs at a time no matter
                // how many threads exist. On a virtual thread it is worse: blocking
                // inside synchronized PINS the thread to its carrier so it cannot
                // unmount, which D27 measured as a 108x collapse with no contention.
                // TODO remove the mutual exclusion from around the call entirely. The
                // lock only ever guarded a list, and the loop below already restores
                // submission order without it.
                synchronized (lock) {
                    return task.call();
                }
            }));
        }
        List<Long> results = new ArrayList<>(futures.size());
        for (Future<Long> future : futures) {
            results.add(future.get());          // preserves submission order
        }
        return results;
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int MACHINE_CORES = Runtime.getRuntime().availableProcessors();

    /*
     * Correctness sample: small, so a broken runner still finishes it.
     */
    private static final int ORDER_TASKS = 64;

    /*
     * CPU sample, sized like the JUnit contract's: enough work to time
     * honestly.
     */
    private static final int CPU_TASKS = Math.max(16, MACHINE_CORES * 4);
    private static final int CPU_ITERATIONS = 2_000_000;

    /*
     * IO sample. Smaller than the contract's 400 x 100 ms, because a
     * serialised runner would take tasks x millis to finish it and this main
     * is meant to answer in seconds. The property asserted is the same one.
     */
    private static final int IO_TASKS = 200;
    private static final long IO_BLOCK_MILLIS = 50;

    /*
     * How long the checker waits for one IO run before calling it a failure.
     * Serialised, those tasks need 200 x 50 ms = 10s; one virtual thread per
     * task needs about 50 ms. Two seconds cannot be reached by accident from
     * either side.
     */
    private static final long IO_BUDGET_MILLIS = 2_000;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 14 — what kind of work is this?", "ExerciseTests$Ex14")
                .reading("docs/02-concurrency/09-forkjoin-parallel-virtual-threads.md § \"EXERCISE 14\"");

        check.that("both workloads return every result, in submission order", () -> {
            try (WorkloadRunner runner = new Ex14Runner()) {
                List<Callable<Long>> cpu = new ArrayList<>();
                List<Callable<Long>> io = new ArrayList<>();
                for (int i = 0; i < ORDER_TASKS; i++) {
                    final long n = i;
                    cpu.add(() -> n * n);
                    io.add(() -> {
                        Thread.sleep(Duration.ofMillis(5));
                        return n * n;
                    });
                }

                // Correctness before performance: a runner that is fast and
                // returns the results shuffled has not solved anything.
                assertSquares(within(5_000, () -> runner.runCpuBound(cpu),
                        () -> "runCpuBound did not return within 5s"), "runCpuBound");
                assertSquares(within(5_000, () -> runner.runIoBound(io),
                        () -> "runIoBound did not return within 5s"), "runIoBound");
            }
        });

        check.that("CPU-bound work runs in parallel across the %d cores".formatted(MACHINE_CORES), () -> {
            // Measure the same work sequentially on this machine, right now, so
            // the comparison cannot be invalidated by a slow or busy box.
            long sequentialStart = System.nanoTime();
            for (int i = 0; i < CPU_TASKS; i++) {
                SINK.addAndGet(spin(i, CPU_ITERATIONS));
            }
            long sequentialMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - sequentialStart);

            try (WorkloadRunner runner = new Ex14Runner()) {
                List<Callable<Long>> cpu = new ArrayList<>();
                for (int i = 0; i < CPU_TASKS; i++) {
                    final int seed = i;
                    cpu.add(() -> spin(seed, CPU_ITERATIONS));
                }

                long start = System.nanoTime();
                within(60_000, () -> runner.runCpuBound(cpu),
                        () -> "runCpuBound did not return within 60s");
                long parallelMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                // A deliberately loose bar: 3x on a machine with >= 4 cores.
                // The point is to catch "no parallelism at all", not to grade
                // the scheduler.
                Check.require(parallelMillis < Math.max(sequentialMillis / 3, 50),
                        "%d CPU-bound tasks took %,d ms in parallel versus %,d ms sequentially "
                        + "on this %d-core machine — a speed-up of %.1fx. That is not parallel "
                        + "execution. The usual cause is a lock held across the whole task, "
                        + "which serialises every one of them no matter how many threads are "
                        + "available.",
                        CPU_TASKS, parallelMillis, sequentialMillis, MACHINE_CORES,
                        sequentialMillis / (double) Math.max(parallelMillis, 1));
            }
        });

        check.that("IO-bound work runs hundreds of tasks concurrently, not cores-at-a-time", () -> {
            AtomicInteger inFlight = new AtomicInteger();
            AtomicInteger peakInFlight = new AtomicInteger();

            try (WorkloadRunner runner = new Ex14Runner()) {
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
                // Bounded, because the failure here is slowness, and a checker
                // that waits for a serialised run to finish becomes the hang
                // instead of reporting it.
                List<Long> results = within(IO_BUDGET_MILLIS, () -> runner.runIoBound(io),
                        () -> stuckIoDiagnosis(inFlight.get(), peakInFlight.get()));
                long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

                Check.equal(results.size(), IO_TASKS, "runIoBound lost tasks");

                Check.require(peakInFlight.get() > IO_TASKS / 4,
                        "at most %d of the %d blocking tasks were ever in flight at once (on a "
                        + "%d-core machine). These tasks do nothing but BLOCK — they need no "
                        + "core, so nothing about this machine justifies running them a few at "
                        + "a time. Two things cause this: running IO work on a platform-thread "
                        + "pool sized for CPU work, and holding a lock across the blocking "
                        + "call. The second also PINS a virtual thread to its carrier, which "
                        + "D27 measured as a 108x collapse with zero contention.",
                        peakInFlight.get(), IO_TASKS, MACHINE_CORES);

                // The concurrency assertion above is the real one; this is its
                // consequence, stated in the units an SLA is written in.
                long serialisedFloor = IO_TASKS * IO_BLOCK_MILLIS / Math.max(MACHINE_CORES, 1);
                Check.require(millis < Math.max(serialisedFloor / 2, IO_BLOCK_MILLIS * 4),
                        "%d tasks x %d ms of pure blocking took %,d ms. A thread-per-core pool "
                        + "would need about %,d ms; one virtual thread per task needs about "
                        + "%d ms, because a blocked virtual thread unmounts and holds no OS "
                        + "thread at all (D27, section 2).",
                        IO_TASKS, IO_BLOCK_MILLIS, millis, serialisedFloor, IO_BLOCK_MILLIS);
            }
        });

        System.exit(check.finish());
    }

    // ── checker helpers ────────────────────────────────────────────────────

    /*
     * Runs one workload on a daemon thread and gives up after budgetMillis,
     * reporting diagnosis as the failure. Both defects here degrade into
     * "takes a very long time" rather than "throws", and a checker that simply
     * waits would become the hang it is supposed to report. The thread is a
     * daemon so that giving up is enough: the JVM can exit with tasks still
     * sleeping on it.
     */
    private static List<Long> within(long budgetMillis, Callable<List<Long>> call,
                                     Supplier<String> diagnosis) throws Exception {
        AtomicReference<List<Long>> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread worker = new Thread(() -> {
            try {
                result.set(call.call());
            } catch (Throwable t) {
                failure.set(t);
            } finally {
                done.countDown();
            }
        }, "ex14-workload");
        worker.setDaemon(true);
        worker.start();

        if (!done.await(budgetMillis, TimeUnit.MILLISECONDS)) {
            throw new AssertionError(diagnosis.get());
        }
        Throwable thrown = failure.get();
        if (thrown instanceof Exception e) {
            throw e;
        }
        if (thrown != null) {
            throw new AssertionError("the workload threw " + thrown, thrown);
        }
        return result.get();
    }

    private static String stuckIoDiagnosis(int inFlight, int peak) {
        return ("%,d blocking tasks x %d ms had not finished after %,d ms. In flight when the "
                + "checker gave up: %d. Peak across the whole run: %d. Strictly "
                + "one at a time that run takes %,d ms; %d at a time, %,d ms; one virtual thread "
                + "per task, about %d ms, because a blocked virtual thread unmounts and holds no "
                + "OS thread at all. These tasks only block — they need no core, so nothing "
                + "about this machine justifies running them a few at a time. Ask what kind of "
                + "work runIoBound is being handed, and whether anything is held across the "
                + "blocking call.")
                .formatted(IO_TASKS, IO_BLOCK_MILLIS, IO_BUDGET_MILLIS, inFlight, peak,
                        IO_TASKS * IO_BLOCK_MILLIS, MACHINE_CORES,
                        IO_TASKS * IO_BLOCK_MILLIS / Math.max(MACHINE_CORES, 1), IO_BLOCK_MILLIS);
    }

    private static void assertSquares(List<Long> results, String method) {
        Check.equal(results.size(), ORDER_TASKS, "%s returned the wrong number of results", method);
        for (int i = 0; i < ORDER_TASKS; i++) {
            Check.equal(results.get(i), (long) i * i,
                    "%s returned result %d out of submission order (or computed it wrongly)",
                    method, i);
        }
    }

    private static final AtomicLong SINK = new AtomicLong();

    /*
     * Genuine CPU work: a serial dependency chain the JIT cannot elide.
     */
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
