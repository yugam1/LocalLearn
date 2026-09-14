package com.locallearn.concurrency.t04locks;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * DEMO 12 — {@link ReentrantReadWriteLock} for read-mostly data, and
 * {@link Condition} for "wait until the state is right".
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t04locks.D12_ReadWriteLockAndCondition}
 *
 * <h2>Part 1 — ReadWriteLock</h2>
 * Many readers may hold the read lock at once; a writer excludes everybody.
 * The win appears only when reads dominate <em>and</em> each read is long
 * enough to overlap meaningfully. The benchmark below sweeps the read ratio so
 * you can see where the crossover is on your machine.
 *
 * <p>Two traps:
 * <ul>
 *   <li><b>Writer starvation.</b> With the default non-fair policy, a steady
 *       stream of readers can keep a writer waiting indefinitely. Construct
 *       with {@code new ReentrantReadWriteLock(true)} if writes must land.</li>
 *   <li><b>No upgrade.</b> You cannot go read→write while holding the read
 *       lock; it deadlocks instantly. You must release the read lock, take the
 *       write lock, and <em>re-check your condition</em>, because the state may
 *       have changed in the gap. Downgrade (write→read) <i>is</i> legal.</li>
 * </ul>
 *
 * <p>In practice, reach for {@code ConcurrentHashMap} first. Its per-bin
 * locking beats a global ReadWriteLock in almost every map-shaped case. Use
 * a ReadWriteLock when the guarded state is not a single collection — a config
 * snapshot, several fields with an invariant between them.
 *
 * <h2>Part 2 — Condition</h2>
 * A {@link Condition} is {@code wait}/{@code notify} for a {@link ReentrantLock},
 * with one decisive advantage: <b>one lock can have several wait-sets</b>. A
 * bounded buffer needs "not full" and "not empty" as separate queues, so
 * {@code signal()} wakes a thread that can actually proceed. With a single
 * monitor you must use {@code notifyAll()} and let the wrong threads wake,
 * re-check, and go back to sleep — the "thundering herd".
 *
 * <p><b>The rule that catches everyone: always wait in a loop.</b>
 * <pre>{@code
 * while (!conditionHolds()) {     // while, never if
 *     notFull.await();
 * }
 * }</pre>
 * Three reasons: a <i>spurious wakeup</i> is permitted by the JLS and needs no
 * cause; {@code signalAll} wakes threads whose condition is still false; and
 * between being signalled and re-acquiring the lock, another thread may have
 * taken the very item you were woken for. {@code if} is a bug in all three cases.
 */
public final class D12_ReadWriteLockAndCondition {

    public static void main(String[] args) throws Exception {
        readWriteLockBenchmark();
        conditionBoundedBuffer();
    }

    private static void readWriteLockBenchmark() {
        Log.section("ReadWriteLock vs exclusive lock — two variables that decide the winner");

        // Variable 1: how much of the traffic is reads.
        // Variable 2: how long a single read HOLDS the lock. This is the one
        // people forget, and it turns out to matter far more than the ratio.
        for (long readNanos : new long[]{0, 2_000, 20_000}) {
            int iterations = readNanos == 0 ? 100_000 : 5_000;
            Log.log("");
            Log.log("read holds the lock for ~%,d ns  (%,d iterations/thread)",
                    readNanos, iterations);
            Log.log("  %-10s %14s %14s %10s", "reads", "exclusive", "read/write", "speedup");

            for (int readPercent : new int[]{50, 90, 99, 100}) {
                long exclusiveMs = time(new ExclusiveMap(), readPercent, readNanos, iterations);
                long rwMs = time(new ReadWriteMap(), readPercent, readNanos, iterations);
                double speedup = (double) exclusiveMs / Math.max(rwMs, 1);
                Log.log("  %-9d%% %11d ms %11d ms %9.2fx %s",
                        readPercent, exclusiveMs, rwMs, speedup,
                        speedup > 1.2 ? "<-- RWLock wins" : speedup < 0.9 ? "(RWLock loses)" : "");
            }
        }

        Log.takeaway("""
                Read the 0 ns block first: a ReadWriteLock LOSES at every read
                ratio, even 100%% reads. A HashMap.get is a few nanoseconds, so
                there is no overlap to win back — you just pay the extra
                bookkeeping of tracking a reader count.

                Now read the 20,000 ns block. Same code, same ratios, and the
                RWLock pulls far ahead.

                So the real rule is NOT "use a ReadWriteLock when reads dominate".
                It is: use one when reads dominate AND each read holds the lock
                long enough for the sharing to pay for itself. Short critical
                section? A plain lock wins. Map-shaped data? ConcurrentHashMap
                beats both, because it shards the locking instead of sharing it.""");
    }

    private interface Store {
        String get(String key, long holdNanos);
        void put(String key, String value);
    }

