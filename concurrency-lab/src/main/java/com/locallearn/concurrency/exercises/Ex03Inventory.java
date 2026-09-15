package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.Inventory;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * <b>EXERCISE 3 — stop the overselling.</b> Demo: {@code t03atomicity.D8_CheckThenActOversell}.
 *
 * <p>Classic check-then-act. Note the twist: {@code reserve} takes a
 * <em>quantity</em>, not a single unit, so {@code decrementAndGet()} is not
 * available as a shortcut — you need a real CAS loop or a lock.
 *
 * <p>Hint: if you go the CAS route, remember to re-read <em>and re-check</em>
 * inside the loop. Reading once outside it recreates the same bug.
 *
 * <p>Worth knowing where this ends up: in a real service, neither fix is
 * enough. Two JVMs behind a load balancer each hold their own lock, so the
 * invariant has to move down to the database — {@code UPDATE ... WHERE stock >= ?}
 * is this same compare-and-set, one layer further out.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex03Inventory   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex3'                                       # the grade
 * </pre>
 */
public final class Ex03Inventory implements Inventory {

    private int stock;

    public Ex03Inventory(int initialStock) {
        this.stock = initialStock;
    }

    @Override
    public boolean reserve(int quantity) {
        if (stock >= quantity) {    // TODO broken: CHECK...
            stock -= quantity;      // TODO broken: ...and ACT are separate steps
            return true;
        }
        return false;
    }

    @Override
    public int remaining() {
        return stock;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int THREADS = 16;
    // 200 trials, the same as the JUnit contract. A whole trial costs about a
    // millisecond here, and at 40 trials the multi-unit case still got through
    // clean roughly one run in ten — which is exactly the sort of "it passed"
    // this lab exists to stop you believing.
    private static final int TRIALS = 200;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 3 — check-then-act oversell", "ExerciseTests$Ex3");

        check.that("never oversells single units — %d threads, %d trials"
                .formatted(THREADS, TRIALS), () ->
                assertNeverOversells(100, 50, 1));

        check.that("never oversells multi-unit reservations — %d trials".formatted(TRIALS), () ->
                // Quantity > 1 is the case where decrementAndGet() is not available
                // as a shortcut, so a half-fix that works above will fail here.
                assertNeverOversells(300, 40, 3));

        check.that("refuses a reservation larger than the remaining stock", () -> {
            // Uncontended, to catch the other failure mode: a fix that is atomic
            // and also wrong, e.g. one that stops refusing at all.
            Inventory inventory = new Ex03Inventory(10);
            Check.require(inventory.reserve(4), "a 4-unit reserve out of 10 was refused");
            Check.require(!inventory.reserve(7), "reserved 7 units with only 6 left");
            Check.require(inventory.reserve(6), "the last 6 units were refused");
            Check.equal(inventory.remaining(), 0, "stock should be exactly empty now");
            Check.require(!inventory.reserve(1), "reserved a unit from an empty stock");
        });

        System.exit(check.finish());
    }

    /**
     * Hammers a fresh inventory from {@code THREADS} threads and checks the two
     * things that must hold afterwards: nothing was sold twice, and nothing
     * appeared or vanished.
     */
    private static void assertNeverOversells(int initialStock, int attempts, int quantity) {
        // Trials, not one run: the window between the check and the act is a few
        // nanoseconds wide, so a single run of the broken code quite often sells
        // exactly the right amount — and one passing run would then read as proof.
        for (int trial = 1; trial <= TRIALS; trial++) {
            Inventory inventory = new Ex03Inventory(initialStock);
            AtomicInteger unitsSold = new AtomicInteger();

            Stress.run(THREADS, attempts, i -> {
                if (inventory.reserve(quantity)) {
                    unitsSold.addAndGet(quantity);
                }
            });

            Check.require(unitsSold.get() <= initialStock,
                    "trial %d of %d: sold %,d units from a stock of %,d — the check and "
                    + "the act are not one indivisible step, so two threads both passed "
                    + "the `stock >= quantity` test on the same units.",
                    trial, TRIALS, unitsSold.get(), initialStock);

            Check.require(inventory.remaining() >= 0,
                    "trial %d of %d: stock went negative (%,d) — more was taken than existed",
                    trial, TRIALS, inventory.remaining());

            Check.equal(inventory.remaining() + unitsSold.get(), initialStock,
                    "trial %d of %d: units sold plus units remaining must equal the "
                    + "initial stock — nothing should appear or vanish",
                    trial, TRIALS);
        }
    }
}
