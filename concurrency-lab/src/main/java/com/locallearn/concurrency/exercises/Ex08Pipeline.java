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

/*
 * EXERCISE 8 — a worker pipeline with backpressure and a clean shutdown
 *
 * THE SCENARIO
 *   A background processing pipeline: callers hand items to submit(), a fixed
 *   pool of worker threads takes them off a shared backlog and runs processor
 *   on each. At the end of the job, or on deploy, something calls
 *   shutdownAndDrain() and expects every item that was already accepted to have
 *   been processed.
 *
 * WHAT IS WRONG RIGHT NOW
 *   Unlike the earlier exercises this one mostly works — items do flow through.
 *   It is broken the way production systems are broken: three defects, each
 *   visible only under its own condition.
 *   1. No backpressure. The backlog is an unbounded LinkedBlockingQueue, so the
 *      constructor's capacity argument is ignored and submit() never blocks. A
 *      producer faster than the workers grows it until memory runs out.
 *   2. Busy-wait. Workers call poll(), which returns null immediately when the
 *      backlog is empty, so an idle worker spins the CPU at full speed doing
 *      nothing.
 *   3. Lossy shutdown. shutdownAndDrain() flips a volatile flag. Workers notice
 *      it at the top of their loop and exit wherever they are, so whatever is
 *      still sitting in the backlog is thrown away — items that were accepted
 *      and are never processed.
 *
 * YOUR TASK
 *   1. backlog field — make it bounded at the constructor's capacity.
 *   2. the worker loop — block in take() instead of spinning on poll(), and
 *      exit only on a sentinel that arrives through the queue, not on a flag.
 *   3. submit(String) — must block while the backlog is at capacity.
 *   4. shutdownAndDrain() — must send one poison pill per worker, then join
 *      every worker, and not return until the backlog is empty.
 *
 * RULES
 *   1. No busy-waiting: the checker measures the idle workers' CPU time.
 *   2. backlogPeak() must never exceed the capacity the constructor was given.
 *   3. Every item submit() accepted must be processed exactly once — not
 *      dropped, not processed twice.
 *   4. Fix them in this order: bound the queue, then park, then drain. All
 *      three in one edit leaves you with a hang and no idea which third caused
 *      it.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 1,500 items against one slow worker keep backlogPeak() at or below 16.
 *   2. 4 idle workers burn almost no CPU over half a second of having nothing
 *      to do.
 *   3. 4,000 items submitted, then shutdownAndDrain() — all 4,000 processed and
 *      liveWorkers() is 0 when it returns.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex08Pipeline
 *
 * HINT
 *   You own both halves already. The bounded blocking queue is Ex7 — here you
 *   may use ArrayBlockingQueue, you have earned it. The shutdown is D15's
 *   poison pill: one sentinel per worker, pushed through the same queue, so
 *   FIFO guarantees it arrives behind every real item. Compare the pill with
 *   ==, not equals() — an ordinary item whose text happens to match would
 *   otherwise kill a worker early.
 *
 * SEE ALSO
 *   Docs — read this first: docs/02-concurrency/05-handoff-blocking-queues.md,
 *     section "EXERCISE 8".
 *   Demos t05handoff.D13_UnboundedBacklog and t05handoff.D15_ShutdownPoisonPill
 *   show these failures live. Reference solution: solutions/Solutions.java.
 */
public final class Ex08Pipeline implements Pipeline {

    // WRONG: a LinkedBlockingQueue built with no argument holds Integer.MAX_VALUE items, so
    // the constructor's `capacity` argument is never used and the backlog has no ceiling.
    // TODO give this queue a bound of `capacity` — which means assigning it in the
    //      constructor, where capacity is in scope, not in the field initialiser here.
    private final LinkedBlockingQueue<String> backlog =
            new LinkedBlockingQueue<>();
    private final List<Thread> workers = new ArrayList<>();
    private final Consumer<String> processor;
    private final AtomicLong processed = new AtomicLong();
    private final AtomicInteger backlogPeak = new AtomicInteger();
    // TODO a flag cannot express "finish what is queued, then stop" — the sentinel that
    //      replaces it travels through the backlog instead, so this field goes away.
    private volatile boolean stopped;