    /** Simulates a read that does real work while holding the lock. */
    private static void burn(long nanos) {
        if (nanos <= 0) {
            return;
        }
        long deadline = System.nanoTime() + nanos;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    /** One lock for everything: readers exclude each other for no reason. */
    static final class ExclusiveMap implements Store {
        private final Map<String, String> map = new HashMap<>();
        private final ReentrantLock lock = new ReentrantLock();

        @Override public String get(String key, long holdNanos) {
            lock.lock();
            try {
                String value = map.get(key);
                burn(holdNanos);
                return value;
            } finally {
                lock.unlock();
            }
        }

        @Override public void put(String key, String value) {
            lock.lock();
            try {
                map.put(key, value);
            } finally {
                lock.unlock();
            }
        }
    }

    /** Readers share; writers exclude. */
    static final class ReadWriteMap implements Store {
        private final Map<String, String> map = new HashMap<>();
        private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

        @Override public String get(String key, long holdNanos) {
            lock.readLock().lock();
            try {
                String value = map.get(key);
                burn(holdNanos);
                return value;
            } finally {
                lock.readLock().unlock();
            }
        }

        @Override public void put(String key, String value) {
            lock.writeLock().lock();
            try {
                map.put(key, value);
            } finally {
                lock.writeLock().unlock();
            }
        }
    }

    private static long time(Store store, int readPercent, long readNanos, int iterations) {
        for (int i = 0; i < 1_000; i++) {
            store.put("key" + i, "value" + i);
        }
        long t0 = System.nanoTime();
        Stress.run(8, iterations, i -> {
            String key = "key" + (i % 1_000);
            if (i % 100 < readPercent) {
                store.get(key, readNanos);
            } else {
                store.put(key, "v" + i);
            }
        });
        return (System.nanoTime() - t0) / 1_000_000L;
    }

    private static void conditionBoundedBuffer() throws Exception {
        Log.section("Condition — a bounded buffer with two separate wait-sets");

        BoundedBuffer<Integer> buffer = new BoundedBuffer<>(5);
        AtomicLong produced = new AtomicLong();
        AtomicLong consumed = new AtomicLong();
        int itemsPerProducer = 2_000;
        int producers = 4;

        Thread[] producerThreads = new Thread[producers];
        for (int p = 0; p < producers; p++) {
            producerThreads[p] = new Thread(() -> {
                for (int i = 0; i < itemsPerProducer; i++) {
                    try {
                        buffer.put(i);
                        produced.incrementAndGet();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }, "producer-" + p);
            producerThreads[p].start();
        }

        Thread[] consumerThreads = new Thread[2];
        for (int c = 0; c < consumerThreads.length; c++) {
            consumerThreads[c] = new Thread(() -> {
                try {
                    while (consumed.get() < (long) producers * itemsPerProducer) {
                        buffer.take();
                        consumed.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "consumer-" + c);
            consumerThreads[c].setDaemon(true);
            consumerThreads[c].start();
        }

        for (Thread t : producerThreads) {
            t.join();
        }
        Stress.sleep(300);
        for (Thread t : consumerThreads) {
            t.interrupt();
        }

        Log.log("produced %,d / consumed %,d with a buffer that never exceeded capacity 5",
                produced.get(), consumed.get());
        Log.takeaway("""
                The buffer never grew past 5 and no producer ever busy-waited —
                blocked producers were parked, costing zero CPU, and woken only
                when a slot actually freed. That is backpressure, and it is the
                same mechanism behind ThreadPoolTaskExecutor's bounded queue in
                docs/phase2_task8.md.

                In real code you would use ArrayBlockingQueue, which is exactly
                this class. Writing it once is how you learn to read it.""");
    }

    /** {@code ArrayBlockingQueue} in miniature — the canonical Condition example. */
    static final class BoundedBuffer<T> {
        private final Object[] items;
        private final ReentrantLock lock = new ReentrantLock();
        private final Condition notFull = lock.newCondition();
        private final Condition notEmpty = lock.newCondition();
        private int head, tail, count;

        BoundedBuffer(int capacity) {
            this.items = new Object[capacity];
        }

        void put(T item) throws InterruptedException {
            lock.lock();
            try {
                while (count == items.length) {   // while, NOT if
                    notFull.await();              // releases the lock while parked
                }
                items[tail] = item;
                tail = (tail + 1) % items.length;
                count++;
                notEmpty.signal();                // wake exactly one consumer
            } finally {
                lock.unlock();
            }
        }

        @SuppressWarnings("unchecked")
        T take() throws InterruptedException {
            lock.lock();
            try {
                while (count == 0) {
                    notEmpty.await();
                }
                T item = (T) items[head];
                items[head] = null;
                head = (head + 1) % items.length;
                count--;
                notFull.signal();                 // wake exactly one producer
                return item;
            } finally {
                lock.unlock();
            }
        }
    }
}
