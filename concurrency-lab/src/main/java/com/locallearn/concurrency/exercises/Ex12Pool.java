package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.BoundedResourcePool;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>EXERCISE 12 — build a bounded resource pool that actually bounds
 * anything.</b> See {@code t07coordination.D20_SemaphorePermits}.
 *
 * <p>Two defects, one per test, and both are real production bugs:
 * <ol>
 *   <li><b>{@code tryAcquire()} and then carrying on anyway.</b> The
 *       non-blocking form returns false when the pool is full, and this code
 *       ignores that and runs the task regardless — so the "limit" is
 *       decorative. If you want the caller to wait, {@code acquire()} is the
 *       verb; if you want to reject, you must actually reject. Running
 *       unaccounted is the one option that is never right, because the
 *       concurrency it permits is unbounded <em>and</em> invisible.</li>
 *   <li><b>{@code release()} after the work instead of in a
 *       {@code finally}.</b> Every task that throws permanently destroys one
 *       slot. The pool does not fail at the first error — it shrinks, one
 *       exception at a time, until the last slot goes and every caller waits
 *       forever, with no deadlock report to explain it (D21).</li>
 * </ol>
 *
 * <p>The shape you want, and it is worth memorising as a shape:
 * <pre>{@code
 * slots.acquire();
 * try {
 *     return task.call();
 * } finally {
 *     slots.release();
 * }
 * }</pre>
 * Note that the in-flight counter needs the same discipline, for the same
 * reason.
 *
 * <p>Once it passes, go back and ask the design question D20 ends on: at a
 * real service boundary, is {@code acquire()} (wait, invisibly, forever) or
 * {@code tryAcquire(timeout)} (wait a bounded time, then shed load
 * deliberately and countably) the behaviour you want? It is topic 5's
 * block/drop/grow decision at a different layer.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex12Pool   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex12'                                 # the grade
 * </pre>
 */
