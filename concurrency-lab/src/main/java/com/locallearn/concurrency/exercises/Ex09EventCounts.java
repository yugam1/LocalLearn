package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.EventCounts;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/*
 * EXERCISE 9 — one atomic call per update, not two
 *
 * THE SCENARIO
 *   A tally of events keyed by name, the shape you find behind a metrics
 *   counter or a work-claim ledger. Many threads call record(key) as events
 *   arrive, and many threads call consume(key) to claim one occurrence each — a
 *   key consumed down to zero must disappear from the map entirely.
 *
 * WHAT IS WRONG RIGHT NOW
 *   Read the field declaration first: the map is already a ConcurrentHashMap,
 *   so every individual call below is atomic and thread-safe, and this class is
 *   still broken. That is the whole lesson. record is get then put — two atomic
 *   calls with a gap between them, so two threads both read 7 and both write 8,
 *   losing an event. consume is worse: it is check-then-act, so two threads
 *   both read a count of 1, both return true, and the same single occurrence is
 *   claimed twice.
 *
 * YOUR TASK
 *   1. record(String) — replace the get/put pair with one call that increments
 *      under the map's bin lock.
 *   2. consume(String) — replace the get/branch/put-or-remove sequence with one
 *      call that decides, decrements, and removes-at-zero in a single step, and
 *      still reports whether it took an occurrence.
 *
 * RULES
 *   1. Do not swap the map for a different type and do not wrap the methods in
 *      synchronized — the map you need is already there.
 *   2. Removing a zeroed key with a separate remove() call reopens the gap you
 *      just closed; the removal has to happen inside the same atomic step.
 *   3. Counts must never go negative, and consume on an absent key returns
 *      false.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 16 threads x 2,000 records over 50 keys, 10 trials — nothing lost.
 *   2. Twice as many consumers as occurrences, 10 trials — consume() returns
 *      true exactly as many times as there were occurrences, every count lands
 *      on zero, and no keys remain.
 *   3. A single-threaded walk through record/consume/remove, to catch a fix
 *      that is atomic and also wrong.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex09EventCounts
 *
 * HINT
 *   merge(key, 1L, Long::sum) for the increment. compute(key, (k, v) -> ...)
 *   for the decrement — and returning null from compute removes the entry,
 *   which is how "consumed to zero must disappear" happens atomically.
 *   compute's mapping function cannot hand you a boolean, so capture the
 *   outcome from inside it (an AtomicBoolean, or a one-element array) and read
 *   it after compute returns; the function runs once per call, under the bin
 *   lock, so what it records is accurate.
 *
 * SEE ALSO
 *   Docs — read this first: docs/02-concurrency/06-shared-structures.md,
 *     section "EXERCISE 9".
 *   Demo t06shared.D17_AtomicMapUpdates shows the failure live. You have met
 *   this same check-then-act shape in D8 (overselling) and Ex6 (the cache
 *   stampede). Reference solution: solutions/Solutions.java.
 */
public final class Ex09EventCounts implements EventCounts {

    // Already thread-safe. Already not enough: atomic calls do not compose into an atomic
    // sequence. The lock this map holds is released between one call and the next.
    private final Map<String, Long> counts = new ConcurrentHashMap<>();

    /*
     * Must guarantee: after N calls with the same key, the count is exactly N.
     * If the read and the write are separate, two threads read the same value
     * and the second write overwrites the first — the event is accepted and
     * never counted.
     */
    @Override
    public void record(String key) {
        // WRONG: get() and put() are each atomic, but another thread can record the same key
        // in the gap between them. Its increment is then overwritten by the put() below.
        // TODO collapse these two lines into ONE map call that increments under the bin lock.
        Long current = counts.get(key);                     // TODO the READ...
        counts.put(key, current == null ? 1L : current + 1); // TODO ...and the WRITE: one step
    }

    /*
     * Must guarantee: across all threads, consume returns true exactly as many
     * times as record was called. Each true is a claim on one real occurrence,
     * so a duplicated true is work done twice on the same event.
     */
    @Override
    public boolean consume(String key) {
        // WRONG: the count is read here and acted on three lines later. Two threads can both
        // read 1, both take the remove() branch, and both return true — the same occurrence
        // claimed twice. This is D8's overselling bug in a Map.
        // TODO do the read, the decision, the decrement and the removal in ONE map call, and
        //      capture the true/false outcome from inside the mapping function.
        Long current = counts.get(key);                     // TODO the CHECK...
        if (current == null || current <= 0) {
            return false;
        }
        if (current == 1) {
            counts.remove(key);                             // TODO ...and the ACT: a lone
        } else {                                            //      remove() reopens the gap
            counts.put(key, current - 1);                   // TODO same gap: can clobber
        }
        return true;
    }

    /*
     * A point-in-time read. Correct as-is: one atomic get, nothing compound
     * about it.
     */
    @Override
    public long count(String key) {
        Long value = counts.get(key);
        return value == null ? 0L : value;
    }

    /*
     * Correct as-is — but it only reports zero keys if consume() removes them
     * atomically.
     */
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
        Check check = Check.named("Exercise 9 — atomic map updates", "ExerciseTests$Ex9")
                .reading("docs/02-concurrency/06-shared-structures.md § \"EXERCISE 9\"");

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
