package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.BoundedQueue;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/*
 * EXERCISE 7 — a bounded queue that blocks
 *
 * THE SCENARIO
 *   This is the hand-off buffer between two pools of threads: producers push
 *   work in, consumers pull it out, and the fixed-size array in the middle is
 *   what stops a fast producer from burying a slow consumer. It is
 *   ArrayBlockingQueue in miniature — the same thing sitting behind
 *   queueCapacity on a Spring task executor.
 *
 * WHAT IS WRONG RIGHT NOW
 *   The skeleton is a plain ring buffer with no lock and no waiting at all. Two
 *   producers can run items[tail] = item and tail = (tail+1) % len at the same
 *   time and write to the same slot, and count++ loses updates the same way i++
 *   does. Worse, neither method ever waits: put keeps writing past a full queue
 *   and overwrites live items, and take on an empty queue hands the caller null
 *   instead of waiting for something to arrive.
 *
 * YOUR TASK
 *   1. put(T) — take a lock, wait while the queue is full, then store the item,
 *      advance tail, update the counters, and wake a waiting taker.
 *   2. take() — take the same lock, wait while the queue is empty, then read
 *      the item, advance head, update count, and wake a waiting putter.
 *   3. size() / peakSize() — must read count and peak under the same lock that
 *      writes them, or they report torn values.
 *
 * RULES
 *   1. No busy-waiting. A while (count == 0) {} spin is functionally correct
 *      and fails: the checker measures CPU time of a blocked thread. It must
 *      actually park.
 *   2. Both methods stay interruptible: keep InterruptedException usable.
 *   3. Write the mechanism. Delegating to new ArrayBlockingQueue<>(capacity)
 *      would pass every check and teach you nothing.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 4 producers and 3 consumers move 12,000 items with none lost or
 *      duplicated, and peakSize() never exceeds the capacity.
 *   2. A taker on an empty queue is WAITING, burns no CPU, and wakes on a put.
 *   3. A putter on a full queue stays blocked until a take() frees a slot, and
 *      the queue hands items back in FIFO order.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex07Queue
 *
 * HINT
 *   One ReentrantLock with TWO Conditions (notFull, notEmpty). The rule that
 *   catches everyone: wait in a while loop, never an if — spurious wakeups are
 *   legal, and between being signalled and re-acquiring the lock another thread
 *   may already have taken the item you woke for.
 *
 * SEE ALSO
 *   Docs — read this first:
 *     docs/02-concurrency/04-locks-deadlock-conditions.md, section
 *     "`Condition` — wait until the state is right".
 *   Demo t04locks.D12_ReadWriteLockAndCondition shows lock plus condition live.
 *   Reference solution: solutions/Solutions.java.
 */
public final class Ex07Queue<T> implements BoundedQueue<T> {

    // The whole state of the queue. Every one of these is read-modify-written by both
    // put() and take(), so every one of them needs the same lock held around it.
    private final Object[] items;
    private int head, tail, count, peak;

    public Ex07Queue(int capacity) {
        this.items = new Object[capacity];
    }

    /*
     * Must guarantee: the item lands in a free slot, exactly one slot, and
     * only once the queue has room. If it writes while full it silently
     * destroys an item a consumer had not read yet; if two producers run these
     * four lines at once they claim the same tail slot and count loses an
     * increment.
     */
    @Override
    public void put(T item) throws InterruptedException {
        // WRONG: nothing guards these four lines and nothing checks for room. A full queue
        // wraps tail around and overwrites; a second producer interleaves and shares a slot.
        // TODO acquire the lock, then wait in a `while (count == items.length)` loop on a
        //      notFull condition before touching anything below.
        items[tail] = item;                         // TODO must run while holding the lock
        tail = (tail + 1) % items.length;           // TODO same lock — advance once per item
        count++;                                    // TODO same lock — read/add/store is one step
        peak = Math.max(peak, count);
        // TODO after the item is in, signal notEmpty so a parked take() can proceed,
        //      then release the lock in a finally block.
    }

    /*
     * Must guarantee: it never returns until it has an item, and the item it
     * returns is handed to exactly one caller. A bounded queue's take() has no
     * "nothing there" answer — returning null on empty is the defect, not a
     * convenience.
     */
    @Override
    @SuppressWarnings("unchecked")
    public T take() throws InterruptedException {
        // WRONG: on an empty queue this reads a null slot and still advances head, so the
        // caller gets null and the queue's bookkeeping drifts. With two consumers, both can
        // read the same head slot and the item comes back twice.
        // TODO acquire the lock, then wait in a `while (count == 0)` loop on a notEmpty
        //      condition — a `while`, not an `if`: another consumer may take the item first.
        T item = (T) items[head];                   // TODO must run while holding the lock
        items[head] = null;
        head = (head + 1) % items.length;           // TODO same lock — advance once per item
        count--;                                    // TODO same lock — one step, like count++
        // TODO before returning, signal notFull so a parked put() can use the slot you freed,
        //      and release the lock in a finally block.
        return item;
    }

