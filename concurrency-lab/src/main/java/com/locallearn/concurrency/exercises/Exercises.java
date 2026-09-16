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

/*
 * ═══════════════════════════════════════════════════════════════════════════
 *  NOT THE FILE YOU WANT. Work in Ex01Counter.java … Ex15IncidentService.java.
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * This is the original single-file form of all fifteen exercises. Each one has
 * since been split into its own runnable file in this same package —
 * Ex01Counter.java through Ex15IncidentService.java — where the exercise
 * carries its own main that stress-tests it. That is where you edit, and
 * pressing Run on one of those files gives you a verdict in about a second.
 *
 * This class stays only because the JUnit contracts in src/test bind to it.
 * Those tests are optional and need Maven; the split files need neither.
 *
 * HOW TO WORK
 *   Open e.g. Ex03Inventory.java and press Run (Code Runner, Ctrl/Cmd+Alt+N),
 *   or from a terminal:
 *
 *     cd concurrency-lab
 *     ./run.sh Ex03Inventory
 *
 *   Each exercise hammers your class from many threads behind a start gate and
 *   repeats the whole trial hundreds of times, so "it passed once" is not an
 *   option. If it passes, your implementation is genuinely correct under
 *   contention, not merely lucky.
 *
 * RULES
 *   1. Do not change the method signatures or the checkers.
 *   2. Do not solve anything by making the exercise single-threaded.
 *   3. A solution that is correct but takes a global lock around everything
 *      will pass the correctness checks — but exercises 6 and 7 also assert
 *      properties (compute-once, no busy-wait) that a naive global lock will
 *      not satisfy.
 *   4. Reference implementations are in solutions/Solutions.java. Try each
 *      exercise before you open it; the whole value is in the attempt.
 */
public final class Exercises {

    private Exercises() {
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 1
    // Moved to its own runnable file: Ex01Counter.java — it carries a main that
    // stress-checks it, so you can iterate with plain java, no Maven round-trip.
    //   java -cp target/classes com.locallearn.concurrency.exercises.Ex01Counter

    // ══════════════════════════════════════════════════════════ EXERCISE 2
    /*
     * Make the stop signal visible. See t02visibility.D4_StaleFlagHang. The
     * test spins a worker on shouldStop() and then calls stop() from another
     * thread. As written, the JIT may hoist the field read out of the worker's
     * loop and the worker never exits — the test will time out rather than
     * fail fast, which is itself the lesson. Hint: one keyword. Think about
     * which of volatile's three guarantees you are relying on here, and
     * whether you need the other two.
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
    /*
     * Stop the overselling. See t03atomicity.D8_CheckThenActOversell. Classic
     * check-then-act. Note the twist: reserve takes a QUANTITY, not a single
     * unit, so decrementAndGet() is not available as a shortcut — you need a
     * real CAS loop or a lock. Hint: if you go the CAS route, remember to re-
     * read and re-check inside the loop. Reading once outside it recreates the
     * same bug.
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
    /*
     *   Make the worker cancellable. See t01threads.D3_InterruptionAndCancellation.
     *
     *   Two bugs in the code below, and they compound:
     *   1. The loop never checks the interrupt flag, so it cannot stop.
     *   2. The catch block swallows InterruptedException — it neither rethrows nor
     *      restores the flag — so the cancellation request is destroyed and nobody
     *      upstream can recover it.
     *
     *   The test asserts three things: the worker stops within 2 seconds of being
     *   interrupted, cleanedUp() is true afterwards, and the thread's interrupt
     *   flag is still set when run() returns.
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

        /*
         * Call this on the way out — from a finally block, so every exit path
         * runs it.
         */
        private void cleanup() {
            cleanedUp = true;
        }
    }

