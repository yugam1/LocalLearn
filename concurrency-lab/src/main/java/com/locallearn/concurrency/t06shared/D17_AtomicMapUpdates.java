package com.locallearn.concurrency.t06shared;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEMO 17 — The bug you already know, in its third disguise.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t06shared.D17_AtomicMapUpdates}
 *
 * <h2>The sentence that causes this</h2>
 * "I switched to {@code ConcurrentHashMap}, so the map is thread-safe now."
 * That sentence is true and it is not the claim you need. What
 * {@code ConcurrentHashMap} guarantees is that <b>each single call</b> is
 * atomic. What it cannot guarantee — what no collection can ever guarantee — is
 * that <em>your sequence of calls</em> is atomic, because it has no idea which
 * of your calls belong together.
 *
 * <pre>{@code
 * if (!map.containsKey(key)) {   // CHECK  — atomic, correct, and instantly stale
 *     map.put(key, value);       // ACT    — atomic, correct, and too late
 * }
 * }</pre>
 *
 * <p>You have now met this exact shape three times:
 * <ul>
 *   <li><b>D8</b>: {@code if (stock > 0) stock--} sold 138 units of a
 *       100-unit product.</li>
 *   <li><b>Ex6</b>: {@code if (cache.get(k) == null) load(k)} ran one expensive
 *       loader 32 times, against the service it was protecting.</li>
 *   <li><b>Here</b>: {@code containsKey}-then-{@code put}, where every caller is
 *       told it was the first to register.</li>
 * </ul>
 * Same bug, new clothes, and the clothes are the whole problem — a thread-safe
 * type makes the code <em>look</em> fixed. The gap between two atomic calls is
 * every bit as wide as the gap between two non-atomic ones.
 *
 * <h2>The fix is to make the map do the whole thing</h2>
 * {@code ConcurrentHashMap} offers a family of methods that each take a
 * <em>whole</em> read-modify-write and perform it under the bin lock:
 *
 * <pre>
 * putIfAbsent(k, v)                 insert only if the key is absent; returns the incumbent
 * computeIfAbsent(k, fn)            compute-and-insert only if absent — Ex6's answer
 * computeIfPresent(k, (k,v) -&gt; ...) update only if present
 * compute(k, (k,v) -&gt; ...)          the general form; return null to REMOVE the entry
 * merge(k, v, (old,new) -&gt; ...)     insert v if absent, else combine — the counter idiom
 * replace(k, expected, new)         compare-and-swap on a map entry (this is CAS from D9,
 *                                   keyed: same optimistic idea, same ABA caveat)
 * </pre>
 *
 * <h2>Why this beats a synchronized map</h2>
 * Both a {@code synchronized} block and {@code computeIfAbsent} give you
 * compute-once. Only one of them lets two threads load two <em>different</em>
 * keys at the same time. {@code ConcurrentHashMap} locks the <b>bin</b> — the
 * single table slot the key hashes to — not the map, so unrelated keys never
 * queue behind each other. This demo measures that difference with eight cold
 * keys and a 200 ms loader, and the gap is the difference between a service that
 * warms up in a fifth of a second and one that takes nearly two.
 *
 * <h2>Two rules for the mapping function</h2>
 * <ol>
 *   <li><b>Keep it short.</b> It runs while the bin lock is held, so every other
 *       key hashing to the same bin waits on it. For a genuinely slow load,
 *       cache a {@code CompletableFuture} instead of the value: the future is
 *       inserted immediately and the lock is released while the work runs.</li>
 *   <li><b>Do not touch the same map inside it.</b> A recursive update is not
 *       merely discouraged; the JDK detects it and throws
 *       {@link IllegalStateException} ("Recursive update"), because the
 *       alternative is a thread deadlocking against a bin lock it already
 *       holds. This demo triggers it so you recognise the message.</li>
 * </ol>
 */
public final class D17_AtomicMapUpdates {

    private static final int THREADS = 16;
    private static final int PER_THREAD = 2_000;
    private static final int DISTINCT_KEYS = 50;
    private static final long EXPECTED_TOTAL = (long) THREADS * PER_THREAD;
    private static final int ELECTION_TRIALS = 200;

    public static void main(String[] args) throws Exception {
        whoRegisteredFirst();
        lostIncrements();
        perBinParallelism();
        recursiveUpdate();

        Log.takeaway("""
                ConcurrentHashMap makes each CALL atomic. Your read-modify-write is
                two calls, and the map cannot know they belong together — so
                containsKey-then-put and get-then-put are exactly as broken here as
                `if (stock > 0) stock--` was in D8. The fix is never "add a
                thread-safe collection"; it is "express the whole operation as ONE
                call" — putIfAbsent, merge, compute, computeIfAbsent — so the map
                holds the bin lock across the read AND the write.

                And the reason to prefer that over a synchronized block is measured
                above: the lock is per bin, so eight cold keys loaded in parallel
                instead of one after another.""");
    }

