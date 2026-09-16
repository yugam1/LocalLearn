package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.InterruptibleWorker;
import com.locallearn.concurrency.support.Check;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * EXERCISE 4 — a background worker you can cancel
 *
 * THE SCENARIO
 *   This is a long-running background task inside a service: it loops forever
 *   doing small units of work. On shutdown, or when a request is abandoned, the
 *   owner of the thread calls thread.interrupt() and then waits for the worker
 *   to exit and release what it was holding.
 *
 * WHAT IS WRONG RIGHT NOW
 *   Nothing in run() reacts to that interrupt. The loop condition is true, so
 *   it never consults the interrupt flag. And Thread.sleep CLEARS the flag when
 *   it throws InterruptedException, so after the catch block discards the
 *   exception there is no trace of the request left: the worker keeps counting
 *   units forever, cleanedUp() stays false, and the caller that asked for the
 *   cancellation waits out its timeout.
 *
 * YOUR TASK
 *   1. run() — leave the loop when the thread has been interrupted, instead of
 *      looping unconditionally.
 *   2. run() — run cleanup() on every exit path, which means a finally block,
 *      not a line at the bottom of the loop.
 *   3. run() — leave the interrupt flag set when you return: either rethrow, or
 *      call Thread.currentThread().interrupt() before exiting.
 *
 * RULES
 *   Do not change the signatures — run() cannot throw a checked exception, so
 *   "rethrow" here means restoring the flag rather than declaring throws. A
 *   worker that stops but skips cleanup leaks whatever it held; a worker that
 *   stops but eats the flag leaves the code above it with no way to learn it
 *   was cancelled. Two of three is the usual half-failure, and each half is its
 *   own production bug.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. Across 5 trials it checks:
 *   1. the worker did some work before being interrupted (so there is something
 *      to cancel);
 *   2. the thread is no longer alive within 2 seconds of interrupt();
 *   3. cleanedUp() is true afterwards;
 *   4. the interrupt flag is still set at the moment run() returns.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex04Worker
 *
 * HINT
 *   Interruption is cooperative: it sets a flag, it does not stop anything. Two
 *   places must cooperate — the loop condition, and every blocking call that
 *   throws InterruptedException and clears the flag on the way out.
 *
 * SEE ALSO
 *   Demo t01threads.D3_InterruptionAndCancellation shows the failure live.
 *   Reference solution: solutions/Solutions.java.
 */
public final class Ex04Worker implements InterruptibleWorker {

    private volatile long units;
    private volatile boolean cleanedUp;

    /*
     * Must guarantee three things once someone calls interrupt() on this
     * thread: it stops within a couple of seconds, cleanup() has run, and the
     * interrupt flag is still set when run() returns. Miss any one of them and
     * the shutdown path either hangs, leaks the resources this worker held, or
     * silently loses the cancellation request before anyone upstream can see
     * it.
     */
    @Override
    public void run() {
        // WRONG: the loop condition never looks at the interrupt flag, and there is no
        // finally block, so no exit path reaches cleanup().
        // TODO make the loop exit when the thread is interrupted, and call cleanup()
        // from a finally block so it runs however the loop ends.
        while (true) {
            try {
                Thread.sleep(10);
                units++;
            } catch (InterruptedException e) {
                // WRONG: sleep() cleared the flag when it threw, and this block neither
                // rethrows nor restores it, so the cancellation request dies here.
                // TODO restore the flag (Thread.currentThread().interrupt()) and stop
                // working, instead of discarding the exception and looping again.
            }
        }
    }

    @Override
    public long unitsCompleted() {
        return units;
    }

    @Override
    public boolean cleanedUp() {
        return cleanedUp;
    }

    /*
     * Call this on the way out — from a finally block, so every exit path runs
     * it.
     */
    private void cleanup() {
        cleanedUp = true;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int TRIALS = 5;
    private static final long WORK_MILLIS = 200;
    private static final long PATIENCE_MILLIS = 2_000;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 4 — cooperative cancellation", "ExerciseTests$Ex4");

        check.that("stops on interrupt, cleans up, keeps the flag — %d trials".formatted(TRIALS), () -> {
            // Trials, because cancellation is a race between the interrupt and
            // whatever the worker happens to be doing at that instant, and the
            // three properties can fail independently.
            for (int trial = 1; trial <= TRIALS; trial++) {
                InterruptibleWorker worker = new Ex04Worker();
                AtomicBoolean flagStillSetOnExit = new AtomicBoolean();

                Thread thread = new Thread(() -> {
                    worker.run();
                    // Read the flag the moment run() returns. If the worker
                    // swallowed InterruptedException without restoring it, the
                    // cancellation request is gone and this reads false.
                    flagStillSetOnExit.set(Thread.currentThread().isInterrupted());
                }, "interruptible-worker");
                thread.setDaemon(true);     // so a worker that ignores us cannot wedge this JVM
                thread.start();

                TimeUnit.MILLISECONDS.sleep(WORK_MILLIS);       // let it do some work
                Check.require(worker.unitsCompleted() > 0,
                        "trial %d of %d: the worker completed no work at all in %,d ms, "
                        + "so there is nothing to cancel yet",
                        trial, TRIALS, WORK_MILLIS);

                thread.interrupt();
                thread.join(PATIENCE_MILLIS);

                Check.require(!thread.isAlive(),
                        "trial %d of %d: still running %,d ms after interrupt(). Either the "
                        + "loop never checks isInterrupted(), or the catch block swallowed "
                        + "InterruptedException and carried on.",
                        trial, TRIALS, PATIENCE_MILLIS);

                Check.require(worker.cleanedUp(),
                        "trial %d of %d: the worker stopped but its cleanup never ran. "
                        + "Cleanup belongs in a finally block so it runs on every exit path.",
                        trial, TRIALS);

                Check.require(flagStillSetOnExit.get(),
                        "trial %d of %d: the interrupt flag was not set when run() returned. "
                        + "Throwing InterruptedException CLEARS the flag; if you catch it "
                        + "without rethrowing, you must call "
                        + "Thread.currentThread().interrupt() to restore it, or callers up "
                        + "the stack can never learn they were cancelled.",
                        trial, TRIALS);
            }
        });

        System.exit(check.finish());
    }
}
