package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.BoundedQueue;
import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.api.Contracts.Counter;
import com.locallearn.concurrency.api.Contracts.Inventory;
import com.locallearn.concurrency.api.Contracts.InterruptibleWorker;
import com.locallearn.concurrency.api.Contracts.StopSignal;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  YOUR WORK GOES HERE.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * <p>Every class below is <b>deliberately broken</b> in the exact way the demos
 * showed you. Fix them one at a time.
 *
 * <h2>How to work</h2>
 * <pre>
 * ./mvnw test                              # run everything — expect failures at first
 * ./mvnw test -Dtest=Ex1CounterTest        # work on one exercise
 * ./mvnw test -Dtest=Sol*                  # prove the tests themselves pass on the reference
 * </pre>
 *
 * <p>Each test hammers your class from many threads behind a start gate and
 * repeats the whole trial hundreds of times, so "it passed once" is not an
 * option. If a test passes, your implementation is genuinely correct under
 * contention, not merely lucky.
 *
 * <h2>Rules</h2>
 * <ul>
 *   <li>Do not change the method signatures or the tests.</li>
 *   <li>Do not solve anything by making the test single-threaded.</li>
 *   <li>A solution that is correct but takes a global lock around everything
 *       will pass the correctness tests — but exercises 6 and 7 also assert
 *       properties (compute-once, no busy-wait) that a naive global lock
 *       will not satisfy.</li>
 *   <li>Reference implementations are in {@code solutions/Solutions.java}.
 *       Try each exercise before you open it; the whole value is in the
 *       attempt.</li>
 * </ul>
 */
public final class Exercises {

