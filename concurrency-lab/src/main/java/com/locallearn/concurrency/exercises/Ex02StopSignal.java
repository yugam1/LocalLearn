package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.StopSignal;
import com.locallearn.concurrency.support.Check;

import java.util.concurrent.TimeUnit;

/**
 * <b>EXERCISE 2 — make the stop signal visible.</b> Demo: {@code t02visibility.D4_StaleFlagHang}.
 *
 * <p>The test spins a worker on {@link #shouldStop()} and then calls
 * {@link #stop()} from another thread. As written, the JIT may hoist the
 * field read out of the worker's loop and the worker never exits — the test
 * will time out rather than fail fast, which is itself the lesson.
 *
 * <p>Hint: one keyword. Think about which of volatile's three guarantees
 * you are relying on here, and whether you need the other two.
 *
 * <p>Note the shape of the failure. Nothing throws, no value is wrong, and the
 * write definitely happened — there is simply a thread that never stops. That
 * is what a visibility bug looks like in production too, which is why it gets
 * misfiled as "stuck on the database" instead of being read as a memory-model
 * problem.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex02StopSignal   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex2'                                        # the grade
 * </pre>
 */
public final class Ex02StopSignal implements StopSignal {

    private volatile boolean stopped;        // TODO broken: no visibility guarantee

    @Override
    public void stop() {
        stopped = true;
    }

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
