package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.StopSignal;
import com.locallearn.concurrency.support.Check;

import java.util.concurrent.TimeUnit;

/*
 * EXERCISE 2 — a stop flag the worker never sees
 *
 * THE SCENARIO
 *   The shutdown switch for a background thread. One thread loops on
 *   shouldStop() doing work; on shutdown, or when the request it was serving is
 *   abandoned, another thread calls stop() and then waits for the worker to
 *   finish. This is the object both threads share to agree that it is time to
 *   stop.
 *
 * WHAT IS WRONG RIGHT NOW
 *   Nothing as the file stands: the field below is already declared volatile
 *   and the checks pass. The defect this exercise is built around is that same
 *   field without the keyword. A plain field creates no happens-before edge
 *   between the write in stop() and the read in shouldStop(), so once the JIT
 *   compiles the worker's loop it is allowed to hoist the read out — load the
 *   value once into a register and test that register forever. The write lands,
 *   the worker never sees it, and join() times out.
 *
 * YOUR TASK
 *   1. stop() — the write must be published so that any thread's next read of
 *      the field sees it.
 *   2. shouldStop() — must re-read the field on every call, so a spinning loop
 *      cannot keep testing a cached copy.
 *
 * RULES
 *   Fix it in the field declaration, not in the loop. Dropping a Thread.sleep
 *   or a println into the spinning loop also makes the worker exit, but only by
 *   accident — those insert a safepoint and the memory-model bug is still
 *   there, waiting for the loop that has neither.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. across 6 trials, a worker spinning on shouldStop() is no longer alive
 *      within 2,000 ms of stop() — each trial spins for 200 ms first so the JIT
 *      has actually compiled the loop;
 *   2. a fresh signal reports false, and reports true after stop() — this
 *      catches a fix that is visible and also wrong, e.g. a flag born stopped.
 *   Watch for the third outcome: if the run neither passes nor fails but simply
 *   sits there, that is the bug reproducing. Kill it and look at the field.
 *
 * HINT
 *   One keyword. Ask which of volatile's three guarantees this needs —
 *   visibility, atomicity, ordering — and whether the other two are doing
 *   anything here. Note the shape of the failure too: nothing throws, no value
 *   is wrong, the write definitely happened. In production that shape gets
 *   misfiled as "stuck on the database" instead of being read as a memory-model
 *   problem.
 *
 * SEE ALSO
 *   Demo t02visibility.D4_StaleFlagHang shows the failure live. Reference
 *   solution: solutions/Solutions.java.
 */
public final class Ex02StopSignal implements StopSignal {

    // volatile is what forces the loop to re-read this field instead of caching it.
    private volatile boolean stopped;

    /*
     * Must publish the flag: after this returns, the next shouldStop() on any
     * thread has to see true. If the write can sit in one core's store buffer,
     * the worker keeps spinning and shutdown hangs on join().
     */
    @Override
    public void stop() {
        stopped = true;
    }

    /*
     * Must be a fresh read of the flag every call. If the JIT may hoist it out
     * of the caller's loop, the worker tests a stale register value forever.
     */
    @Override
    public boolean shouldStop() {
        return stopped;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int TRIALS = 6;
    private static final long WARMUP_MILLIS = 200;
    private static final long PATIENCE_MILLIS = 2_000;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 2 — stop-flag visibility", "ExerciseTests$Ex2");

        check.that("a spinning worker stops within %,d ms — %d trials"
                .formatted(PATIENCE_MILLIS, TRIALS), () -> {
            // Trials, because the hoist is a JIT decision and not a certainty:
            // one worker that happens to exit proves nothing about the next.
            for (int trial = 1; trial <= TRIALS; trial++) {
                StopSignal signal = new Ex02StopSignal();

                Thread worker = new Thread(() -> {
                    while (!signal.shouldStop()) {
                        // Deliberately empty. Any blocking call in here — even
                        // Thread.sleep(1) — would insert a safepoint and
                        // accidentally make the broken version work.
                    }
                }, "stop-signal-worker");
                worker.setDaemon(true);     // so a hung worker cannot wedge this JVM
                worker.start();

                // Let the JIT compile the loop. The hoisting optimisation that
                // causes this bug only happens once C2 kicks in, so flipping the
                // flag immediately would pass against broken code.
                TimeUnit.MILLISECONDS.sleep(WARMUP_MILLIS);

                signal.stop();
                worker.join(PATIENCE_MILLIS);

                Check.require(!worker.isAlive(),
                        "trial %d of %d: the worker was still spinning %,d ms after stop(). "
                        + "The write happened — the worker's loop just never re-read the "
                        + "field, because the JIT hoisted the read out of the loop. Without "
                        + "a happens-before edge it is allowed to.",
                        trial, TRIALS, PATIENCE_MILLIS);
            }
        });

        check.that("shouldStop() is false before stop() and true after", () -> {
            // Here to catch the other failure mode: a "fix" that is visible and
            // also wrong, e.g. a flag that starts out already stopped.
            StopSignal signal = new Ex02StopSignal();
            Check.require(!signal.shouldStop(), "a fresh signal already reports stopped");
            signal.stop();
            Check.require(signal.shouldStop(), "stop() did not set the flag at all");
        });

        System.exit(check.finish());
    }
}
