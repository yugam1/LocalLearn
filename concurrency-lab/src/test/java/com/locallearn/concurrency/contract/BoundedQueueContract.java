package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.BoundedQueue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 7 contract — the blocking bounded queue.
 *
 * <p>The last test is the interesting one: it measures CPU time to reject a
 * busy-wait implementation. A spin-wait is functionally correct and will pass
 * every other assertion here while burning a core per blocked thread, which is
 * exactly the kind of bug that only shows up as a cloud bill.
 */
public abstract class BoundedQueueContract {

    protected abstract <T> BoundedQueue<T> newQueue(int capacity);

    @Test
    @Timeout(120)
    @DisplayName("many producers and consumers lose nothing and never exceed capacity")
    void transfersEveryItemWithoutExceedingCapacity() throws Exception {
        int capacity = 5;
        int producers = 4;
        int consumers = 3;
        int itemsPerProducer = 3_000;
        int totalItems = producers * itemsPerProducer;

        BoundedQueue<Integer> queue = newQueue(capacity);
        ConcurrentLinkedQueue<Integer> received = new ConcurrentLinkedQueue<>();
        CountDownLatch gate = new CountDownLatch(1);
        AtomicInteger consumedCount = new AtomicInteger();
        List<Thread> threads = new ArrayList<>();

        for (int p = 0; p < producers; p++) {
            int base = p * itemsPerProducer;
            threads.add(start("producer-" + p, false, () -> {
                gate.await();
                for (int i = 0; i < itemsPerProducer; i++) {
                    queue.put(base + i);
                }
            }));
        }
        for (int c = 0; c < consumers; c++) {
            threads.add(start("consumer-" + c, true, () -> {
                gate.await();
                while (consumedCount.get() < totalItems) {
                    Integer item = queue.take();
                    assertThat(item).as("take() returned null instead of blocking "
                                        + "while the queue was empty").isNotNull();
                    received.add(item);
                    consumedCount.incrementAndGet();
                }
            }));
        }

        gate.countDown();

        for (Thread t : threads) {
            if (!t.isDaemon()) {
                t.join(TimeUnit.SECONDS.toMillis(60));
                assertThat(t.isAlive())
                        .as("%s never finished — put() is blocked forever, which means "
                            + "a consumer took an item without signalling notFull, or "
                            + "the wait is inside an `if` rather than a `while`", t.getName())
                        .isFalse();
            }
        }
        for (int i = 0; i < 600 && consumedCount.get() < totalItems; i++) {
            TimeUnit.MILLISECONDS.sleep(100);
        }
        threads.forEach(Thread::interrupt);

        assertThat(queue.peakSize())
                .as("the queue held %d items at its peak but capacity is %d — put() "
                    + "must block while full, not grow past it",
                    queue.peakSize(), capacity)
                .isLessThanOrEqualTo(capacity);

        assertThat(received)
                .as("every item put must be taken exactly once — no losses, no duplicates")
                .hasSize(totalItems)
                .doesNotHaveDuplicates();
    }

    @Test
    @Timeout(30)
    @DisplayName("take() blocks on an empty queue instead of busy-waiting")
    void takeBlocksWithoutBurningCpu() throws Exception {
        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        assertThat(threadMx.isThreadCpuTimeSupported())
                .as("this JVM cannot measure per-thread CPU time").isTrue();
        threadMx.setThreadCpuTimeEnabled(true);

        BoundedQueue<Integer> queue = newQueue(2);
        AtomicInteger takerThreadCpuMillis = new AtomicInteger(-1);
        CountDownLatch started = new CountDownLatch(1);

        Thread taker = new Thread(() -> {
            long id = Thread.currentThread().threadId();
            long before = threadMx.getThreadCpuTime(id);
            started.countDown();
            try {
                queue.take();                       // should park, not spin
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            long after = threadMx.getThreadCpuTime(id);
            takerThreadCpuMillis.set((int) TimeUnit.NANOSECONDS.toMillis(after - before));
        }, "blocking-taker");
        taker.setDaemon(true);
        taker.start();

        started.await();
        TimeUnit.MILLISECONDS.sleep(1_000);         // it should be parked this whole time

        assertThat(taker.getState())
                .as("after 1s on an empty queue the taker should be parked (WAITING or "
                    + "TIMED_WAITING). RUNNABLE means it is spinning, burning a core.")
                .isIn(Thread.State.WAITING, Thread.State.TIMED_WAITING);

        queue.put(42);                              // wake it
        taker.join(TimeUnit.SECONDS.toMillis(5));

        assertThat(taker.isAlive())
                .as("the taker did not wake after put() — nothing signalled notEmpty")
                .isFalse();
        assertThat(takerThreadCpuMillis.get())
                .as("the taker used %d ms of CPU while blocked for ~1000 ms of wall "
                    + "clock. A parked thread uses almost none; anything near the wall "
                    + "time means a busy-wait loop.", takerThreadCpuMillis.get())
                .isLessThan(200);
    }

    @Test
    @Timeout(30)
    @DisplayName("put() blocks on a full queue instead of overwriting")
    void putBlocksWhenFull() throws Exception {
        BoundedQueue<Integer> queue = newQueue(2);
        queue.put(1);
        queue.put(2);

        CountDownLatch attempted = new CountDownLatch(1);
        Thread putter = new Thread(() -> {
            attempted.countDown();
            try {
                queue.put(3);                       // must block: queue is full
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "blocking-putter");
        putter.setDaemon(true);
        putter.start();

        attempted.await();
        TimeUnit.MILLISECONDS.sleep(500);

        assertThat(putter.isAlive())
                .as("put() returned on a full queue — it must block until a slot frees, "
                    + "not overwrite an existing item")
                .isTrue();
        assertThat(queue.size()).isEqualTo(2);

        assertThat(queue.take()).isEqualTo(1);      // FIFO, and frees a slot
        putter.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(putter.isAlive())
                .as("put() did not wake after a slot freed — nothing signalled notFull")
                .isFalse();
        assertThat(queue.size()).isEqualTo(2);
    }

    @FunctionalInterface
    private interface Body {
        void run() throws Exception;
    }

    private static Thread start(String name, boolean daemon, Body body) {
        Thread t = new Thread(() -> {
            try {
                body.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                throw new IllegalStateException(name + " failed", e);
            }
        }, name);
        t.setDaemon(daemon);
        t.start();
        return t;
    }
}
