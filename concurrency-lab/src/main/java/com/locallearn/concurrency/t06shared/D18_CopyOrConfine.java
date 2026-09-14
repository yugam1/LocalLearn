package com.locallearn.concurrency.t06shared;

import com.locallearn.concurrency.support.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEMO 18 — Two ways to share nothing: <b>copy it</b>, or <b>confine it</b>.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t06shared.D18_CopyOrConfine}
 *
 * <p>Everything in topics 3 and 4 answered "two threads touch the same mutable
 * state" with coordination: make the operation atomic, or exclude one thread
 * while the other works. There are two other answers, and neither involves a
 * lock on the read path at all:
 *
 * <ul>
 *   <li>{@link CopyOnWriteArrayList} — <b>copy it.</b> Readers walk an immutable
 *       array that nobody will ever modify, so reads need no lock, no volatile
 *       read per element, nothing. Writers take a lock, clone the entire array,
 *       modify the clone, and publish it to the {@code volatile} array field.
 *       Every write costs O(n).</li>
 *   <li>{@link ThreadLocal} — <b>confine it.</b> The value is not shared, so
 *       there is nothing to coordinate. Thread confinement is a real concurrency
 *       strategy and it is the cheapest one available, right up until the thread
 *       is not yours.</li>
 * </ul>
 *
 * <h2>Part A — the copy has a bill, and it is quadratic</h2>
 * "Reads are lock-free" is the half of the sentence people repeat. The other
 * half is that building a list of n elements one {@code add} at a time copies
 * 1 + 2 + … + n elements, which is O(n²) total. This demo measures it: the
 * per-element cost is not constant, it grows with the list, and by 16,000
 * elements the same loop that an {@code ArrayList} finishes in single-digit
 * milliseconds takes hundreds.
 *
 * <p>So the real decision rule is not "reads dominate" — that is the same
 * mistake D12 corrected for {@code ReadWriteLock}. It is <b>the list is small,
 * and writes are genuinely rare events rather than merely a minority of
 * operations</b>. A listener list is the canonical fit: a few dozen entries,
 * written at startup or when a module registers, read on every event. A cache,
 * a session set, or anything accumulating per-request is a catastrophe.
 *
 * <h2>Part B — confinement is a property of the thread, not of your code</h2>
 * A {@code ThreadLocal} is not a magic per-call variable. It is a
 * {@code ThreadLocalMap} hanging off {@link Thread} itself, keyed by the
 * {@code ThreadLocal} object. The value lives exactly as long as the thread
 * does.
 *
 * <p>On a pooled thread that is a bug waiting to happen, because the pool
 * deliberately outlives your request. Task A sets a value, forgets to clear it,
 * and returns the thread to the pool with the value still attached. Task B —
 * a different user, a different tenant, a different request id — picks up that
 * thread and reads task A's value. Nothing throws. This demo shows it.
 *
 * <p>That is exactly the MDC hygiene rule from
 * {@code ../01-foundations/07-logging-mdc-correlation-ids.md}: MDC is a
 * {@code ThreadLocal} map, and {@code MDC.clear()} in a {@code finally} is
 * non-negotiable for precisely this reason. The correlation id bleeding into the
 * next request's log lines is the mild version; the same mechanism carrying a
 * tenant id or a security principal is the version that becomes an incident.
 *
 * <p><b>And {@code set(null)} is not {@code remove()}.</b> Setting null leaves
 * the entry in the map with a null value — the slot, and the key's weak
 * reference, are still there. This demo proves it without reflection: a
 * {@code ThreadLocal} with an {@code initialValue()} returns null after
 * {@code set(null)} (the entry exists, so no initial value is computed) and
 * returns a freshly computed value after {@code remove()} (the entry is gone).
 * Only one of those two calls actually released anything.
 */
public final class D18_CopyOrConfine {

    private static final int OPS_PER_THREAD = 200_000;

