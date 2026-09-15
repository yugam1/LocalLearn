package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * <b>EXERCISE 6 — compute each value exactly once.</b>
 * See {@code t04locks.D12_ReadWriteLockAndCondition}.
 *
 * <p>This is the "cache stampede" / "thundering herd" problem. When 32
 * threads all miss on the same cold key at once, the naive version runs the
 * expensive loader 32 times. In production that is 32 simultaneous calls to
 * the service you were trying to protect, precisely when it is already slow.
 *
 * <p>The test asserts <b>two</b> things:
 * <ul>
 *   <li>correctness — every caller gets the right value, and the map is not
 *       corrupted (the {@code HashMap} here is not thread-safe either);</li>
 *   <li>{@link #loadCount()} equals the number of distinct keys. Exactly
 *       once per key. Not "roughly once".</li>
 * </ul>
 *
 * <p>Hint: {@code ConcurrentHashMap.computeIfAbsent} gives you this for
 * free, and gives it to you atomically per key rather than under one global
 * lock — so two threads asking for two <em>different</em> cold keys still
 * load in parallel. That last part is why it beats
 * {@code synchronized get()}, which also passes the compute-once assertion
 * but serialises every unrelated load behind one lock.
 *
 * <p>Read the failure numbers rather than just the pass/fail. "4 keys, 61
 * loads" is the stampede; "4 keys, 5 loads" is the same bug with a narrower
 * window and is exactly as broken. That is why the checker runs trials.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex06Cache   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex6'                                   # the grade
 * </pre>
 */
public final class Ex06Cache implements ComputeOnceCache {

    private final Map<String, String> cache = new HashMap<>();
    private final Function<String, String> loader;
    private int loads;

    public Ex06Cache(Function<String, String> expensiveLoader) {
        this.loader = expensiveLoader;
    }

    @Override
    public String get(String key) {
        String value = cache.get(key);      // TODO broken: check...
        if (value == null) {
            loads++;                        // ...and act, with an expensive
            value = loader.apply(key);      //     call in between
            cache.put(key, value);
        }
        return value;
    }

    @Override
    public int loadCount() {
        return loads;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int THREADS = 32;
    private static final int PER_THREAD = 40;
    private static final int DISTINCT_KEYS = 4;
    private static final int TRIALS = 10;
    private static final int MANY_KEYS = 5_000;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 6 — cache stampede", "ExerciseTests$Ex6");

        check.that("%d threads missing on the same cold key load it once — %d trials"
                .formatted(THREADS, TRIALS), () -> {
            // Trials, because the stampede is a window: the loader must be slow
            // enough and the threads close enough together. One trial that
            // happens to serialise proves nothing.
            for (int trial = 1; trial <= TRIALS; trial++) {
                AtomicInteger loaderCalls = new AtomicInteger();
                ComputeOnceCache cache = new Ex06Cache(key -> {
                    loaderCalls.incrementAndGet();
                    Stress.sleep(2);         // an expensive load widens the window
                    return "value-for-" + key;
                });

                Stress.run(THREADS, PER_THREAD, i -> {
                    String key = "key-" + (i % DISTINCT_KEYS);
                    String value = cache.get(key);
                    Check.require(("value-for-" + key).equals(value),
                            "a caller got %s for %s — the map is being read while "
                            + "another thread is writing it", value, key);
                });

                Check.equal(cache.loadCount(), DISTINCT_KEYS,
                        "trial %d of %d: the loader ran %d times for %d distinct keys. "
                        + "When many threads miss on the same cold key at once they "
                        + "all pass the null check and all call the loader — in "
                        + "production that is N simultaneous calls to the service you "
                        + "were trying to protect.",
                        trial, TRIALS, cache.loadCount(), DISTINCT_KEYS);

                Check.equal(loaderCalls.get(), DISTINCT_KEYS,
                        "trial %d of %d: the cache's own load counter disagrees with "
                        + "the loader's — that counter is not being updated atomically",
                        trial, TRIALS);
            }
        });

        check.that("concurrent writes across %,d keys do not corrupt the map"
                .formatted(MANY_KEYS), () -> {
            AtomicInteger loaderCalls = new AtomicInteger();
            ComputeOnceCache cache = new Ex06Cache(key -> {
                loaderCalls.incrementAndGet();
                return "value-for-" + key;
            });

            // Bounded, because a HashMap resized by two threads at once can leave
            // a cycle in a bin and spin forever. We want that reported, not
            // suffered — and an unsynchronised HashMap really can do this.
            boolean completed = Stress.run(8, MANY_KEYS, i -> {
                String key = "key-" + i;
                String value = cache.get(key);
                Check.require(("value-for-" + key).equals(value),
                        "got %s for %s — a concurrent put lost or mangled the entry",
                        value, key);
            }, 20);

            Check.require(completed,
                    "the run did not finish in 20s. A HashMap resized by two threads "
                    + "at once can leave a cycle in a bin, and a get() walking that "
                    + "bin never terminates.");

            Check.equal(loaderCalls.get(), MANY_KEYS,
                    "a plain HashMap loses entries when resized concurrently, so keys "
                    + "get reloaded — and the map may be structurally corrupt besides");

            Check.equal(cache.loadCount(), MANY_KEYS,
                    "the cache's own load counter is not being updated atomically");
        });

        check.that("a repeated single-threaded get loads once", () -> {
            ComputeOnceCache cache = new Ex06Cache(key -> "value-for-" + key);
            // Here to catch the other failure mode: a fix that is thread-safe and
            // also not a cache, e.g. one that reloads on every call.
            Check.require("value-for-a".equals(cache.get("a")), "wrong value for key a");
            Check.require("value-for-a".equals(cache.get("a")), "wrong value for key a");
            Check.require("value-for-b".equals(cache.get("b")), "wrong value for key b");
            Check.equal(cache.loadCount(), 2, "two distinct keys should mean two loads");
        });

        System.exit(check.finish());
    }
}
