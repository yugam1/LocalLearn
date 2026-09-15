package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.BoundedQueue;
import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.api.Contracts.Inventory;
import com.locallearn.concurrency.api.Contracts.InterruptibleWorker;
import com.locallearn.concurrency.api.Contracts.Pipeline;
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
    // Moved to its own runnable file: Ex01Counter.java — it carries a main that
    // stress-checks it, so you can iterate with plain java, no Maven round-trip.
    //   java -cp target/classes com.locallearn.concurrency.exercises.Ex01Counter

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

    // ══════════════════════════════════════════════════════════ EXERCISE 8
    /**
     * <b>Fix the pipeline.</b> See {@code t05handoff.D13_UnboundedBacklog} and
     * {@code t05handoff.D15_ShutdownPoisonPill}.
     *
     * <p>This is the first exercise where the broken version <em>mostly
     * works</em> — items flow through and get processed. It is broken the way
     * production systems are broken: three latent defects that each show up
     * under a different condition, and every one of them was a demo:
     * <ol>
     *   <li><b>No backpressure</b> (D13). The backlog is unbounded, so a fast
     *       producer grows it without limit — the test's slow consumer makes
     *       {@code backlogPeak()} blow straight past the capacity you were given.</li>
     *   <li><b>Busy-wait</b> (D14's verb grid, Ex7's lesson). Workers spin on
     *       {@code poll()}, burning a core each while the queue is empty.</li>
     *   <li><b>Lossy shutdown</b> (D15). A volatile flag stops the workers
     *       wherever they happen to be, abandoning whatever is still queued —
     *       the test counts every submitted item and will find the missing ones.</li>
     * </ol>
     *
     * <p>Hint: you already own both halves of the fix. A bounded blocking queue
     * is Ex7 (here you may just use {@code ArrayBlockingQueue} — you've earned
     * it); the shutdown is D15's poison pill, one per worker, sent through the
     * same queue so FIFO guarantees it arrives after every real item. Compare
     * the pill with {@code ==}, and think about why {@code equals()} would be
     * a bug.
     */
    public static final class Ex8Pipeline implements Pipeline {
        private final java.util.concurrent.LinkedBlockingQueue<String> backlog =
                new java.util.concurrent.LinkedBlockingQueue<>();   // TODO broken: unbounded — capacity is ignored
        private final java.util.List<Thread> workers = new java.util.ArrayList<>();
        private final java.util.function.Consumer<String> processor;
        private final java.util.concurrent.atomic.AtomicLong processed =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger backlogPeak =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile boolean stopped;

        public Ex8Pipeline(int capacity, int workerCount, java.util.function.Consumer<String> processor) {
            this.processor = processor;
            for (int w = 0; w < workerCount; w++) {
                Thread worker = new Thread(() -> {
                    while (!stopped) {                  // TODO broken: D15 attempt 1½ — flag-based,
                        String item = backlog.poll();   // TODO broken: and poll() busy-waits (D14)
                        if (item != null) {
                            processor.accept(item);
                            processed.incrementAndGet();
                        }
                    }
                    // TODO broken: when stopped flips, whatever is still queued is abandoned
                }, "pipeline-worker-" + w);
                worker.setDaemon(true);
                workers.add(worker);
                worker.start();
            }
        }

        @Override
        public void submit(String item) throws InterruptedException {
            backlog.put(item);                          // TODO broken: never blocks — no backpressure (D13)
            backlogPeak.accumulateAndGet(backlog.size(), Math::max);
        }

        @Override
        public void shutdownAndDrain() throws InterruptedException {
            stopped = true;                             // TODO broken: "stop now", not "drain then stop"
            for (Thread worker : workers) {
                worker.join();
            }
        }

        @Override
        public long processed() {
            return processed.get();
        }

        @Override
        public int backlogPeak() {
            return backlogPeak.get();
        }

        @Override
        public int liveWorkers() {
            int alive = 0;
            for (Thread worker : workers) {
                if (worker.isAlive()) {
                    alive++;
                }
            }
            return alive;
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 9
    /**
     * <b>Make the compound updates atomic.</b> See
     * {@code t06shared.D17_AtomicMapUpdates}.
     *
     * <p><b>Read the field declaration first.</b> The map is already a
     * {@code ConcurrentHashMap}. Every single call below is atomic and
     * thread-safe, and this class is still broken — which is the entire lesson
     * of topic 6, and the reason the starting code is not a {@code HashMap}.
     *
     * <p>{@code record} is {@code get}-then-{@code put}: two atomic calls with a
     * race-shaped hole between them. That is {@code count++} from D7 wearing a
     * Map's clothes, and it loses increments at the same rate. {@code consume}
     * is worse — it is {@code containsKey}-then-act, so two callers can both see
     * a count of 1 and both claim the same single occurrence. You have now met
     * this shape in D8 (overselling), Ex6 (the cache stampede) and D17.
     *
     * <p>The fix is not "use a thread-safe map" — you already have one. It is
     * <b>express each whole read-modify-write as one call</b>, so the map holds
     * the bin lock across the read and the write together:
     * <ul>
     *   <li>{@code merge(key, 1L, Long::sum)} for the increment;</li>
     *   <li>{@code compute(key, (k, v) -> ...)} for the decrement — and note
     *       that <b>returning null from compute removes the entry</b>, which is
     *       exactly how you satisfy "a key consumed to zero must disappear"
     *       atomically rather than with a second {@code remove} call that would
     *       reopen the same gap.</li>
     * </ul>
     *
     * <p>Careful with {@code consume}: it has to report whether it actually
     * consumed something, and the mapping function cannot return that to you.
     * Capture it from inside the function — an effectively-final one-element
     * array or an {@code AtomicBoolean} — and read it after {@code compute}
     * returns. The mapping function runs exactly once per successful call, under
     * the bin lock, so what it records is accurate.
     */
    public static final class Ex9EventCounts
            implements com.locallearn.concurrency.api.Contracts.EventCounts {

        // Already thread-safe. Already not enough.
        private final Map<String, Long> counts = new java.util.concurrent.ConcurrentHashMap<>();

        @Override
        public void record(String key) {
            Long current = counts.get(key);                     // TODO broken: READ...
            counts.put(key, current == null ? 1L : current + 1); // TODO broken: ...and WRITE, separately
        }

        @Override
        public boolean consume(String key) {
            Long current = counts.get(key);                     // TODO broken: CHECK...
            if (current == null || current <= 0) {
                return false;
            }
            if (current == 1) {
                counts.remove(key);                             // TODO broken: ...and ACT, separately —
            } else {                                            //     two callers can both get here
                counts.put(key, current - 1);
            }
            return true;
        }

        @Override
        public long count(String key) {
            Long value = counts.get(key);
            return value == null ? 0L : value;
        }

        @Override
        public int distinctKeys() {
            return counts.size();
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 10
    /**
     * <b>Confine the correlation id to its own thread, and clean it up.</b>
     * See {@code t06shared.D18_CopyOrConfine}.
     *
     * <p>Three defects, each with its own test, and all three are things that
     * have shipped in real filters:
     * <ol>
     *   <li><b>Not confined at all.</b> A {@code static String} is shared by
     *       every thread in the JVM, so two concurrent requests overwrite each
     *       other's id. Thread confinement is the fix, and {@link ThreadLocal}
     *       is how you express it.</li>
     *   <li><b>Cleanup is not in a {@code finally}.</b> When the body throws,
     *       the unbind is skipped and the value stays attached to the thread.
     *       On a pooled thread that means the next request — a different user —
     *       inherits it. This is precisely why the MDC rule in
     *       {@code ../01-foundations/07-logging-mdc-correlation-ids.md} is
     *       {@code MDC.clear()} in a {@code finally}, non-negotiable.</li>
     *   <li><b>Nesting is not restored.</b> Clearing on exit is wrong when a
     *       scope was nested inside another: the outer request's id must come
     *       back, not vanish. Save the previous value before setting, and put it
     *       back afterwards.</li>
     * </ol>
     *
     * <p>One more rule that no test here can see but every reviewer should:
     * when you do clear, use {@code remove()}, never {@code set(null)}.
     * {@code set(null)} leaves the entry in the thread's map holding a null —
     * the slot is never reclaimed, and on a pool thread that lives forever, so
     * does the entry. D18 proves the difference with an {@code initialValue}.
     */
    public static final class Ex10Context
            implements com.locallearn.concurrency.api.Contracts.RequestContext {

        // TODO broken: one field for the whole JVM. Every thread shares it.
        private static String correlationId;

        @Override
        public void runWithCorrelationId(String id, Runnable body) {
            correlationId = id;
            body.run();
            // TODO broken: not in a finally, so a throwing body skips it — and
            // TODO broken: clears instead of restoring, so nesting loses the outer id.
            correlationId = null;
        }

        @Override
        public String currentCorrelationId() {
            return correlationId;
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 11
    /**
     * <b>Make the rounds actually synchronise.</b> See
     * {@code t07coordination.D19_LatchVersusBarrier}.
     *
     * <p>The starting code uses one {@link java.util.concurrent.CountDownLatch},
     * created once in the constructor, as a per-round barrier. Round 1 works
     * perfectly. From round 2 onwards the latch's count is already zero, so
     * {@code countDown()} does nothing and {@code await()} returns immediately —
     * there is no rendezvous left at all, and the workers run free while the
     * code still looks synchronised. Nothing throws and nothing hangs; the
     * tallies simply stop being 6.
     *
     * <p>A latch is <b>one-shot</b>. It counts down to zero and stays there
     * forever, and there is no {@code reset()} by design. What you want is the
     * other half of the 2×2 — a reusable rendezvous of a fixed set of parties —
     * which is {@link java.util.concurrent.CyclicBarrier}.
     *
     * <p>Use the <b>barrier action</b> for the tally. It runs on the last thread
     * to arrive, once per round, while every other party is still parked, which
     * makes it the only instant in the round when nothing else is touching the
     * shared state. Tallying anywhere else — even with a correct barrier — races
     * with the workers starting the next round.
     *
     * <p>And do not "fix" this with {@code while (arrived.get() < workers) { }}.
     * It passes the correctness tests and burns a core per waiting worker; the
     * third test measures per-thread CPU time and rejects it, exactly as Ex7's
     * and Ex8's CPU assertions did.
     */
    public static final class Ex11Rounds
            implements com.locallearn.concurrency.api.Contracts.RoundSync {

        private final int workers;
        private final java.util.function.IntConsumer roundWork;
        private final java.util.List<Integer> tallies =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final java.util.concurrent.atomic.AtomicInteger arrived =
                new java.util.concurrent.atomic.AtomicInteger();

        // TODO broken: created ONCE. A latch cannot be reused — after round 1
        // TODO broken: its count is zero, so there is no barrier at all.
        private final java.util.concurrent.CountDownLatch roundDone;

        public Ex11Rounds(int workers, java.util.function.IntConsumer roundWork) {
            this.workers = workers;
            this.roundWork = roundWork;
            this.roundDone = new java.util.concurrent.CountDownLatch(workers);
        }

        @Override
        public void runAll(int rounds) throws InterruptedException {
            java.util.concurrent.CountDownLatch allFinished =
                    new java.util.concurrent.CountDownLatch(workers);
            for (int w = 0; w < workers; w++) {
                final int id = w;
                Thread worker = new Thread(() -> {
                    try {
                        for (int round = 0; round < rounds; round++) {
                            roundWork.accept(id);
                            arrived.incrementAndGet();
                            roundDone.countDown();      // TODO broken: no-op once at zero
                            roundDone.await();          // TODO broken: returns instantly once at zero
                            if (id == 0) {
                                tallies.add(arrived.getAndSet(0));
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        allFinished.countDown();        // in a finally: see D19
                    }
                }, "round-worker-" + id);
                worker.setDaemon(true);
                worker.start();
            }
            allFinished.await();
        }

        @Override
        public java.util.List<Integer> tallies() {
            return new java.util.ArrayList<>(tallies);
        }

        @Override
        public int workers() {
            return workers;
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 12
    /**
     * <b>Build a bounded resource pool that actually bounds anything.</b>
     * See {@code t07coordination.D20_SemaphorePermits}.
     *
     * <p>Two defects, one per test, and both are real production bugs:
     * <ol>
     *   <li><b>{@code tryAcquire()} and then carrying on anyway.</b> The
     *       non-blocking form returns false when the pool is full, and this code
     *       ignores that and runs the task regardless — so the "limit" is
     *       decorative. If you want the caller to wait, {@code acquire()} is the
     *       verb; if you want to reject, you must actually reject. Running
     *       unaccounted is the one option that is never right, because the
     *       concurrency it permits is unbounded <em>and</em> invisible.</li>
     *   <li><b>{@code release()} after the work instead of in a
     *       {@code finally}.</b> Every task that throws permanently destroys one
     *       slot. The pool does not fail at the first error — it shrinks, one
     *       exception at a time, until the last slot goes and every caller waits
     *       forever, with no deadlock report to explain it (D21).</li>
     * </ol>
     *
     * <p>The shape you want, and it is worth memorising as a shape:
     * <pre>{@code
     * slots.acquire();
     * try {
     *     return task.call();
     * } finally {
     *     slots.release();
     * }
     * }</pre>
     * Note that the in-flight counter needs the same discipline, for the same
     * reason.
     *
     * <p>Once it passes, go back and ask the design question D20 ends on: at a
     * real service boundary, is {@code acquire()} (wait, invisibly, forever) or
     * {@code tryAcquire(timeout)} (wait a bounded time, then shed load
     * deliberately and countably) the behaviour you want? It is topic 5's
     * block/drop/grow decision at a different layer.
     */
    public static final class Ex12Pool
            implements com.locallearn.concurrency.api.Contracts.BoundedResourcePool {

        private final int limit;
        private final java.util.concurrent.Semaphore slots;
        private final java.util.concurrent.atomic.AtomicInteger inFlight =
                new java.util.concurrent.atomic.AtomicInteger();
        private final java.util.concurrent.atomic.AtomicInteger peak =
                new java.util.concurrent.atomic.AtomicInteger();

        public Ex12Pool(int limit) {
            this.limit = limit;
            this.slots = new java.util.concurrent.Semaphore(limit);
        }

        @Override
        public <T> T execute(java.util.concurrent.Callable<T> task) throws Exception {
            // TODO broken: tryAcquire does not wait — and the task then runs
            // TODO broken: whether or not a slot was obtained, so nothing is bounded.
            boolean acquired = slots.tryAcquire();

            peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            T result = task.call();
            inFlight.decrementAndGet();

            // TODO broken: not in a finally — a throwing task never gets here,
            // TODO broken: and that slot is gone for the lifetime of the process.
            if (acquired) {
                slots.release();
            }
            return result;
        }

        @Override
        public int peakConcurrency() {
            return peak.get();
        }

        @Override
        public int availableSlots() {
            return slots.availablePermits();
        }

        /** The size the pool was built with — {@link #availableSlots()} must return to it. */
        public int limit() {
            return limit;
        }
    }

    // ═════════════════════════════════════════════════════════ EXERCISE 13
    /**
     * <b>Build a thread pool.</b> See {@code t08pools.D22_PoolGrowthOrder} and
     * {@code t08pools.D24_SizingLifecycleAndLostExceptions}.
     *
     * <p>This is the exercise the whole curriculum has been building toward, and
     * you already own every part of it: worker threads that park rather than
     * spin (Ex7), a bounded queue that applies backpressure (Ex7), and a
     * shutdown that drains instead of abandoning (Ex8's poison pill). What is
     * new is the <b>submission rule</b>, and it is the one thing almost everyone
     * gets backwards:
     *
     * <pre>
     *   1. workers &lt; core?        -> start a worker for this task
     *   2. queue accepts the task? -> queue it            &lt;-- BEFORE growing
     *   3. workers &lt; max?         -> start a worker for this task
     *   4. otherwise               -> RejectedExecutionException
     * </pre>
     *
     * <p>Four planted defects, each its own failing test:
     * <ol>
     *   <li><b>The order is inverted.</b> {@link Ex13Pool#execute} grows the pool
     *       to {@code maxPoolSize} before it ever offers to the queue, so the
     *       queue is dead weight and the pool creates threads for load one queue
     *       slot would have absorbed (D22).</li>
     *   <li><b>Busy-wait.</b> Workers spin on {@code poll()} instead of parking
     *       in {@code take()}, burning a core each while idle (D14, Ex7, Ex8).</li>
     *   <li><b>Lossy graceful shutdown.</b> {@code shutdownAndAwait} flips a flag,
     *       so queued tasks are abandoned rather than drained (D15, D24).</li>
     *   <li><b>{@code shutdownNow} hides the damage.</b> It returns an empty list
     *       instead of the tasks it never started, so the caller cannot see,
     *       count or requeue what was lost (D24).</li>
     * </ol>
     *
     * <p>Hint for defect 3: you cannot use a poison pill <em>and</em> honour
     * {@code shutdownNow}'s interrupt in the same worker loop without deciding
     * what each one means. Write the two shutdowns as the two different verbs
     * they are — drain versus abandon.
     */
    public static final class Ex13Pool implements com.locallearn.concurrency.api.Contracts.MiniPool {
        private final int corePoolSize;
        private final int maxPoolSize;
        private final java.util.concurrent.BlockingQueue<Runnable> queue;
        private final java.util.List<Thread> workers =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        private final java.util.concurrent.atomic.AtomicLong completed =
                new java.util.concurrent.atomic.AtomicLong();
        private final java.util.concurrent.atomic.AtomicInteger largest =
                new java.util.concurrent.atomic.AtomicInteger();
        private volatile boolean stopped;

        public Ex13Pool(int corePoolSize, int maxPoolSize, int queueCapacity) {
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.queue = new java.util.concurrent.ArrayBlockingQueue<>(queueCapacity);
        }

        @Override
        public void execute(Runnable task) {
            if (stopped) {
                throw new java.util.concurrent.RejectedExecutionException("pool is shut down");
            }
            // TODO broken (defect 1): this grows the pool all the way to
            // maxPoolSize BEFORE it ever tries the queue — the submission rule
            // upside down. Result: the queue is never used while threads remain
            // available, which is exactly backwards from ThreadPoolExecutor (D22).
            synchronized (workers) {
                if (workers.size() < maxPoolSize) {
                    addWorker(task);
                    return;
                }
            }
            if (!queue.offer(task)) {
                throw new java.util.concurrent.RejectedExecutionException("queue full");
            }
        }

        private void addWorker(Runnable firstTask) {
            Thread worker = new Thread(() -> {
                Runnable task = firstTask;
                while (!stopped) {                  // TODO broken (defect 3): a flag stops
                    if (task != null) {             // workers wherever they are, abandoning
                        try {                       // whatever is still queued
                            task.run();
                        } catch (RuntimeException e) {
                            // a pool must survive a failing task
                        }
                        completed.incrementAndGet();
                    }
                    task = queue.poll();            // TODO broken (defect 2): poll() returns
                                                    // null immediately, so an idle worker
                                                    // spins at 100% CPU (D14's verb grid)
                }
            }, "minipool-worker-" + workers.size());
            workers.add(worker);
            largest.accumulateAndGet(workers.size(), Math::max);
            worker.start();
        }

        @Override
        public void shutdownAndAwait() throws InterruptedException {
            stopped = true;                         // TODO broken (defect 3): "stop now",
            for (Thread worker : workers) {         // not "finish the queue, then stop"
                worker.join();
            }
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            stopped = true;
            for (Thread worker : workers) {
                worker.interrupt();
            }
            // TODO broken (defect 4): the tasks still sitting in the queue are
            // silently dropped. shutdownNow()'s whole contract is that it HANDS
            // THEM BACK so the caller can count or requeue them (D24).
            return java.util.List.of();
        }

        @Override
        public int poolSize() {
            int alive = 0;
            synchronized (workers) {
                for (Thread worker : workers) {
                    if (worker.isAlive()) {
                        alive++;
                    }
                }
            }
            return alive;
        }

        @Override
        public int largestPoolSize() {
            return largest.get();
        }

        @Override
        public int queueSize() {
            return queue.size();
        }

        @Override
        public long completed() {
            return completed.get();
        }
    }

    // ═════════════════════════════════════════════════════════ EXERCISE 14
    /**
     * <b>Two workloads, one runner, and no single strategy that fits both.</b>
     * See {@code t09parallel.D27_VirtualThreadsAndPinning}.
     *
     * <p>Everything below is written the way a reasonable person writes it the
     * first time: one executor, sized to the core count, shared by both methods,
     * with a lock to keep the shared result list safe. Every individual decision
     * is defensible. Together they are wrong, and the tests will tell you which
     * workload each decision ruins.
     *
     * <p>Two planted defects:
     * <ol>
     *   <li><b>One strategy for two kinds of work.</b> A pool of {@code cores}
     *       platform threads is right for CPU-bound work and catastrophic for
     *       IO-bound work: 400 tasks that each block for 100 ms can only run
     *       {@code cores} at a time. D27 measured this exact shape — a
     *       cores-sized pool managed ~120 blocking tasks per second where
     *       virtual threads managed ~68,000.</li>
     *   <li><b>The lock is held across the task itself.</b> {@code synchronized}
     *       around {@code task.call()} serialises every task, so neither
     *       workload gets any parallelism at all. And once you switch the IO path
     *       to virtual threads it gets a second, subtler penalty: a virtual
     *       thread that blocks inside {@code synchronized} is <b>pinned</b> to
     *       its carrier and cannot unmount. D27 measured a <b>108x</b> collapse
     *       from pinning alone, with zero contention.</li>
     * </ol>
     *
     * <p>Your job is to ask "what kind of work is this?" separately for each
     * method, and to make sure that whatever synchronisation survives does not
     * wrap a blocking call. Hint: the lock exists only to protect a list. There
     * are ways to collect results in order that need no lock at all.
     */
    public static final class Ex14Runner
            implements com.locallearn.concurrency.api.Contracts.WorkloadRunner {

        private static final int CORES = Runtime.getRuntime().availableProcessors();

        // TODO broken (defect 1): ONE executor for two completely different
        // kinds of work. Sized for CPU-bound work, which makes it the wrong
        // shape for anything that blocks.
        private final java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newFixedThreadPool(CORES);

        private final Object lock = new Object();

        @Override
        public java.util.List<Long> runCpuBound(
                java.util.List<java.util.concurrent.Callable<Long>> tasks) throws Exception {
            return runAll(tasks);
        }

        @Override
        public java.util.List<Long> runIoBound(
                java.util.List<java.util.concurrent.Callable<Long>> tasks) throws Exception {
            return runAll(tasks);                   // TODO broken: same executor, both workloads
        }

        private java.util.List<Long> runAll(
                java.util.List<java.util.concurrent.Callable<Long>> tasks) throws Exception {
            java.util.List<java.util.concurrent.Future<Long>> futures = new java.util.ArrayList<>();
            for (java.util.concurrent.Callable<Long> task : tasks) {
                futures.add(executor.submit(() -> {
                    // TODO broken (defect 2): the lock is held across the whole
                    // task, including whatever blocking it does. This serialises
                    // every task, and on a virtual thread it also PINS the
                    // carrier for the duration of the blocking call (D27).
                    synchronized (lock) {
                        return task.call();
                    }
                }));
            }
            java.util.List<Long> results = new java.util.ArrayList<>(futures.size());
            for (java.util.concurrent.Future<Long> future : futures) {
                results.add(future.get());          // preserves submission order
            }
            return results;
        }

        @Override
        public void close() {
            executor.shutdownNow();
        }
    }


    // ══════════════════════════════════════════════════════════ EXERCISE 15
    /**
     * <b>The incident.</b> See {@code t10diagnostics.D30_TheIncident} and
     * {@code t10diagnostics.D31_DiagnosingTheIncident}.
     *
     * <p>This is the capstone, and it is deliberately unlike every other
     * exercise on this page. The others name their mechanism in the heading, so
     * you always know which chapter the fix comes from. Production never does
     * that. Here you get a service, four failing tests, and four
     * <em>symptoms</em> — and which of topics 1 to 9 applies to each is the
     * thing you are being examined on.
     *
     * <p>The service below is about seventy lines and looks entirely reasonable.
     * Four lines are wrong:
     *
     * <ol>
     *   <li><b>Two requests hang; the rest of the service keeps working.</b> The
     *       JVM will tell you what this one is if you ask it the right question.
     *       Both stuck threads report {@code WAITING}, which is worth
     *       remembering before you go grepping for {@code BLOCKED}.</li>
     *   <li><b>Under concurrent load the service stops completely and stays
     *       stopped</b> — and nothing reports a deadlock, because there is no
     *       cycle of lock <em>ownership</em> anywhere. Look at where the worker
     *       threads are parked and at what the pool's own queue is doing. Ask
     *       yourself what those threads are waiting <em>for</em>, and who was
     *       supposed to do it.</li>
     *   <li><b>Nothing hangs and some answers are wrong.</b> Requests that carry
     *       no tenant come back attributed to somebody else's tenant. No thread
     *       dump, deadlock report, or CPU measurement will ever show you this;
     *       only comparing what you sent with what came back. Ask what a pooled
     *       thread still carries after it finishes a task.</li>
     *   <li><b>A core is pinned while the service is idle.</b> The thread doing
     *       it is {@code RUNNABLE}, which is exactly what a thread doing useful
     *       work looks like, so the state word cannot help you. The verb you
     *       chose can.</li>
     * </ol>
     *
     * <p>Run {@code D30_TheIncident} to watch all four, then
     * {@code D31_DiagnosingTheIncident} only once you have made your own
     * diagnosis. Each of the four defects came from a different topic; if you
     * find yourself fixing two of them the same way, one of the diagnoses is
     * wrong.
     */
    public static final class Ex15IncidentService
            implements com.locallearn.concurrency.api.Contracts.IncidentService {

        /**
         * The per-request context. This is {@code SecurityContextHolder}, it is
         * SLF4J's {@code MDC}, and it is in every service you will ever work on:
         * set once at the entry point so that code three layers down does not
         * need a tenant parameter it does not care about.
         */
        private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

        private final java.util.concurrent.ThreadPoolExecutor pool;
        private final java.util.concurrent.BlockingQueue<String> auditQueue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private final Thread reaper;
        private final long[] ledgers = new long[LEDGERS];
        private final java.util.concurrent.locks.ReentrantLock[] locks =
                new java.util.concurrent.locks.ReentrantLock[LEDGERS];
        private final java.util.concurrent.atomic.AtomicLong completed =
                new java.util.concurrent.atomic.AtomicLong();
        private volatile boolean running = true;

        public Ex15IncidentService(int workers) {
            java.util.concurrent.atomic.AtomicInteger seq =
                    new java.util.concurrent.atomic.AtomicInteger();
            this.pool = new java.util.concurrent.ThreadPoolExecutor(
                    workers, workers, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable,
                                THREAD_PREFIX + "worker-" + seq.getAndIncrement());
                        thread.setDaemon(true);     // a wedged pool must not outlive the test JVM
                        return thread;
                    });
            for (int i = 0; i < LEDGERS; i++) {
                ledgers[i] = INITIAL_BALANCE;
                locks[i] = new java.util.concurrent.locks.ReentrantLock();
            }
            this.reaper = new Thread(this::reap, THREAD_PREFIX + "reaper");
            this.reaper.setDaemon(true);
            this.reaper.start();
        }

        @Override
        public String handle(String tenant, String request) throws Exception {
            java.util.concurrent.Future<String> outer = pool.submit(() -> {
                if (tenant != null) {
                    CURRENT_TENANT.set(tenant);                 // TODO symptom 3
                }

                // Validation is farmed out so it can run "in parallel", and the
                // request waits for the verdict before answering.
                java.util.concurrent.Future<String> validation =
                        pool.submit(() -> validate(request));   // TODO symptom 2
                validation.get();

                String effective = CURRENT_TENANT.get();
                if (effective == null) {
                    effective = ANONYMOUS;
                }
                auditQueue.add(effective + "/" + request);
                completed.incrementAndGet();
                return "tenant=" + effective + "|req=" + request;
            });
            return outer.get();
        }

        private String validate(String request) {
            return request.isEmpty() ? "rejected" : "accepted";
        }

        @Override
        public void transfer(int fromLedger, int toLedger, long amount) {
            if (fromLedger == toLedger) {
                return;
            }
            locks[fromLedger].lock();                           // TODO symptom 1
            try {
                locks[toLedger].lock();
                try {
                    ledgers[fromLedger] -= amount;
                    ledgers[toLedger] += amount;
                } finally {
                    locks[toLedger].unlock();
                }
            } finally {
                locks[fromLedger].unlock();
            }
        }

        /** Drains the audit queue in the background. */
        private void reap() {
            while (running) {
                String entry = auditQueue.poll();               // TODO symptom 4
                if (entry != null) {
                    // pretend to write it somewhere
                    entry.length();
                }
            }
        }

        @Override
        public long ledgerTotal() {
            long total = 0;
            for (int i = 0; i < LEDGERS; i++) {
                locks[i].lock();
                try {
                    total += ledgers[i];
                } finally {
                    locks[i].unlock();
                }
            }
            return total;
        }

        @Override
        public long completed() {
            return completed.get();
        }

        @Override
        public void shutdown() {
            running = false;
            reaper.interrupt();
            pool.shutdownNow();
        }
    }
}