    public static void main(String[] args) throws Exception {
        copyOnWriteWriteCost();
        copyOnWriteReadBenchmark();
        copyOnWriteSnapshotSemantics();
        threadLocalPoolLeak();
        setNullIsNotRemove();

        Log.takeaway("""
                Copy and confine are both ways of not sharing, and both send you a
                bill later.

                CopyOnWriteArrayList charges O(n) per write, so building it is O(n^2)
                — measured above, and the per-element cost visibly grows with the
                list. It is right for a small list written at startup and read on
                every event (listeners, handler chains, feature flags) and wrong for
                anything that accumulates. Note the read benchmark: its advantage is
                largest with no writes at all and shrinks as writes arrive, so "reads
                dominate" is not the rule — "writes are rare EVENTS" is, and a list
                that grows per request has no rare writes at all.

                ThreadLocal charges you the pool. Confinement is a property of the
                thread, and a pooled thread is not yours — it outlives your request
                by design and carries whatever you left on it to the next one. The
                rule is set in a try, remove() in the finally, every time. And
                remove(), never set(null): set(null) leaves the entry in place, as
                the initialValue test above demonstrates.""");
    }

    // ══════════════════════════════════════ PART A — COPY ═══════════════════

    /** Building a CopyOnWriteArrayList one add() at a time is O(n^2). */
    private static void copyOnWriteWriteCost() {
        Log.section("COPY-ON-WRITE — the cost of one write is the size of the list");

        // Warm up first. Without this the 1,000-element row measures the JIT
        // compiling ArrayList.add, not ArrayList.add, and the table comes out
        // non-monotonic in a way that looks like a finding and is not.
        for (int warmup = 0; warmup < 3; warmup++) {
            timeAdds(new ArrayList<>(), 4_000);
            timeAdds(new CopyOnWriteArrayList<>(), 4_000);
        }

        Log.log("%-10s %14s %14s %18s", "elements", "ArrayList", "COW list", "COW ns/element");
        // Starting at 4,000: below that the whole loop finishes in a millisecond
        // or two and the per-element figure is mostly timer resolution and
        // allocation jitter, which reads as a finding and is not one.
        for (int n : new int[]{4_000, 8_000, 16_000, 32_000, 64_000}) {
            long plain = bestOfThree(() -> timeAdds(new ArrayList<>(), n));
            long cow = bestOfThree(() -> timeAdds(new CopyOnWriteArrayList<>(), n));
            Log.log("%,10d %,11d ms %,11d ms %,15d ns",
                    n, plain / 1_000_000, cow / 1_000_000, cow / n);
        }
        Log.log("Read the last column, not the middle one. ArrayList's amortised");
        Log.log("cost per add is flat; the COW list's grows in step with the list,");
        Log.log("because every single add clones the whole backing array. Doubling");
        Log.log("the element count roughly QUADRUPLES the total time — that is what");
        Log.log("O(n^2) looks like when you meet it in a profiler.");
    }

    /** @return nanoseconds to add {@code n} elements one at a time. */
    private static long timeAdds(List<String> list, int n) {
        long start = System.nanoTime();
        for (int i = 0; i < n; i++) {
            list.add("element-" + i);
        }
        return System.nanoTime() - start;
    }

