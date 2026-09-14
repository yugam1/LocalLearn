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
     * Exercise 13 — see {@code t08pools.D22_PoolGrowthOrder} and
     * {@code t08pools.D24_SizingLifecycleAndLostExceptions}.
     *
     * <p>A thread pool, built from the parts you already own: worker threads
     * (topic 1), a bounded blocking queue (Ex7), and a draining shutdown
     * (Ex8/D15). The whole of {@link java.util.concurrent.ThreadPoolExecutor}'s
     * behaviour that matters is in the four-step submission rule, and you are
     * going to implement it:
     *
     * <pre>
     *   1. fewer than corePoolSize workers?  -> start a new worker for this task
     *   2. else, does the QUEUE accept it?   -> queue it
     *   3. else, fewer than maxPoolSize?     -> start a new worker for this task
     *   4. else                              -> RejectedExecutionException
     * </pre>
     *
     * Step 2 comes before step 3. That ordering is the entire exercise: get it
     * backwards and you have built a pool whose queue is never used and which
     * creates threads for load a queue slot would have absorbed.
     */
    public interface MiniPool {
        /**
         * Submits a task, following the four-step rule above.
         *
         * @throws java.util.concurrent.RejectedExecutionException if the pool is
         *         at {@code maxPoolSize} and the queue is full, or after any
         *         shutdown has begun.
         */
        void execute(Runnable task);

        /**
         * Graceful shutdown: stop accepting, <b>run everything already queued</b>,
         * then stop every worker. Returns only once all workers have terminated.
         * This is {@code shutdown()} + {@code awaitTermination()} in one call.
         */
        void shutdownAndAwait() throws InterruptedException;

        /**
         * Abrupt shutdown: stop accepting, interrupt the workers, and
         * <b>return the tasks that were never started</b> so the caller can see
         * exactly what was abandoned. This is {@code shutdownNow()}.
         */
        java.util.List<Runnable> shutdownNow();

        /** Worker threads created and not yet terminated. */
        int poolSize();

        /** Largest value {@link #poolSize()} ever reached. */
        int largestPoolSize();

        /** Tasks currently waiting in the queue. */
        int queueSize();

        /** Tasks that have run to completion. */
        long completed();
    }

    /**
     * Exercise 14 — see {@code t09parallel.D27_VirtualThreadsAndPinning}.
     *
     * <p>One runner, two workloads, and <b>no single strategy is correct for
     * both</b>. That is the entire point: the question "what kind of work is
     * this?" has to be asked before the executor is chosen, because the right
     * answer for CPU-bound work is the wrong answer for IO-bound work and vice
     * versa.
     *
     * <p>Both methods must return results in the same order as the tasks they
     * were given, and must propagate a task's exception rather than swallowing
     * it (D24: a {@code Future} nobody asks is a failure nobody hears).
     */
    public interface WorkloadRunner extends AutoCloseable {
        /**
         * Runs tasks that spend essentially all their time <b>computing</b>.
         * Must actually run them in parallel across the machine's cores.
         */
        java.util.List<Long> runCpuBound(java.util.List<java.util.concurrent.Callable<Long>> tasks)
                throws Exception;

        /**
         * Runs tasks that spend essentially all their time <b>blocked</b>.
         * Must achieve concurrency far beyond the core count — thousands of
         * simultaneously-blocked tasks is the target, not dozens.
         */
        java.util.List<Long> runIoBound(java.util.List<java.util.concurrent.Callable<Long>> tasks)
                throws Exception;

        @Override
        void close();
    }


    /**
     * Exercise 15 — the capstone. See {@code t10diagnostics.D30_TheIncident} and
     * {@code t10diagnostics.D31_DiagnosingTheIncident}.
     *
     * <p>A small request-processing service with four planted defects, each from
     * a different earlier topic. Unlike every other contract here, this one does
     * not tell you which mechanism is involved — working that out from the
     * symptom is the exercise.
     *
     * <p>Implementations must name their threads with the {@link #THREAD_PREFIX}
     * prefix. The contract test measures per-thread CPU time and has no other
     * way to find them.
     */
    public interface IncidentService {

        /** Every thread an implementation starts must be named {@code incident-something}. */
        String THREAD_PREFIX = "incident-";

        /** What {@link #handle} must report for a request that carries no tenant. */
        String ANONYMOUS = "anonymous";

        /** Number of internal ledgers, indexed 0..LEDGERS-1. */
        int LEDGERS = 6;

        /** Starting balance of every ledger, so the invariant total is LEDGERS * this. */
        long INITIAL_BALANCE = 1_000_000L;

        /**
         * Handles one request and returns
         * {@code "tenant=<t>|req=<request>"}, where {@code <t>} is
         * {@code tenant} — or exactly {@link #ANONYMOUS} when {@code tenant} is
         * {@code null}, which is how unauthenticated system traffic (health
         * probes, scheduled sweeps) arrives.
         *
         * <p>It must return that for <b>every</b> call, whatever else the
         * service happened to be doing a moment earlier on the same thread, and
         * it must keep returning promptly however many callers invoke it at
         * once.
         */
        String handle(String tenant, String request) throws Exception;

        /**
         * Moves {@code amount} between two internal ledgers as one atomic step.
         * Called concurrently, from many threads, on the same pairs in both
         * directions.
         */
        void transfer(int fromLedger, int toLedger, long amount);

        /** Sum of every ledger. Invariant: always {@code LEDGERS * INITIAL_BALANCE}. */
        long ledgerTotal();

        /** How many calls to {@link #handle} have completed. */
        long completed();

        /** Stops the service. Must not block forever, whatever state the service is in. */
        void shutdown() throws InterruptedException;
    }
}
