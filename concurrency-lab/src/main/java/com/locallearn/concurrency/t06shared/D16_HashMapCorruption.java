package com.locallearn.concurrency.t06shared;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DEMO 16 — What a plain {@link HashMap} actually does when two threads write to
 * it, and what {@link ConcurrentHashMap} gives you instead — including the two
 * guarantees it deliberately does <em>not</em> give you.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t06shared.D16_HashMapCorruption}
 *
 * <h2>Why a HashMap loses entries, mechanically</h2>
 * A {@code HashMap} is an array of bins. {@code put} computes the bin index from
 * the key's hash, walks that bin's list, and links a new node into it. Two
 * things in that sentence are not atomic and nobody made them so:
 *
 * <ol>
 *   <li><b>Linking into a bin.</b> Two threads whose keys land in the same bin
 *       both read the same {@code tab[i]} head pointer and both write a new node
 *       into it. The second write overwrites the first. One entry, silently
 *       gone — no exception, no log line, just a key you stored and can never
 *       read back.</li>
 *   <li><b>Resizing.</b> When the map passes its load factor it allocates a
 *       bigger table and rehashes every node into it. That is a long, multi-step
 *       operation on the shared {@code table} field. A thread that puts during
 *       someone else's resize may write into the <em>old</em> table, which is
 *       about to be discarded — so the entries written during that window
 *       disappear together. This is why the loss is lumpy rather than smooth,
 *       and why the percentage swings so much between runs.</li>
 * </ol>
 *
 * <p>It is worth saying what this is <b>not</b>. It is not a CPU-cache
 * visibility problem, and marking the map field {@code volatile} fixes exactly
 * nothing — the reference was never the thing being raced on. The race is on the
 * map's internal table array, two objects further down, and {@code volatile} on
 * a field says nothing whatsoever about the object it points at. This is the
 * same distinction D4 and D15 forced: a visibility bug and a structural race
 * look identical from the outside and have nothing in common underneath.
 *
 * <h2>"Not thread-safe" is a family of failures, not one</h2>
 * JDK 7's resize reversed each bin's order, which could splice a bin into a
 * cycle, and a later {@code get} would walk that cycle forever at 100% CPU. JDK
 * 8 rewrote resize to preserve order specifically to kill that bug, and people
 * repeat "the infinite loop was fixed in Java 8" as though the structure became
 * safe. It did not. Running this demo repeatedly produces <b>three different
 * outcomes</b>, all of which I have observed on this machine:
 * <ol>
 *   <li><b>Lost entries</b> — the common case, 2–16% of 40,000 keys.</li>
 *   <li><b>A worker that never returns from {@code put}</b>, still inside the
 *       map when the 8-second timeout fires, burning a core. It ignores
 *       {@code interrupt()} because it is not blocked on anything — it is
 *       looping (exactly D3's "interruption is a request" boundary).</li>
 *   <li><b>{@code ClassCastException: HashMap$Node cannot be cast to
 *       HashMap$TreeNode}</b> — the map recorded that a bin had been treeified
 *       while another thread was still linking plain nodes into it, so the bin's
 *       own idea of its structure is now false.</li>
 * </ol>
 * Outcomes 2 and 3 are strictly worse than outcome 1, because outcome 1 at least
 * leaves the process healthy. The one you get is a coin flip, which is why "we
 * tested it and it worked" carries no information here.
 *
 * <h2>The two things ConcurrentHashMap does not promise</h2>
 * <ul>
 *   <li><b>{@code size()} is an estimate.</b> There is no single counter to
 *       read, because one would be exactly the contended cache line the whole
 *       design exists to avoid — the count is sharded across striped cells, the
 *       {@code LongAdder} mechanism from D9, built in. {@code size()} sums those
 *       cells one at a time, so the number it returns belongs to no single
 *       instant. For a quiescent map it is exact; for a busy one it is a recent
 *       plausible value, and the API says so.</li>
 *   <li><b>Iterators are weakly consistent.</b> They never throw
 *       {@link ConcurrentModificationException}, they walk the table as it exists
 *       while they walk it, and they may or may not reflect writes made after
 *       they started. A {@code HashMap} iterator instead fails fast: it notices
 *       {@code modCount} moved and throws. Fail-fast is not a thread-safety
 *       mechanism — it is a best-effort bug detector that happens to catch this
 *       particular bug some of the time.</li>
 * </ul>
 */
public final class D16_HashMapCorruption {

    private static final int THREADS = 8;
    private static final int KEYS_PER_THREAD = 5_000;
    private static final int EXPECTED = THREADS * KEYS_PER_THREAD;
    private static final int TRIALS = 5;

    public static void main(String[] args) throws Exception {
        lostEntries();
        sizeIsAnEstimate();
        checkThenActOnSize();
        iteratorSemantics();

        Log.takeaway("""
                A HashMap shared across writing threads does not throw, does not
                warn, and does not work: entries vanish because linking into a bin
                and swapping the table during a resize are multi-step operations
                nobody made atomic — and often enough a worker simply never comes
                back out of put(). ConcurrentHashMap gives you every write back and
                charges two honest caveats: size() is an estimate, because the
                count is sharded to avoid the one contended cache line, and
                iterators are weakly consistent, because a true snapshot would mean
                locking the whole map — the thing you came here to stop doing.

                Neither caveat is a bug. Treating size() as a fact you may then act
                on IS a bug, and it is D8's check-then-act wearing a Map's clothes.""");
    }

    // ── 1. Entries simply disappear (or a worker never returns) ────────────
    private static void lostEntries() {
        Log.section("LOST ENTRIES — " + THREADS + " threads x " + KEYS_PER_THREAD
                + " distinct keys each, expecting " + String.format("%,d", EXPECTED));
        Log.log("Every thread writes keys no other thread writes: not one duplicate");
        Log.log("in the whole run, so the final size MUST be %,d.", EXPECTED);

        boolean stuckThreadsLeftBehind = false;
        for (int trial = 1; trial <= TRIALS; trial++) {
            String plain = stuckThreadsLeftBehind
                    ? "skipped (see below)"
                    : fillDistinctKeys(new HashMap<>());
            stuckThreadsLeftBehind |= plain.startsWith("SPUN");

            Log.log("trial %d: HashMap %-52s  synchronizedMap %s   ConcurrentHashMap %s",
                    trial, plain,
                    fillDistinctKeys(Collections.synchronizedMap(new HashMap<>())),
                    fillDistinctKeys(new ConcurrentHashMap<>()));
        }

        Log.log("Loss is lumpy, not smooth, because a put landing inside someone");
        Log.log("else's resize writes into the table that is about to be discarded —");
        Log.log("so entries go missing in batches, and the percentage swings.");
        if (stuckThreadsLeftBehind) {
            Log.log("");
            Log.log("!! A worker never returned from HashMap.put and is still burning a");
            Log.log("!! core right now — it ignores interrupt() because it is not blocked");
            Log.log("!! on anything, it is looping. The remaining HashMap trials were");
            Log.log("!! skipped, and every measurement below this line is running on a");
            Log.log("!! machine with %d stuck threads on it. Re-run for clean numbers.",
                    THREADS);
        }
    }

    /**
     * Fills a map with {@link #EXPECTED} keys that no two threads share, and
     * reports what happened. There are three distinct outcomes and you will see
     * all of them if you run this enough times, which is the point: "not
     * thread-safe" is not one failure, it is a family.
     */
    private static String fillDistinctKeys(Map<String, Integer> map) {
        try {
            boolean finished = Stress.run(THREADS, KEYS_PER_THREAD, i -> {
                String key = Thread.currentThread().getName() + "-" + i;
                map.put(key, i);
            }, 8);
            if (!finished) {
                return "SPUN — a worker never came back out of put()";
            }
            int size = map.size();
            return String.format("%,7d (lost %,6d = %4.1f%%)",
                    size, EXPECTED - size, 100.0 * (EXPECTED - size) / EXPECTED);
        } catch (IllegalStateException e) {
            // Stress wraps the first worker exception. A ClassCastException of
            // Node to TreeNode means the bin's structure itself is inconsistent:
            // the map decided this bin was treeified and it is not.
            Throwable cause = e.getCause() == null ? e : e.getCause();
            return "THREW " + cause.getClass().getSimpleName() + " — the bin is structurally corrupt";
        }
    }

    // ── 2. size() belongs to no single instant ─────────────────────────────
    private static void sizeIsAnEstimate() throws InterruptedException {
        Log.section("size() ON A BUSY ConcurrentHashMap BELONGS TO NO SINGLE INSTANT");

        ConcurrentHashMap<Integer, Integer> map = new ConcurrentHashMap<>();
        AtomicLong completedPuts = new AtomicLong();
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(700);
        Thread[] writers = startWriters(map, completedPuts, deadline, 6);

        // Ordering matters and is the honest part of this measurement. We read
        // the ground truth FIRST: every put it counts had already returned, so
        // its increment is already in some counter cell. size() is called
        // afterwards, so an exact size() can only be >= truth. Any shortfall is
        // size()'s own non-atomic sum, not our sampling window.
        long worstShortfall = 0;
        long worstSurplus = 0;
        long samples = 0;
        while (System.nanoTime() < deadline) {
            long truth = completedPuts.get();
            int reported = map.size();
            worstShortfall = Math.max(worstShortfall, truth - reported);
            worstSurplus = Math.max(worstSurplus, reported - truth);
            samples++;
        }
        joinAll(writers);

        Log.log("sampled %,d times while 6 writers hammered the map", samples);
        Log.log("worst shortfall (size() BELOW puts already returned): %,d", worstShortfall);
        Log.log("worst surplus   (size() ABOVE them — puts that landed mid-sum): %,d", worstSurplus);
        Log.log("final size() once quiescent: %,d, against %,d puts — exact",
                map.size(), completedPuts.get());
        Log.log("The surplus is the interesting column: size() sums striped cells");
        Log.log("one at a time, so it happily includes puts that completed after the");
        Log.log("sum began. It is a recent plausible number, not a reading at time T.");
    }

    // ── 3. The reason that caveat matters: it is D8 again ──────────────────
    private static void checkThenActOnSize() {
        Log.section("WHY IT MATTERS — `if (map.size() < CAP) map.put(...)` IS D8");

        int cap = 1_000;
        for (int trial = 1; trial <= 5; trial++) {
            ConcurrentHashMap<String, Integer> map = new ConcurrentHashMap<>();
            Stress.run(8, 400, i -> {
                String key = Thread.currentThread().getName() + "-" + i;
                if (map.size() < cap) {       // CHECK — true for many threads at once
                    map.put(key, i);          // ACT   — all of them insert
                }
            });
            Log.log("trial %d: cap %,d, final size %,d — overshot by %,d",
                    trial, cap, map.size(), map.size() - cap);
        }
        Log.log("Every individual call here is atomic and thread-safe. The bug is in");
        Log.log("the GAP between two of them — precisely D8's overselling inventory,");
        Log.log("and precisely Ex6's cache stampede. Third outfit, same bug.");
        Log.log("A thread-safe collection makes each operation atomic. It cannot make");
        Log.log("your SEQUENCE of operations atomic; only you can decide that, with");
        Log.log("compute/merge (D17) or a lock.");
    }

    // ── 4. Fail-fast vs weakly consistent ──────────────────────────────────
    private static void iteratorSemantics() throws InterruptedException {
        Log.section("ITERATORS — fail-fast vs weakly consistent");

        Log.log("HashMap:           %s", iterateWhileWriting(new HashMap<>()));
        Log.log("ConcurrentHashMap: %s", iterateWhileWriting(new ConcurrentHashMap<>()));
        Log.log("Fail-fast is a bug detector, not a safety mechanism: it is best");
        Log.log("effort, it is allowed to miss, and when it does fire the damage is");
        Log.log("already done. Weakly consistent means the walk never throws and");
        Log.log("never blocks — and never claims to be a snapshot of one instant.");
    }

    private static String iterateWhileWriting(Map<Integer, Integer> map) throws InterruptedException {
        for (int i = 0; i < 2_000; i++) {
            map.put(i, i);
        }
        final boolean[] writerDone = {false};
        Thread writer = new Thread(() -> {
            for (int i = 2_000; i < 200_000; i++) {
                map.put(i, i);
            }
            writerDone[0] = true;
        }, "iter-writer");
        writer.setDaemon(true);
        writer.start();

        try {
            long walked = 0;
            int disagreements = 0;
            for (int pass = 0; pass < 40 && !writerDone[0]; pass++) {
                int reportedSize = map.size();
                long counted = 0;
                Iterator<Integer> it = map.keySet().iterator();
                while (it.hasNext()) {
                    it.next();
                    counted++;
                }
                walked += counted;
                if (counted != reportedSize) {
                    disagreements++;
                }
            }
            writer.join();
            return String.format(
                    "walked %,d keys, threw nothing; size() disagreed with the iterator's own count in %d of the passes",
                    walked, disagreements);
        } catch (ConcurrentModificationException e) {
            writer.join();
            return "ConcurrentModificationException — fail-fast noticed modCount move";
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    private static Thread[] startWriters(Map<Integer, Integer> map, AtomicLong completedPuts,
                                         long deadline, int count) {
        Thread[] writers = new Thread[count];
        for (int w = 0; w < count; w++) {
            final int id = w;
            writers[w] = new Thread(() -> {
                int i = 0;
                while (System.nanoTime() < deadline) {
                    map.put(id * 10_000_000 + i++, 1);
                    completedPuts.incrementAndGet();   // ground truth: this put has RETURNED
                }
            }, "writer-" + w);
            writers[w].setDaemon(true);
            writers[w].start();
        }
        return writers;
    }

    private static void joinAll(Thread[] threads) throws InterruptedException {
        for (Thread thread : threads) {
            thread.join();
        }
    }

    private D16_HashMapCorruption() {
    }
}
