package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.BoundedResourcePool;
import com.locallearn.concurrency.support.Stress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * EXERCISE 12 contract — a concurrency limit that actually limits, and survives
 * failure.
 *
 * <p>Two defects, and the second is the one that matters in production:
 * <ul>
 *   <li>{@link #neverRunsMoreTasksAtOnceThanTheLimit()} — the cap is not
 *       enforced. Obvious once measured.</li>
 *   <li>{@link #doesNotLeakASlotWhenTheTaskThrows()} — every failure
 *       permanently destroys one slot. This one passes every happy-path test
 *       ever written, and the system it breaks does not fall over at the first
 *       error; it shrinks, one exception at a time, until the last slot is gone
 *       and every caller parks forever with no deadlock report (D21).</li>
 * </ul>
 * The leak is asserted on {@code availableSlots()} rather than by waiting for a
 * hang, so the failure arrives as a message you can read instead of a timeout.
 */
public abstract class BoundedResourcePoolContract {

    protected abstract BoundedResourcePool newPool(int limit);

    @Test
    @Timeout(60)
    @DisplayName("32 callers against a 4-slot pool never run more than 4 at once")
    void neverRunsMoreTasksAtOnceThanTheLimit() {
        int limit = 4;
        BoundedResourcePool pool = newPool(limit);
        AtomicLong completed = new AtomicLong();

        Stress.run(32, 20, i -> {
            try {
                pool.execute(() -> {
                    Stress.sleep(1);
                    completed.incrementAndGet();
                    return null;
                });
            } catch (Exception e) {
                throw new IllegalStateException("execute() threw for a task that does not", e);
            }
        });

        assertThat(pool.peakConcurrency())
                .as("%d tasks ran at once against a pool of %d. tryAcquire() returns "
                    + "false when the pool is full — running the task anyway makes the "
                    + "limit decorative, and the concurrency it permits is unbounded AND "
                    + "invisible. If callers should wait, the verb is acquire(); if they "
                    + "should be turned away, you must actually turn them away. See D20",
                    pool.peakConcurrency(), limit)
                .isLessThanOrEqualTo(limit);

        assertThat(completed.get())
                .as("only %,d of 640 tasks ran — a bounded pool makes callers WAIT, it "
                    + "does not silently drop their work", completed.get())
                .isEqualTo(640);

        assertThat(pool.availableSlots())
                .as("after every task finished, %d of %d slots are free — acquires and "
                    + "releases are not balanced", pool.availableSlots(), limit)
                .isEqualTo(limit);
    }

    @Test
    @Timeout(60)
    @DisplayName("a task that throws gives its slot back")
    void doesNotLeakASlotWhenTheTaskThrows() throws Exception {
        int limit = 4;
        BoundedResourcePool pool = newPool(limit);

        // Exactly as many failures as there are slots: enough to empty the pool
        // if, and only if, the release is on a path an exception can skip.
        for (int attempt = 0; attempt < limit; attempt++) {
            assertThatThrownBy(() -> pool.execute(() -> {
                throw new IllegalStateException("downstream call failed");
            })).isInstanceOf(IllegalStateException.class);
        }

        assertThat(pool.availableSlots())
                .as("after %d failed tasks the pool has %d of %d slots left — each "
                    + "exception permanently destroyed one. release() was placed after "
                    + "the work, so a throwing task skips it, and a permit that is never "
                    + "released is gone for the life of the process. Put it in a FINALLY. "
                    + "Note the shape of this failure: the pool does not break at the "
                    + "first error, it shrinks one exception at a time until the last "
                    + "slot goes and every caller parks forever — and no thread dump will "
                    + "report a deadlock, because a permit has no owner (D20, D21)",
                    limit, pool.availableSlots(), limit)
                .isEqualTo(limit);

        // Functional confirmation: a healthy call still gets through promptly.
        // Only reached once the assertion above passes, so this can never be the
        // thing that turns a real defect into an unexplained timeout.
        CountDownLatch finished = new CountDownLatch(1);
        Thread caller = new Thread(() -> {
            try {
                pool.execute(() -> "ok");
                finished.countDown();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }, "post-failure-caller");
        caller.setDaemon(true);
        caller.start();

        assertThat(finished.await(5, TimeUnit.SECONDS))
                .as("a healthy task submitted after %d failures never got a slot within "
                    + "5 seconds — the pool is exhausted and will never recover", limit)
                .isTrue();
    }

    @Test
    @Timeout(30)
    @DisplayName("execute() returns the task's value and restores the slot")
    void returnsTheResultAndRestoresTheSlot() throws Exception {
        BoundedResourcePool pool = newPool(2);

        assertThat(pool.execute(() -> "hello")).isEqualTo("hello");
        assertThat(pool.execute(() -> 42)).isEqualTo(42);
        assertThat(pool.availableSlots()).isEqualTo(2);
        assertThat(pool.peakConcurrency())
                .as("two sequential tasks never overlapped, so the peak must be 1")
                .isEqualTo(1);
    }
}