    /** Where the crossover actually is, as a function of write ratio. */
    private static void copyOnWriteReadBenchmark() throws InterruptedException {
        Log.section("COPY-ON-WRITE — 8 threads, 1,000-element list, "
                + String.format("%,d", OPS_PER_THREAD) + " ops each");
        Log.log("Warming up (the first numbers off a cold JIT are meaningless)...");
        for (int warmup = 0; warmup < 2; warmup++) {
            mixedWorkload(Collections.synchronizedList(seed(new ArrayList<>())), 0.01);
            mixedWorkload(seed(new CopyOnWriteArrayList<>()), 0.01);
        }

        Log.log("%-14s %16s %16s %10s", "write ratio", "synchronizedList", "COW list", "speedup");
        for (double writeRatio : new double[]{0.0, 0.001, 0.01, 0.05, 0.10}) {
            long synced = bestOfThreeInterruptibly(
                    () -> mixedWorkload(Collections.synchronizedList(seed(new ArrayList<>())), writeRatio));
            long cow = bestOfThreeInterruptibly(
                    () -> mixedWorkload(seed(new CopyOnWriteArrayList<>()), writeRatio));
            Log.log("%12.1f%% %,13d ms %,13d ms %9.2fx",
                    writeRatio * 100, synced / 1_000_000, cow / 1_000_000, (double) synced / cow);
        }
        Log.log("Each cell is the best of three runs after a warm-up, because a");
        Log.log("single timing here is mostly a measurement of what else the machine");
        Log.log("was doing. speedup > 1 means the COW list wins.");
        Log.log("It wins hugely with no writes at all — readers take no lock and");
        Log.log("contend on nothing, while every synchronizedList.get() acquires a");
        Log.log("monitor that all 8 threads are fighting over. The advantage then");
        Log.log("falls away as writes become a real fraction of traffic: each write");
        Log.log("clones 1,000 elements AND replaces the array every reader was");
        Log.log("happily sharing.");
    }

