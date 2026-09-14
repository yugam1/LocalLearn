package com.locallearn.concurrency.api;

import java.util.List;

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

    /**
     * Exercise 8 — see {@code t05handoff.D13_UnboundedBacklog} and
     * {@code t05handoff.D15_ShutdownPoisonPill}.
     * A producer–consumer pipeline: bounded backlog (backpressure, never
     * dropped items), worker threads that park while idle, and a shutdown that
     * drains every accepted item before the workers exit.
     */
    public interface Pipeline {
        /**
         * Hands one item to the pipeline. Must apply backpressure: when the
         * backlog is at capacity this <b>blocks</b> — it never drops the item
         * and never lets the backlog grow past capacity.
         */
        void submit(String item) throws InterruptedException;

        /**
         * Drains and stops: returns only after <b>every</b> item accepted by
         * {@link #submit} has been processed and every worker thread has
         * terminated. Behaviour of {@code submit} after this is undefined.
         */
        void shutdownAndDrain() throws InterruptedException;

        /** How many items the processor has completed so far. */
        long processed();

        /** Highest backlog ever observed — the test asserts this never exceeded capacity. */
        int backlogPeak();

        /** Worker threads currently alive. Must be 0 after {@link #shutdownAndDrain} returns. */
        int liveWorkers();
    }

    /**
     * Exercise 9 — see {@code t06shared.D16_HashMapCorruption} and
     * {@code t06shared.D17_AtomicMapUpdates}.
     *
     * <p>A per-key event tally. Every {@link #record} must be counted, every
     * {@link #consume} must correspond to exactly one earlier {@code record},
     * and a key whose count reaches zero must disappear from the map rather than
     * linger at 0.
     *
     * <p>The interesting part is that the starting code already uses a
     * {@code ConcurrentHashMap} and is still wrong, because "each call is
     * atomic" is not "my sequence of calls is atomic".
     */
    public interface EventCounts {
        /** Records one occurrence of {@code key}. */
        void record(String key);

        /**
         * Consumes one recorded occurrence of {@code key}.
         *
         * @return true if there was one to consume; false if the count was
         *         already zero. Must never let two callers consume the same
         *         single occurrence, and must never drive a count below zero.
         */
        boolean consume(String key);

        /** Current count for {@code key}; 0 if the key is absent. Never negative. */
        long count(String key);

        /** Keys currently present. A key consumed down to zero must not be one. */
        int distinctKeys();
    }

    /**
     * Exercise 10 — see {@code t06shared.D18_CopyOrConfine}, and
     * {@code ../01-foundations/07-logging-mdc-correlation-ids.md}, which is this
     * same mechanism with a logging API on top.
     *
     * <p>A per-request correlation id, confined to the thread serving the
     * request. Three properties the tests enforce, each a separate defect in the
     * starting code:
     * <ol>
     *   <li>the id is visible only to the thread that bound it;</li>
     *   <li>it is unbound on <b>every</b> exit path, including the exceptional
     *       one — otherwise the next request on that pooled thread inherits it;</li>
     *   <li>nested scopes restore the enclosing id rather than clearing it.</li>
     * </ol>
     */
    public interface RequestContext {
        /**
         * Binds {@code correlationId} to the current thread, runs {@code body},
         * and restores whatever was bound before — on every exit path.
         */
        void runWithCorrelationId(String correlationId, Runnable body);

        /** The id bound to the calling thread, or {@code null} if none is. */
        String currentCorrelationId();
    }

    /**
     * Exercise 11 — see {@code t07coordination.D19_LatchVersusBarrier}.
     *
     * <p>A fixed team of workers running a computation in rounds. No worker may
     * begin round <i>r+1</i> until every worker has finished round <i>r</i> and
     * the round has been tallied exactly once.
     *
     * <p>Worker threads must be named {@code round-worker-<id>}; one test finds
     * them by name to check they <b>park</b> at the barrier rather than spinning.
     */
    public interface RoundSync {
        /** Runs {@code rounds} rounds on all workers; returns when every worker is done. */
        void runAll(int rounds) throws InterruptedException;

        /**
         * One entry per completed round, in order: how many workers had arrived
         * at the moment that round was tallied. Every entry must equal
         * {@link #workers()}, and there must be exactly one entry per round.
         */
        List<Integer> tallies();

        /** The fixed number of worker threads. */
        int workers();
    }

    /**
     * Exercise 12 — see {@code t07coordination.D20_SemaphorePermits}.
     *
     * <p>A bounded resource pool: at most {@code limit} tasks may run at once,
     * callers <b>wait</b> for a slot rather than proceeding unaccounted, and a
     * task that throws must not cost the pool a slot.
     */
    public interface BoundedResourcePool {
        /**
         * Runs {@code task} while holding one of the pool's slots, blocking
         * until a slot is free. Exceptions from the task propagate to the
         * caller — and must not leak the slot.
         */
        <T> T execute(java.util.concurrent.Callable<T> task) throws Exception;

        /** Highest number of tasks ever observed running at the same time. */
        int peakConcurrency();

        /** Slots currently free. Back to the pool's full size once all work has finished. */
        int availableSlots();
    }
}