    private Exercises() {
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 1
    /**
     * <b>Fix the lost updates.</b> See {@code t03atomicity.D7_LostUpdates}.
     *
     * <p>{@code value++} is read-modify-write: three steps that another thread
     * can interleave with. Make the increment atomic.
     *
     * <p>Hint: there are three valid answers here ({@code AtomicLong},
     * {@code synchronized}, {@code LongAdder}). Try all three — then look at
     * D9's benchmark and decide which you would actually ship for a metrics
     * counter, and which for an ID generator.
     */
    public static final class Ex1Counter implements Counter {
        private long value;

        @Override
        public void increment() {
            value++;                    // TODO broken: not atomic
        }

        @Override
        public long count() {
            return value;               // TODO broken: not safely published
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 2
    /**
     * <b>Make the stop signal visible.</b> See {@code t02visibility.D4_StaleFlagHang}.
     *
     * <p>The test spins a worker on {@link #shouldStop()} and then calls
     * {@link #stop()} from another thread. As written, the JIT may hoist the
     * field read out of the worker's loop and the worker never exits — the test
     * will time out rather than fail fast, which is itself the lesson.
     *
     * <p>Hint: one keyword. Think about which of volatile's three guarantees
     * you are relying on here, and whether you need the other two.
     */
    public static final class Ex2StopSignal implements StopSignal {
        private boolean stopped;        // TODO broken: no visibility guarantee

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public boolean shouldStop() {
            return stopped;
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 3
    /**
     * <b>Stop the overselling.</b> See {@code t03atomicity.D8_CheckThenActOversell}.
     *
     * <p>Classic check-then-act. Note the twist: {@code reserve} takes a
     * <em>quantity</em>, not a single unit, so {@code decrementAndGet()} is not
     * available as a shortcut — you need a real CAS loop or a lock.
     *
     * <p>Hint: if you go the CAS route, remember to re-read <em>and re-check</em>
     * inside the loop. Reading once outside it recreates the same bug.
     */
    public static final class Ex3Inventory implements Inventory {
        private int stock;

        public Ex3Inventory(int initialStock) {
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
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 4
    /**
     * <b>Make the worker cancellable.</b> See {@code t01threads.D3_InterruptionAndCancellation}.
     *
     * <p>Two bugs in the code below, and they compound:
     * <ol>
     *   <li>The loop never checks the interrupt flag, so it cannot stop.</li>
     *   <li>The catch block swallows {@link InterruptedException} — it neither
     *       rethrows nor restores the flag — so the cancellation request is
     *       destroyed and nobody upstream can recover it.</li>
     * </ol>
     *
     * <p>The test asserts three things: the worker stops within 2 seconds of
     * being interrupted, {@link #cleanedUp()} is true afterwards, and the
     * thread's interrupt flag is still set when {@code run()} returns.
     */
    public static final class Ex4Worker implements InterruptibleWorker {
        private volatile long units;
        private volatile boolean cleanedUp;

        @Override
        public void run() {
            while (true) {              // TODO broken: never checks for cancellation
                try {
                    Thread.sleep(10);
                    units++;
                } catch (InterruptedException e) {
                    // TODO broken: swallowed. The flag was cleared by the throw
                    // and is not restored, so the request is simply gone.
                }
            }
        }

        @Override
        public long unitsCompleted() {
            return units;
        }

        @Override
        public boolean cleanedUp() {
            return cleanedUp;
        }

        /** Call this on the way out — from a finally block, so every exit path runs it. */
        private void cleanup() {
            cleanedUp = true;
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 5
    /**
     * <b>Transfer money without deadlocking.</b> See {@code t04locks.D11_Deadlock}.
     *
     * <p>The test runs many threads transferring in both directions between the
     * same accounts. As written it deadlocks within milliseconds, and the test
     * fails on a timeout.
     *
     * <p>Two things must hold when it finishes: no deadlock, and
     * {@link #totalMoney()} unchanged — money is neither created nor destroyed.
     * Note that simply removing the locks fixes the deadlock and breaks the
     * second property, so you cannot cheat your way past this one.
     *
     * <p>Hint: the bug is not "two locks". It is "two locks acquired in two
     * different orders". Fix the order, or stop holding one while waiting for
     * the other.
     */
    public static final class Ex5Bank implements com.locallearn.concurrency.api.Contracts.Bank {
        private final long[] balances;
        private final Object[] locks;

        public Ex5Bank(int accounts, long initialBalance) {
            balances = new long[accounts];
            locks = new Object[accounts];
            for (int i = 0; i < accounts; i++) {
                balances[i] = initialBalance;
                locks[i] = new Object();
            }
        }

        @Override
        public void transfer(int fromAccount, int toAccount, long amount) {
            // TODO broken: lock order depends on the arguments, so transfer(1,2)
            // racing transfer(2,1) produces a circular wait.
            synchronized (locks[fromAccount]) {
                synchronized (locks[toAccount]) {
                    balances[fromAccount] -= amount;
                    balances[toAccount] += amount;
                }
            }
        }

        @Override
        public long balance(int account) {
            synchronized (locks[account]) {
                return balances[account];
            }
        }

        @Override
        public long totalMoney() {
            long total = 0;
            for (int i = 0; i < balances.length; i++) {
                total += balance(i);
            }
            return total;
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 6
    /**
     * <b>Compute each value exactly once.</b> See {@code t04locks.D12_ReadWriteLockAndCondition}.
     *
     * <p>This is the "cache stampede" / "thundering herd" problem. When 32
     * threads all miss on the same cold key at once, the naive version runs the
     * expensive loader 32 times. In production that is 32 simultaneous calls to
     * the service you were trying to protect, precisely when it is already slow.
     *
     * <p>The test asserts <b>two</b> things:
     * <ul>
     *   <li>correctness — every caller gets the right value, and the map is not
     *       corrupted (the {@code HashMap} here is not thread-safe either);</li>
     *   <li>{@link #loadCount()} equals the number of distinct keys. Exactly
     *       once per key. Not "roughly once".</li>
     * </ul>
     *
     * <p>Hint: {@code ConcurrentHashMap.computeIfAbsent} gives you this for
     * free, and gives it to you atomically per key rather than under one global
     * lock — so two threads asking for two <em>different</em> cold keys still
     * load in parallel. That last part is why it beats
     * {@code synchronized get()}, which also passes the compute-once assertion
     * but serialises every unrelated load behind one lock.
     */
    public static final class Ex6Cache implements ComputeOnceCache {
        private final Map<String, String> cache = new HashMap<>();
        private final Function<String, String> loader;
        private int loads;

        public Ex6Cache(Function<String, String> expensiveLoader) {
            this.loader = expensiveLoader;
        }

        @Override
        public String get(String key) {
            String value = cache.get(key);      // TODO broken: check...
            if (value == null) {
                loads++;                        // ...and act, with an expensive
                value = loader.apply(key);      //     call in between
                cache.put(key, value);
            }
            return value;
        }

        @Override
        public int loadCount() {
            return loads;
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 7
    /**
     * <b>Build a blocking bounded queue.</b> See {@code t04locks.D12_ReadWriteLockAndCondition}.
     *
     * <p>The hardest one, and the most worth doing: this is
     * {@code ArrayBlockingQueue} in miniature, and writing it once is how you
     * learn to read every bounded-queue-shaped thing you will meet afterwards —
     * including the {@code queueCapacity} on Spring's
     * {@code ThreadPoolTaskExecutor} (docs/phase2_task8.md).
     *
     * <p>Requirements the test enforces:
     * <ul>
     *   <li>{@code put} blocks while the queue is full; {@code take} blocks while empty.</li>
     *   <li>{@link #peakSize()} never exceeds the capacity — not even transiently.</li>
     *   <li>No items are lost or duplicated across many producers and consumers.</li>
     *   <li><b>No busy-waiting.</b> The test measures CPU time and fails a
     *       spin-wait solution. Blocked threads must actually park.</li>
     *   <li>Both methods stay interruptible.</li>
     * </ul>
     *
     * <p>Hint: one {@code ReentrantLock} with <b>two</b> {@code Condition}s
     * ({@code notFull}, {@code notEmpty}). And the rule that catches everyone:
     * wait in a {@code while} loop, never an {@code if} — spurious wakeups are
     * legal, and between being signalled and re-acquiring the lock another
     * thread may already have taken the item you were woken for.
     *
     * <p>(Yes, {@code return new ArrayBlockingQueue<>(capacity)} would pass.
     * Don't. Write the mechanism, then go read {@code ArrayBlockingQueue}'s
     * source and notice it is the same thing.)
     */
    public static final class Ex7Queue<T> implements BoundedQueue<T> {
        private final Object[] items;
        private int head, tail, count, peak;

        public Ex7Queue(int capacity) {
            this.items = new Object[capacity];
        }

        @Override
        public void put(T item) throws InterruptedException {
            // TODO broken: no locking, no blocking, and it overwrites when full
            items[tail] = item;
            tail = (tail + 1) % items.length;
            count++;
            peak = Math.max(peak, count);
        }

        @Override
        @SuppressWarnings("unchecked")
        public T take() throws InterruptedException {
            // TODO broken: returns null when empty instead of blocking
            T item = (T) items[head];
            items[head] = null;
            head = (head + 1) % items.length;
            count--;
            return item;
        }

        @Override
        public int size() {
            return count;
        }

        @Override
        public int peakSize() {
            return peak;
        }
    }
}
