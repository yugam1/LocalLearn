package com.locallearn.concurrency.t05handoff;

import com.locallearn.concurrency.support.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

/**
 * DEMO 14 — The BlockingQueue family is one decision wearing five class names:
 * <b>how much slack do you allow between producer and consumer?</b>
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t05handoff.D14_QueueFamily}
 *
 * <h2>The family as one table</h2>
 * <pre>
 * capacity 0          SynchronousQueue        no buffer at all — every put waits
 *                                             for a take. A rendezvous, not a queue.
 * capacity N          ArrayBlockingQueue      one lock, pre-allocated array.
 *                     LinkedBlockingQueue(N)  TWO locks (put-lock + take-lock), so
 *                                             producers and consumers don't contend
 *                                             with each other — usually faster when
 *                                             both sides are busy.
 * capacity infinite   LinkedBlockingQueue()   see D13. "Infinite" means "until OOM".
 * special ordering    PriorityBlockingQueue   unbounded, heap-ordered, no FIFO.
 *                     DelayQueue              elements invisible until their delay
 *                                             expires — this is how ScheduledThreadPool
 *                                             works underneath.
 * </pre>
 *
 * <h2>Why SynchronousQueue exists</h2>
 * It looks useless — a queue that can't hold anything — until you see who uses
 * it: {@code Executors.newCachedThreadPool()}. The pool offers each task to a
 * {@code SynchronousQueue}; if an idle worker is already waiting in
 * {@code poll()}, the hand-off succeeds instantly; if not, {@code offer()}
 * returns false <em>immediately</em> and the pool spawns a new thread. The
 * zero-capacity queue is the mechanism that turns "no idle worker" into a
 * signal, with no latency and no buffer to hide it.
 *
 * <h2>The verb grid — the other half of the API</h2>
 * Every queue has three verbs per direction; choosing the wrong one is a bug:
 * <pre>
 *              rejects (returns/false)   blocks          blocks with deadline
 * insert       offer(e)                  put(e)          offer(e, time, unit)
 * remove       poll()                    take()          poll(time, unit)
 * </pre>
 * {@code poll()} on an empty queue returns null <em>right now</em> — a loop
 * around it is a busy-wait (the bug Ex7's CPU-time test catches). {@code take()}
 * parks. Production consumer loops almost always want {@code poll(timeout)}:
 * it parks like take, but wakes periodically so shutdown flags get a look-in
 * (see D15).
 */
public final class D14_QueueFamily {

    private static final int ITEMS = 1_000_000;

    public static void main(String[] args) throws Exception {
        semantics();
        Log.section("THROUGHPUT — 1,000,000 hand-offs");
        Log.log("%-28s %10s %10s", "queue", "1P/1C", "4P/4C");
        benchmark("ArrayBlockingQueue(1024)", () -> new ArrayBlockingQueue<>(1024));
        benchmark("LinkedBlockingQueue(1024)", () -> new LinkedBlockingQueue<>(1024));
        benchmark("SynchronousQueue", SynchronousQueue::new);

        Log.takeaway("""
                One decision, five classes: how much slack between producer and
                consumer, and what happens when it runs out.

                Run this a few times before you believe any of it. Across 7 runs on
                my machine, exactly two findings replicated every single time:

                1. ArrayBlockingQueue is FASTER at 4P/4C than at 1P/1C — often 2x.
                   Lock contention is not the dominant cost here; park/unpark is.
                   With one thread per side the queue keeps hitting empty and full,
                   so nearly every hand-off costs a context switch. More threads
                   keep work in flight and the fast path stays hot. "More
                   contention" and "slower" are not synonyms.
                2. SynchronousQueue is the worst performer at 4P/4C. Zero capacity
                   means zero slack to absorb a scheduling hiccup: every single
                   hand-off is a rendezvous.

                ArrayBlockingQueue vs LinkedBlockingQueue went the way the theory
                predicts — Linked's split put/take locks win at 1P/1C where the two
                sides never contend, Array's pre-allocated array wins at 4P/4C where
                producers contend with each other anyway and allocation is what's
                left — but only in 5 of 7 runs each. Background load flips it.

                That is the real lesson: a 10% difference between two queues is not
                a design input, it is noise you will re-measure next Tuesday. Pick
                by SEMANTICS (bounded? fair? priority? rendezvous?) and let the
                benchmark settle only the ties.""");
    }

    private static void semantics() throws InterruptedException {
        Log.section("SEMANTICS — what each queue does when you push its limit");

        BlockingQueue<String> bounded = new ArrayBlockingQueue<>(2);
        Log.log("ArrayBlockingQueue(2):  offer x3 -> %b, %b, %b   (third refused: full)",
                bounded.offer("a"), bounded.offer("b"), bounded.offer("c"));

        BlockingQueue<String> unbounded = new LinkedBlockingQueue<>();
        Log.log("LinkedBlockingQueue():  offer x3 -> %b, %b, %b   (never refuses — see D13)",
                unbounded.offer("a"), unbounded.offer("b"), unbounded.offer("c"));

        BlockingQueue<String> sync = new SynchronousQueue<>();
        Log.log("SynchronousQueue:       offer with nobody waiting -> %b   (capacity is ZERO)",
                sync.offer("a"));

        CountDownLatch consumerReady = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                consumerReady.countDown();
                sync.take();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "sync-consumer");
        consumer.start();
        consumerReady.await();
        TimeUnit.MILLISECONDS.sleep(100);               // let it park in take()
        Log.log("SynchronousQueue:       offer with a consumer parked in take() -> %b   (direct hand-off)",
                sync.offer("a"));
        consumer.join();

        BlockingQueue<String> empty = new ArrayBlockingQueue<>(2);
        Log.log("poll() on an EMPTY queue -> %s, returned immediately; take() would park.",
                empty.poll());
        Log.log("A while-loop around poll() is a busy-wait — the bug Ex7 and Ex8 both reject.");
    }

    private static void benchmark(String label, QueueFactory factory) throws InterruptedException {
        long oneToOne = transfer(factory.create(), 1, 1);
        long fourToFour = transfer(factory.create(), 4, 4);
        Log.log("%-28s %8dms %8dms", label, oneToOne, fourToFour);
    }

    /** Moves {@link #ITEMS} integers through the queue and returns wall millis. */
    private static long transfer(BlockingQueue<Integer> queue, int producers, int consumers)
            throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(producers + consumers);
        int perProducer = ITEMS / producers;
        int perConsumer = ITEMS / consumers;

        for (int p = 0; p < producers; p++) {
            startWorker("producer-" + p, gate, done, () -> {
                for (int i = 0; i < perProducer; i++) {
                    queue.put(i);
                }
            });
        }
        for (int c = 0; c < consumers; c++) {
            startWorker("consumer-" + c, gate, done, () -> {
                for (int i = 0; i < perConsumer; i++) {
                    queue.take();
                }
            });
        }

        long start = System.nanoTime();
        gate.countDown();
        done.await();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static void startWorker(String name, CountDownLatch gate, CountDownLatch done,
                                    Body body) {
        Thread t = new Thread(() -> {
            try {
                gate.await();
                body.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                done.countDown();
            }
        }, name);
        t.setDaemon(true);
        t.start();
    }

    @FunctionalInterface
    private interface QueueFactory {
        BlockingQueue<Integer> create();
    }

    @FunctionalInterface
    private interface Body {
        void run() throws InterruptedException;
    }

    private D14_QueueFamily() {
    }
}
