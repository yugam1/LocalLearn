package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.EventCounts;
import com.locallearn.concurrency.support.Stress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 9 contract — atomic read-modify-write on a map.
 *
 * <p>Note what is <em>not</em> being tested: whether the map is thread-safe. It
 * is — the broken implementation already uses a {@code ConcurrentHashMap}. What
 * these tests catch is the gap between two atomic calls, which is the same
 * defect as D8's overselling and Ex6's stampede in a third set of clothes.
 */
public abstract class EventCountsContract {

    protected abstract EventCounts newCounts();

    @Test
    @Timeout(60)
    @DisplayName("every record() is counted, 16 threads x 2,000 over 50 keys")
    void countsEveryRecordedEvent() {
        int threads = 16;
        int perThread = 2_000;
        int distinctKeys = 50;
        long expected = (long) threads * perThread;

        for (int trial = 1; trial <= 10; trial++) {
            EventCounts counts = newCounts();
            Stress.run(threads, perThread, i -> counts.record("key-" + (i % distinctKeys)));

            long total = 0;
            for (int k = 0; k < distinctKeys; k++) {
                total += counts.count("key-" + k);
            }

            assertThat(total)
                    .as("trial %d: %,d events were recorded but only %,d were counted — "
                        + "%,d lost. The map IS thread-safe; the bug is that get() and "
                        + "put() are two separate atomic calls with a race-shaped gap "
                        + "between them, which is D7's count++ wearing a Map's clothes. "
                        + "Replace the pair with ONE call that holds the bin lock across "
                        + "both halves: merge(key, 1L, Long::sum). See D17",
                        trial, expected, total, expected - total)
                    .isEqualTo(expected);
        }
    }

    @Test
    @Timeout(60)
    @DisplayName("consume() never takes the same occurrence twice, and never goes negative")
    void consumeSucceedsExactlyAsManyTimesAsRecordDid() {
        int threads = 16;
        int perThread = 500;
        int distinctKeys = 20;
        long recorded = (long) threads * perThread;

        for (int trial = 1; trial <= 10; trial++) {
            EventCounts counts = newCounts();

            // Phase 1: fill, single-threaded, so the starting state is exact
            // whatever record() does. This test is about consume() alone.
            for (int i = 0; i < recorded; i++) {
                counts.record("key-" + (i % distinctKeys));
            }

            // Phase 2: twice as many consumers as there are occurrences, so
            // every key is contended right down to its last one — which is
            // where check-then-act breaks.
            AtomicLong successes = new AtomicLong();
            Stress.run(threads, perThread * 2, i -> {
                if (counts.consume("key-" + (i % distinctKeys))) {
                    successes.incrementAndGet();
                }
            });

            assertThat(successes.get())
                    .as("trial %d: %,d occurrences were recorded but consume() returned "
                        + "true %,d times — %+d. Two callers both read a count of 1 and "
                        + "both believed they had claimed it: check-then-act, exactly as "
                        + "in D8. Do the check and the decrement in ONE call — "
                        + "compute(key, (k, v) -> ...) — and capture whether it fired "
                        + "from inside the mapping function. See D17",
                        trial, recorded, successes.get(), successes.get() - recorded)
                    .isEqualTo(recorded);

            for (int k = 0; k < distinctKeys; k++) {
                String key = "key-" + k;
                assertThat(counts.count(key))
                        .as("trial %d: %s ended at %d. A count must never go negative, and "
                            + "everything recorded was consumed, so it must be 0",
                            trial, key, counts.count(key))
                        .isZero();
            }

            assertThat(counts.distinctKeys())
                    .as("trial %d: %d keys remain after every occurrence was consumed. A "
                        + "key whose count reaches zero must be REMOVED, and removing it "
                        + "with a separate remove() call reopens the very gap you just "
                        + "closed — return null from the compute() mapping function "
                        + "instead, which deletes the entry inside the same atomic step",
                        trial, counts.distinctKeys())
                    .isZero();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a single thread still records, consumes and removes correctly")
    void worksSingleThreaded() {
        EventCounts counts = newCounts();

        assertThat(counts.consume("absent")).isFalse();
        assertThat(counts.count("absent")).isZero();
        assertThat(counts.distinctKeys()).isZero();

        counts.record("a");
        counts.record("a");
        counts.record("b");
        assertThat(counts.count("a")).isEqualTo(2);
        assertThat(counts.count("b")).isEqualTo(1);
        assertThat(counts.distinctKeys()).isEqualTo(2);

        assertThat(counts.consume("a")).isTrue();
        assertThat(counts.count("a")).isEqualTo(1);
        assertThat(counts.distinctKeys()).isEqualTo(2);

        assertThat(counts.consume("a")).isTrue();
        assertThat(counts.count("a")).isZero();
        assertThat(counts.distinctKeys())
                .as("'a' was consumed down to zero, so it must no longer be a key")
                .isEqualTo(1);

        assertThat(counts.consume("a")).isFalse();
    }
}
