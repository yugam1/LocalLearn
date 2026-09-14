package com.locallearn.concurrency.t02visibility;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * DEMO 6 — Safe publication: how an object gets from the thread that built it
 * to the threads that use it, without them seeing it half-built.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t02visibility.D6_SafePublication}
 *
 * <h2>The theory: why {@code holder = new Config(42)} is not atomic</h2>
 * It is three steps:
 * <ol>
 *   <li>allocate memory (all fields are still zero/null)</li>
 *   <li>run the constructor, writing {@code value = 42}</li>
 *   <li>publish the reference into {@code holder}</li>
 * </ol>
 * Steps 2 and 3 have no dependency the compiler or CPU must respect, so on a
 * weakly-ordered machine they may be <b>reordered</b>. Another thread can then
 * observe {@code holder != null} while {@code holder.value} is still 0.
 *
 * <p>This is the famous <b>broken double-checked locking</b> bug, and the reason
 * the DCL singleton idiom is only correct when the instance field is
 * {@code volatile}.
 *
 * <h2>Two ways to publish safely</h2>
 * <ul>
 *   <li><b>{@code volatile} on the reference.</b> The volatile write is a
 *       release: everything the constructor wrote happens-before any thread's
 *       volatile read of that reference. Reordering step 3 before step 2
 *       becomes illegal.</li>
 *   <li><b>{@code final} fields.</b> The JMM gives {@code final} a special
 *       guarantee (JLS 17.5): if the reference is published <em>after</em> the
 *       constructor returns, any thread that sees the reference is guaranteed
 *       to see correctly-initialised final fields, with no synchronisation at
 *       all. This is why immutable objects are the easy path to thread safety,
 *       and why {@code String} can be shared freely.</li>
 * </ul>
 * Also safe: publishing through a {@code static} initialiser, a
 * {@code ConcurrentMap}, an {@code AtomicReference}, or a lock held by both
 * the writer and every reader.
 *
 * <h2>PART 1 will probably report zero tears. Read this before concluding anything.</h2>
 * x86 and x86-64 are <b>TSO</b> (Total Store Order): the hardware never
 * reorders one store past another store. So step 3 cannot overtake step 2 at
 * the CPU level, and HotSpot's JIT does not reorder them here either. On this
 * machine the broken code is, in practice, accidentally correct.
 *
 * <p>That is exactly the trap. The same class deployed to an ARM server
 * (Graviton, Ampere, an Apple-silicon dev laptop) runs on a weakly-ordered CPU
 * where store-store reordering is permitted and this bug is observable. "I ran
 * it a million times and it was fine" is a statement about your CPU, not about
 * your program. The JMM is the contract; the hardware is just today's
 * implementation of it.
 *
 * <p><b>PART 2 shows a related bug that IS reproducible everywhere</b>, so you
 * can see unsafe sharing actually destroy data rather than take it on faith.
 */
public final class D6_SafePublication {

    private static final int TRIALS = 200_000;

    private static PlainConfig plain;
    private static volatile PlainConfig viaVolatile;
    private static FinalConfig viaFinal;

    private static volatile int epoch;
    private static volatile int donePublisher, doneReader;
    private static int observedValue;

    /** Mutable, non-final field: publication order is unconstrained by the JMM. */
    static final class PlainConfig {
        int value;                      // NOT final
        PlainConfig(int v) { this.value = v; }
    }

    /** Final field: the JMM freeze action at the end of the constructor protects it. */
    static final class FinalConfig {
        final int value;
        FinalConfig(int v) { this.value = v; }
    }

    public static void main(String[] args) {
        partOneRaceForTornPublication();
        partTwoUnsafeSharingDestroysData();
    }

    // ---------------------------------------------------------------- part 1

    private static void partOneRaceForTornPublication() {
        Log.section("PART 1 — racing a publisher against a reader for a torn object");

        int tornPlain = race(Mode.PLAIN);
        int tornVolatile = race(Mode.VOLATILE);
        int tornFinal = race(Mode.FINAL);

        Log.log("");
        Log.log("plain reference    : %,d torn observations", tornPlain);
        Log.log("volatile reference : %,d torn observations  (guaranteed 0 by the JMM)", tornVolatile);
        Log.log("final field        : %,d torn observations  (guaranteed 0 by JLS 17.5)", tornFinal);

        if (tornPlain == 0) {
            Log.takeaway("""
                    Zero tears on the plain reference — as expected on x86/x86-64,
                    which is Total Store Order and will not reorder store-with-store.
                    Your CPU papered over the bug.

                    Do not read that as "the code is fine". Deploy the same class
                    to ARM (Graviton, Ampere, an Apple-silicon laptop), where
                    store-store reordering IS permitted, and it becomes reachable.
                    You cannot test your way to memory-model correctness; you can
                    only establish a happens-before edge, or not.

                    The volatile and final rows are zero for a different and much
                    stronger reason: not "we didn't hit it", but "it cannot happen".""");
        } else {
            Log.takeaway("""
                    You caught %,d torn objects on the plain reference. Your CPU
                    permits store-store reordering, so the reader saw a non-null
                    pointer to an object whose constructor had not finished.
                    volatile and final make that unreachable.""", tornPlain);
        }
    }

    private enum Mode { PLAIN, VOLATILE, FINAL }

