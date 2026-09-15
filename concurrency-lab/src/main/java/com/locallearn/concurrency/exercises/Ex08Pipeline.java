package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.Pipeline;
import com.locallearn.concurrency.support.Check;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * <b>EXERCISE 8 — fix the pipeline.</b> See {@code t05handoff.D13_UnboundedBacklog}
 * and {@code t05handoff.D15_ShutdownPoisonPill}.
 *
 * <p>This is the first exercise where the broken version <em>mostly
 * works</em> — items flow through and get processed. It is broken the way
 * production systems are broken: three latent defects that each show up
 * under a different condition, and every one of them was a demo:
 * <ol>
 *   <li><b>No backpressure</b> (D13). The backlog is unbounded, so a fast
 *       producer grows it without limit — the test's slow consumer makes
 *       {@code backlogPeak()} blow straight past the capacity you were given.</li>
 *   <li><b>Busy-wait</b> (D14's verb grid, Ex7's lesson). Workers spin on
 *       {@code poll()}, burning a core each while the queue is empty.</li>
 *   <li><b>Lossy shutdown</b> (D15). A volatile flag stops the workers
 *       wherever they happen to be, abandoning whatever is still queued —
 *       the test counts every submitted item and will find the missing ones.</li>
 * </ol>
 *
 * <p>Hint: you already own both halves of the fix. A bounded blocking queue
 * is Ex7 (here you may just use {@code ArrayBlockingQueue} — you've earned
 * it); the shutdown is D15's poison pill, one per worker, sent through the
 * same queue so FIFO guarantees it arrives after every real item. Compare
 * the pill with {@code ==}, and think about why {@code equals()} would be
 * a bug.
 *
 * <p>Fix them in order. Backpressure first, because a bounded queue is what
 * makes {@code take()} worth using; then the parking; then the drain. Trying
 * to do all three in one edit is how you end up with a pipeline that hangs
 * and no idea which third of it is at fault.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex08Pipeline   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex8'                                      # the grade
 * </pre>
 */
public final class Ex08Pipeline implements Pipeline {

    private final LinkedBlockingQueue<String> backlog =
            new LinkedBlockingQueue<>();   // TODO broken: unbounded — capacity is ignored
    private final List<Thread> workers = new ArrayList<>();
    private final Consumer<String> processor;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicInteger backlogPeak = new AtomicInteger();
    private volatile boolean stopped;