    /*
     * Reads shared state written by put()/take(); must be read under the same
     * lock.
     */
    @Override
    public int size() {
        // TODO read count under the lock, so callers never see a half-updated value.
        return count;
    }

    /*
     * The high-water mark the checker uses to prove put() really blocked at
     * capacity.
     */
    @Override
    public int peakSize() {
        // TODO read peak under the lock, for the same reason as size().
        return peak;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int CAPACITY = 5;
    private static final int PRODUCERS = 4;
    private static final int CONSUMERS = 3;
    private static final int PER_PRODUCER = 3_000;
    private static final int TOTAL_ITEMS = PRODUCERS * PER_PRODUCER;
    private static final long JOIN_TIMEOUT_MILLIS = 5_000;

    /*
     * How long the parked-taker check watches before it measures.
     */
    private static final long PARKED_OBSERVATION_MILLIS = 1_000;
    /*
     * CPU a genuinely parked thread may burn over that window. A spinner burns
     * ~all of it.
     */
    private static final long PARKED_CPU_BUDGET_MILLIS = 200;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 7 — blocking bounded queue", "ExerciseTests$Ex7")
                .reading("docs/02-concurrency/04-locks-deadlock-conditions.md § \"`Condition`\"");

        check.that("%d producers and %d consumers lose nothing, never exceed capacity"
                .formatted(PRODUCERS, CONSUMERS), Ex07Queue::handOffEveryItem);

        check.that("take() blocks on an empty queue instead of busy-waiting",
                Ex07Queue::takeParksWhenEmpty);

        check.that("put() blocks on a full queue instead of overwriting",
                Ex07Queue::putBlocksWhenFull);

        System.exit(check.finish());
    }

    /*
     * The throughput check: capacity CAPACITY against TOTAL_ITEMS items, so
     * the queue spends the whole run at one boundary or the other and both
     * waits get exercised thousands of times.
     */
    private static void handOffEveryItem() throws Exception {
        BoundedQueue<Integer> queue = new Ex07Queue<>(CAPACITY);
        ConcurrentLinkedQueue<Integer> received = new ConcurrentLinkedQueue<>();
        AtomicInteger consumedCount = new AtomicInteger();
        AtomicInteger nullsTaken = new AtomicInteger();
        CountDownLatch gate = new CountDownLatch(1);
        List<Thread> producers = new ArrayList<>();
        List<Thread> consumers = new ArrayList<>();

        for (int p = 0; p < PRODUCERS; p++) {
            int base = p * PER_PRODUCER;
            producers.add(start("producer-" + p, () -> {
                gate.await();
                for (int i = 0; i < PER_PRODUCER; i++) {
                    queue.put(base + i);
                }
            }));
        }
        for (int c = 0; c < CONSUMERS; c++) {
            consumers.add(start("consumer-" + c, () -> {
                gate.await();
                while (consumedCount.get() < TOTAL_ITEMS) {
                    Integer item = queue.take();
                    if (item == null) {
                        nullsTaken.incrementAndGet();
                    } else {
                        received.add(item);
                    }
                    // Counted either way, so a broken take() that never blocks
                    // still terminates this loop and we can report what it did.
                    consumedCount.incrementAndGet();
                }
            }));
        }

        gate.countDown();

        for (Thread producer : producers) {
            producer.join(JOIN_TIMEOUT_MILLIS);
            Check.require(!producer.isAlive(),
                    "%s never finished — put() is blocked forever. Either a consumer "
                    + "took an item without signalling notFull, or the wait is inside "
                    + "an `if` rather than a `while`.", producer.getName());
        }
        long deadline = System.nanoTime() + JOIN_TIMEOUT_MILLIS * 1_000_000L;
        while (consumedCount.get() < TOTAL_ITEMS && System.nanoTime() < deadline) {
            Stress.sleep(10);
        }
        consumers.forEach(Thread::interrupt);       // they park in take() when drained

        Check.require(consumedCount.get() >= TOTAL_ITEMS,
                "the consumers stalled: every item was produced but only %,d of %,d "
                + "came back out before the %,dms deadline. Something took an item "
                + "without signalling notEmpty, or a waiter is parked in an `if` that "
                + "it will never re-check.",
                consumedCount.get(), TOTAL_ITEMS, JOIN_TIMEOUT_MILLIS);

        Check.equal(nullsTaken.get(), 0,
                "take() returned null %,d times instead of blocking while the queue "
                + "was empty — a bounded queue's take() has no 'nothing there' answer, "
                + "it waits", nullsTaken.get());

        Check.require(queue.peakSize() <= CAPACITY,
                "the queue held %d items at its peak but capacity is %d — put() must "
                + "block while full, not grow past it", queue.peakSize(), CAPACITY);

        Check.equal(received.size(), TOTAL_ITEMS,
                "every item put must be taken exactly once; %,d came back",
                received.size());

        Set<Integer> distinct = new HashSet<>(received);
        Check.equal(distinct.size(), TOTAL_ITEMS,
                "%,d of the items taken were duplicates — two consumers read the same "
                + "slot, so head moved outside the lock",
                received.size() - distinct.size());
    }

