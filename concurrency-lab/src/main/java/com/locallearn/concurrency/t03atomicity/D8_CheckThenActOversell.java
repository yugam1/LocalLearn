package com.locallearn.concurrency.t03atomicity;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * DEMO 8 — Check-then-act: how a warehouse with 100 units sells 137 of them.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t03atomicity.D8_CheckThenActOversell}
 *
 * <h2>The shape of the bug</h2>
 * <pre>{@code
 * if (stock > 0) {     // CHECK  — true for thread A and thread B simultaneously
 *     stock--;         // ACT    — both decrement; stock goes to -1
 *     return true;     // both callers believe they reserved a unit
 * }
 * }</pre>
 *
 * The check and the act are individually fine. The bug lives in the gap: the
 * condition thread A verified is no longer true by the time A acts on it. This
 * is a <b>compound action</b>, and there is no amount of making the individual
 * steps atomic that fixes it.
 *
 * <p>This is not an academic example — it is overselling inventory, double
 * refunds, two users claiming the same username, and duplicate order numbers.
 * The order-service equivalent is in
 * {@code InventoryService.reserveStock}; its real-world fix is the optimistic
 * locking / {@code @Version} pattern from docs/phase1_task5.md, which is the
 * exact same compare-and-set idea pushed down into the database.
 *
 * <h2>Three fixes, and when each is right</h2>
 * <ol>
 *   <li><b>{@code AtomicInteger} with a CAS retry loop.</b> Correct and
 *       lock-free. Use when the state is a single variable.</li>
 *   <li><b>{@code synchronized} / {@code ReentrantLock}.</b> Correct and the
 *       only option when the invariant spans several fields (e.g. decrement
 *       stock AND append to a reservation list, together).</li>
 *   <li><b>Push it into the storage layer</b> — {@code UPDATE ... WHERE stock > 0},
 *       or a {@code @Version} column. Necessary once more than one JVM is
 *       involved, because in-process locks protect exactly one process.</li>
 * </ol>
 *
 * <p>Note that {@code AtomicInteger.decrementAndGet()} alone does <b>not</b>
 * fix this: it makes the decrement atomic but leaves the check outside it. The
 * demo shows that variant failing too, which is the subtle part worth seeing.
 */
public final class D8_CheckThenActOversell {

    private static final int INITIAL_STOCK = 100;
    private static final int SHOPPERS = 16;
    private static final int ATTEMPTS_EACH = 50;
    private static final int TRIALS = 500;

    public static void main(String[] args) {
        Log.section("%,d trials of: %,d shoppers x %,d attempts against %,d units of stock"
                .formatted(TRIALS, SHOPPERS, ATTEMPTS_EACH, INITIAL_STOCK));

        run("BROKEN  plain check-then-act", BrokenInventory::new);
        run("BROKEN  atomic decrement only", StillBrokenAtomicInventory::new);
        run("CORRECT CAS retry loop       ", CasInventory::new);
        run("CORRECT synchronized         ", SynchronizedInventory::new);

        Log.takeaway("""
                Row 2 is the instructive one. Making the decrement atomic feels
                like the fix and is not: the `if` is still outside the atomic
                region, so two threads still both pass it. An operation is only
                atomic if the ENTIRE invariant — check and act together — is
                inside one indivisible step.

                CAS loop, lock, or `UPDATE ... WHERE stock > 0`. Nothing else.

                Now compare the two BROKEN rows. The half-fix does not look
                harmless — it looks BETTER. It typically oversells in a few
                percent of trials instead of ten-plus, and by one or two units
                instead of dozens. That is the worst possible outcome for a bug:
                rare enough to survive your test suite, rare enough in production
                to look like a data-entry mistake, and permanent.

                "It happens less often now" is not a fix. Either the check and
                the act are inside one indivisible step, or they are not.""");
    }

    /**
     * Repeats the whole scenario {@link #TRIALS} times, because a race is
     * probabilistic: any single run of the broken code has a fair chance of
     * producing the right answer. Aggregating is how you tell "correct" apart
     * from "got lucky" — and it is exactly what the exercise tests do.
     */
    private static void run(String label, Supplier<Inventory> factory) {
        int badTrials = 0;
        int worstOversell = 0;
        int totalOversold = 0;

        for (int trial = 0; trial < TRIALS; trial++) {
            Inventory inventory = factory.get();
            AtomicInteger sold = new AtomicInteger();

            Stress.run(SHOPPERS, ATTEMPTS_EACH, i -> {
                if (inventory.reserveOne()) {
                    sold.incrementAndGet();
                }
            });

            int oversell = sold.get() - INITIAL_STOCK;
            if (oversell > 0 || inventory.remaining() < 0) {
                badTrials++;
                totalOversold += oversell;
                worstOversell = Math.max(worstOversell, oversell);
            }
        }

        if (badTrials == 0) {
            Log.log("%s → never oversold in %,d trials                          consistent",
                    label, TRIALS);
        } else {
            Log.log("%s → oversold in %,d/%,d trials (%.0f%%), %,d units total, worst %d  *** WRONG ***",
                    label, badTrials, TRIALS, 100.0 * badTrials / TRIALS, totalOversold, worstOversell);
        }
    }

    interface Inventory {
        boolean reserveOne();
        int remaining();
    }

    /** The bug in its natural habitat. */
    static final class BrokenInventory implements Inventory {
        private volatile int stock = INITIAL_STOCK;   // volatile does not help here

        @Override public boolean reserveOne() {
            if (stock > 0) {        // CHECK
                stock--;            // ACT — another thread may have gone first
                return true;
            }
            return false;
        }

        @Override public int remaining() { return stock; }
    }

    /** The tempting half-fix: atomic decrement, non-atomic check. Still broken. */
    static final class StillBrokenAtomicInventory implements Inventory {
        private final AtomicInteger stock = new AtomicInteger(INITIAL_STOCK);

        @Override public boolean reserveOne() {
            if (stock.get() > 0) {          // CHECK — atomic read...
                stock.decrementAndGet();    // ACT   — ...atomic write, but a
                return true;                //         separate step from the check
            }
            return false;
        }

        @Override public int remaining() { return stock.get(); }
    }

    /** Correct and lock-free: re-read, re-check, and only commit if nothing moved. */
    static final class CasInventory implements Inventory {
        private final AtomicInteger stock = new AtomicInteger(INITIAL_STOCK);

        @Override public boolean reserveOne() {
            while (true) {
                int current = stock.get();
                if (current == 0) {
                    return false;
                }
                // compareAndSet only succeeds if `stock` is STILL `current`.
                // If another thread moved it, we loop and re-check the condition.
                if (stock.compareAndSet(current, current - 1)) {
                    return true;
                }
            }
        }

        @Override public int remaining() { return stock.get(); }
    }

    /** Correct and the only shape that extends to multi-field invariants. */
    static final class SynchronizedInventory implements Inventory {
        private int stock = INITIAL_STOCK;

        @Override public synchronized boolean reserveOne() {
            if (stock > 0) {
                stock--;
                return true;
            }
            return false;
        }

        @Override public synchronized int remaining() { return stock; }
    }
}
