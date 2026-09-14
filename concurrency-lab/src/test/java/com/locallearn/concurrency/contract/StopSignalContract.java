package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.StopSignal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 2 contract — see {@code t02visibility.D4_StaleFlagHang}.
 *
 * <p>Note how this one fails: not with a wrong value, but with a worker thread
 * that never exits. Visibility bugs present as hangs, which is why they are so
 * often misdiagnosed as "slow" or "stuck on the database".
 */
public abstract class StopSignalContract {

    protected abstract StopSignal newSignal();

    @Test
    @Timeout(30)
    @DisplayName("a spinning worker sees stop() promptly, 10 times in a row")
    void workerSeesTheStopSignal() throws Exception {
        for (int trial = 1; trial <= 10; trial++) {
            StopSignal signal = newSignal();

            Thread worker = new Thread(() -> {
                while (!signal.shouldStop()) {
                    // Deliberately empty. Any blocking call in here — even
                    // Thread.sleep(1) — would insert a safepoint and accidentally
                    // make the broken version work, hiding the bug.
                }
            }, "stop-signal-worker");
            worker.setDaemon(true);   // so a hung worker cannot wedge the build
            worker.start();

            // Let the JIT compile the loop. The hoisting optimisation that causes
            // this bug only happens once C2 kicks in, so a test that flips the
            // flag immediately would pass against broken code.
            TimeUnit.MILLISECONDS.sleep(200);

            signal.stop();
            worker.join(TimeUnit.SECONDS.toMillis(2));

            assertThat(worker.isAlive())
                    .as("trial %d: the worker was still spinning 2 seconds after stop(). "
                        + "The write happened — the worker's loop just never re-read the "
                        + "field, because the JIT hoisted the read out of the loop. "
                        + "Without a happens-before edge it is allowed to.", trial)
                    .isFalse();
        }
    }

    @Test
    @Timeout(10)
    @DisplayName("shouldStop() is false before stop() and true after")
    void reportsStateCorrectly() {
        StopSignal signal = newSignal();
        assertThat(signal.shouldStop()).isFalse();
        signal.stop();
        assertThat(signal.shouldStop()).isTrue();
    }
}
