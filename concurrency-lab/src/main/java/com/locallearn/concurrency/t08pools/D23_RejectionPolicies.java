package com.locallearn.concurrency.t08pools;

import com.locallearn.concurrency.support.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEMO 23 — The four rejection policies, measured. This is topic 5's
 * block / drop / grow trichotomy with JDK class names on it.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t08pools.D23_RejectionPolicies}
 *
 * <h2>The mapping you should carry away</h2>
 * Topic 5 established that when a producer outruns a consumer there are exactly
 * three possible responses and no fourth: <b>block</b> the producer
 * (backpressure), <b>drop</b> something (load shedding), or <b>grow</b> the
 * backlog (an {@code OutOfMemoryError} with a delay fuse). A
 * {@code ThreadPoolExecutor} is a producer/consumer system, so the same three
 * responses are all that is on offer — the rejection policy is simply where you
 * declare which one you chose:
 * <pre>
 *   CallerRunsPolicy     → BLOCK  — the submitting thread runs the task itself,
 *                                   so it is too busy to submit anything else.
 *                                   Backpressure, implemented by conscription.
 *   AbortPolicy          → DROP   — loudly. The caller gets an exception and
 *                                   decides (retry, shed, fail the request).
 *   DiscardPolicy        → DROP   — silently. The newest task disappears.
 *   DiscardOldestPolicy  → DROP   — silently, from the OTHER end: evict the
 *                                   head of the queue, then retry this task.
 *   (an unbounded queue) → GROW   — no policy needed, because rejection never
 *                                   happens. See D22 section 4.
 * </pre>
 *
 * <h2>Why CallerRunsPolicy is the usual production default</h2>
 * It is the only policy that loses nothing <em>and</em> slows the source down.
 * The mechanism is worth stating precisely, because "the caller runs it" sounds
 * like a mere fallback: while the submitting thread is executing a task it is
 * not in its submit loop, so the arrival rate drops to zero for the duration.
 * Saturation propagates backwards to whoever is generating the work — which is
 * exactly what backpressure means, and exactly what a bounded queue's
 * {@code put()} did in topic 5.
 *
 * <p>Its cost is equally worth stating: the "caller" in a web application is a
 * request-handling thread, so saturating the pool now blocks an HTTP worker.
 * That is a deliberate trade — a slow request instead of a lost one — and it is
 * the wrong trade if the caller is an event-loop thread that must never block.
 *
 * <h2>The DiscardOldest subtlety</h2>
 * {@code DiscardOldestPolicy} drops the <em>oldest queued</em> task, not the new
 * one. That is right when fresher data supersedes stale data (a metrics tick, a
 * price update, a UI repaint) and catastrophic when the queued items are
 * distinct units of work (orders, payments) — you will silently drop the
 * requests that have already waited longest, which are usually the ones a user
 * is still watching a spinner for.
 */
public final class D23_RejectionPolicies {

    private static final int TASKS = 2_000;
    private static final int CORE = 2;
    private static final int MAX = 2;
    private static final int QUEUE = 8;

    public static void main(String[] args) throws Exception {
        Log.section("FOUR POLICIES — same pool (core=2, max=2, queue=8), same 2,000 tasks");
        Log.log("Each task burns ~1ms of CPU. One producer submits as fast as it can.");
        Log.log("");
        Log.log("%-22s %9s %9s %9s %9s %11s", "policy", "ran", "onCaller", "rejected",
                "lost", "producer");

        measure("AbortPolicy", new ThreadPoolExecutor.AbortPolicy());
        measure("CallerRunsPolicy", new ThreadPoolExecutor.CallerRunsPolicy());
        measure("DiscardPolicy", new ThreadPoolExecutor.DiscardPolicy());
        measure("DiscardOldestPolicy", new ThreadPoolExecutor.DiscardOldestPolicy());

        Log.takeaway("""
                Read the 'lost' column first. Three of the four policies lose work;
                only CallerRuns completes all 2,000 tasks, and it pays for that in
                the 'producer' column — the submitting thread is conscripted into
                doing the work, so it cannot submit while it is busy. That is not a
                side effect, it IS the backpressure.

                AbortPolicy does not lose work either, strictly speaking: it hands
                the loss back to the caller as an exception and makes the caller
                decide. That is the honest choice when the caller HAS a sensible
                decision available (retry with backoff, shed, return 503).

                Discard and DiscardOldest lose work silently, which is only
                acceptable when the work is genuinely disposable — and note they
                lose from opposite ends of the queue. DiscardOldest drops the tasks
                that have waited LONGEST, i.e. the users who have been staring at a
                spinner the longest. Choose it only when fresh supersedes stale.

                This is topic 5's trichotomy again: block (CallerRuns), drop
                (Abort/Discard/DiscardOldest), grow (an unbounded queue, which
                needs no policy because it never rejects). There is no fourth
                option at any layer of any system.""");
    }

    private static void measure(String name, RejectedExecutionHandler handler) throws Exception {
        AtomicInteger ranOnPool = new AtomicInteger();
        AtomicInteger ranOnCaller = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                CORE, MAX, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(QUEUE),
                r -> new Thread(r, "pool-worker"), handler);

        final String producerName = "policy-producer";
        Thread producer = new Thread(() -> {
            for (int i = 0; i < TASKS; i++) {
                try {
                    pool.execute(() -> {
                        // Which thread am I on? That is the whole measurement for
                        // CallerRunsPolicy: the submitter executes the task inline.
                        if (Thread.currentThread().getName().equals(producerName)) {
                            ranOnCaller.incrementAndGet();
                        } else {
                            ranOnPool.incrementAndGet();
                        }
                        burnMicros(1_000);
                    });
                } catch (RejectedExecutionException e) {
                    rejected.incrementAndGet();
                }
            }
        }, producerName);

        long start = System.nanoTime();
        producer.start();
        producer.join();
        long producerMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        pool.shutdown();
        pool.awaitTermination(60, TimeUnit.SECONDS);

        int completed = ranOnPool.get() + ranOnCaller.get();
        int lost = TASKS - completed - rejected.get();

        Log.log("%-22s %9d %9d %9d %9d %9dms",
                name, ranOnPool.get(), ranOnCaller.get(), rejected.get(), lost, producerMillis);
    }

    /** Burns CPU without parking, so the pool genuinely stays saturated. */
    private static void burnMicros(long micros) {
        long deadline = System.nanoTime() + micros * 1_000L;
        while (System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }
    }

    private D23_RejectionPolicies() {
    }
}
