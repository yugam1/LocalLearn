package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.BoundedQueue;
import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.api.Contracts.Counter;
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
