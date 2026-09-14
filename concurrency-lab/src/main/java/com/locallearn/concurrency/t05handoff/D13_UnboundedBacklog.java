package com.locallearn.concurrency.t05handoff;

import com.locallearn.concurrency.support.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * DEMO 13 — An unbounded queue is not a buffer, it is a promise to buy more
 * RAM. A bounded queue is backpressure: it slows the producer down to the
 * consumer's pace, which is the only honest thing to do.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t05handoff.D13_UnboundedBacklog}
 *
 * <h2>What you will see</h2>
 * Same producer, same consumer, two queues. The producer is fast (it just
 * creates a 128-byte message); the consumer is slow (~10µs of "work" per item —
 * a generous stand-in for a DB write). With an unbounded
 * {@link LinkedBlockingQueue}, {@code put()} never blocks, so the backlog grows
 * at (producer rate − consumer rate) forever. This demo has a safety valve at
 * one million items so it doesn't OOM your JVM; <b>production has no such
 * valve</b> — the heap is the valve, and it fires as an
 * {@code OutOfMemoryError} at 3 a.m., in whatever thread happened to allocate
 * last, which is usually nowhere near the queue that caused it.
 *
 * <p>With an {@link ArrayBlockingQueue} of 1,024, {@code put()} blocks the
 * moment the consumer falls 1,024 items behind. The producer's throughput
 * collapses to exactly the consumer's throughput, memory stays flat, and the
 * system degrades <em>at the edge</em> — the caller waits, times out, sheds
 * load — instead of dying in the middle.
 *
 * <h2>The three-way choice every queue forces</h2>
 * When a producer outruns a consumer, exactly one of three things can happen —
 * there is no fourth option, only the choice of which one and where:
 * <ol>
 *   <li><b>Block</b> the producer — bounded queue, {@code put()}. Backpressure.</li>
 *   <li><b>Drop</b> something — bounded queue, {@code offer()} returning false
 *       (drop newest), or take-then-offer (drop oldest). Load shedding.</li>
 *   <li><b>Grow</b> the backlog — unbounded queue. This is choosing "crash
 *       later" and calling it "no choice made".</li>
 * </ol>
 * This is the same trichotomy as {@code ThreadPoolExecutor}'s rejection
 * policies and Kafka's {@code buffer.memory}/{@code max.block.ms} — you will
 * meet it at every layer of the stack.
 */
public final class D13_UnboundedBacklog {

    private static final int PAYLOAD_BYTES = 128;
    private static final int SAFETY_VALVE = 1_000_000;
    private static final long RUN_MILLIS = 1_500;

    public static void main(String[] args) throws Exception {
        runTrial("UNBOUNDED LinkedBlockingQueue", new LinkedBlockingQueue<>());
        runTrial("BOUNDED ArrayBlockingQueue(1024)", new ArrayBlockingQueue<>(1024));

        Log.takeaway("""
                The unbounded run "kept up" by hiding the deficit in the heap; the
                safety valve is the only reason it survived. The bounded run made
                the deficit visible immediately: produced ~= consumed + capacity.
                An unbounded queue doesn't remove the producer/consumer speed
                mismatch — it just moves the failure from "slow now" to "dead
                later". Capacity is not a tuning detail; it is WHERE YOUR SYSTEM
                FAILS, chosen on purpose.""");
    }

    private static void runTrial(String label, BlockingQueue<byte[]> queue)
            throws InterruptedException {
        Log.section(label);

        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(RUN_MILLIS);
        final long[] produced = new long[1];
        final long[] consumed = new long[1];
        final boolean[] valveFired = new boolean[1];

        Thread producer = new Thread(() -> {
            try {
                while (System.nanoTime() < deadline) {
                    if (queue.size() >= SAFETY_VALVE) {     // demo-only escape hatch
                        valveFired[0] = true;
                        return;
                    }
                    queue.put(new byte[PAYLOAD_BYTES]);     // unbounded: never blocks
                    produced[0]++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "producer");

        Thread consumer = new Thread(() -> {
            try {
                while (System.nanoTime() < deadline || !queue.isEmpty()) {
                    byte[] item = queue.poll(50, TimeUnit.MILLISECONDS);
                    if (item == null && System.nanoTime() >= deadline) {
                        return;
                    }
                    if (item != null) {
                        LockSupport.parkNanos(10_000);      // ~10µs of pretend work
                        consumed[0]++;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "consumer");
        consumer.setDaemon(true);                           // don't drain forever after report

        producer.start();
        consumer.start();
        producer.join();

        long backlog = produced[0] - consumed[0];
        Log.log("produced %,d   consumed %,d   backlog %,d items", produced[0], consumed[0], backlog);
        Log.log("backlog retains roughly %,d KB of heap (%,d items x ~%d bytes each + node overhead)",
                backlog * (PAYLOAD_BYTES + 16) / 1024, backlog, PAYLOAD_BYTES + 16);
        if (valveFired[0]) {
            Log.log("SAFETY VALVE fired at %,d queued items — in production this line is an OutOfMemoryError",
                    SAFETY_VALVE);
        }
    }

    private D13_UnboundedBacklog() {
    }
}
