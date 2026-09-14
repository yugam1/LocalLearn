package com.locallearn.concurrency.t02visibility;

import com.locallearn.concurrency.support.Log;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CyclicBarrier;

/**
 * DEMO 5 — Catch the JVM and CPU reordering your statements, producing a
 * result that is impossible under sequential reasoning.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t02visibility.D5_ReorderingWitness}
 *
 * <h2>The experiment (the classic "store buffer" / Dekker litmus test)</h2>
 * Two threads, two fields both starting at 0:
 * <pre>{@code
 * Thread A          Thread B
 * x = 1;            y = 1;
 * r1 = y;           r2 = x;
 * }</pre>
 *
 * Enumerate every interleaving of those four statements by hand. In each one,
 * at least one thread ran its store before the other's load, so you always get
 * {@code r1 == 1 || r2 == 1}. The outcome {@code r1 == 0 && r2 == 0} is
 * <b>unreachable</b> if the program is sequentially consistent.
 *
 * <p>Run this demo. You will see it happen — a handful of times per million
 * trials on x86, considerably more often on ARM (Apple Silicon).
 *
 * <h2>Why it is allowed</h2>
 * Two independent causes, and from here you cannot tell them apart:
 * <ul>
 *   <li><b>Compiler reordering.</b> {@code x = 1} and {@code r1 = y} touch
 *       different fields with no data dependency, so the JIT may emit them in
 *       either order.</li>
 *   <li><b>Store buffering in hardware.</b> Even x86 — otherwise strongly
 *       ordered — buffers stores and lets later loads bypass them. StoreLoad
 *       is precisely the one reordering x86 permits. The store to {@code x}
 *       still sits in A's store buffer while A's load of {@code y} has already
 *       executed.</li>
 * </ul>
 *
 * <h2>The point</h2>
 * "It works on my machine" is not evidence of correctness. The JMM defines what
 * is <em>guaranteed</em>; anything you did not establish with a happens-before
 * edge is up for grabs, and the hardware will eventually collect on that — on a
 * different CPU architecture, under production load, at 3am.
 *
 * <p>Fix: mark {@code x} and {@code y} {@code volatile}. A volatile store
 * followed by a volatile load emits a StoreLoad barrier (on x86, a locked
 * instruction or {@code mfence}), and the anomaly count drops to zero. The
 * fields are right there — add the keyword, rerun, compare.
 *
 * <h2>Note on the harness</h2>
 * The two worker threads are created <b>once</b> and re-synchronised each trial
 * with a <b>spin barrier</b> — a volatile epoch counter that everyone busy-waits
 * on. Two things this deliberately avoids:
 * <ul>
 *   <li><b>Creating threads per trial</b> would cost ~1ms each, capping us at a
 *       few thousand trials — nowhere near enough to observe an event this rare.</li>
 *   <li><b>A {@link java.util.concurrent.CyclicBarrier}</b> works, but it parks
 *       and unparks the threads at every rendezvous. That is a syscall, it
 *       pushes the threads off-core, and the resulting memory barriers largely
 *       mask the very reordering we are hunting. Swapping a CyclicBarrier for
 *       this spin barrier took one run from 1 anomaly in 27s to dozens in a
 *       couple of seconds.</li>
 * </ul>
 * Busy-waiting burns three cores solid for the duration; that is the correct
 * trade here and completely wrong in production code. This reuse-plus-spin
 * shape is what the OpenJDK jcstress harness uses.
 */
public final class D5_ReorderingWitness {

    private static final int TRIALS = 1_000_000;

    // Add `volatile` to these two and rerun: anomalies drop to 0.
    private static int x, y;
    private static int r1, r2;

    /** Spin-barrier state. Volatile because the whole point is that these MUST be seen. */
    private static volatile int epoch;
    private static volatile int doneA, doneB;

    public static void main(String[] args) {
        Log.section("Hunting for r1 == 0 && r2 == 0 (sequentially impossible)");

        Thread a = new Thread(() -> {
            for (int trial = 1; trial <= TRIALS; trial++) {
                while (epoch < trial) {
                    Thread.onSpinWait();
                }
                x = 1;
                r1 = y;
                doneA = trial;
            }
        }, "writer-A");

        Thread b = new Thread(() -> {
            for (int trial = 1; trial <= TRIALS; trial++) {
                while (epoch < trial) {
                    Thread.onSpinWait();
                }
                y = 1;
                r2 = x;
                doneB = trial;
            }
        }, "writer-B");

        a.setDaemon(true);
        b.setDaemon(true);
        a.start();
        b.start();

        int anomalies = 0;
        long startNanos = System.nanoTime();

        for (int trial = 1; trial <= TRIALS; trial++) {
            x = 0;
            y = 0;
            r1 = -1;
            r2 = -1;

            epoch = trial;                                  // release both workers
            while (doneA < trial || doneB < trial) {        // wait for both
                Thread.onSpinWait();
            }

            if (r1 == 0 && r2 == 0) {
                if (anomalies < 5) {
                    Log.log("ANOMALY at trial %,d: r1=0, r2=0", trial);
                }
                anomalies++;
            }
        }

        double seconds = (System.nanoTime() - startNanos) / 1e9;
        Log.takeaway("""
                %,d anomalies in %,d trials (%.5f%%) in %.1fs.

                Every one of those is a reordering that is invisible in the
                source code — no interleaving of those four statements can
                produce it. If you got 0, rerun; the rate swings a lot with
                machine load and is much higher on ARM than x86.

                Then add `volatile` to x and y at the top of this file and
                confirm you can never get one again. That is the difference a
                StoreLoad barrier makes.""",
                anomalies, TRIALS, 100.0 * anomalies / TRIALS, seconds);
    }
}