    // ── 1. Everyone thinks they were first ─────────────────────────────────
    /**
     * Reported the way D8 reports overselling, and for the same reason: a single
     * trial of this proves nothing, because most of the time the race simply
     * does not happen. What matters is <em>how often</em>, and the answer —
     * "usually fine" — is the property that makes this bug ship.
     */
    private static void whoRegisteredFirst() {
        Log.section("CHECK-THEN-ACT — \"am I the first to register this key?\"");
        Log.log("32 threads race to claim ONE key, %d trials. Exactly one must win.", ELECTION_TRIALS);

        int brokenBadTrials = 0;
        int brokenWorst = 0;
        int fixedBadTrials = 0;
        for (int trial = 1; trial <= ELECTION_TRIALS; trial++) {
            ConcurrentHashMap<String, String> broken = new ConcurrentHashMap<>();
            AtomicInteger brokenWinners = new AtomicInteger();
            ConcurrentHashMap<String, String> fixed = new ConcurrentHashMap<>();
            AtomicInteger fixedWinners = new AtomicInteger();

            Stress.run(32, 1, i -> {
                String me = Thread.currentThread().getName();

                // BROKEN: two atomic calls with a race-shaped hole between them.
                if (!broken.containsKey("leader")) {
                    broken.put("leader", me);
                    brokenWinners.incrementAndGet();
                }

                // FIXED: one call. The map performs the check and the act while
                // it holds the bin lock, and returns the incumbent (or null if
                // there wasn't one, which is how you know you won).
                if (fixed.putIfAbsent("leader", me) == null) {
                    fixedWinners.incrementAndGet();
                }
            });

            if (brokenWinners.get() > 1) {
                brokenBadTrials++;
                brokenWorst = Math.max(brokenWorst, brokenWinners.get());
            }
            if (fixedWinners.get() != 1) {
                fixedBadTrials++;
            }
        }

        Log.log("containsKey-then-put: elected more than one leader in %d of %d trials (%.0f%%), worst %d leaders",
                brokenBadTrials, ELECTION_TRIALS, 100.0 * brokenBadTrials / ELECTION_TRIALS, brokenWorst);
        Log.log("putIfAbsent:          elected exactly one leader in %d of %d trials",
                ELECTION_TRIALS - fixedBadTrials, ELECTION_TRIALS);
        Log.log("Read the first line the way D8 taught you to: it is mostly fine.");
        Log.log("Mostly fine is the property that gets a bug past code review, past");
        Log.log("the test suite, and into the release. Every one of those threads");
        Log.log("called put() on a thread-safe map and every one was told it was");
        Log.log("first — in a real service, two schedulers both believing they own");
        Log.log("the nightly job, and only one of them is right.");
    }

    // ── 2. get-then-put loses increments, exactly like D7 ───────────────────
    private static void lostIncrements() {
        Log.section("PER-KEY COUNTERS — " + THREADS + " threads x " + String.format("%,d", PER_THREAD)
                + " increments over " + DISTINCT_KEYS + " keys, expecting "
                + String.format("%,d", EXPECTED_TOTAL));

        for (int trial = 1; trial <= 5; trial++) {
            long plainHashMap = countWith(new HashMap<>(), Style.GET_THEN_PUT);
            long getThenPut = countWith(new ConcurrentHashMap<>(), Style.GET_THEN_PUT);
            long merged = countWith(new ConcurrentHashMap<>(), Style.MERGE);
            long computed = countWith(new ConcurrentHashMap<>(), Style.COMPUTE);

            Log.log("trial %d: HashMap+get/put %,8d   CHM+get/put %,8d (lost %4.1f%%)   CHM.merge %,8d   CHM.compute %,8d",
                    trial, plainHashMap, getThenPut,
                    100.0 * (EXPECTED_TOTAL - getThenPut) / EXPECTED_TOTAL, merged, computed);
        }
        Log.log("The middle column is the one to stare at. The map is thread-safe,");
        Log.log("every call is atomic, and increments still vanish — because");
        Log.log("`map.put(k, map.get(k) + 1)` is D7's count++ with extra ceremony.");
    }

    private enum Style { GET_THEN_PUT, MERGE, COMPUTE }

    private static long countWith(Map<String, Long> map, Style style) {
        Stress.run(THREADS, PER_THREAD, i -> {
            String key = "key-" + (i % DISTINCT_KEYS);
            switch (style) {
                case GET_THEN_PUT -> {
                    Long current = map.get(key);                  // READ
                    map.put(key, current == null ? 1 : current + 1);  // WRITE — separate step
                }
                // merge: "insert 1 if absent, otherwise apply the function to the
                // old value and the new one" — the whole read-modify-write runs
                // under the bin lock, so nothing can interleave.
                case MERGE -> map.merge(key, 1L, Long::sum);
                // compute: the general form. Same atomicity; use it when the new
                // value depends on the key as well, or when returning null to
                // delete the entry is part of the operation.
                case COMPUTE -> map.compute(key, (k, v) -> v == null ? 1L : v + 1);
            }
        });
        long total = 0;
        for (long value : map.values()) {
            total += value;
        }
        return total;
    }