    /*
     * Must guarantee: each worker keeps processing until it is told to stop,
     * and "told to stop" must mean "after the backlog is empty", not "right
     * now, wherever you are". It must also spend its idle time parked, not
     * spinning.
     */
    public Ex08Pipeline(int capacity, int workerCount, Consumer<String> processor) {
        this.processor = processor;
        for (int w = 0; w < workerCount; w++) {
            Thread worker = new Thread(() -> {
                // WRONG: the loop condition is a flag, so the moment shutdown sets it every
                // worker leaves — items still in the backlog are never processed. And poll()
                // returns immediately when the backlog is empty, so this loop spins a core.
                // TODO loop forever and leave only when take() hands back the poison pill
                //      (compare with ==), instead of testing a flag.
                while (!stopped) {
                    // TODO block in backlog.take() so an idle worker parks, not spins.
                    String item = backlog.poll();
                    if (item != null) {
                        processor.accept(item);
                        processed.incrementAndGet();
                    }
                }
                // TODO nothing drains here: whatever is still queued when the flag flips is
                //      abandoned. Once the pill ends the loop, everything ahead of it in the
                //      FIFO has already been processed, so there is nothing left to drain.
            }, "pipeline-worker-" + w);
            worker.setDaemon(true);
            workers.add(worker);
            worker.start();
        }
    }

    /*
     * Must guarantee: a caller submitting faster than the workers drain is
     * made to wait. Without that the backlog is not a buffer, it is a deferred
     * OutOfMemoryError — and the queue hides the failure right up to the
     * moment the heap runs out.
     */
    @Override
    public void submit(String item) throws InterruptedException {
        // WRONG: put() is the blocking call, but on an unbounded queue there is never a
        // full state to block on, so this returns instantly no matter how far behind the
        // workers are. The line is fine; the queue it is called on is not.
        // TODO once backlog is bounded at capacity, this put() blocks by itself — the fix
        //      for this method is the field declaration above.
        backlog.put(item);
        backlogPeak.accumulateAndGet(backlog.size(), Math::max);
    }

    /*
     * Must guarantee: when this returns, every item already accepted by
     * submit() has been processed and every worker thread is dead. "Asked them
     * to stop" is not the same as "they finished" — the caller has no other
     * signal that the work is done.
     */
    @Override
    public void shutdownAndDrain() throws InterruptedException {
        // WRONG: this says "stop now", not "drain then stop". A worker mid-loop sees the
        // flag on its next iteration and exits with items still sitting in the backlog.
        // TODO replace this with one poison pill per worker, put() through the same backlog
        //      so FIFO places it behind every item already submitted.
        stopped = true;
        for (Thread worker : workers) {
            worker.join();          // correct as-is: it is what makes "drained" observable
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
        Check check = Check.named("Exercise 8 — backpressure & draining shutdown", "ExerciseTests$Ex8")
                .reading("docs/02-concurrency/05-handoff-blocking-queues.md § \"EXERCISE 8\"");

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

    /*
     * D13. A fast producer against one deliberately slow worker, so the
     * backlog is under constant pressure for the whole run and an unbounded
     * queue cannot stay small by luck. Correctness assertions cannot see this
     * defect — only the peak can.
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

    /*
     * D14's verb grid. A poll() loop is functionally correct and burns a core
     * per idle worker; nothing but CPU time distinguishes it from take(),
     * which is why this check measures instead of asserting on behaviour.
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

    /*
     * D15. Stop-flag shutdown abandons whatever is still queued, and counting
     * is the only witness — every other observable stays perfectly plausible.
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

    /*
     * Runs body on a daemon thread and gives up on it after seconds. Every
     * other exercise fails by producing a wrong number. This one can fail by
     * never finishing: a put that blocks forever because no worker is
     * draining, or a join on a worker parked on an empty queue. Without a
     * watchdog the checker would not report the hang, it would BECOME the hang
     * — and a fast loop you have to Ctrl-C is not a fast loop.
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

    /*
     * Best-effort cleanup. A failed check must not leave spinning daemon
     * workers behind to skew the CPU measurement of the check after it.
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

    /*
     * Spin-sleeps: the waits here are far too short for Thread.sleep to be
     * accurate.
     */
    private static void sleepMicros(long micros) {
        long deadline = System.nanoTime() + micros * 1_000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }
}
