package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.EventCounts;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * <b>EXERCISE 9 — make the compound updates atomic.</b> See
 * {@code t06shared.D17_AtomicMapUpdates}.
 *
 * <p><b>Read the field declaration first.</b> The map is already a
 * {@code ConcurrentHashMap}. Every single call below is atomic and
 * thread-safe, and this class is still broken — which is the entire lesson
 * of topic 6, and the reason the starting code is not a {@code HashMap}.
 *
 * <p>{@code record} is {@code get}-then-{@code put}: two atomic calls with a
 * race-shaped hole between them. That is {@code count++} from D7 wearing a
 * Map's clothes, and it loses increments at the same rate. {@code consume}
 * is worse — it is {@code containsKey}-then-act, so two callers can both see
 * a count of 1 and both claim the same single occurrence. You have now met
 * this shape in D8 (overselling), Ex6 (the cache stampede) and D17.
 *
 * <p>The fix is not "use a thread-safe map" — you already have one. It is
 * <b>express each whole read-modify-write as one call</b>, so the map holds
 * the bin lock across the read and the write together:
 * <ul>
 *   <li>{@code merge(key, 1L, Long::sum)} for the increment;</li>
 *   <li>{@code compute(key, (k, v) -> ...)} for the decrement — and note
 *       that <b>returning null from compute removes the entry</b>, which is
 *       exactly how you satisfy "a key consumed to zero must disappear"
 *       atomically rather than with a second {@code remove} call that would
 *       reopen the same gap.</li>
 * </ul>
 *
 * <p>Careful with {@code consume}: it has to report whether it actually
 * consumed something, and the mapping function cannot return that to you.
 * Capture it from inside the function — an effectively-final one-element
 * array or an {@code AtomicBoolean} — and read it after {@code compute}
 * returns. The mapping function runs exactly once per successful call, under
 * the bin lock, so what it records is accurate.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex09EventCounts   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex9'                                         # the grade
 * </pre>
 */
public final class Ex09EventCounts implements EventCounts {

    // Already thread-safe. Already not enough.
    private final Map<String, Long> counts = new ConcurrentHashMap<>();

    @Override
    public void record(String key) {
        Long current = counts.get(key);                     // TODO broken: READ...
        counts.put(key, current == null ? 1L : current + 1); // TODO broken: ...and WRITE, separately
    }

    @Override
    public boolean consume(String key) {
        Long current = counts.get(key);                     // TODO broken: CHECK...
        if (current == null || current <= 0) {
            return false;
        }
        if (current == 1) {
            counts.remove(key);                             // TODO broken: ...and ACT, separately —
        } else {                                            //     two callers can both get here
            counts.put(key, current - 1);
        }
        return true;
    }

    @Override
    public long count(String key) {
        Long value = counts.get(key);
        return value == null ? 0L : value;
    }