    // ── 3. Per-bin locking: unrelated keys really do load in parallel ──────
    private static void perBinParallelism() throws InterruptedException {
        Log.section("PER-BIN LOCKING — 8 threads, 8 DIFFERENT cold keys, 200ms loader each");

        long serialised = timeColdLoads(true);
        long perBin = timeColdLoads(false);

        Log.log("synchronized (map)  { get / load / put }  : %,5d ms   (one loader at a time)", serialised);
        Log.log("map.computeIfAbsent(key, loader)          : %,5d ms   (one loader PER KEY)", perBin);
        Log.log("Both are compute-once. Only one of them is concurrent. The global");
        Log.log("lock serialises eight unrelated loads behind each other — which is");
        Log.log("the exact bottleneck the cache existed to remove. This is also why");
        Log.log("D12's ReadWriteLock advice ended with \"map-shaped data →");
        Log.log("ConcurrentHashMap\": it SHARDS the locking instead of sharing it.");
    }

    private static long timeColdLoads(boolean globalLock) throws InterruptedException {
        Map<String, String> map = new ConcurrentHashMap<>();
        AtomicInteger loaderCalls = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(8);

        for (int t = 0; t < 8; t++) {
            final String key = "key-" + t;      // every thread wants a DIFFERENT key
            Thread thread = new Thread(() -> {
                try {
                    gate.await();
                    if (globalLock) {
                        synchronized (map) {
                            if (!map.containsKey(key)) {
                                map.put(key, slowLoad(key, loaderCalls));
                            }
                        }
                    } else {
                        map.computeIfAbsent(key, k -> slowLoad(k, loaderCalls));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "loader-" + t);
            thread.setDaemon(true);
            thread.start();
        }

        long start = System.nanoTime();
        gate.countDown();
        done.await();
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
        if (loaderCalls.get() != 8) {
            Log.log("  (!) loader ran %d times for 8 keys", loaderCalls.get());
        }
        return millis;
    }

    private static String slowLoad(String key, AtomicInteger calls) {
        calls.incrementAndGet();
        Stress.sleep(200);
        return "value-for-" + key;
    }

    // ── 4. The mapping function must not touch the same map ────────────────
    private static void recursiveUpdate() {
        Log.section("THE ONE HARD RULE FOR compute/merge/computeIfAbsent");

        // Keys 0 and 16 land in the SAME bin of a default 16-slot table, which
        // is what makes this reproducible. computeIfAbsent parks a reservation
        // node in the empty bin before running the function; the nested call
        // finds that reservation and refuses, because the only alternative is
        // this thread waiting on a bin it is itself in the middle of writing.
        ConcurrentHashMap<Integer, String> sameBin = new ConcurrentHashMap<>();
        try {
            sameBin.computeIfAbsent(0, k -> {
                sameBin.computeIfAbsent(16, k2 -> "written from inside the mapping function");
                return "value-0";
            });
            Log.log("same bin  (keys 0 and 16): no exception — map now %s", sameBin);
        } catch (IllegalStateException e) {
            Log.log("same bin  (keys 0 and 16): %s: \"%s\"",
                    e.getClass().getSimpleName(), e.getMessage());
        }

        // A different bin does NOT throw. That is the trap: the check is not a
        // contract, it is the JDK catching the subset of cases it can see. Your
        // code is broken either way; only one version tells you.
        ConcurrentHashMap<Integer, String> otherBin = new ConcurrentHashMap<>();
        try {
            otherBin.computeIfAbsent(0, k -> {
                otherBin.put(7, "different bin");
                return "value-0";
            });
            Log.log("other bin (keys 0 and 7):  no exception — map now %s", otherBin);
        } catch (IllegalStateException e) {
            Log.log("other bin (keys 0 and 7):  %s: \"%s\"",
                    e.getClass().getSimpleName(), e.getMessage());
        }

        Log.log("The mapping function runs while the bin lock is HELD, so touching");
        Log.log("the same map from inside it can mean waiting on a bin you are");
        Log.log("already writing. The JDK detects that case and throws rather than");
        Log.log("hang — but only when the keys collide, so the absence of an");
        Log.log("exception is not evidence that your mapping function is safe.");
        Log.log("Same reason it must be quick: everything hashing to that bin is");
        Log.log("waiting on it. Slow load? Cache a CompletableFuture, so the map");
        Log.log("stores a placeholder instantly and the bin lock is released while");
        Log.log("the work runs.");
    }

    private D17_AtomicMapUpdates() {
    }
}