    /**
     * Publishes one object per trial and has the reader <b>spin until it sees a
     * non-null reference</b>, then immediately read the field.
     *
     * <p>The spin matters. An earlier version of this demo had the reader take a
     * single sample per trial, and it read null 98% of the time — it simply got
     * there before the publisher. A sample that misses the publication entirely
     * tells you nothing. Spinning parks the reader right at the instant the
     * reference lands, which is the only moment a tear could be visible.
     *
     * @return how many times the reader saw a non-null reference whose field was
     *         still 0 — i.e. a partially-constructed object.
     */
    private static int race(Mode mode) {
        epoch = 0;
        donePublisher = 0;
        doneReader = 0;

        Thread publisher = new Thread(() -> {
            for (int trial = 1; trial <= TRIALS; trial++) {
                while (epoch < trial) {
                    Thread.onSpinWait();
                }
                switch (mode) {
                    case PLAIN -> plain = new PlainConfig(42);
                    case VOLATILE -> viaVolatile = new PlainConfig(42);
                    case FINAL -> viaFinal = new FinalConfig(42);
                }
                donePublisher = trial;
            }
        }, "publisher-" + mode);

        Thread reader = new Thread(() -> {
            for (int trial = 1; trial <= TRIALS; trial++) {
                while (epoch < trial) {
                    Thread.onSpinWait();
                }
                int value = -1;
                // Spin waiting for the reference to appear, then read the field
                // the instant it does. The exit condition is the publisher's own
                // completion signal rather than a fixed spin budget: a fixed
                // budget burns millions of wasted iterations on every trial the
                // reader wins, which made this demo take minutes instead of
                // seconds.
                do {
                    switch (mode) {
                        case PLAIN -> {
                            PlainConfig p = plain;
                            if (p != null) {
                                value = p.value;
                            }
                        }
                        case VOLATILE -> {
                            PlainConfig v = viaVolatile;
                            if (v != null) {
                                value = v.value;
                            }
                        }
                        case FINAL -> {
                            FinalConfig f = viaFinal;
                            if (f != null) {
                                value = f.value;
                            }
                        }
                    }
                } while (value == -1 && donePublisher < trial);
                observedValue = value;
                doneReader = trial;
            }
        }, "reader-" + mode);

        publisher.setDaemon(true);
        reader.setDaemon(true);
        publisher.start();
        reader.start();

        int torn = 0;
        int missed = 0;
        for (int trial = 1; trial <= TRIALS; trial++) {
            plain = null;
            viaVolatile = null;
            viaFinal = null;
            observedValue = -1;

            epoch = trial;
            while (donePublisher < trial || doneReader < trial) {
                Thread.onSpinWait();
            }

            if (observedValue == 0) {
                torn++;               // non-null reference, uninitialised field
            } else if (observedValue == -1) {
                missed++;             // never saw the publication; sample wasted
            }
        }

        Log.log("%-9s → %,7d torn, %,7d samples missed the publication window",
                mode, torn, missed);
        return torn;
    }

    // ---------------------------------------------------------------- part 2

    /**
     * Unsafe <em>sharing</em> (as opposed to unsafe publication) of a mutable
     * object, which is reproducible on every CPU because it is a logic race,
     * not a memory-ordering one. Two threads resizing the same {@link HashMap}
     * interleave their bucket-array rewrites and simply lose entries.
     *
     * <p>Historically this could also spin forever: in Java 7 a concurrent
     * resize could build a circular linked list in a bucket, and a later
     * {@code get()} would loop at 100% CPU. Java 8 changed the resize to
     * preserve order, so today you "merely" get silent data loss — which is
     * arguably worse, because nothing alerts.
     */
    private static int key(int iteration) {
        return (int) (Thread.currentThread().threadId() * 1_000_000L + iteration);
    }

    private static void partTwoUnsafeSharingDestroysData() {
        Log.section("PART 2 — sharing a plain HashMap across threads (reproducible everywhere)");

        int threads = 8;
        int entriesPerThread = 20_000;
        int expected = threads * entriesPerThread;

        // Keys must be globally unique or the counts mean nothing. threadId()
        // is small and monotonic, so this cannot collide across threads.
        Map<Integer, Integer> unsafe = new HashMap<>();
        String unsafeOutcome;
        boolean finished;
        try {
            // Bounded, because this genuinely may never finish — see below.
            finished = Stress.run(threads, entriesPerThread, i -> unsafe.put(key(i), i), 10);
            unsafeOutcome = finished
                    ? "completed"
                    : "*** HUNG *** — workers still spinning inside HashMap.put after 10s";
        } catch (RuntimeException e) {
            finished = false;
            unsafeOutcome = "threw " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }

        Map<Integer, Integer> safe = new ConcurrentHashMap<>();
        Stress.run(threads, entriesPerThread, i -> safe.put(key(i), i));

        Log.log("expected entries      : %,d", expected);
        Log.log("plain HashMap         : %s", unsafeOutcome);
        if (finished) {
            int lost = expected - unsafe.size();
            Log.log("                        %,d entries (lost %,d = %.2f%%)",
                    unsafe.size(), lost, 100.0 * lost / expected);
        }
        Log.log("ConcurrentHashMap     : %,d entries (lost %,d)",
                safe.size(), expected - safe.size());

        Log.takeaway("""
                Whichever outcome you got, note that neither is an error you
                could catch:

                  * LOST ENTRIES — no exception, no warning, no log line. Two
                    threads resized the bucket array simultaneously and
                    overwrote each other's work. Your map is simply smaller
                    than what you put in it.

                  * HUNG — concurrent structural modification spliced a bucket's
                    node chain into a cycle, and a thread is now walking it
                    forever at 100%% CPU. It ignores interrupt(), because there
                    is no blocking call for the interrupt to surface at. A
                    thread dump shows it RUNNABLE inside HashMap.put, which is
                    the classic fingerprint of this exact bug. (This was
                    notorious on Java 7; Java 8's order-preserving resize made
                    it rarer, not impossible.)

                The rule: a mutable object shared across threads needs a
                thread-safe type, confinement to a single thread, or a lock held
                by EVERY accessor — readers included. There is no fourth option,
                and "we only write from one thread" is not one of them unless
                the readers synchronise too.""");
    }
}
