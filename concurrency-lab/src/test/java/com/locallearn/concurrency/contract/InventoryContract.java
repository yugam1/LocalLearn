package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.Inventory;
import com.locallearn.concurrency.support.Stress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 3 contract — see {@code t03atomicity.D8_CheckThenActOversell}.
 */
public abstract class InventoryContract {

    protected abstract Inventory newInventory(int initialStock);

    @Test
    @Timeout(120)
    @DisplayName("never oversells single units, across 200 trials")
    void neverOversellsSingleUnits() {
        assertNeverOversells(100, 16, 50, 1);
    }

    @Test
    @Timeout(120)
    @DisplayName("never oversells multi-unit reservations")
    void neverOversellsMultiUnitReservations() {
        // Quantity > 1 is the case where decrementAndGet() is not available as a
        // shortcut, so a half-fix that works above will fail here.
        assertNeverOversells(300, 16, 40, 3);
    }

    private void assertNeverOversells(int initialStock, int threads, int attempts, int quantity) {
        for (int trial = 1; trial <= 200; trial++) {
            Inventory inventory = newInventory(initialStock);
            AtomicInteger unitsSold = new AtomicInteger();

            Stress.run(threads, attempts, i -> {
                if (inventory.reserve(quantity)) {
                    unitsSold.addAndGet(quantity);
                }
            });

            assertThat(unitsSold.get())
                    .as("trial %d: sold %d units from a stock of %d — the check and the "
                        + "act are not one indivisible step, so two threads both passed "
                        + "the `stock >= quantity` test on the same units.",
                        trial, unitsSold.get(), initialStock)
                    .isLessThanOrEqualTo(initialStock);

            assertThat(inventory.remaining())
                    .as("trial %d: stock went negative", trial)
                    .isGreaterThanOrEqualTo(0);

            assertThat(inventory.remaining() + unitsSold.get())
                    .as("trial %d: units sold plus units remaining must equal the initial "
                        + "stock — nothing should appear or vanish", trial)
                    .isEqualTo(initialStock);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("refuses a reservation larger than the remaining stock")
    void refusesOverLargeReservation() {
        Inventory inventory = newInventory(10);
        assertThat(inventory.reserve(4)).isTrue();
        assertThat(inventory.reserve(7)).as("only 6 left").isFalse();
        assertThat(inventory.reserve(6)).isTrue();
        assertThat(inventory.remaining()).isZero();
        assertThat(inventory.reserve(1)).isFalse();
    }
}
