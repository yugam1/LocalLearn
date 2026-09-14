package com.locallearn.concurrency.solutions;

import com.locallearn.concurrency.api.Contracts;
import com.locallearn.concurrency.api.Contracts.Bank;
import com.locallearn.concurrency.api.Contracts.BoundedQueue;
import com.locallearn.concurrency.api.Contracts.ComputeOnceCache;
import com.locallearn.concurrency.api.Contracts.Counter;
import com.locallearn.concurrency.api.Contracts.Inventory;
import com.locallearn.concurrency.api.Contracts.InterruptibleWorker;
import com.locallearn.concurrency.api.Contracts.Pipeline;
import com.locallearn.concurrency.api.Contracts.StopSignal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntConsumer;

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

    // ══════════════════════════════════════════════════════════ SOLUTION 9
    /**
     * One call per read-modify-write, so the map holds the bin lock across the
     * read <em>and</em> the write. The map type never changed — it was a
     * {@code ConcurrentHashMap} in the broken version too, which is the point.
     *
     * <p>{@code merge(key, 1L, Long::sum)} is the counter idiom: insert 1 if the
     * key is absent, otherwise combine the old value with the new one. It
     * replaces {@code get}-then-{@code put} and it is not merely tidier — it is
     * the difference between losing ~10% of increments and losing none.
     *
     * <p>{@code compute} handles the decrement, and the detail worth carrying
     * away is that <b>returning null removes the entry</b>. That satisfies "a key
     * consumed down to zero must disappear" inside the same atomic step, rather
     * than with a follow-up {@code remove()} that would reopen exactly the gap we
     * are closing.
     *
     * <p>{@code consume} has to report whether it took something, and a mapping
     * function cannot return two values. The one-element array captures it from
     * inside the function — safe because the function runs exactly once per call,
     * on this thread, under the bin lock, and is read only after {@code compute}
     * has returned.
     *
     * <p>Two alternatives worth knowing. {@code AtomicLong} values with
     * {@code computeIfAbsent(key, k -> new AtomicLong())} then
     * {@code incrementAndGet()} is faster under heavy per-key contention, because
     * the increment no longer touches the map at all — but removal-at-zero then
     * needs {@code remove(key, value)} and careful thought about a racing
     * {@code record}. And {@code LongAdder} values (D9) win when the same key is
     * hammered by many threads and read rarely. {@code merge} is the right
     * default: it is one line, it is correct, and it is obvious.
     */
    public static final class Sol9EventCounts implements Contracts.EventCounts {

        private final ConcurrentHashMap<String, Long> counts = new ConcurrentHashMap<>();

        @Override
        public void record(String key) {
            counts.merge(key, 1L, Long::sum);
        }

        @Override
        public boolean consume(String key) {
            boolean[] consumed = {false};
            counts.compute(key, (k, current) -> {
                if (current == null || current <= 0) {
                    return null;              // absent, and stays absent
                }
                consumed[0] = true;
                return current == 1 ? null : current - 1;   // null REMOVES the entry
            });
            return consumed[0];
        }

        @Override
        public long count(String key) {
            return counts.getOrDefault(key, 0L);
        }

        @Override
        public int distinctKeys() {
            // Exact here because the tests call it when the map is quiescent.
            // On a busy map this is an estimate — D16 measured it drifting by
            // tens of thousands — and you must never branch on it (D16's
            // `if (map.size() < CAP) put(...)` overshoots for exactly that reason).
            return counts.size();
        }
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 10
    /**
     * Thread confinement, done properly: a {@link ThreadLocal} for the storage,
     * a {@code finally} for the cleanup, and save-and-restore rather than clear
     * so that nesting works.
     *
     * <p>The {@code ThreadLocal} is {@code static final} on purpose. A
     * {@code ThreadLocal} instance is the <em>key</em> into each thread's map,
     * so one shared key is what makes "the same context" mean the same thing
     * everywhere. A per-instance {@code ThreadLocal} created per request would
     * be a fresh key each time — every lookup missing, and every dead key left
     * in the thread's map.
     *
     * <p>The restore logic is the part people skip:
     * <pre>{@code
     * if (previous == null) ID.remove(); else ID.set(previous);
     * }</pre>
     * Unconditionally clearing breaks nesting — an inner scope exiting would
     * wipe the outer request's id. Unconditionally setting {@code previous} would
     * be worse still: when {@code previous} was null it would store a null and
     * <b>leave the entry in the thread's map</b>, which is the leak D18
     * demonstrates with an {@code initialValue}. {@code remove()}, never
     * {@code set(null)}.
     *
     * <p>This is MDC, one layer down. {@code MDC.put} / {@code MDC.clear()} in a
     * {@code finally} is this class with a logging API attached, and the two
     * consequences carry over unchanged: the value does not follow work you hand
     * to another thread (so {@code @Async} and {@code CompletableFuture} lose it
     * unless you copy it across with a task decorator), and on a pooled thread a
     * missing {@code remove()} is both a correctness bug and a memory leak.
     */
    public static final class Sol10Context implements Contracts.RequestContext {

        private static final ThreadLocal<String> ID = new ThreadLocal<>();

        @Override
        public void runWithCorrelationId(String correlationId, Runnable body) {
            String previous = ID.get();
            ID.set(correlationId);
            try {
                body.run();
            } finally {
                if (previous == null) {
                    ID.remove();              // remove(), NOT set(null) — see D18
                } else {
                    ID.set(previous);         // restore the enclosing scope
                }
            }
        }

        @Override
        public String currentCorrelationId() {
            return ID.get();
        }
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 11
    /**
     * A {@link CyclicBarrier} with a barrier action — the reusable half of the
     * 2×2, which is what the one-shot latch could never be.
     *
     * <p>Two things make this work, and both are easy to get wrong:
     *
     * <ol>
     *   <li><b>The barrier resets itself.</b> There is no {@code reset()} call
     *       anywhere below. The moment the last party arrives, the barrier trips
     *       and is immediately armed again for the next round. That is the whole
     *       difference from a latch, whose count reaches zero once and stays
     *       there — so the latch version had no barrier at all from round 2 on,
     *       silently.</li>
     *   <li><b>The tally lives in the barrier action.</b> It runs on the last
     *       thread to arrive, exactly once per round, while every other party is
     *       still parked. That is the only instant in the round when no worker is
     *       running, which makes it the only place the tally can be exact.
     *       Tallying after {@code await()} returns — even with a perfectly good
     *       barrier — races with the other workers starting the next round.</li>
     * </ol>
     *
     * <p>Workers park inside {@code await()} rather than spinning, which is what
     * the CPU-time test checks. A {@code while (arrived.get() < workers) { }}
     * loop would satisfy every correctness assertion and burn a core per waiting
     * worker — the same defect Ex7 and Ex8 measured, and the same reason those
     * tests exist.
     *
     * <p>{@link BrokenBarrierException} has to be caught, and catching it is not
     * ceremony. If any party is interrupted or times out, the rendezvous can
     * never complete, so the barrier marks itself broken and wakes everybody with
     * this exception instead of leaving them parked forever. That is precisely
     * the diagnosis a latch or semaphore cannot give you (D21).
     */
    public static final class Sol11Rounds implements Contracts.RoundSync {

        private final int workers;
        private final IntConsumer roundWork;
        private final List<Integer> tallies = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger arrived = new AtomicInteger();
        private final CyclicBarrier barrier;

        public Sol11Rounds(int workers, IntConsumer roundWork) {
            this.workers = workers;
            this.roundWork = roundWork;
            // The action runs on the last arriver, once per round, with every
            // other party still parked — the one safe window in the round.
            this.barrier = new CyclicBarrier(workers, () -> tallies.add(arrived.getAndSet(0)));
        }

        @Override
        public void runAll(int rounds) throws InterruptedException {
            CountDownLatch allFinished = new CountDownLatch(workers);
            for (int w = 0; w < workers; w++) {
                final int id = w;
                Thread worker = new Thread(() -> {
                    try {
                        for (int round = 0; round < rounds; round++) {
                            roundWork.accept(id);
                            arrived.incrementAndGet();
                            barrier.await();          // parks; reusable; resets itself
                        }
                    } catch (InterruptedException | BrokenBarrierException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        allFinished.countDown();      // in a finally, always (D19)
                    }
                }, "round-worker-" + id);
                worker.setDaemon(true);
                worker.start();
            }
            allFinished.await();
        }

        @Override
        public List<Integer> tallies() {
            return new ArrayList<>(tallies);
        }

        @Override
        public int workers() {
            return workers;
        }
    }

    // ══════════════════════════════════════════════════════════ SOLUTION 12
    /**
     * {@code acquire()} to wait for a slot, and {@code release()} in a
     * {@code finally} so that no exception can cost the pool one.
     *
     * <p>The {@code finally} is not defensive style, it is the difference
     * between a pool and a slow leak. A {@code release()} placed after the work
     * is skipped on every exceptional path, and a permit that is never released
     * is gone for the lifetime of the process. The failure is cumulative: the
     * pool shrinks one exception at a time, so what you see in production is not
     * an outage at the first error but a gradual slide, ending in every caller
     * parked forever — with no deadlock report, because a permit has no owner
     * and therefore no edge in the wait-for graph (D21).
     *
     * <p>The in-flight counter gets the same treatment and for the same reason:
     * a decrement placed after {@code task.call()} would drift upward on every
     * failure and quietly corrupt {@link #peakConcurrency()}.
     *
     * <p>{@code acquire()} rather than {@code tryAcquire()} is what the contract
     * asks for here — callers wait, nothing is rejected, nothing runs
     * unaccounted. Know what that buys and what it costs: it is topic 5's
     * <b>block</b>, and the queue it creates is made of parked threads that no
     * dashboard shows you. At a real service boundary
     * {@code tryAcquire(timeout)} is usually the better default, because
     * returning false is a decision you can count, log, alert on and turn into a
     * 503 — topic 5's <b>drop</b>, chosen deliberately rather than by omission.
     *
     * <p>One last property worth noticing: this is a pool of <em>permission</em>,
     * not of objects. Nothing here hands out a connection. If you need the object
     * too, the semaphore guards the borrow and a {@code BlockingQueue} holds the
     * instances — which is exactly how HikariCP is built, and why its
     * {@code maximumPoolSize} and {@code connectionTimeout} are the same two
     * knobs as {@code limit} and a {@code tryAcquire} timeout.
     */
    public static final class Sol12Pool implements Contracts.BoundedResourcePool {

        private final Semaphore slots;
        private final AtomicInteger inFlight = new AtomicInteger();
        private final AtomicInteger peak = new AtomicInteger();

        public Sol12Pool(int limit) {
            this.slots = new Semaphore(limit);
        }

        @Override
        public <T> T execute(Callable<T> task) throws Exception {
            slots.acquire();                 // WAIT for a slot; never proceed without one
            try {
                peak.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                try {
                    return task.call();
                } finally {
                    inFlight.decrementAndGet();
                }
            } finally {
                slots.release();             // every exit path, including the throw
            }
        }

        @Override
        public int peakConcurrency() {
            return peak.get();
        }

        @Override
        public int availableSlots() {
            return slots.availablePermits();
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
