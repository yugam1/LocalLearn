package com.locallearn.concurrency.t02visibility;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.TimeUnit;

/**
 * DEMO 4 — The one-word bug: a stop flag without {@code volatile} can hang
 * forever, even though the writer definitely wrote it.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t02visibility.D4_StaleFlagHang}
 *
 * <h2>What you will see</h2>
 * The plain-field reader usually spins past the 3-second deadline and never
 * notices {@code stop = true}. The {@code volatile} reader stops in
 * microseconds. Same code, one keyword apart.
 *
 * <h2>Why — and it is not "CPU caches"</h2>
 * The folk explanation is "the value is stuck in the other core's cache".
 * That is mostly wrong: cache coherence (MESI) propagates writes in
 * nanoseconds on x86 and ARM alike. The real culprit is the <b>compiler</b>.
 *
 * <p>The JMM says: with no synchronisation between two threads, there is no
 * <i>happens-before</i> edge, so the JIT is free to assume nothing else mutates
 * the field. It then performs a completely legal optimisation called
 * <b>hoisting</b>:
 *
 * <pre>{@code
 * while (!stop) { n++; }          // what you wrote
 *
 * if (!stop) {                    // what C2 compiles it to
 *     while (true) { n++; }       // the read is hoisted out of the loop
 * }
 * }</pre>
 *
 * The field is never re-read, so no amount of cache coherence can help. This
 * is why the bug is all-or-nothing: it does not "eventually" notice.
 *
 * <h2>What volatile actually guarantees</h2>
 * <ol>
 *   <li><b>Atomicity of the access</b> — no torn reads (matters for
 *       {@code long}/{@code double} on 32-bit VMs).</li>
 *   <li><b>Visibility</b> — the read must actually happen, from memory, every
 *       time. Hoisting is forbidden.</li>
 *   <li><b>Ordering</b> — a volatile write <i>happens-before</i> every
 *       subsequent volatile read of the same field, and everything the writer
 *       did <em>before</em> that write is visible to the reader <em>after</em>
 *       it. This is the part people forget, and it is what makes the
 *       "publish with a volatile flag" idiom safe.</li>
 * </ol>
 *
 * <p><b>What volatile does NOT give you: atomicity of compound actions.</b>
 * {@code volatile int n; n++} is still a lost-update race — see
 * {@code t03atomicity.D7_LostUpdates}.
 */
public final class D4_StaleFlagHang {

    /** No volatile. The JIT may hoist reads of this out of the reader's loop. */
    private static boolean plainStop = false;

    /** Same field, one keyword different. */
    private static volatile boolean volatileStop = false;

    public static void main(String[] args) throws Exception {
        runTrial("PLAIN boolean", () -> {
            long n = 0;
            while (!plainStop) {
                n++;
            }
            return n;
        }, () -> plainStop = true);

        runTrial("VOLATILE boolean", () -> {
            long n = 0;
            while (!volatileStop) {
                n++;
            }
            return n;
        }, () -> volatileStop = true);

        Log.takeaway("""
                If the plain reader timed out, you just watched the JIT hoist a
                field read out of a loop. If it happened to stop, run again with
                -Xint (interpreter only) to see it stop every time, then without
                it: the bug appears exactly when C2 compiles the loop. That is
                the tell that this is a compiler-reordering bug, not a cache bug.""");
    }

    /**
     * Starts a spinning reader, waits 200ms so the JIT compiles the loop, flips
     * the flag, and reports whether the reader ever noticed.
     */
    private static void runTrial(String label, SpinLoop reader, Runnable writer)
            throws InterruptedException {
        Log.section(label);

        long[] iterations = new long[1];
        Thread readerThread = new Thread(() -> iterations[0] = reader.spin(), "reader");
        readerThread.setDaemon(true);       // so a hung reader cannot block JVM exit
        readerThread.start();

        Stress.sleep(200);                  // give C2 time to compile and hoist
        Log.log("writer sets stop = true now");
        writer.run();

        readerThread.join(TimeUnit.SECONDS.toMillis(3));

        if (readerThread.isAlive()) {
            Log.log("reader is STILL SPINNING after 3s — it never saw the write. State: %s",
                    readerThread.getState());
        } else {
            Log.log("reader exited after %,d iterations", iterations[0]);
        }
    }

    @FunctionalInterface
    private interface SpinLoop {
        long spin();
    }
}