    /*
     * The check a spin-wait fails. A correct take() parks and burns no CPU; a
     * while (isEmpty()) {} loop is functionally right and costs a core per
     * blocked thread, which is the kind of bug that surfaces as a cloud bill
     * rather than an exception.
     */
    private static void takeParksWhenEmpty() throws Exception {
        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        Check.require(threadMx.isThreadCpuTimeSupported(),
                "this JVM cannot measure per-thread CPU time");
        threadMx.setThreadCpuTimeEnabled(true);

        BoundedQueue<Integer> queue = new Ex07Queue<>(2);
        AtomicInteger cpuMillis = new AtomicInteger(-1);
        AtomicInteger tookNull = new AtomicInteger();
        CountDownLatch started = new CountDownLatch(1);

        Thread taker = new Thread(() -> {
            long id = Thread.currentThread().threadId();
            long before = threadMx.getThreadCpuTime(id);
            started.countDown();
            try {
                if (queue.take() == null) {         // should park, not spin, not return
                    tookNull.incrementAndGet();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cpuMillis.set((int) TimeUnit.NANOSECONDS.toMillis(threadMx.getThreadCpuTime(id) - before));
        }, "blocking-taker");
        taker.setDaemon(true);
        taker.start();

        started.await();
        Stress.sleep(PARKED_OBSERVATION_MILLIS);    // it should be parked this whole time

        Thread.State state = taker.getState();
        Check.require(state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING,
                "after %,dms on an empty queue the taker is %s. It should be WAITING or "
                + "TIMED_WAITING — parked. TERMINATED means take() returned instead of "
                + "waiting; RUNNABLE means it is spinning and burning a core.",
                PARKED_OBSERVATION_MILLIS, state);

        queue.put(42);                              // wake it
        taker.join(JOIN_TIMEOUT_MILLIS);

        Check.require(!taker.isAlive(),
                "the taker did not wake after put() — nothing signalled notEmpty");
        Check.equal(tookNull.get(), 0, "take() handed back null rather than the item");
        Check.require(cpuMillis.get() < PARKED_CPU_BUDGET_MILLIS,
                "the taker used %,dms of CPU while blocked for ~%,dms of wall clock. A "
                + "parked thread uses almost none; anything near the wall time means a "
                + "busy-wait loop.", cpuMillis.get(), PARKED_OBSERVATION_MILLIS);
    }

    /*
     * The other half: a full queue must push back, not quietly overwrite.
     */
    private static void putBlocksWhenFull() throws Exception {
        BoundedQueue<Integer> queue = new Ex07Queue<>(2);
        queue.put(1);
        queue.put(2);

        CountDownLatch attempted = new CountDownLatch(1);
        Thread putter = new Thread(() -> {
            attempted.countDown();
            try {
                queue.put(3);                       // must block: the queue is full
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "blocking-putter");
        putter.setDaemon(true);
        putter.start();

        attempted.await();
        Stress.sleep(500);

        Check.require(putter.isAlive(),
                "put() returned on a full queue — it must block until a slot frees, "
                + "not overwrite an existing item");
        Check.equal(queue.size(), 2, "the queue grew past its capacity of 2");

        Integer first = queue.take();               // FIFO, and it frees a slot
        Check.require(first != null && first == 1,
                "expected the queue to hand back 1 first (FIFO) but got %s", first);

        putter.join(JOIN_TIMEOUT_MILLIS);
        Check.require(!putter.isAlive(),
                "put() did not wake after a slot freed — nothing signalled notFull");
        Check.equal(queue.size(), 2, "the blocked put() did not land after the take()");
    }

    /*
     * All daemon, all joined with a timeout: this checker reports hangs, it
     * does not have them.
     */
    private static Thread start(String name, Body body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new IllegalStateException(name + " failed", e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }
}
