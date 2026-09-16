package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.Inventory;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.atomic.AtomicInteger;

/*
 * EXERCISE 3 — an inventory that oversells
 *
 * THE SCENARIO
 *   The stock level for one product during a flash sale. Sixteen checkout
 *   threads call reserve(quantity) at the same moment, and a true means those
 *   units are now committed to that customer. Sell units that do not exist and
 *   someone has to cancel a paid order by hand.
 *
 * WHAT IS WRONG RIGHT NOW
 *   Nothing as the file stands: the code below is your own fix and the checks
 *   pass. The defect this exercise is built around is the check-then-act pair
 *   if (stock >= quantity) stock -= quantity;. Those are two separate steps.
 *   Two threads both read a stock of 3, both pass the test for 3 units, and
 *   both subtract: 6 units sold out of 3, and stock goes negative.
 *
 * YOUR TASK
 *   1. reserve(int) — make the check and the subtraction one indivisible step,
 *      so no second thread can pass the same check on the same units.
 *   2. remaining() — must report the stock left after every reservation that
 *      has already committed.
 *
 * RULES
 *   reserve takes a QUANTITY, not a single unit, so decrementAndGet() is not
 *   available as a shortcut: it has to be a CAS loop or a lock. Refusing a
 *   caller you could have served is allowed; returning true without committing
 *   the units is not.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 16 threads reserving single units, 200 trials, never sell past the
 *      stock;
 *   2. the same with quantity 3, which is where a fix that leans on
 *      decrementAndGet() falls over;
 *   3. units sold plus units remaining always equal the initial stock, so
 *      nothing appears or vanishes;
 *   4. uncontended, an oversized reservation is still refused — catching a fix
 *      that is atomic and also wrong, e.g. one that stops refusing at all.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex03Inventory
 *
 * HINT
 *   On the CAS route, re-read AND re-check inside the loop; reading once
 *   outside it rebuilds the same bug with extra steps. Worth knowing where this
 *   ends up: in a real service neither fix is enough, because two JVMs behind a
 *   load balancer each hold their own lock — the invariant has to move down to
 *   the database, where UPDATE ... WHERE stock >= ? is this same compare-and-
 *   set one layer out.
 *
 * SEE ALSO
 *   Docs — read this first: docs/02-concurrency/03-atomicity-races-cas.md,
 *     section "Check-then-act: how 100 units of stock sell 138".
 *   Demo t03atomicity.D8_CheckThenActOversell shows the failure live. Reference
 *   solution: solutions/Solutions.java.
 */
public final class Ex03Inventory implements Inventory {

    private AtomicInteger stock;

    public Ex03Inventory(int initialStock) {
        this.stock = new AtomicInteger(initialStock);
    }

    /*
     * Must never hand out units that are not there: a true return means the
     * quantity was taken from the stock that was actually present at that
     * instant. If the check and the subtraction can be split by another
     * thread, two callers buy the same units and the order has to be cancelled
     * by hand later.
     */
    @Override
    public boolean reserve(int quantity) {
        int value = stock.get();
        if (value >= quantity) {    // checked against the captured value above
            // The CAS is the indivisible step: it commits only while stock is still
            // `value`, so a reservation that raced with another one takes nothing.
            // A lost race returns false here rather than retrying — safe (nothing is
            // oversold), but it refuses a caller that a retry loop would have served.
            return stock.compareAndSet(value,value- quantity);
        }
        return false;
    }

    /*
     * Must reflect every reservation that has already committed, and must
     * never go negative — sold plus remaining is the initial stock at all
     * times.
     */
    @Override
    public int remaining() {
        return stock.get();
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
        Check check = Check.named("Exercise 3 — check-then-act oversell", "ExerciseTests$Ex3")
                .reading("docs/02-concurrency/03-atomicity-races-cas.md § \"Check-then-act\"");

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

    /*
     * Hammers a fresh inventory from THREADS threads and checks the two things
     * that must hold afterwards: nothing was sold twice, and nothing appeared
     * or vanished.
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
