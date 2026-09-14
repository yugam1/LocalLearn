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

    // ═════════════════════════════════════════════════════════ SOLUTION 13
    /**
     * A thread pool in ~90 lines. Every piece is something the lab already
     * built; the only genuinely new thing is the order of the four checks in
     * {@link #execute}.
     *
     * <h2>Why the queue is tried before growing the pool</h2>
     * A queue slot is a pointer; a thread is ~1 MB of reserved stack plus a
     * scheduler entity. So the pool spends the cheap resource first and treats
     * thread creation as the last thing it does before refusing work outright.
     * This is why {@code maxPoolSize} is unreachable configuration whenever the
     * queue is large (D22).
     *
     * <h2>Why shutdown needs two verbs and not a flag</h2>
     * A single {@code volatile stopped} flag cannot express both meanings, and
     * worse, it cannot even deliver one of them: a worker parked in
     * {@code take()} never re-reads it (topic 5, P3). So:
     * <ul>
     *   <li><b>Drain</b> ({@link #shutdownAndAwait}) uses Ex8's poison pill —
     *       one per worker, through the same FIFO, so it necessarily arrives
     *       after every task submitted before it. "Saw the pill" proves "drained
     *       everything".</li>
     *   <li><b>Abandon</b> ({@link #shutdownNow}) drains the queue into a list,
     *       hands it back, and interrupts the workers. The list is the point:
     *       the loss becomes countable instead of silent.</li>
     * </ul>
     *
     * <h2>The detail that is easy to miss</h2>
     * A worker is created <em>with</em> its first task rather than being pointed
     * at the queue, because a brand-new worker must run the task that caused it
     * to exist — otherwise, with a full queue, that task would have nowhere to
     * go. {@code ThreadPoolExecutor.addWorker} takes a {@code firstTask} for the
     * same reason.
     */
    public static final class Sol13Pool implements com.locallearn.concurrency.api.Contracts.MiniPool {
        /** Compared with ==, never equals(): identity is the whole guarantee. */
        private static final Runnable PILL = () -> { };

        private final int corePoolSize;
        private final int maxPoolSize;
        private final java.util.concurrent.BlockingQueue<Runnable> queue;

        private final List<Thread> workers = new ArrayList<>();
        private final Object poolLock = new Object();       // guards `workers` and `shutdown`
        private final AtomicLong completed = new AtomicLong();
        private final AtomicInteger largest = new AtomicInteger();
        private volatile boolean shutdown;

        public Sol13Pool(int corePoolSize, int maxPoolSize, int queueCapacity) {
            this.corePoolSize = corePoolSize;
            this.maxPoolSize = maxPoolSize;
            this.queue = new java.util.concurrent.ArrayBlockingQueue<>(queueCapacity);
        }

        @Override
        public void execute(Runnable task) {
            if (task == null) {
                throw new NullPointerException("task");
            }
            synchronized (poolLock) {
                if (shutdown) {
                    throw new java.util.concurrent.RejectedExecutionException("pool is shut down");
                }
                // Step 1 — below core: always a new worker, even if the queue is empty.
                // A pool with idle capacity should be growing toward its core size.
                if (workers.size() < corePoolSize) {
                    addWorker(task);
                    return;
                }
            }
            // Step 2 — the QUEUE, before any further growth. This is the rule.
            if (queue.offer(task)) {
                return;
            }
            synchronized (poolLock) {
                if (shutdown) {
                    throw new java.util.concurrent.RejectedExecutionException("pool is shut down");
                }
                // Step 3 — the queue refused, so now (and only now) grow to max.
                if (workers.size() < maxPoolSize) {
                    addWorker(task);
                    return;
                }
            }
            // Step 4 — at max with a full queue. AbortPolicy's behaviour (D23).
            throw new java.util.concurrent.RejectedExecutionException(
                    "pool at max (" + maxPoolSize + ") and queue full");
        }

        /** Must be called holding {@code poolLock}. */
        private void addWorker(Runnable firstTask) {
            Thread worker = new Thread(() -> runWorker(firstTask),
                    "minipool-worker-" + workers.size());
            workers.add(worker);
            largest.accumulateAndGet(workers.size(), Math::max);
            worker.start();
        }

        private void runWorker(Runnable firstTask) {
            try {
                Runnable task = firstTask;
                while (true) {
                    if (task == PILL) {
                        return;                     // drained: FIFO put the pill last
                    }
                    if (task != null) {
                        try {
                            task.run();
                        } catch (RuntimeException | Error e) {
                            // A pool must outlive a failing task. Note this is the
                            // execute() semantics of D24: the failure is visible to
                            // an UncaughtExceptionHandler, and the worker survives.
                            Thread current = Thread.currentThread();
                            Thread.UncaughtExceptionHandler handler =
                                    current.getUncaughtExceptionHandler();
                            if (handler != null) {
                                handler.uncaughtException(current, e);
                            }
                        } finally {
                            completed.incrementAndGet();
                        }
                    }
                    task = queue.take();            // PARKS while empty — never a spin
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // shutdownNow(): abandon, flag restored
            }
        }

        @Override
        public void shutdownAndAwait() throws InterruptedException {
            List<Thread> snapshot;
            synchronized (poolLock) {
                shutdown = true;                    // stop accepting
                snapshot = new ArrayList<>(workers);
            }
            // One pill per worker, through the data queue. put() blocks if the
            // queue is full, which is correct: it simply waits for a worker to
            // make room, and every real task still ahead of the pill runs first.
            for (int i = 0; i < snapshot.size(); i++) {
                queue.put(PILL);
            }
            for (Thread worker : snapshot) {
                worker.join();
            }
        }

        @Override
        public List<Runnable> shutdownNow() {
            List<Thread> snapshot;
            synchronized (poolLock) {
                shutdown = true;
                snapshot = new ArrayList<>(workers);
            }
            // Drain the backlog into a list FIRST, so the caller can see exactly
            // what was abandoned — then interrupt. Draining after interrupting
            // would race with workers still taking from the queue.
            List<Runnable> neverStarted = new ArrayList<>();
            queue.drainTo(neverStarted);
            neverStarted.removeIf(r -> r == PILL);  // pills are not user work
            for (Thread worker : snapshot) {
                worker.interrupt();
            }
            return neverStarted;
        }

        @Override
        public int poolSize() {
            int alive = 0;
            synchronized (poolLock) {
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

    // ═════════════════════════════════════════════════════════ SOLUTION 14
    /**
     * Two workloads, two executors, because they are two different questions.
     *
     * <pre>
     *   CPU-bound : a bounded platform pool sized to the cores. There are only
     *               N cores; more runnable threads than that cannot compute
     *               faster, they can only take turns. (D24 measured the
     *               plateau.)
     *   IO-bound  : one virtual thread per task. A blocked virtual thread
     *               unmounts and holds no OS thread, so "how many can block at
     *               once" stops being a resource question. (D27 measured
     *               ~68,000 blocking tasks/sec against ~120 for a cores-sized
     *               platform pool.)
     * </pre>
     *
     * <h2>Why there is no lock here at all</h2>
     * The broken version locked to protect a shared result list. That lock was
     * doing real work — {@code ArrayList} genuinely is not thread-safe — but it
     * was the wrong fix for the problem, because it serialised the tasks
     * themselves. The right fix is to not share the list: each task returns its
     * value through its own {@code Future}, and the caller assembles the results
     * single-threadedly afterwards, in submission order, for free.
     *
     * <p>That matters twice over on the IO path. Holding a monitor across a
     * blocking call <b>pins</b> the virtual thread to its carrier (Java 21), and
     * D27 measured a 108x throughput collapse from pinning with no contention at
     * all. The general rule this leaves you with: <b>never hold a lock across a
     * blocking call</b> — it was always bad for throughput, and with virtual
     * threads it is bad for throughput in a new and much larger way.
     */
    public static final class Sol14Runner
            implements com.locallearn.concurrency.api.Contracts.WorkloadRunner {

        private static final int CORES = Runtime.getRuntime().availableProcessors();

        /** CPU work: bounded, because cores are the scarce resource. */
        private final java.util.concurrent.ExecutorService cpuPool =
                java.util.concurrent.Executors.newFixedThreadPool(CORES);

        @Override
        public List<Long> runCpuBound(List<java.util.concurrent.Callable<Long>> tasks)
                throws Exception {
            return invokeAllInOrder(cpuPool, tasks);
        }

        @Override
        public List<Long> runIoBound(List<java.util.concurrent.Callable<Long>> tasks)
                throws Exception {
            // A new virtual-thread executor per call is deliberate and cheap:
            // this is a thread FACTORY, not a pool, so there is nothing to reuse
            // and nothing to size. try-with-resources closes it, and close()
            // waits for every task — structured, in the D28 sense.
            try (java.util.concurrent.ExecutorService ioExecutor =
                         java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                return invokeAllInOrder(ioExecutor, tasks);
            }
        }

        /**
         * Submits everything, then collects in submission order. No shared
         * mutable state, therefore no lock, therefore nothing held across a
         * blocking call.
         */
        private static List<Long> invokeAllInOrder(
                java.util.concurrent.ExecutorService executor,
                List<java.util.concurrent.Callable<Long>> tasks) throws Exception {
            List<java.util.concurrent.Future<Long>> futures = new ArrayList<>(tasks.size());
            for (java.util.concurrent.Callable<Long> task : tasks) {
                futures.add(executor.submit(task));
            }
            List<Long> results = new ArrayList<>(tasks.size());
            for (java.util.concurrent.Future<Long> future : futures) {
                try {
                    results.add(future.get());
                } catch (java.util.concurrent.ExecutionException e) {
                    // D24: a Future swallows the failure until someone asks.
                    // We are asking, so surface the real cause, not the wrapper.
                    if (e.getCause() instanceof Exception cause) {
                        throw cause;
                    }
                    throw e;
                }
            }
            return results;
        }

        @Override
        public void close() {
            cpuPool.shutdownNow();
        }
    }
}
