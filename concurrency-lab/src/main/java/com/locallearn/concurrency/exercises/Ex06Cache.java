package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/*
 * EXERCISE 6 — load each key exactly once
 *
 * THE SCENARIO
 *   A read-through cache sitting in front of something expensive — a remote
 *   call, a slow query. Every request thread calls get(String); a hit returns
 *   from the map, a miss runs loader and stores the result. The point of the
 *   cache is that a cold key costs one load, no matter how many threads want it
 *   at once.
 *
 * WHAT IS WRONG RIGHT NOW
 *   get() is check-then-act with a slow call wedged in the middle. Thirty-two
 *   threads miss on the same cold key, all read null, and all call the loader —
 *   that is the cache stampede, and in production it is N simultaneous calls to
 *   the service you were trying to protect, exactly when it is already
 *   struggling. Two more defects ride along: loads++ is a read-modify-write
 *   that loses counts, and a plain HashMap written by several threads can drop
 *   entries or leave a cycle in a bin that makes a later get() spin forever.
 *
 * YOUR TASK
 *   1. get(String) — make the miss path run the loader once per key even when
 *      many threads miss on that key simultaneously, and return that one value
 *      to all of them.
 *   2. The cache field — replace the HashMap with a map that tolerates
 *      concurrent writes.
 *   3. The loads field and loadCount() — count loads in a way that cannot lose
 *      an increment.
 *
 * RULES
 *   Each key must be computed exactly once — not "roughly once". Keep unrelated
 *   keys parallel: wrapping the whole of get() in one synchronized block passes
 *   the compute-once check but serialises every load in the system behind a
 *   single lock. Do not change the signatures, and do not turn this into a non-
 *   cache that reloads on every call.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 32 threads hammering 4 cold keys, 10 trials: loadCount() is 4 and the
 *      loader's own counter agrees, and every caller got the right value;
 *   2. 8 threads across 5,000 distinct keys finish within 20s with no lost or
 *      mangled entries — one load per key;
 *   3. repeated single-threaded gets for the same key load it only once.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex06Cache
 *
 * HINT
 *   ConcurrentHashMap.computeIfAbsent gives you compute-once atomically per
 *   key, so two threads wanting two different cold keys still load in parallel.
 *   Read the failure numbers, not just PASS/FAIL: "4 keys, 61 loads" is the
 *   stampede, and "4 keys, 5 loads" is the same bug with a narrower window.
 *
 * SEE ALSO
 *   Demo t04locks.D12_ReadWriteLockAndCondition shows the locking machinery
 *   behind this. Reference solution: solutions/Solutions.java.
 */
public final class Ex06Cache implements ComputeOnceCache {

    // TODO swap this for a map that survives concurrent writes — a HashMap written by
    // two threads at once can lose entries or corrupt a bin.
    private final Map<String, String> cache = new HashMap<>();
    private final Function<String, String> loader;
    // TODO make this counter atomic — `loads++` is read-modify-write and drops counts.
    private int loads;

    public Ex06Cache(Function<String, String> expensiveLoader) {
        this.loader = expensiveLoader;
    }

    /*
     * Must guarantee that a cold key is loaded exactly once however many
     * threads ask for it at the same instant, and that they all get that one
     * value back. If it does not, every miss fans out into N calls to the
     * expensive loader, and the map itself can be left inconsistent by the
     * overlapping writes.
     */
    @Override
    public String get(String key) {
        // WRONG: the null check and the put are two separate steps with a slow load in
        // between, so every thread that arrives during that window sees null, counts a
        // load, and calls the loader too.
        // TODO make "miss, load, store" one atomic step per key — the whole miss path
        // for a given key must happen once, while other keys still load in parallel.
        String value = cache.get(key);
        if (value == null) {
            loads++;
            value = loader.apply(key);
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