    // ══════════════════════════════════════════════════════════ EXERCISE 5
    /*
     * Transfer money without deadlocking. See t04locks.D11_Deadlock. The test
     * runs many threads transferring in both directions between the same
     * accounts. As written it deadlocks within milliseconds, and the test
     * fails on a timeout. Two things must hold when it finishes: no deadlock,
     * and totalMoney() unchanged — money is neither created nor destroyed.
     * Note that simply removing the locks fixes the deadlock and breaks the
     * second property, so you cannot cheat your way past this one. Hint: the
     * bug is not "two locks". It is "two locks acquired in two different
     * orders". Fix the order, or stop holding one while waiting for the other.
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
    /*
     *   Compute each value exactly once. See
     *   t04locks.D12_ReadWriteLockAndCondition.
     *
     *   This is the "cache stampede" / "thundering herd" problem. When 32 threads
     *   all miss on the same cold key at once, the naive version runs the expensive
     *   loader 32 times. In production that is 32 simultaneous calls to the service
     *   you were trying to protect, precisely when it is already slow.
     *
     *   The test asserts TWO things:
     *   1. correctness — every caller gets the right value, and the map is not
     *      corrupted (the HashMap here is not thread-safe either);
     *   2. loadCount() equals the number of distinct keys. Exactly once per key.
     *      Not "roughly once".
     *
     *   Hint: ConcurrentHashMap.computeIfAbsent gives you this for free, and gives
     *   it to you atomically per key rather than under one global lock — so two
     *   threads asking for two DIFFERENT cold keys still load in parallel. That
     *   last part is why it beats synchronized get(), which also passes the
     *   compute-once assertion but serialises every unrelated load behind one lock.
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
    /*
     *   Build a blocking bounded queue. See t04locks.D12_ReadWriteLockAndCondition.
     *
     *   The hardest one, and the most worth doing: this is ArrayBlockingQueue in
     *   miniature, and writing it once is how you learn to read every bounded-
     *   queue-shaped thing you will meet afterwards — including the queueCapacity
     *   on Spring's ThreadPoolTaskExecutor (docs/phase2_task8.md).
     *
     *   Requirements the test enforces:
     *   1. put blocks while the queue is full; take blocks while empty.
     *   2. peakSize() never exceeds the capacity — not even transiently.
     *   3. No items are lost or duplicated across many producers and consumers.
     *   4. No busy-waiting. The test measures CPU time and fails a spin-wait
     *      solution. Blocked threads must actually park.
     *   5. Both methods stay interruptible.
     *
     *   Hint: one ReentrantLock with TWO Conditions (notFull, notEmpty). And the
     *   rule that catches everyone: wait in a while loop, never an if — spurious
     *   wakeups are legal, and between being signalled and re-acquiring the lock
     *   another thread may already have taken the item you were woken for.
     *
     *   (Yes, return new ArrayBlockingQueue<>(capacity) would pass. Don't. Write
     *   the mechanism, then go read ArrayBlockingQueue's source and notice it is
     *   the same thing.)
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
    /*
     *   Fix the pipeline. See t05handoff.D13_UnboundedBacklog and
     *   t05handoff.D15_ShutdownPoisonPill.
     *
     *   This is the first exercise where the broken version mostly works — items
     *   flow through and get processed. It is broken the way production systems are
     *   broken: three latent defects that each show up under a different condition,
     *   and every one of them was a demo:
     *   1. No backpressure (D13). The backlog is unbounded, so a fast producer
     *      grows it without limit — the test's slow consumer makes backlogPeak()
     *      blow straight past the capacity you were given.
     *   2. Busy-wait (D14's verb grid, Ex7's lesson). Workers spin on poll(),
     *      burning a core each while the queue is empty.
     *   3. Lossy shutdown (D15). A volatile flag stops the workers wherever they
     *      happen to be, abandoning whatever is still queued — the test counts
     *      every submitted item and will find the missing ones.
     *
     *   Hint: you already own both halves of the fix. A bounded blocking queue is
     *   Ex7 (here you may just use ArrayBlockingQueue — you've earned it); the
     *   shutdown is D15's poison pill, one per worker, sent through the same queue
     *   so FIFO guarantees it arrives after every real item. Compare the pill with
     *   ==, and think about why equals() would be a bug.
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
    /*
     *   Make the compound updates atomic. See t06shared.D17_AtomicMapUpdates.
     *
     *   Read the field declaration first. The map is already a ConcurrentHashMap.
     *   Every single call below is atomic and thread-safe, and this class is still
     *   broken — which is the entire lesson of topic 6, and the reason the starting
     *   code is not a HashMap.
     *
     *   record is get-then-put: two atomic calls with a race-shaped hole between
     *   them. That is count++ from D7 wearing a Map's clothes, and it loses
     *   increments at the same rate. consume is worse — it is containsKey-then-act,
     *   so two callers can both see a count of 1 and both claim the same single
     *   occurrence. You have now met this shape in D8 (overselling), Ex6 (the cache
     *   stampede) and D17.
     *
     *   The fix is not "use a thread-safe map" — you already have one. It is
     *   express each whole read-modify-write as one call, so the map holds the bin
     *   lock across the read and the write together:
     *   1. merge(key, 1L, Long::sum) for the increment;
     *   2. compute(key, (k, v) -> ...) for the decrement — and note that returning
     *      null from compute removes the entry, which is exactly how you satisfy "a
     *      key consumed to zero must disappear" atomically rather than with a
     *      second remove call that would reopen the same gap.
     *
     *   Careful with consume: it has to report whether it actually consumed
     *   something, and the mapping function cannot return that to you. Capture it
     *   from inside the function — an effectively-final one-element array or an
     *   AtomicBoolean — and read it after compute returns. The mapping function
     *   runs exactly once per successful call, under the bin lock, so what it
     *   records is accurate.
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
    /*
     *   Confine the correlation id to its own thread, and clean it up. See
     *   t06shared.D18_CopyOrConfine.
     *
     *   Three defects, each with its own test, and all three are things that have
     *   shipped in real filters:
     *   1. Not confined at all. A static String is shared by every thread in the
     *      JVM, so two concurrent requests overwrite each other's id. Thread
     *      confinement is the fix, and ThreadLocal is how you express it.
     *   2. Cleanup is not in a finally. When the body throws, the unbind is skipped
     *      and the value stays attached to the thread. On a pooled thread that
     *      means the next request — a different user — inherits it. This is
     *      precisely why the MDC rule in ../01-foundations/07-logging-mdc-
     *      correlation-ids.md is MDC.clear() in a finally, non-negotiable.
     *   3. Nesting is not restored. Clearing on exit is wrong when a scope was
     *      nested inside another: the outer request's id must come back, not
     *      vanish. Save the previous value before setting, and put it back
     *      afterwards.
     *
     *   One more rule that no test here can see but every reviewer should: when you
     *   do clear, use remove(), never set(null). set(null) leaves the entry in the
     *   thread's map holding a null — the slot is never reclaimed, and on a pool
     *   thread that lives forever, so does the entry. D18 proves the difference
     *   with an initialValue.
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
    /*
     * Make the rounds actually synchronise. See
     * t07coordination.D19_LatchVersusBarrier. The starting code uses one
     * java.util.concurrent.CountDownLatch, created once in the constructor, as
     * a per-round barrier. Round 1 works perfectly. From round 2 onwards the
     * latch's count is already zero, so countDown() does nothing and await()
     * returns immediately — there is no rendezvous left at all, and the
     * workers run free while the code still looks synchronised. Nothing throws
     * and nothing hangs; the tallies simply stop being 6. A latch is one-shot.
     * It counts down to zero and stays there forever, and there is no reset()
     * by design. What you want is the other half of the 2×2 — a reusable
     * rendezvous of a fixed set of parties — which is
     * java.util.concurrent.CyclicBarrier. Use the barrier action for the
     * tally. It runs on the last thread to arrive, once per round, while every
     * other party is still parked, which makes it the only instant in the
     * round when nothing else is touching the shared state. Tallying anywhere
     * else — even with a correct barrier — races with the workers starting the
     * next round. And do not "fix" this with while (arrived.get() < workers) {
     * }. It passes the correctness tests and burns a core per waiting worker;
     * the third test measures per-thread CPU time and rejects it, exactly as
     * Ex7's and Ex8's CPU assertions did.
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
    /*
     *   Build a bounded resource pool that actually bounds anything. See
     *   t07coordination.D20_SemaphorePermits.
     *
     *   Two defects, one per test, and both are real production bugs:
     *   1. tryAcquire() and then carrying on anyway. The non-blocking form returns
     *      false when the pool is full, and this code ignores that and runs the
     *      task regardless — so the "limit" is decorative. If you want the caller
     *      to wait, acquire() is the verb; if you want to reject, you must actually
     *      reject. Running unaccounted is the one option that is never right,
     *      because the concurrency it permits is unbounded AND invisible.
     *   2. release() after the work instead of in a finally. Every task that throws
     *      permanently destroys one slot. The pool does not fail at the first error
     *      — it shrinks, one exception at a time, until the last slot goes and
     *      every caller waits forever, with no deadlock report to explain it (D21).
     *
     *   The shape you want, and it is worth memorising as a shape:
     *     slots.acquire();
     *     try {
     *         return task.call();
     *     } finally {
     *         slots.release();
     *     }
     *
     *   Note that the in-flight counter needs the same discipline, for the same
     *   reason.
     *
     *   Once it passes, go back and ask the design question D20 ends on: at a real
     *   service boundary, is acquire() (wait, invisibly, forever) or
     *   tryAcquire(timeout) (wait a bounded time, then shed load deliberately and
     *   countably) the behaviour you want? It is topic 5's block/drop/grow decision
     *   at a different layer.
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

        /*
         * The size the pool was built with — availableSlots() must return to
         * it.
         */
        public int limit() {
            return limit;
        }
    }

    // ═════════════════════════════════════════════════════════ EXERCISE 13
    /*
     *   Build a thread pool. See t08pools.D22_PoolGrowthOrder and
     *   t08pools.D24_SizingLifecycleAndLostExceptions.
     *
     *   This is the exercise the whole curriculum has been building toward, and you
     *   already own every part of it: worker threads that park rather than spin
     *   (Ex7), a bounded queue that applies backpressure (Ex7), and a shutdown that
     *   drains instead of abandoning (Ex8's poison pill). What is new is the
     *   submission rule, and it is the one thing almost everyone gets backwards:
     *     1. workers < core?        -> start a worker for this task
     *     2. queue accepts the task? -> queue it            <-- BEFORE growing
     *     3. workers < max?         -> start a worker for this task
     *     4. otherwise               -> RejectedExecutionException
     *
     *   Four planted defects, each its own failing test:
     *   1. The order is inverted. Ex13Pool#execute grows the pool to maxPoolSize
     *      before it ever offers to the queue, so the queue is dead weight and the
     *      pool creates threads for load one queue slot would have absorbed (D22).
     *   2. Busy-wait. Workers spin on poll() instead of parking in take(), burning
     *      a core each while idle (D14, Ex7, Ex8).
     *   3. Lossy graceful shutdown. shutdownAndAwait flips a flag, so queued tasks
     *      are abandoned rather than drained (D15, D24).
     *   4. shutdownNow hides the damage. It returns an empty list instead of the
     *      tasks it never started, so the caller cannot see, count or requeue what
     *      was lost (D24).
     *
     *   Hint for defect 3: you cannot use a poison pill AND honour shutdownNow's
     *   interrupt in the same worker loop without deciding what each one means.
     *   Write the two shutdowns as the two different verbs they are — drain versus
     *   abandon.
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
    /*
     *   Two workloads, one runner, and no single strategy that fits both. See
     *   t09parallel.D27_VirtualThreadsAndPinning.
     *
     *   Everything below is written the way a reasonable person writes it the first
     *   time: one executor, sized to the core count, shared by both methods, with a
     *   lock to keep the shared result list safe. Every individual decision is
     *   defensible. Together they are wrong, and the tests will tell you which
     *   workload each decision ruins.
     *
     *   Two planted defects:
     *   1. One strategy for two kinds of work. A pool of cores platform threads is
     *      right for CPU-bound work and catastrophic for IO-bound work: 400 tasks
     *      that each block for 100 ms can only run cores at a time. D27 measured
     *      this exact shape — a cores-sized pool managed ~120 blocking tasks per
     *      second where virtual threads managed ~68,000.
     *   2. The lock is held across the task itself. synchronized around task.call()
     *      serialises every task, so neither workload gets any parallelism at all.
     *      And once you switch the IO path to virtual threads it gets a second,
     *      subtler penalty: a virtual thread that blocks inside synchronized is
     *      PINNED to its carrier and cannot unmount. D27 measured a 108x collapse
     *      from pinning alone, with zero contention.
     *
     *   Your job is to ask "what kind of work is this?" separately for each method,
     *   and to make sure that whatever synchronisation survives does not wrap a
     *   blocking call. Hint: the lock exists only to protect a list. There are ways
     *   to collect results in order that need no lock at all.
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
    /*
     *   The incident. See t10diagnostics.D30_TheIncident and
     *   t10diagnostics.D31_DiagnosingTheIncident.
     *
     *   This is the capstone, and it is deliberately unlike every other exercise on
     *   this page. The others name their mechanism in the heading, so you always
     *   know which chapter the fix comes from. Production never does that. Here you
     *   get a service, four failing tests, and four SYMPTOMS — and which of topics
     *   1 to 9 applies to each is the thing you are being examined on.
     *
     *   The service below is about seventy lines and looks entirely reasonable.
     *   Four lines are wrong:
     *   1. Two requests hang; the rest of the service keeps working. The JVM will
     *      tell you what this one is if you ask it the right question. Both stuck
     *      threads report WAITING, which is worth remembering before you go
     *      grepping for BLOCKED.
     *   2. Under concurrent load the service stops completely and stays stopped —
     *      and nothing reports a deadlock, because there is no cycle of lock
     *      OWNERSHIP anywhere. Look at where the worker threads are parked and at
     *      what the pool's own queue is doing. Ask yourself what those threads are
     *      waiting FOR, and who was supposed to do it.
     *   3. Nothing hangs and some answers are wrong. Requests that carry no tenant
     *      come back attributed to somebody else's tenant. No thread dump, deadlock
     *      report, or CPU measurement will ever show you this; only comparing what
     *      you sent with what came back. Ask what a pooled thread still carries
     *      after it finishes a task.
     *   4. A core is pinned while the service is idle. The thread doing it is
     *      RUNNABLE, which is exactly what a thread doing useful work looks like,
     *      so the state word cannot help you. The verb you chose can.
     *
     *   Run D30_TheIncident to watch all four, then D31_DiagnosingTheIncident only
     *   once you have made your own diagnosis. Each of the four defects came from a
     *   different topic; if you find yourself fixing two of them the same way, one
     *   of the diagnoses is wrong.
     */
    public static final class Ex15IncidentService
            implements com.locallearn.concurrency.api.Contracts.IncidentService {

        /*
         * The per-request context. This is SecurityContextHolder, it is
         * SLF4J's MDC, and it is in every service you will ever work on: set
         * once at the entry point so that code three layers down does not need
         * a tenant parameter it does not care about.
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

        /*
         * Drains the audit queue in the background.
         */
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