    private static long bestOfThree(java.util.function.LongSupplier run) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            best = Math.min(best, run.getAsLong());
        }
        return best;
    }

    private static long bestOfThreeInterruptibly(InterruptibleTiming run) throws InterruptedException {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < 3; i++) {
            best = Math.min(best, run.time());
        }
        return best;
    }

    @FunctionalInterface
    private interface InterruptibleTiming {
        long time() throws InterruptedException;
    }

    private static List<String> seed(List<String> list) {
        for (int i = 0; i < 1_000; i++) {
            list.add("element-" + i);
        }
        return list;
    }

    private static long mixedWorkload(List<String> list, double writeRatio) throws InterruptedException {
        int threads = 8;
        int opsPerThread = OPS_PER_THREAD;
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);

        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    gate.await();
                    ThreadLocalRandom random = ThreadLocalRandom.current();
                    int sink = 0;
                    for (int i = 0; i < opsPerThread; i++) {
                        int index = random.nextInt(1_000);
                        if (random.nextDouble() < writeRatio) {
                            list.set(index, "rewritten-" + i);
                        } else {
                            sink += list.get(index).length();
                        }
                    }
                    if (sink == Integer.MIN_VALUE) {
                        System.out.print("");     // stop the JIT eliding the reads
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "cow-worker-" + t);
            worker.setDaemon(true);
            worker.start();
        }

        long start = System.nanoTime();
        gate.countDown();
        done.await();
        return System.nanoTime() - start;
    }

    /** A COW iterator is a true snapshot — which is a feature and a surprise. */
    private static void copyOnWriteSnapshotSemantics() {
        Log.section("COPY-ON-WRITE — the iterator is a SNAPSHOT, not a weak view");

        CopyOnWriteArrayList<String> listeners = new CopyOnWriteArrayList<>(
                List.of("audit", "metrics", "email"));

        Iterator<String> iterator = listeners.iterator();   // snapshot taken HERE
        listeners.add("slack");
        listeners.remove("email");

        List<String> seen = new ArrayList<>();
        while (iterator.hasNext()) {
            seen.add(iterator.next());
        }
        Log.log("list is now      %s", listeners);
        Log.log("the iterator saw %s   (the array as it was when iterator() was called)", seen);

        try {
            listeners.iterator().remove();
            Log.log("iterator.remove() succeeded — unexpected");
        } catch (UnsupportedOperationException e) {
            Log.log("iterator.remove() throws UnsupportedOperationException — of course:");
            Log.log("  the array it is walking is immutable and is not the live one.");
        }
        Log.log("Contrast with ConcurrentHashMap (D16), which is WEAKLY CONSISTENT:");
        Log.log("it may or may not show you concurrent writes. COW is stronger and");
        Log.log("more predictable — a genuine point-in-time snapshot — and you pay");
        Log.log("for it with the copy. This is exactly what you want for dispatching");
        Log.log("an event to listeners: a listener registering mid-dispatch cannot");
        Log.log("corrupt the walk, and cannot half-receive the event either.");
    }

    // ══════════════════════════════════ PART B — CONFINE ════════════════════

    /** The value outlives the request, because the thread does. */
    private static void threadLocalPoolLeak() throws Exception {
        Log.section("THREAD-LOCAL — a pooled thread is not yours");

        ThreadLocal<String> tenant = new ThreadLocal<>();
        // ONE thread, so the second request is guaranteed to land on the thread
        // the first one used. A real pool does this whenever it is not saturated,
        // which is most of the time — the bug is just less reproducible there.
        ExecutorService pool = Executors.newFixedThreadPool(1, r -> {
            Thread t = new Thread(r, "pool-worker");
            t.setDaemon(true);
            return t;
        });

        Log.log("--- leaky version: set(), no remove() ---");
        pool.submit(() -> {
            tenant.set("acme-corp");                       // request 1 binds its tenant
            Log.log("request 1 (tenant acme-corp) ran on %s and did NOT clean up",
                    Thread.currentThread().getName());
        }).get();
        pool.submit(() -> {
            Log.log("request 2 (no tenant of its own) ran on %s and sees tenant = %s",
                    Thread.currentThread().getName(), tenant.get());
        }).get();

        Log.log("--- correct version: remove() in a finally ---");
        pool.submit(() -> {
            try {
                tenant.set("globex-inc");
                Log.log("request 3 (tenant globex-inc) ran on %s",
                        Thread.currentThread().getName());
                throw new IllegalStateException("request 3 fails, as requests do");
            } finally {
                tenant.remove();                            // every exit path
            }
        });
        TimeUnit.MILLISECONDS.sleep(100);
        pool.submit(() -> Log.log("request 4 (no tenant of its own) sees tenant = %s", tenant.get())).get();
        pool.shutdown();

        Log.log("Request 2 read a value it never set, belonging to a customer it");
        Log.log("has nothing to do with, and nothing anywhere threw. Request 3 threw");
        Log.log("and request 4 was still clean, because the remove() was in a");
        Log.log("finally — not after the work, where an exception would skip it.");
        Log.log("This IS the MDC rule from 01-foundations/07: MDC is a ThreadLocal");
        Log.log("map and MDC.clear() belongs in a finally for exactly this reason.");
        Log.log("Two more consequences worth carrying: a ThreadLocal value is");
        Log.log("invisible to any thread you hand work to (so @Async and");
        Log.log("CompletableFuture lose it), and on a pool thread that never dies it");
        Log.log("is also a memory leak — the value is strongly referenced from the");
        Log.log("thread for as long as the pool lives.");
    }

    /** {@code set(null)} leaves the entry behind; only {@code remove()} clears it. */
    private static void setNullIsNotRemove() {
        Log.section("THREAD-LOCAL — set(null) is NOT remove()");

        AtomicInteger initialValuesComputed = new AtomicInteger();
        ThreadLocal<String> withDefault = ThreadLocal.withInitial(
                () -> "fresh-" + initialValuesComputed.incrementAndGet());

        Log.log("first get()            -> %s   (entry created, initialValue ran)", withDefault.get());
        withDefault.set("explicit");
        Log.log("after set(\"explicit\")  -> %s", withDefault.get());

        withDefault.set(null);
        Log.log("after set(null)        -> %s   (initialValue did NOT run: the entry",
                withDefault.get());
        Log.log("                                is still in the map, holding null)");

        withDefault.remove();
        Log.log("after remove()         -> %s   (initialValue ran again: the entry",
                withDefault.get());
        Log.log("                                was genuinely gone)");

        Log.log("initialValue() ran %d times in total.", initialValuesComputed.get());
        Log.log("That difference is the whole proof, and it needs no reflection:");
        Log.log("set(null) nulls the VALUE and leaves the slot and its key reference");
        Log.log("in the thread's map. On a pool thread that map is never collected,");
        Log.log("so the slot is never reclaimed. remove() deletes the entry.");
    }

    private D18_CopyOrConfine() {
    }
}
