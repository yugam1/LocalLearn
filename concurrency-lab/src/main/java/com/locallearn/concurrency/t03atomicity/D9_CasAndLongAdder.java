package com.locallearn.concurrency.t03atomicity;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * DEMO 9 — How CAS actually works, what it costs under contention, and why
 * {@link LongAdder} exists.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t03atomicity.D9_CasAndLongAdder}
 *
 * <h2>Compare-And-Swap in one sentence</h2>
 * {@code compareAndSet(expected, next)} atomically says: "if this field is
 * still {@code expected}, set it to {@code next} and tell me true; otherwise
 * change nothing and tell me false." One CPU instruction
 * ({@code lock cmpxchg} on x86, {@code LDREX/STREX} on ARM). No OS involvement,
 * no thread ever parks.
 *
 * <h2>Optimistic vs pessimistic — the same split as in your database</h2>
 * <table>
 *   <caption>Two strategies</caption>
 *   <tr><th></th><th>Lock (pessimistic)</th><th>CAS (optimistic)</th></tr>
 *   <tr><td>Assumes</td><td>conflict is likely</td><td>conflict is rare</td></tr>
 *   <tr><td>On conflict</td><td>park the thread (OS context switch, ~1-10µs)</td><td>retry the loop (nanoseconds)</td></tr>
 *   <tr><td>Fails badly when</td><td>contention is low (pays for nothing)</td><td>contention is high (livelock-ish retry storm)</td></tr>
 *   <tr><td>Deadlock possible</td><td>yes</td><td>no — nothing is held</td></tr>
 * </table>
 *
 * This is exactly {@code @Version} optimistic locking vs
 * {@code PESSIMISTIC_WRITE} in docs/phase1_task5.md. Same trade-off, different
 * layer.
 *
 * <h2>The ABA problem</h2>
 * CAS compares <em>values</em>, not history. If the value goes
 * A → B → A while your thread was preparing, your {@code compareAndSet(A, ...)}
 * succeeds, even though the world changed underneath you. Harmless for a
 * counter; a genuine correctness bug for a lock-free stack, where the node you
 * pop may have been freed and recycled. The fix is
 * {@link java.util.concurrent.atomic.AtomicStampedReference}, which CASes a
 * (value, version) pair — and a database {@code @Version} column is precisely
 * the same trick.
 *
 * <h2>Why LongAdder beats AtomicLong under contention</h2>
 * {@code AtomicLong} has all threads CAS <b>one</b> memory word. Under 8+ hot
 * threads, most CAS attempts fail and retry, and the cache line for that word
 * ping-pongs between cores. {@code LongAdder} keeps an array of per-thread
 * cells, each padded onto its own cache line; threads increment different
 * cells and never contend. {@code sum()} adds them up on demand.
 *
 * <p>Trade-off: writes scale nearly linearly, but {@code sum()} is O(cells)
 * and is not an atomic snapshot. Perfect for metrics counters — which is why
 * Micrometer's {@code Counter} uses one (docs/phase5_tasks24_to_27.md).
 * Wrong when you need {@code getAndIncrement}'s returned value, such as for
 * generating IDs.
 */
public final class D9_CasAndLongAdder {

    public static void main(String[] args) {
        showCasMechanics();
        showAbaProblem();
        benchmarkUnderContention();
    }

    private static void showCasMechanics() {
        Log.section("CAS mechanics — succeed only if nothing moved");

        AtomicInteger value = new AtomicInteger(10);
        Log.log("value = %d", value.get());
        Log.log("compareAndSet(10, 20) → %s, value = %d", value.compareAndSet(10, 20), value.get());
        Log.log("compareAndSet(10, 30) → %s, value = %d  (expected 10, found 20 — refused)",
                value.compareAndSet(10, 30), value.get());

        // Every Atomic* mutator is this loop underneath. incrementAndGet() is
        // literally: read, compute, CAS, repeat until it sticks.
        Log.log("hand-rolled incrementAndGet via CAS → %d", handRolledIncrement(value));
    }

    private static int handRolledIncrement(AtomicInteger counter) {
        while (true) {
            int current = counter.get();
            int next = current + 1;
            if (counter.compareAndSet(current, next)) {
                return next;
            }
            // Lost the race — somebody else wrote first. Re-read and retry.
            // Note: this is lock-FREE (the system always progresses) but not
            // wait-free (this particular thread could in theory retry forever).
        }
    }

    private static void showAbaProblem() {
        Log.section("The ABA problem — CAS compares values, not history");

        AtomicInteger shared = new AtomicInteger(100);   // A

        int readValue = shared.get();                    // our thread reads A = 100
        Log.log("thread 1 reads %d and starts computing...", readValue);

        shared.set(50);                                  // another thread: A → B
        shared.set(100);                                 // and back:      B → A
        Log.log("meanwhile another thread did 100 → 50 → 100");

        boolean succeeded = shared.compareAndSet(readValue, 999);
        Log.log("thread 1's compareAndSet(%d, 999) → %s — it succeeded even though "
                + "the value was changed twice in between", readValue, succeeded);
        Log.log("fix: AtomicStampedReference, which CASes (value, stamp) together — "
                + "the same idea as a @Version column in JPA");
    }

    private static void benchmarkUnderContention() {
        Log.section("AtomicLong vs LongAdder vs synchronized, under real contention");

        int[] threadCounts = {1, 2, 4, 8, 16};
        int incrementsPerThread = 500_000;

        Log.log("%-8s %14s %14s %14s", "threads", "AtomicLong", "LongAdder", "synchronized");

        for (int threads : threadCounts) {
            AtomicLong atomic = new AtomicLong();
            LongAdder adder = new LongAdder();
            long[] guarded = new long[1];
            Object lock = new Object();

            long atomicMs = time(() -> Stress.run(threads, incrementsPerThread, i -> atomic.incrementAndGet()));
            long adderMs = time(() -> Stress.run(threads, incrementsPerThread, i -> adder.increment()));
            long syncMs = time(() -> Stress.run(threads, incrementsPerThread, i -> {
                synchronized (lock) {
                    guarded[0]++;
                }
            }));

            long expected = (long) threads * incrementsPerThread;
            if (atomic.get() != expected || adder.sum() != expected || guarded[0] != expected) {
                throw new AssertionError("a supposedly-correct counter lost updates");
            }

            Log.log("%-8d %11d ms %11d ms %11d ms", threads, atomicMs, adderMs, syncMs);
        }

        Log.takeaway("""
                Read the columns down, not across. At 1 thread AtomicLong wins —
                LongAdder's extra indirection is pure overhead with no contention.
                As threads climb, AtomicLong flattens or regresses (every core is
                CASing the same cache line) while LongAdder stays close to flat.

                Rule: LongAdder for write-heavy metrics you read rarely.
                AtomicLong when you need the value each operation returns.
                A lock when the invariant spans more than one variable.""");
    }

    private static long time(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1_000_000L;
    }
}
