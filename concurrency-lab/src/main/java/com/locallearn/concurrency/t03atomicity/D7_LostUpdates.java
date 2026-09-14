package com.locallearn.concurrency.t03atomicity;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEMO 7 — {@code count++} is three operations, and {@code volatile} does not
 * save you.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t03atomicity.D7_LostUpdates}
 *
 * <h2>What {@code count++} compiles to</h2>
 * <pre>
 * getfield  count     // 1. READ  the current value
 * iconst_1
 * iadd                // 2. MODIFY (add one)
 * putfield  count     // 3. WRITE the result back
 * </pre>
 *
 * Two threads can both execute step 1 and read {@code 7}, both compute
 * {@code 8}, and both write {@code 8}. Two increments, one net effect — a
 * <b>lost update</b>. This is a <i>read-modify-write</i> race, and it is the
 * single most common concurrency bug in business code.
 *
 * <h2>Why volatile is not the fix — the key insight</h2>
 * {@code volatile} guarantees each individual read and each individual write is
 * fresh and ordered. It says nothing about the <em>gap between them</em>. The
 * two threads still both read 7; they just each read a very up-to-date 7.
 *
 * <p>Watch the numbers below: the volatile counter loses updates on the same
 * catastrophic scale as the plain one — typically 70-90% of them. Which of the
 * two loses more shifts from run to run, because all volatile changed was the
 * timing (it forces a real memory access each iteration, so the loop is slower
 * and the overlap window moves). The semantics are untouched: both threads
 * still read the same value and both still write it back.
 *
 * <h2>What actually works</h2>
 * <ul>
 *   <li>{@link AtomicInteger#incrementAndGet()} — a single CAS-backed hardware
 *       instruction ({@code lock xadd} on x86). Non-blocking, best under low
 *       to moderate contention.</li>
 *   <li>{@code synchronized} / {@code ReentrantLock} — makes the three steps
 *       one indivisible critical section. Necessary when you need to update
 *       <em>several</em> variables together (see D8).</li>
 *   <li>{@code LongAdder} — trades exact-read cost for far better write
 *       throughput under heavy contention (see D9).</li>
 * </ul>
 */
public final class D7_LostUpdates {

    private static final int THREADS = 8;
    private static final int INCREMENTS_PER_THREAD = 100_000;
    private static final int EXPECTED = THREADS * INCREMENTS_PER_THREAD;

    private static int plain;
    private static volatile int volatileCount;
    private static final AtomicInteger ATOMIC = new AtomicInteger();
    private static int guarded;
    private static final Object GUARD = new Object();

    public static void main(String[] args) {
        Log.section("%,d threads x %,d increments — expecting %,d each time"
                .formatted(THREADS, INCREMENTS_PER_THREAD, EXPECTED));

        report("plain int          ", time(() -> Stress.run(THREADS, INCREMENTS_PER_THREAD, i -> plain++)), plain);
        report("volatile int       ", time(() -> Stress.run(THREADS, INCREMENTS_PER_THREAD, i -> volatileCount++)), volatileCount);
        report("AtomicInteger      ", time(() -> Stress.run(THREADS, INCREMENTS_PER_THREAD, i -> ATOMIC.incrementAndGet())), ATOMIC.get());
        report("synchronized block ", time(() -> Stress.run(THREADS, INCREMENTS_PER_THREAD, i -> {
            synchronized (GUARD) {
                guarded++;
            }
        })), guarded);

        Log.takeaway("""
                The volatile row is the one to stare at. It loses updates on the
                same catastrophic scale as the plain row — which row loses more
                varies run to run and machine to machine, and that variance is
                itself the point: volatile changed the timing, not the semantics.
                It never gets close to correct, because it was never addressing
                this problem.

                volatile == visibility + ordering.
                atomicity of read-modify-write == Atomic* or a lock.
                They are different problems, and volatile only solves the first.""");
    }

    private static long time(Runnable r) {
        long t0 = System.nanoTime();
        r.run();
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static void report(String label, long millis, int actual) {
        long lost = EXPECTED - actual;
        Log.log("%s → %,10d  (lost %,7d = %5.2f%%)  in %,4d ms  %s",
                label, actual, lost, 100.0 * lost / EXPECTED, millis,
                lost == 0 ? "CORRECT" : "*** WRONG ***");
    }
}
