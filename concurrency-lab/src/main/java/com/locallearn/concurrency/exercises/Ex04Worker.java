package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.InterruptibleWorker;
import com.locallearn.concurrency.support.Check;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * <b>EXERCISE 4 — make the worker cancellable.</b>
 * Demo: {@code t01threads.D3_InterruptionAndCancellation}.
 *
 * <p>Two bugs in the code below, and they compound:
 * <ol>
 *   <li>The loop never checks the interrupt flag, so it cannot stop.</li>
 *   <li>The catch block swallows {@link InterruptedException} — it neither
 *       rethrows nor restores the flag — so the cancellation request is
 *       destroyed and nobody upstream can recover it.</li>
 * </ol>
 *
 * <p>The test asserts three things: the worker stops within 2 seconds of
 * being interrupted, {@link #cleanedUp()} is true afterwards, and the
 * thread's interrupt flag is still set when {@code run()} returns.
 *
 * <p>Getting two of the three is the usual half-failure, and each half is its
 * own production bug: a worker that stops but skips its cleanup leaks whatever
 * it was holding, and a worker that stops but eats the flag leaves the code
 * above it with no way to learn it was cancelled.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex04Worker   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex4'                                    # the grade
 * </pre>
 */
public final class Ex04Worker implements InterruptibleWorker {

    private volatile long units;
    private volatile boolean cleanedUp;

    @Override
    public void run() {
        while (true) {              // TODO broken: never checks for cancellation
            try {
                Thread.sleep(10);
                units++;
            } catch (InterruptedException e) {
                // TODO broken: swallowed. The flag was cleared by the throw
                // and is not restored, so the request is simply gone.
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

    /** Call this on the way out — from a finally block, so every exit path runs it. */
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
