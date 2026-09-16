package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.BoundedResourcePool;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/*
 * EXERCISE 12 — a pool whose limit actually limits
 *
 * THE SCENARIO
 *   This is the guard in front of a scarce downstream resource — a connection
 *   pool, a rate-limited third-party API, a GPU. Callers hand execute(task) a
 *   piece of work; the pool must let at most limit of them run at once and make
 *   the rest wait their turn. peakConcurrency() and availableSlots() exist so
 *   the checker can see whether the limit was honoured and whether the slots
 *   came back.
 *
 * WHAT IS WRONG RIGHT NOW
 *   Two defects, one per test:
 *   1. tryAcquire() is the non-blocking form: it returns false rather than
 *      waiting when the pool is full. The code stores that answer in acquired,
 *      ignores it, and calls task.call() either way — so with a limit of 4 and
 *      32 callers, all 32 can be running at once. The concurrency is unbounded
 *      AND unmeasured.
 *   2. slots.release() sits after task.call() with no finally. A task that
 *      throws jumps over it, and that permit is gone for the life of the
 *      process. The pool does not break at the first error — it shrinks, one
 *      exception at a time, until the last slot goes and every caller parks
 *      forever. No thread dump will call it a deadlock, because a permit has no
 *      owning thread.
 *
 * YOUR TASK
 *   1. execute(Callable) — take a slot with the blocking form, slots.acquire(),
 *      so a caller with no slot waits instead of running unaccounted.
 *   2. execute(Callable) — wrap the work in try / finally and release the slot
 *      in the finally, so a throwing task gives it back.
 *   3. execute(Callable) — the inFlight counter needs the same discipline:
 *      decrement it in the finally too, or a failed task leaves the pool
 *      believing work is still running.
 *
 * RULES
 *   The task's exception must reach the caller unchanged — a pool lends you a
 *   slot, it does not get to decide your failure did not happen. Callers must
 *   WAIT, not be silently dropped: the checker counts completions and expects
 *   every submitted task to have run. The shape, worth memorising as a shape:
 *     slots.acquire();
 *     try {
 *         return task.call();
 *     } finally {
 *         slots.release();
 *     }
 *
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 32 callers × 20 tasks against a 4-slot pool, 3 trials: peak concurrency
 *      never exceeds 4, every task completes, and all 4 slots are free at the
 *      end.
 *   2. After 4 tasks that throw, all 4 slots are still there and a healthy task
 *      submitted afterwards gets one within 3 seconds.
 *   3. execute() returns the task's own value, and two sequential tasks report
 *      a peak of 1.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex12Pool
 *
 * HINT
 *   Once it passes, ask the design question D20 ends on: at a real service
 *   boundary, is acquire() (wait, invisibly, forever) or tryAcquire(timeout)
 *   (wait a bounded time, then shed load deliberately and countably) the
 *   behaviour you want? That is topic 5's block/drop/grow decision one layer
 *   down.
 *
 * SEE ALSO
 *   Docs — read this first: docs/02-concurrency/07-coordination.md, section
 *     "EXERCISE 12".
 *   Demo t07coordination.D20_SemaphorePermits shows the failure live; D21
 *   covers why a lost permit never appears as a deadlock. Reference solution:
 *   solutions/Solutions.java.
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

    /*
     * Runs task while holding one of the pool's limit slots, and must
     * guarantee two things: never more than limit tasks running at once, and
     * one release for every acquire on every exit path. Break the first and
     * the limit is decorative; break the second and the pool shrinks by one
     * slot per failed task until every caller waits forever.
     */
    @Override
    public <T> T execute(java.util.concurrent.Callable<T> task) throws Exception {
        // WRONG: tryAcquire() returns false instead of waiting when the pool is full,
        // and the result is recorded but never acted on — the task below runs either
        // way, so the pool bounds nothing.
        // TODO block until a slot is genuinely free: slots.acquire(). Callers must wait
        // TODO their turn, not run unaccounted.
        boolean acquired = slots.tryAcquire();

        // WRONG: the body is not guarded. A task that throws skips both the in-flight
        // decrement and the release below, so the pool believes work is still running
        // and one permit is destroyed permanently.
        // TODO wrap task.call() in a try/finally: acquire above, call inside the try,
        // TODO and put the two lines of bookkeeping below into the finally.
        peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
        T result = task.call();
        inFlight.decrementAndGet();

        // TODO move this release into the finally block so it runs on every exit path,
        // TODO including a thrown exception — a permit never released is gone for the
        // TODO life of the process, and it never shows up as a deadlock.
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

    /*
     * The size the pool was built with — availableSlots() must return to it.
     */
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
        Check check = Check.named("Exercise 12 — a limit that limits", "ExerciseTests$Ex12")
                .reading("docs/02-concurrency/07-coordination.md § \"EXERCISE 12\"");

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