    public Ex08Pipeline(int capacity, int workerCount, Consumer<String> processor) {
        this.processor = processor;
        for (int w = 0; w < workerCount; w++) {
            Thread worker = new Thread(() -> {
                while (!stopped) {                  // TODO broken: D15 attempt 1½ — flag-based,
                    String item = backlog.poll();   // TODO broken: and poll() busy-waits (D14)
                    if (item != null) {
                        processor.accept(item);
                        processed.incrementAndGet();
                    }
                }
                // TODO broken: when stopped flips, whatever is still queued is abandoned
            }, "pipeline-worker-" + w);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    @Override
    public void submit(String item) throws InterruptedException {
        backlog.put(item);                          // TODO broken: never blocks — no backpressure (D13)
        backlogPeak.accumulateAndGet(backlog.size(), Math::max);
    }

    @Override
    public void shutdownAndDrain() throws InterruptedException {
        stopped = true;                             // TODO broken: "stop now", not "drain then stop"
        for (Thread worker : workers) {
            worker.join();
        }
    }

    @Override
    public long processed() {
        return processed.get();
    }

    @Override
    public int backlogPeak() {
        return backlogPeak.get();
    }

    @Override
    public int liveWorkers() {
        int alive = 0;
        for (Thread worker : workers) {
            if (worker.isAlive()) {
                alive++;
            }
        }
        return alive;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int CAPACITY = 16;
    private static final int PRESSURE_ITEMS = 1_500;
    private static final int PRESSURE_TRIALS = 2;

    private static final int IDLE_WORKERS = 4;
    private static final long IDLE_MILLIS = 500;

    private static final int DRAIN_ITEMS = 4_000;
    private static final int DRAIN_WORKERS = 3;
    private static final int DRAIN_TRIALS = 3;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 8 — backpressure & draining shutdown", "ExerciseTests$Ex8");

        check.that("submit() blocks at capacity %d — %,d items, %d trials"
                .formatted(CAPACITY, PRESSURE_ITEMS, PRESSURE_TRIALS), () ->
                withinSeconds(30, Ex08Pipeline::assertAppliesBackpressure));

        check.that("idle workers park instead of spinning on poll()", () ->
                withinSeconds(30, Ex08Pipeline::assertIdleWorkersPark));

        check.that("shutdownAndDrain() processes every accepted item — %,d items, %d trials"
                .formatted(DRAIN_ITEMS, DRAIN_TRIALS), () ->
                withinSeconds(30, Ex08Pipeline::assertShutdownDrains));

        System.exit(check.finish());
    }

    /**
     * D13. A fast producer against one deliberately slow worker, so the backlog
     * is under constant pressure for the whole run and an unbounded queue cannot
     * stay small by luck. Correctness assertions cannot see this defect — only
     * the peak can.
     */
    private static void assertAppliesBackpressure() throws InterruptedException {
        for (int trial = 1; trial <= PRESSURE_TRIALS; trial++) {
            Pipeline pipeline = new Ex08Pipeline(CAPACITY, 1, item -> sleepMicros(200));
            try {
                for (int i = 0; i < PRESSURE_ITEMS; i++) {
                    pipeline.submit("item-" + i);
                }
                pipeline.shutdownAndDrain();

                Check.require(pipeline.backlogPeak() <= CAPACITY,
                        "trial %d of %d: the backlog reached %,d items but capacity is %d. "
                        + "submit() must BLOCK while full (put), not accept without limit — "
                        + "an unbounded backlog is not a buffer, it is a deferred "
                        + "OutOfMemoryError (D13)",
                        trial, PRESSURE_TRIALS, pipeline.backlogPeak(), CAPACITY);
            } finally {
                drainQuietly(pipeline);
            }
        }
    }

    /**
     * D14's verb grid. A {@code poll()} loop is functionally correct and burns a
     * core per idle worker; nothing but CPU time distinguishes it from
     * {@code take()}, which is why this check measures instead of asserting on
     * behaviour.
     */
    private static void assertIdleWorkersPark() throws InterruptedException {
        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        Check.require(threadMx.isThreadCpuTimeSupported(),
                "this JVM cannot measure per-thread CPU time, so this check cannot run");
        threadMx.setThreadCpuTimeEnabled(true);

        Pipeline pipeline = new Ex08Pipeline(8, IDLE_WORKERS, item -> { });
        try {
            pipeline.submit("warm-up");                 // let the workers start and go idle
            TimeUnit.MILLISECONDS.sleep(200);

            List<Long> workerIds = pipelineWorkerThreadIds(threadMx);
            Check.require(!workerIds.isEmpty(),
                    "expected %d live worker threads named 'pipeline-worker-*' and found none",
                    IDLE_WORKERS);

            long cpuBefore = totalCpuNanos(threadMx, workerIds);
            TimeUnit.MILLISECONDS.sleep(IDLE_MILLIS);   // half a second with nothing to do
            long cpuAfter = totalCpuNanos(threadMx, workerIds);

            long idleCpuMillis = TimeUnit.NANOSECONDS.toMillis(cpuAfter - cpuBefore);
            // The same 20%-of-a-core-each budget the JUnit contract uses, over a
            // shorter window so the fast loop stays fast.
            long budgetMillis = IDLE_MILLIS / 5 * workerIds.size();
            Check.require(idleCpuMillis < budgetMillis,
                    "the %d idle workers burned %,d ms of CPU across %,d ms of wall clock "
                    + "(budget %,d ms). Parked threads use almost none; this much means a "
                    + "poll() spin loop. Block in take() instead (D14's verb grid)",
                    workerIds.size(), idleCpuMillis, IDLE_MILLIS, budgetMillis);
        } finally {
            drainQuietly(pipeline);
        }
    }

    /**
     * D15. Stop-flag shutdown abandons whatever is still queued, and counting is
     * the only witness — every other observable stays perfectly plausible.
     */
    private static void assertShutdownDrains() throws InterruptedException {
        for (int trial = 1; trial <= DRAIN_TRIALS; trial++) {
            AtomicLong seen = new AtomicLong();
            Pipeline pipeline = new Ex08Pipeline(32, DRAIN_WORKERS, item -> {
                seen.incrementAndGet();
                sleepMicros(50);            // slow enough that a backlog exists at shutdown
            });
            try {
                for (int i = 0; i < DRAIN_ITEMS; i++) {
                    pipeline.submit("item-" + i);
                }
                pipeline.shutdownAndDrain();    // must not return until the backlog is empty

                Check.equal(pipeline.processed(), DRAIN_ITEMS,
                        "trial %d of %d: shutdown abandoned items that had already been "
                        + "accepted. A stop flag stops workers wherever they are; to DRAIN, "
                        + "send one poison pill per worker through the same FIFO so it "
                        + "arrives after every real item (D15)",
                        trial, DRAIN_TRIALS);

                Check.equal(seen.get(), DRAIN_ITEMS,
                        "trial %d of %d: the processor ran the wrong number of times — "
                        + "items were dropped or double-processed",
                        trial, DRAIN_TRIALS);

                Check.equal(pipeline.liveWorkers(), 0,
                        "trial %d of %d: worker threads were still alive after "
                        + "shutdownAndDrain() returned — it must join every worker, not "
                        + "just ask them to stop",
                        trial, DRAIN_TRIALS);
            } finally {
                drainQuietly(pipeline);
            }
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * Runs {@code body} on a daemon thread and gives up on it after
     * {@code seconds}.
     *
     * <p>Every other exercise fails by producing a wrong number. This one can
     * fail by never finishing: a {@code put} that blocks forever because no
     * worker is draining, or a {@code join} on a worker parked on an empty
     * queue. Without a watchdog the checker would not report the hang, it would
     * <em>become</em> the hang — and a fast loop you have to Ctrl-C is not a
     * fast loop.
     */
    private static void withinSeconds(int seconds, Check.Body body) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread runner = new Thread(() -> {
            try {
                body.run();
            } catch (Throwable t) {
                failure.set(t);
            }
        }, "ex8-check");
        runner.setDaemon(true);     // a wedged check must not keep the JVM alive
        runner.start();
        runner.join(seconds * 1_000L);

        if (runner.isAlive()) {
            throw new AssertionError(String.format(
                    "still running after %ds — the pipeline hung rather than failed. "
                    + "Something is blocking with nothing to wake it: a put() into a full "
                    + "queue no worker is draining, or a join() on a worker parked in take() "
                    + "that never got its poison pill. Run `jcmd <pid> Thread.print` to see "
                    + "where.", seconds));
        }

        Throwable thrown = failure.get();
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown != null) {
            throw (Exception) thrown;
        }
    }

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
     * Best-effort cleanup. A failed check must not leave spinning daemon workers
     * behind to skew the CPU measurement of the check after it.
     */
    private static void drainQuietly(Pipeline pipeline) {
        try {
            if (pipeline.liveWorkers() > 0) {
                pipeline.shutdownAndDrain();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // the implementation under test is broken; that is what the checks are for
        }
    }

    /** Spin-sleeps: the waits here are far too short for {@code Thread.sleep} to be accurate. */
    private static void sleepMicros(long micros) {
        long deadline = System.nanoTime() + micros * 1_000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
