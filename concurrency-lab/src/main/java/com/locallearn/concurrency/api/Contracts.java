package com.locallearn.concurrency.api;

/**
 * The contracts the exercises implement and the tests verify.
 *
 * <p>They live in one file because each is three lines and they are only
 * interesting together: every one of them is a shape you have already seen
 * misbehave in the demos. The same test suite runs against your
 * {@code exercises.*} implementation and against the reference
 * {@code solutions.*} one, so a failing exercise test and a passing solution
 * test prove the harness works and the bug is yours.
 */
public final class Contracts {

    private Contracts() {
    }

    /**
     * Exercise 1 — see {@code t03atomicity.D7_LostUpdates}.
     * Every {@link #increment()} must be reflected in {@link #count()}.
     */
    public interface Counter {
        void increment();

        long count();
    }

    /**
     * Exercise 2 — see {@code t02visibility.D4_StaleFlagHang}.
     * A worker polls {@link #shouldStop()}; another thread calls {@link #stop()}.
     * The worker must actually notice, promptly, every time.
     */
    public interface StopSignal {
        void stop();

        boolean shouldStop();
    }

    /**
     * Exercise 3 — see {@code t03atomicity.D8_CheckThenActOversell}.
     * {@link #reserve(int)} must never let total reservations exceed the initial
     * stock, and {@link #remaining()} must never go negative.
     */
    public interface Inventory {
        /** @return true if all {@code quantity} units were reserved; false if there was not enough. */
        boolean reserve(int quantity);

        int remaining();
    }

    /**
     * Exercise 4 — see {@code t01threads.D3_InterruptionAndCancellation}.
     * A long-running task that must stop promptly when its thread is
     * interrupted, restore the interrupt flag, and run its cleanup.
     */
    public interface InterruptibleWorker extends Runnable {
        /** How many units of work completed before stopping. */
        long unitsCompleted();

        /** True if the worker's cleanup ran. Must be true after any exit path. */
        boolean cleanedUp();
    }

    /**
     * Exercise 5 — see {@code t04locks.D11_Deadlock}.
     * Move money between accounts from many threads without ever deadlocking
     * and without ever creating or destroying money.
     */
    public interface Bank {
        void transfer(int fromAccount, int toAccount, long amount);

        long balance(int account);

        long totalMoney();
    }

    /**
     * Exercise 6 — see {@code t04locks.D12_ReadWriteLockAndCondition}.
     * A cache where an expensive value is computed <b>at most once per key</b>,
     * even when many threads ask for the same missing key simultaneously.
     */
    public interface ComputeOnceCache {
        String get(String key);

        /** How many times the expensive loader actually ran. */
        int loadCount();
    }

    /**
     * Exercise 7 — see {@code t04locks.D12_ReadWriteLockAndCondition}.
     * A fixed-capacity buffer. {@code put} blocks while full, {@code take}
     * blocks while empty, neither busy-waits, and capacity is never exceeded.
     */
    public interface BoundedQueue<T> {
        void put(T item) throws InterruptedException;

        T take() throws InterruptedException;

        int size();

        /** Highest size ever observed — the test asserts this never exceeded capacity. */
        int peakSize();
    }
}