    @Override
    public int distinctKeys() {
        return counts.size();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int THREADS = 16;
    private static final int TRIALS = 10;

    private static final int RECORD_PER_THREAD = 2_000;
    private static final int RECORD_KEYS = 50;

    private static final int CONSUME_PER_THREAD = 500;
    private static final int CONSUME_KEYS = 20;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 9 — atomic map updates", "ExerciseTests$Ex9");

        check.that("every record() is counted — %d threads x %,d over %d keys, %d trials"
                .formatted(THREADS, RECORD_PER_THREAD, RECORD_KEYS, TRIALS), () -> {
            long expected = (long) THREADS * RECORD_PER_THREAD;
            // Trials, because a lost update is probabilistic: one trial that
            // happens to interleave politely would read as proof of correctness.
            for (int trial = 1; trial <= TRIALS; trial++) {
                EventCounts counts = new Ex09EventCounts();
                Stress.run(THREADS, RECORD_PER_THREAD, i -> counts.record("key-" + (i % RECORD_KEYS)));

                long total = 0;
                for (int k = 0; k < RECORD_KEYS; k++) {
                    total += counts.count("key-" + k);
                }

                Check.equal(total, expected,
                        "trial %d of %d: events were recorded and not counted. The map IS "
                        + "thread-safe; the bug is that get() and put() are two separate "
                        + "atomic calls with a race-shaped gap between them, which is D7's "
                        + "count++ wearing a Map's clothes. Replace the pair with ONE call "
                        + "that holds the bin lock across both halves: "
                        + "merge(key, 1L, Long::sum). See D17",
                        trial, TRIALS);
            }
        });

        check.that("consume() never takes the same occurrence twice — %d threads, %d trials"
                .formatted(THREADS, TRIALS), () -> {
            long recorded = (long) THREADS * CONSUME_PER_THREAD;

            for (int trial = 1; trial <= TRIALS; trial++) {
                EventCounts counts = new Ex09EventCounts();

                // Phase 1: fill, single-threaded, so the starting state is exact
                // whatever record() does. This check is about consume() alone.
                for (int i = 0; i < recorded; i++) {
                    counts.record("key-" + (i % CONSUME_KEYS));
                }

                // Phase 2: twice as many consumers as there are occurrences, so
                // every key is contended right down to its last one — which is
                // where check-then-act breaks.
                AtomicLong successes = new AtomicLong();
                Stress.run(THREADS, CONSUME_PER_THREAD * 2, i -> {
                    if (counts.consume("key-" + (i % CONSUME_KEYS))) {
                        successes.incrementAndGet();
                    }
                });

                Check.equal(successes.get(), recorded,
                        "trial %d of %d: consume() returned true a different number of times "
                        + "than there were occurrences. Two callers both read a count of 1 "
                        + "and both believed they had claimed it: check-then-act, exactly as "
                        + "in D8. Do the check and the decrement in ONE call — "
                        + "compute(key, (k, v) -> ...) — and capture whether it fired from "
                        + "inside the mapping function. See D17",
                        trial, TRIALS);

                for (int k = 0; k < CONSUME_KEYS; k++) {
                    String key = "key-" + k;
                    Check.equal(counts.count(key), 0,
                            "trial %d of %d: %s did not end at zero. A count must never go "
                            + "negative, and everything recorded was consumed",
                            trial, TRIALS, key);
                }

                Check.equal(counts.distinctKeys(), 0,
                        "trial %d of %d: keys remain after every occurrence was consumed. A "
                        + "key whose count reaches zero must be REMOVED, and removing it "
                        + "with a separate remove() call reopens the very gap you just "
                        + "closed — return null from the compute() mapping function instead, "
                        + "which deletes the entry inside the same atomic step",
                        trial, TRIALS);
            }
        });

        check.that("a single thread still records, consumes and removes correctly", () -> {
            // Here to catch the other failure mode: an atomic fix that is atomic
            // and also wrong, e.g. one that never removes the zeroed key.
            EventCounts counts = new Ex09EventCounts();

            Check.require(!counts.consume("absent"), "consumed an occurrence of an absent key");
            Check.equal(counts.count("absent"), 0, "an absent key must count zero");
            Check.equal(counts.distinctKeys(), 0, "a fresh tally must hold no keys");

            counts.record("a");
            counts.record("a");
            counts.record("b");
            Check.equal(counts.count("a"), 2, "two records of 'a' must count 2");
            Check.equal(counts.count("b"), 1, "one record of 'b' must count 1");
            Check.equal(counts.distinctKeys(), 2, "'a' and 'b' are two distinct keys");

            Check.require(counts.consume("a"), "the first consume of 'a' was refused");
            Check.equal(counts.count("a"), 1, "'a' should have one occurrence left");
            Check.equal(counts.distinctKeys(), 2, "'a' still has an occurrence, so it is still a key");

            Check.require(counts.consume("a"), "the second consume of 'a' was refused");
            Check.equal(counts.count("a"), 0, "'a' has been fully consumed");
            Check.equal(counts.distinctKeys(), 1,
                    "'a' was consumed down to zero, so it must no longer be a key");

            Check.require(!counts.consume("a"), "consumed 'a' a third time — it was empty");
        });

        System.exit(check.finish());
    }
}
