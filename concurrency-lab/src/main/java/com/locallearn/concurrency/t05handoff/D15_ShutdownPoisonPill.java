package com.locallearn.concurrency.t05handoff;

import com.locallearn.concurrency.support.Log;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * DEMO 15 — Shutting down a consumer is part of the protocol, not an
 * afterthought. Three attempts, two of them wrong in ways that pass code
 * review.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t05handoff.D15_ShutdownPoisonPill}
 *
 * <h2>Attempt 1 — volatile flag + take(): hangs forever</h2>
 * <pre>{@code while (!done) { process(queue.take()); } }</pre>
 * This looks like D4's stale-flag bug but is a <b>different failure with the
 * same symptom</b>. The flag here is volatile — perfectly visible. The problem
 * is that the consumer is <em>parked inside take()</em> on an empty queue when
 * the producer sets the flag. A parked thread executes nothing; it cannot
 * re-check any flag, however visible, until something wakes it — and nothing
 * ever will, because the producer that would have fed it is the one that just
 * said goodbye. D4 was "the read was optimised away"; this is "the reader is
 * asleep and the alarm clock left the building".
 *
 * <h2>Attempt 2 — interrupt(): stops promptly, drops the backlog</h2>
 * {@code take()} throws {@link InterruptedException}, the worker exits — and
 * every item still in the queue is silently abandoned. Interruption means
 * "abandon what you're doing"; if the queue holds orders, "abandon" is the
 * wrong verb. This is the right tool only when discarding in-flight work is
 * acceptable (and D3 taught the flag-restore discipline it requires).
 *
 * <h2>Attempt 3 — the poison pill: exact</h2>
 * Shutdown becomes an <b>in-band message</b>: a sentinel object sent through
 * the same queue, one per consumer. Because the queue is FIFO, the pill
 * arrives <em>after</em> every real item, so "saw the pill" proves "drained
 * everything before it". No race, no lost items, no timeout tuning. This is
 * how {@code ExecutorService.shutdown()} (drain, then stop) differs from
 * {@code shutdownNow()} (interrupt, return the backlog) — the same two
 * semantics you see here, one layer up.
 *
 * <p>The pill is compared with {@code ==}, not {@code equals()}: it must be
 * <em>that exact object</em>, so no legitimate item can ever impersonate it.
 */
public final class D15_ShutdownPoisonPill {

    private static final int ITEMS = 10_000;
    private static final String PILL = new String("POISON");   // identity matters, hence new

    public static void main(String[] args) throws Exception {
        flagAndTake();
        interruptAndDrop();
        poisonPill();

        Log.takeaway("""
                A volatile flag can't wake a parked thread; an interrupt wakes it
                but abandons the backlog; a poison pill rides the same FIFO as the
                data, so it arrives after everything it must not outrun. Shutdown
                semantics are a design decision — drain (pill / shutdown()) vs
                abandon (interrupt / shutdownNow()) — make it on purpose.""");
    }

    // ── Attempt 1 ──────────────────────────────────────────────────────────
    /**
     * Two failure modes, and which one you get is a race:
     * <ul>
     *   <li>flag flips while items remain → the consumer re-checks, sees
     *       {@code done}, and exits with a <b>silently dropped tail</b>;</li>
     *   <li>flag flips while the consumer is parked in {@code take()} → it
     *       <b>hangs forever</b>.</li>
     * </ul>
     * This method forces the second one, because it is the more surprising and
     * the one people argue about. We wait until the queue is drained and the
     * consumer is genuinely parked before flipping the flag.
     */
    private static void flagAndTake() throws InterruptedException {
        Log.section("ATTEMPT 1 — volatile done flag + take()");
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1024);
        FlagConsumer consumer = new FlagConsumer(queue);
        Thread worker = new Thread(consumer, "flag-consumer");
        worker.setDaemon(true);                     // it will hang; don't hang the JVM too
        worker.start();

        for (int i = 0; i < ITEMS; i++) {
            queue.put("item-" + i);
        }
        Log.log("producer finished %,d items", ITEMS);

        // Wait for the consumer to drain everything and park in take(). Now the
        // flag flip lands in the worst possible moment — which in production is
        // simply "a quiet period", i.e. most of the time.
        while (!queue.isEmpty() || worker.getState() != Thread.State.WAITING) {
            TimeUnit.MILLISECONDS.sleep(5);
        }
        Log.log("consumer has drained the queue and is parked in take(), state=%s",
                worker.getState());

        consumer.done = true;                       // visible immediately — and useless
        Log.log("producer sets done=true now");

        worker.join(TimeUnit.SECONDS.toMillis(2));
        Log.log("2s later: processed %,d, state=%s, alive=%b",
                consumer.processed, worker.getState(), worker.isAlive());
        Log.log("the flag IS visible — but a parked thread executes NOTHING, so it");
        Log.log("cannot re-check any flag, however visible. Nothing will ever wake it:");
        Log.log("the only thread that could have is the producer that just left.");
        Log.log("(Flip the flag a moment earlier instead and you get the other bug:");
        Log.log("the consumer exits promptly and silently drops the remaining items.)");
    }

    private static final class FlagConsumer implements Runnable {
        private final BlockingQueue<String> queue;
        volatile boolean done;
        volatile int processed;

        FlagConsumer(BlockingQueue<String> queue) {
            this.queue = queue;
        }

        @Override
        public void run() {
            try {
                while (!done) {
                    queue.take();                   // parked here when done flips
                    processed++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // ── Attempt 2 ──────────────────────────────────────────────────────────
    private static void interruptAndDrop() throws InterruptedException {
        Log.section("ATTEMPT 2 — interrupt()");
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(ITEMS);
        int[] processed = new int[1];

        Thread worker = new Thread(() -> {
            try {
                while (true) {
                    String item = queue.take();
                    Thread.onSpinWait();            // pretend work, keep it slow-ish
                    processed[0]++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // D3's rule: restore the flag
            }
        }, "interrupted-consumer");
        worker.start();

        for (int i = 0; i < ITEMS; i++) {
            queue.put("item-" + i);
        }
        worker.interrupt();                         // "stop NOW" — and it means it
        worker.join(TimeUnit.SECONDS.toMillis(5));

        Log.log("processed %,d of %,d — %,d items abandoned in the queue",
                processed[0], ITEMS, ITEMS - processed[0]);
        Log.log("prompt, clean exit — and silent data loss. Right tool ONLY when");
        Log.log("dropping in-flight work is an acceptable meaning of 'stop'.");
    }

    // ── Attempt 3 ──────────────────────────────────────────────────────────
    private static void poisonPill() throws InterruptedException {
        Log.section("ATTEMPT 3 — poison pill");
        BlockingQueue<String> queue = new ArrayBlockingQueue<>(1024);
        int[] processed = new int[1];

        Thread worker = new Thread(() -> {
            try {
                while (true) {
                    String item = queue.take();
                    if (item == PILL) {             // identity check — see class doc
                        return;
                    }
                    processed[0]++;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "pill-consumer");
        worker.start();

        for (int i = 0; i < ITEMS; i++) {
            queue.put("item-" + i);
        }
        queue.put(PILL);                            // FIFO: arrives after every real item
        worker.join(TimeUnit.SECONDS.toMillis(10));

        Log.log("processed %,d of %,d — exact, and the worker exited on its own",
                processed[0], ITEMS);
        Log.log("N consumers need N pills (each pill stops exactly one taker).");
    }

    private D15_ShutdownPoisonPill() {
    }
}
