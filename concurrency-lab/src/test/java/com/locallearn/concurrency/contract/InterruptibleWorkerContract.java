package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.InterruptibleWorker;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 4 contract — see {@code t01threads.D3_InterruptionAndCancellation}.
 *
 * <p>Asserts all three parts of cooperative cancellation, because getting two
 * of them right is the common half-failure: a worker that stops but skips its
 * cleanup, or one that stops but eats the flag so its caller cannot tell it was
 * cancelled.
 */
public abstract class InterruptibleWorkerContract {

    protected abstract InterruptibleWorker newWorker();

    @Test
    @Timeout(30)
    @DisplayName("stops promptly when interrupted, runs cleanup, and preserves the flag")
    void stopsOnInterrupt() throws Exception {
        for (int trial = 1; trial <= 5; trial++) {
            InterruptibleWorker worker = newWorker();
            AtomicBoolean flagStillSetOnExit = new AtomicBoolean();

            Thread thread = new Thread(() -> {
                worker.run();
                // Read the flag the moment run() returns. If the worker
                // swallowed InterruptedException without restoring it, the
                // cancellation request is gone and this reads false.
                flagStillSetOnExit.set(Thread.currentThread().isInterrupted());
            }, "interruptible-worker");
            thread.setDaemon(true);
            thread.start();

            TimeUnit.MILLISECONDS.sleep(200);           // let it do some work
            assertThat(worker.unitsCompleted())
                    .as("trial %d: the worker should have completed some work before "
                        + "being interrupted", trial)
                    .isPositive();

            thread.interrupt();
            thread.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(thread.isAlive())
                    .as("trial %d: still running 2s after interrupt(). Either the loop "
                        + "never checks isInterrupted(), or the catch block swallowed "
                        + "InterruptedException and carried on.", trial)
                    .isFalse();

            assertThat(worker.cleanedUp())
                    .as("trial %d: the worker stopped but its cleanup never ran. Cleanup "
                        + "belongs in a finally block so it runs on every exit path.", trial)
                    .isTrue();

            assertThat(flagStillSetOnExit.get())
                    .as("trial %d: the interrupt flag was not set when run() returned. "
                        + "Throwing InterruptedException CLEARS the flag; if you catch it "
                        + "without rethrowing, you must call "
                        + "Thread.currentThread().interrupt() to restore it, or callers "
                        + "up the stack can never learn they were cancelled.", trial)
                    .isTrue();
        }
    }
}