public final class Ex12Pool
        implements com.locallearn.concurrency.api.Contracts.BoundedResourcePool {

    private final int limit;
    private final java.util.concurrent.Semaphore slots;
    private final java.util.concurrent.atomic.AtomicInteger inFlight =
            new java.util.concurrent.atomic.AtomicInteger();
    private final java.util.concurrent.atomic.AtomicInteger peak =
            new java.util.concurrent.atomic.AtomicInteger();

    public Ex12Pool(int limit) {
        this.limit = limit;
        this.slots = new java.util.concurrent.Semaphore(limit);
    }

    @Override
    public <T> T execute(java.util.concurrent.Callable<T> task) throws Exception {
        // TODO broken: tryAcquire does not wait — and the task then runs
        // TODO broken: whether or not a slot was obtained, so nothing is bounded.
        boolean acquired = slots.tryAcquire();

        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        T result = task.call();
        inFlight.decrementAndGet();

        // TODO broken: not in a finally — a throwing task never gets here,
        // TODO broken: and that slot is gone for the lifetime of the process.
        if (acquired) {
            slots.release();
        }
        return result;
    }

    @Override
    public int peakConcurrency() {
        return peak.get();
    }

    @Override
    public int availableSlots() {
        return slots.availablePermits();
    }

    /** The size the pool was built with — {@link #availableSlots()} must return to it. */
    public int limit() {
        return limit;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int LIMIT = 4;
    private static final int CALLERS = 32;
    private static final int PER_CALLER = 20;
    private static final int TRIALS = 3;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 12 — a limit that limits", "ExerciseTests$Ex12");

        check.that("%d callers against a %d-slot pool never run more than %d at once — %d trials"
                .formatted(CALLERS, LIMIT, LIMIT, TRIALS), () -> {
            long expected = (long) CALLERS * PER_CALLER;
            // Trials, because peak concurrency is a measurement: a pool that
            // enforces nothing can still be observed at 3 if the callers happen
            // not to overlap. They do overlap, three times over, under a gate.
            for (int trial = 1; trial <= TRIALS; trial++) {
                BoundedResourcePool pool = new Ex12Pool(LIMIT);
                AtomicLong completed = new AtomicLong();

                // The timeout variant, so a pool that has lost all its permits
                // is REPORTED rather than silently making this run the hang.
                boolean finished = Stress.run(CALLERS, PER_CALLER, i -> {
                    try {
                        pool.execute(() -> {
                            Stress.sleep(1);
                            completed.incrementAndGet();
                            return null;
                        });
                    } catch (Exception e) {
                        throw new IllegalStateException(
                                "execute() threw for a task that does not", e);
                    }
                }, 20);

                Check.require(finished,
                        "trial %d of %d: the callers had not finished after 20 s. Every "
                        + "one of them is parked waiting for a slot that was acquired and "
                        + "never released — and no thread dump will call it a deadlock, "
                        + "because a permit has no owner (D20, D21)", trial, TRIALS);

                Check.require(pool.peakConcurrency() <= LIMIT,
                        "trial %d of %d: %d tasks ran at once against a pool of %d. "
                        + "tryAcquire() returns false when the pool is full — running the "
                        + "task anyway makes the limit decorative, and the concurrency it "
                        + "permits is unbounded AND invisible. If callers should wait, the "
                        + "verb is acquire(); if they should be turned away, you must "
                        + "actually turn them away (D20)",
                        trial, TRIALS, pool.peakConcurrency(), LIMIT);

                Check.equal(completed.get(), expected,
                        "trial %d of %d: a bounded pool makes callers WAIT, it does not "
                        + "silently drop their work", trial, TRIALS);

                Check.equal(pool.availableSlots(), LIMIT,
                        "trial %d of %d: every task has finished, so every slot should be "
                        + "free again — acquires and releases are not balanced",
                        trial, TRIALS);
            }
        });

        check.that("a task that throws gives its slot back", () -> {
            BoundedResourcePool pool = new Ex12Pool(LIMIT);

            // Exactly as many failures as there are slots: enough to empty the
            // pool if, and only if, the release is on a path an exception can skip.
            for (int attempt = 1; attempt <= LIMIT; attempt++) {
                boolean propagated = false;
                try {
                    pool.execute(() -> {
                        throw new IllegalStateException("downstream call failed");
                    });
                } catch (IllegalStateException expected) {
                    propagated = true;              // the task's failure must reach the caller
                }
                Check.require(propagated,
                        "attempt %d of %d: execute() swallowed the task's exception. A pool "
                        + "borrows you a slot; it does not get to decide that your failure "
                        + "did not happen", attempt, LIMIT);
            }

            Check.equal(pool.availableSlots(), LIMIT,
                    "after %d failed tasks the pool has slots missing — each exception "
                    + "permanently destroyed one. release() was placed after the work, so a "
                    + "throwing task skips it, and a permit that is never released is gone "
                    + "for the life of the process. Put it in a FINALLY. Note the shape of "
                    + "this failure: the pool does not break at the first error, it shrinks "
                    + "one exception at a time until the last slot goes and every caller "
                    + "parks forever — and no thread dump will report a deadlock, because a "
                    + "permit has no owner (D20, D21)", LIMIT);

            // Functional confirmation: a healthy call still gets through promptly.
            // Only reached once the assertion above passes, so this can never be
            // the thing that turns a real defect into an unexplained hang — and it
            // is bounded anyway, because the whole point of the bug is that an
            // exhausted pool waits forever.
            CountDownLatch finished = new CountDownLatch(1);
            Thread caller = new Thread(() -> {
                try {
                    pool.execute(() -> "ok");
                    finished.countDown();
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, "post-failure-caller");
            caller.setDaemon(true);                 // so an exhausted pool cannot wedge this JVM
            caller.start();

            Check.require(finished.await(3, TimeUnit.SECONDS),
                    "a healthy task submitted after %d failures never got a slot within "
                    + "3 seconds — the pool is exhausted and will never recover", LIMIT);
        });

        check.that("execute() returns the task's value and restores the slot", () -> {
            // Here to catch the other failure mode: a pool that bounds correctly
            // and is also wrong, e.g. one that swallows the result or counts a
            // peak of 2 for two tasks that never overlapped.
            BoundedResourcePool pool = new Ex12Pool(2);

            Object text = pool.execute(() -> "hello");
            Check.require("hello".equals(text),
                    "execute() returned %s instead of the task's value", text);

            int answer = pool.execute(() -> 42);
            Check.equal(answer, 42, "execute() did not return the task's value");

            Check.equal(pool.availableSlots(), 2,
                    "two sequential tasks both finished, so both slots must be free");
            Check.equal(pool.peakConcurrency(), 1,
                    "two sequential tasks never overlapped, so the peak must be 1");
        });

        System.exit(check.finish());
    }
}
