package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.Counter;
import com.locallearn.concurrency.support.Stress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 1 contract — see {@code t03atomicity.D7_LostUpdates}.
 *
 * <p>Run against your implementation ({@code Ex1CounterTest}) and against the
 * reference ({@code Sol1CounterTest}). Both run the same assertions.
 */
public abstract class CounterContract {

    protected abstract Counter newCounter();

    @Test
    @Timeout(60)
    @DisplayName("every increment is counted, under 8 threads x 100k, repeated")
    void countsEveryIncrement() {
        int threads = 8;
        int perThread = 100_000;
        long expected = (long) threads * perThread;

        // 20 trials, not 1. A single trial of the broken version has a real
        // chance of passing; 20 do not. This is the difference between a test
        // that catches races and a test that launders them.
        for (int trial = 1; trial <= 20; trial++) {
            Counter counter = newCounter();
            Stress.run(threads, perThread, i -> counter.increment());

            assertThat(counter.count())
                    .as("trial %d: %d threads each incremented %d times, so the count "
                        + "must be exactly %d. A lower number means increments were lost "
                        + "to a read-modify-write race.", trial, threads, perThread, expected)
                    .isEqualTo(expected);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a single thread still counts correctly")
    void worksSingleThreaded() {
        Counter counter = newCounter();
        for (int i = 0; i < 1_000; i++) {
            counter.increment();
        }
        assertThat(counter.count()).isEqualTo(1_000);
    }
}
