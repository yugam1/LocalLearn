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

/**
 * <b>EXERCISE 7 — build a blocking bounded queue.</b>
 * See {@code t04locks.D12_ReadWriteLockAndCondition}.
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
 *
 * <p>Every check below joins with a timeout rather than waiting forever. A
 * half-finished queue is the one thing in this lab that can hang your terminal
 * instead of failing it, and "the checker reports a hang" is far more useful
 * than "the checker became one". You will want the same habit in your tests.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex07Queue   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex7'                                   # the grade
 * </pre>
 */
public final class Ex07Queue<T> implements BoundedQueue<T> {

    private final Object[] items;
    private int head, tail, count, peak;

    public Ex07Queue(int capacity) {
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

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int CAPACITY = 5;
    private static final int PRODUCERS = 4;
    private static final int CONSUMERS = 3;
    private static final int PER_PRODUCER = 3_000;
    private static final int TOTAL_ITEMS = PRODUCERS * PER_PRODUCER;
    private static final long JOIN_TIMEOUT_MILLIS = 5_000;

    /** How long the parked-taker check watches before it measures. */
    private static final long PARKED_OBSERVATION_MILLIS = 1_000;
    /** CPU a genuinely parked thread may burn over that window. A spinner burns ~all of it. */
    private static final long PARKED_CPU_BUDGET_MILLIS = 200;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 7 — blocking bounded queue", "ExerciseTests$Ex7");

        check.that("%d producers and %d consumers lose nothing, never exceed capacity"
                .formatted(PRODUCERS, CONSUMERS), Ex07Queue::handOffEveryItem);

        check.that("take() blocks on an empty queue instead of busy-waiting",
                Ex07Queue::takeParksWhenEmpty);

        check.that("put() blocks on a full queue instead of overwriting",
                Ex07Queue::putBlocksWhenFull);

        System.exit(check.finish());
    }

    /**
     * The throughput check: capacity {@value #CAPACITY} against
     * {@value #TOTAL_ITEMS} items, so the queue spends the whole run at one
     * boundary or the other and both waits get exercised thousands of times.
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

    /**
     * The check a spin-wait fails. A correct {@code take()} parks and burns no
     * CPU; a {@code while (isEmpty()) {}} loop is functionally right and costs a
     * core per blocked thread, which is the kind of bug that surfaces as a cloud
     * bill rather than an exception.
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

    /** The other half: a full queue must push back, not quietly overwrite. */
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

    /** All daemon, all joined with a timeout: this checker reports hangs, it does not have them. */
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
