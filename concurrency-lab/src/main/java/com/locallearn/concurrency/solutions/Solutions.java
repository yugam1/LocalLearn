package com.locallearn.concurrency.solutions;

import com.locallearn.concurrency.api.Contracts.Bank;
import com.locallearn.concurrency.api.Contracts.BoundedQueue;
import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.api.Contracts.Counter;
import com.locallearn.concurrency.api.Contracts.Inventory;
import com.locallearn.concurrency.api.Contracts.InterruptibleWorker;
import com.locallearn.concurrency.api.Contracts.Pipeline;
import com.locallearn.concurrency.api.Contracts.StopSignal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * ═══════════════════════════════════════════════════════════════════════════
 *  REFERENCE SOLUTIONS — try each exercise first.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * <p>The same test suite runs against these and against {@code exercises.*}.
 * If {@code Sol*Test} passes and {@code Ex*Test} fails, the harness is sound
 * and the bug is in your implementation — which is the point of having both.
 *
 * <p>Each class documents <em>why</em> this shape and not another one, because
 * several alternatives are also correct and the interesting part is the
 * trade-off between them.
 */
public final class Solutions {

    private Solutions() {
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 1
    /**
     * {@code LongAdder} over {@code AtomicLong} because this is a write-heavy
     * counter read rarely — exactly the metrics-counter shape from D9, where
     * LongAdder was ~10x faster at 16 threads.
     *
     * <p>If you wrote {@code AtomicLong}, that is also correct and is the right
     * answer when you need the value each increment returns (ID generation).
     * {@code synchronized} is correct too and the slowest of the three here;
     * it earns its place only when more than one field is involved.
     */
    public static final class Sol1Counter implements Counter {
        private final LongAdder value = new LongAdder();

        @Override
        public void increment() {
            value.increment();
        }

        @Override
        public long count() {
            // Not an atomic snapshot: sum() walks the cells and a concurrent
            // increment may land mid-walk. Fine for a counter you read when
            // quiescent; wrong if you need a consistent point-in-time value.
            return value.sum();
        }
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 2
    /**
     * One keyword. Of volatile's three guarantees we need <b>visibility</b>:
     * the read must actually happen every iteration instead of being hoisted
     * out of the worker's loop.
     *
     * <p>We do not need atomicity (a boolean write is already atomic) and we do
     * not need the ordering guarantee here — though if {@code stop()} also set
     * up state the worker reads afterwards, the ordering half is what would
     * make that safe, and that is the "publish with a volatile flag" idiom.
     *
     * <p>{@code AtomicBoolean} also works and is strictly heavier. Use it when
     * you need {@code compareAndSet} — e.g. "only the first caller to stop runs
     * the shutdown".
     */
    public static final class Sol2StopSignal implements StopSignal {
        private volatile boolean stopped;

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public boolean shouldStop() {
            return stopped;
        }
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 3
    /**
     * A CAS retry loop: read, check, and commit only if nothing moved.
     * Lock-free, so no thread ever parks and no deadlock is possible.
     *
     * <p>The critical detail is that the re-read is <b>inside</b> the loop. If
     * you hoist {@code current} out, you have rebuilt the original bug with
     * extra steps.
     *
     * <p>A {@code synchronized} version is equally correct and would be the
     * better choice the moment reserving also has to touch a second field (a
     * reservation list, an audit counter) under the same invariant — CAS
     * cannot span two variables.
     *
     * <p>And in a real service none of this is sufficient: with two JVMs behind
     * a load balancer, an in-process lock protects one process. The invariant
     * has to move to the database — {@code UPDATE ... WHERE stock >= ?} or the
     * {@code @Version} optimistic-locking pattern in docs/phase1_task5.md,
     * which is this same compare-and-set idea one layer down.
     */
    public static final class Sol3Inventory implements Inventory {
        private final AtomicInteger stock;

        public Sol3Inventory(int initialStock) {
            this.stock = new AtomicInteger(initialStock);
        }

        @Override
        public boolean reserve(int quantity) {
            while (true) {
                int current = stock.get();          // re-read EVERY attempt
                if (current < quantity) {
                    return false;                   // re-check EVERY attempt
                }
                if (stock.compareAndSet(current, current - quantity)) {
                    return true;
                }
                // Lost the race; somebody changed stock since our read. Loop.
            }
        }

        @Override
        public int remaining() {
            return stock.get();
        }
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 4
    /**
     * Cooperative cancellation, all three parts:
     * <ol>
     *   <li>the loop polls {@code isInterrupted()}, so CPU-bound stretches can
     *       still notice;</li>
     *   <li>the catch <b>restores</b> the flag that the throw cleared, so
     *       callers up the stack can still see the request;</li>
     *   <li>cleanup is in a {@code finally}, so it runs on every exit path.</li>
     * </ol>
     */
    public static final class Sol4Worker implements InterruptibleWorker {
        private volatile long units;
        private volatile boolean cleanedUp;

        @Override
        public void run() {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(10);
                    units++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // restore what the throw cleared
            } finally {
                cleanedUp = true;                     // runs on every path out
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
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 5
    /**
     * Lock ordering — the fix to reach for first. Acquire by ascending account
     * index, always, on every path. That makes a circular wait impossible:
     * a cycle would require some thread to hold a higher index while waiting
     * for a lower one, and no thread ever does.
     *
     * <p>No retries, no timeouts, nothing to tune. Compare that with
     * {@code tryLock}-and-backoff (D11, fix 2), which is correct but needs
     * randomised jitter to avoid livelock and re-does work on every retry.
     *
     * <p>The self-transfer guard matters: without it, {@code transfer(3, 3, x)}
     * takes the same monitor twice. That happens to be harmless for
     * {@code synchronized} because it is reentrant — but the same bug with a
     * non-reentrant lock deadlocks a thread against itself, and it is worth
     * being explicit rather than leaning on reentrancy by accident.
     */
    public static final class Sol5Bank implements Bank {
        private final long[] balances;
        private final Object[] locks;

        public Sol5Bank(int accounts, long initialBalance) {
            balances = new long[accounts];
            locks = new Object[accounts];
            for (int i = 0; i < accounts; i++) {
                balances[i] = initialBalance;
                locks[i] = new Object();
            }
        }

        @Override
        public void transfer(int fromAccount, int toAccount, long amount) {
            if (fromAccount == toAccount) {
                return;
            }
            // A total order over the locks. Any consistent order works — index,
            // primary key, identity hash — as long as EVERY path uses the same one.
            int first = Math.min(fromAccount, toAccount);
            int second = Math.max(fromAccount, toAccount);

            synchronized (locks[first]) {
                synchronized (locks[second]) {
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
            // Note: this is NOT an atomic snapshot — it takes each lock in turn,
            // so a transfer can complete between two of the reads. It is exact
            // only when the system is quiescent, which is when the test calls it.
            // A truly atomic total would need every lock held at once (in
            // ascending order, naturally).
            long total = 0;
            for (int i = 0; i < balances.length; i++) {
                total += balance(i);
            }
            return total;
        }
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 6
    /**
     * {@code ConcurrentHashMap.computeIfAbsent} — the whole solution is one
     * line, and the reason it is the right line is worth spelling out.
     *
     * <p>It holds the bin lock for that key's hash bucket while the mapping
     * function runs, so exactly one thread loads a given key and the rest block
     * and receive its result. Crucially the lock is <b>per bin</b>, not global:
     * two threads loading two different cold keys still run in parallel. A
     * {@code synchronized get()} also passes the compute-once assertion but
     * serialises every unrelated load behind one lock, which is precisely the
     * bottleneck you were trying to remove.
     *
     * <p>Two caveats worth knowing before you use this in anger:
     * <ul>
     *   <li>The mapping function must not touch the same map — recursive update
     *       throws {@code IllegalStateException} or, on older versions, hangs.</li>
     *   <li>A slow loader holds the bin lock, so other keys hashing to the same
     *       bin wait. For genuinely slow loads, cache a
     *       {@code CompletableFuture} instead of the value: the future is
     *       inserted immediately and the bin lock is released while the work
     *       proceeds.</li>
     * </ul>
     */
    public static final class Sol6Cache implements ComputeOnceCache {
        private final Map<String, String> cache = new ConcurrentHashMap<>();
        private final Function<String, String> loader;
        private final AtomicInteger loads = new AtomicInteger();

        public Sol6Cache(Function<String, String> expensiveLoader) {
            this.loader = expensiveLoader;
        }

        @Override
        public String get(String key) {
            return cache.computeIfAbsent(key, k -> {
                loads.incrementAndGet();
                return loader.apply(k);
            });
        }

        @Override
        public int loadCount() {
            return loads.get();
        }
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 7
    /**
     * {@code ArrayBlockingQueue} in miniature: one lock, two conditions.
     *
     * <p>Two conditions rather than one monitor is the whole point. With a
     * single wait-set you must call {@code notifyAll()} and wake every blocked
     * producer <em>and</em> consumer, most of whom re-check, find their
     * condition still false, and go back to sleep — the thundering herd. With
     * separate {@code notFull} and {@code notEmpty} queues, {@code signal()}
     * wakes exactly one thread that can actually make progress.
     *
     * <p>And {@code while}, never {@code if}, around every {@code await()}.
     * Three independent reasons, any one of which is sufficient: spurious
     * wakeups are permitted by the JLS and need no cause; {@code signalAll}
     * wakes threads whose condition is still false; and between being signalled
     * and re-acquiring the lock, another thread may already have taken the item
     * you were woken for.
     */
    public static final class Sol7Queue<T> implements BoundedQueue<T> {
        private final Object[] items;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();
        private final Condition notEmpty = lock.newCondition();
        private int head, tail, count, peak;

        public Sol7Queue(int capacity) {
            this.items = new Object[capacity];
        }

        @Override
        public void put(T item) throws InterruptedException {
            lock.lock();
            try {
                while (count == items.length) {   // while, NOT if
                    notFull.await();              // releases the lock while parked
                }
                items[tail] = item;
                tail = (tail + 1) % items.length;
                count++;
                peak = Math.max(peak, count);
                notEmpty.signal();                // wake one consumer, not everybody
            } finally {
                lock.unlock();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public T take() throws InterruptedException {
            lock.lock();
            try {
                while (count == 0) {
                    notEmpty.await();
                }
                T item = (T) items[head];
                items[head] = null;               // null it out so the queue does
                                                  // not pin a dead reference
                head = (head + 1) % items.length;
                count--;
                notFull.signal();
                return item;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int size() {
            lock.lock();
            try {
                return count;
            } finally {
                lock.unlock();
            }
        }

        @Override
        public int peakSize() {
            lock.lock();
            try {
                return peak;
            } finally {
                lock.unlock();
            }
        }
    }

    /**
     * EXERCISE 8 — the pipeline: bounded queue for backpressure, parked
     * workers, poison-pill drain.
     *
     * <p>Three fixes, one per planted defect:
     * <ol>
     *   <li><b>Backpressure</b>: the backlog is an {@code ArrayBlockingQueue}
     *       of exactly the given capacity, and {@code submit} uses {@code put},
     *       which blocks while full. A fast producer is slowed to the workers'
     *       pace instead of growing the heap (D13). Note the peak is sampled
     *       <em>after</em> put returns — the queue itself enforces the bound,
     *       the counter only reports it.</li>
     *   <li><b>No busy-wait</b>: workers block in {@code take()}, parking while
     *       the backlog is empty, exactly like Sol7 — the library queue parks
     *       on the same two-condition mechanism you built there.</li>
     *   <li><b>Drain-then-stop</b>: shutdown sends one {@link #PILL} per worker
     *       through the data queue. FIFO means each pill arrives after every
     *       item submitted before shutdown, so a worker that sees a pill has
     *       nothing left to drain. Identity comparison ({@code ==}) means no
     *       real item can impersonate it — {@code equals()} would let the
     *       string "POISON" from a user kill a worker early, which is why the
     *       pill is a deliberately unique instance.</li>
     * </ol>
     *
     * <p>One subtlety: the pills also occupy queue capacity, so with capacity 1
     * and 8 workers, {@code shutdownAndDrain} feeds pills one at a time as
     * workers make room. {@code put} handles that for free — another reason
     * blocking verbs beat clever bookkeeping.
     */
    public static final class Sol8Pipeline implements Pipeline {
        /** Compared with ==, never equals(): must be THIS object, see class doc. */
        private static final String PILL = new String("POISON-PILL");

        private final ArrayBlockingQueue<String> backlog;
        private final List<Thread> workers = new ArrayList<>();
        private final AtomicLong processed = new AtomicLong();
        private final AtomicInteger backlogPeak = new AtomicInteger();

        public Sol8Pipeline(int capacity, int workerCount, Consumer<String> processor) {
            this.backlog = new ArrayBlockingQueue<>(capacity);
            for (int w = 0; w < workerCount; w++) {
                Thread worker = new Thread(() -> {
                    try {
                        while (true) {
                            String item = backlog.take();   // parks while empty — no spin
                            if (item == PILL) {
                                return;                     // drained: FIFO puts the pill last
                            }
                            processor.accept(item);
                            processed.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt(); // abandon-style stop, if ever used
                    }
                }, "pipeline-worker-" + w);
                workers.add(worker);
                worker.start();
            }
        }

        @Override
        public void submit(String item) throws InterruptedException {
            backlog.put(item);                              // blocks at capacity: backpressure
            backlogPeak.accumulateAndGet(backlog.size(), Math::max);
        }

        @Override
        public void shutdownAndDrain() throws InterruptedException {
            for (int i = 0; i < workers.size(); i++) {
                backlog.put(PILL);                          // one pill stops exactly one worker
            }
            for (Thread worker : workers) {
                worker.join();                              // returns only when all have drained
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

    // ══════════════════════════════════════════════════════════ SOLUTION 15
    /**
     * The incident, repaired. Four defects, four different mechanisms, four
     * different topics — and the point of the exercise is that nothing in the
     * symptom told you which was which.
     *
     * <h2>1. The partial hang — a lock-ordering deadlock (topic 4)</h2>
     * {@code transfer(0, 1)} took lock 0 then lock 1 while {@code transfer(1, 0)}
     * took them in the opposite order: a circular wait, the last of Coffman's
     * four conditions. The fix is a <b>total order</b> over the locks — ascending
     * index here, but any consistent order works as long as every path uses the
     * same one. A cycle would now require a thread holding a higher index to
     * wait for a lower one, and no thread ever does. No retries, no timeouts,
     * nothing to tune.
     *
     * <p>This is the only one of the four that {@code findDeadlockedThreads()}
     * would have handed you, and even then only because both locks are AQS
     * locks with owners. Note the threads showed {@code WAITING}, not
     * {@code BLOCKED}.
     *
     * <h2>2. The total hang — pool exhaustion (topics 5 and 8)</h2>
     * Every worker submitted its validation sub-task to <b>the pool it was
     * running on</b> and then blocked on the resulting {@code Future}. With
     * four workers and four such requests in flight, all four threads were
     * waiting for tasks that could only be run by a thread that was already
     * waiting. The queue was unbounded, so nothing was rejected and nothing
     * threw; the service simply stopped, permanently, and no deadlock detector
     * saw a thing, because a {@code Future} has no owner to form a cycle with.
     *
     * <p>The fix here is to stop splitting the work at all: validation is three
     * instructions and is now called directly. The general rule it comes from is
     * the one to remember — <b>never block a pool thread on work that can only
     * be performed by the same pool.</b> When the sub-task genuinely must run
     * elsewhere (it is slow, or it is I/O), give it its <em>own</em> pool, so
     * the two can never starve each other.
     *
     * <h2>3. The wrong answers — a ThreadLocal left on a pooled thread (topic 6)</h2>
     * The context was installed only when a request had a tenant and was never
     * removed, so a worker went back into the pool still wearing the last
     * identity it served, and the next task to land on that thread inherited it.
     * The thread is reused; the {@code ThreadLocal} is per thread, not per task.
     *
     * <p>Two things are needed and neither alone is sufficient: set it
     * <b>unconditionally</b>, so an absent tenant overwrites rather than
     * inherits, and {@code remove()} it in a {@code finally}, so nothing
     * survives the task even if the body throws. In a pool, {@code remove()} is
     * not an optimisation to avoid a memory leak — it is a correctness
     * requirement, and the failure it prevents is billing the wrong customer.
     *
     * <h2>4. The pinned core — a busy-wait (topic 5)</h2>
     * {@code poll()} returns {@code null} immediately on an empty queue, so a
     * loop around it spins. The fix is the verb: {@code poll(timeout, unit)}
     * parks like {@code take()} but surfaces periodically, which is what a
     * consumer loop that also has to notice a shutdown flag actually wants.
     */
    public static final class Sol15IncidentService
            implements com.locallearn.concurrency.api.Contracts.IncidentService {

        private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

        private final java.util.concurrent.ThreadPoolExecutor pool;
        private final java.util.concurrent.BlockingQueue<String> auditQueue =
                new java.util.concurrent.LinkedBlockingQueue<>();
        private final Thread reaper;
        private final long[] ledgers = new long[LEDGERS];
        private final ReentrantLock[] locks = new ReentrantLock[LEDGERS];
        private final AtomicLong completed = new AtomicLong();
        private volatile boolean running = true;

        public Sol15IncidentService(int workers) {
            AtomicInteger seq = new AtomicInteger();
            this.pool = new java.util.concurrent.ThreadPoolExecutor(
                    workers, workers, 0L, java.util.concurrent.TimeUnit.MILLISECONDS,
                    new java.util.concurrent.LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable,
                                THREAD_PREFIX + "worker-" + seq.getAndIncrement());
                        thread.setDaemon(true);
                        return thread;
                    });
            for (int i = 0; i < LEDGERS; i++) {
                ledgers[i] = INITIAL_BALANCE;
                locks[i] = new ReentrantLock();
            }
            this.reaper = new Thread(this::reap, THREAD_PREFIX + "reaper");
            this.reaper.setDaemon(true);
            this.reaper.start();
        }

        @Override
        public String handle(String tenant, String request) throws Exception {
            java.util.concurrent.Future<String> outer = pool.submit(() -> {
                // FIX 3a: set unconditionally. A request with no tenant must
                // OVERWRITE whatever this thread was carrying, not inherit it.
                CURRENT_TENANT.set(tenant == null ? ANONYMOUS : tenant);
                try {
                    // FIX 2: the validation runs here, on this thread. Nothing
                    // is submitted to the pool we are currently occupying, so
                    // no worker can ever wait for a task only it could run.
                    validate(request);

                    String effective = CURRENT_TENANT.get();
                    auditQueue.add(effective + "/" + request);
                    completed.incrementAndGet();
                    return "tenant=" + effective + "|req=" + request;
                } finally {
                    // FIX 3b: in a finally, so the context cannot survive this
                    // task even if the body throws. The thread is about to be
                    // handed to a stranger.
                    CURRENT_TENANT.remove();
                }
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
            // FIX 1: a total order over the locks, so a circular wait cannot
            // form. Any consistent order works; every path must use the same one.
            int first = Math.min(fromLedger, toLedger);
            int second = Math.max(fromLedger, toLedger);

            locks[first].lock();
            try {
                locks[second].lock();
                try {
                    ledgers[fromLedger] -= amount;
                    ledgers[toLedger] += amount;
                } finally {
                    locks[second].unlock();
                }
            } finally {
                locks[first].unlock();
            }
        }

        private void reap() {
            while (running) {
                try {
                    // FIX 4: poll(timeout) parks instead of spinning, but still
                    // surfaces regularly so the running flag gets a look-in.
                    // take() would park too, and would never notice the flag.
                    String entry = auditQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                    if (entry != null) {
                        entry.length();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();  // restore, then leave
                    return;
                }
            }
        }

        @Override
        public long ledgerTotal() {
            // Not an atomic snapshot — it takes each lock in turn, so a transfer
            // can land between two reads. Exact when quiescent, which is when
            // the test asks.
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
        public void shutdown() throws InterruptedException {
            running = false;
            reaper.interrupt();
            pool.shutdown();
            if (!pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
            reaper.join(java.util.concurrent.TimeUnit.SECONDS.toMillis(5));
        }
    }
}
