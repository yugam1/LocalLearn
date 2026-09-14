package com.locallearn.concurrency.t07coordination;

import com.locallearn.concurrency.support.Log;

import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Phaser;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEMO 19 — Coordination is a 2×2, not four API pages.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t07coordination.D19_LatchVersusBarrier}
 *
 * <p>Four classes get taught here — {@link CountDownLatch}, {@link CyclicBarrier},
 * {@code Semaphore}, {@link Phaser} — and taught as four independent APIs they
 * are impossible to keep straight. They are not four things. They are two
 * questions:
 *
 * <pre>
 *                  │ waiting for EVENTS to happen   │ waiting for PARTIES to arrive
 *     ─────────────┼────────────────────────────────┼──────────────────────────────
 *      ONE-SHOT    │ CountDownLatch                 │ (a latch used as a start gate)
 *      REUSABLE    │ Semaphore  (permits, D20)      │ CyclicBarrier / Phaser
 * </pre>
 *
 * <p>Two axes decide everything:
 * <ol>
 *   <li><b>Is it reusable?</b> A latch counts down and stays at zero forever;
 *       there is no reset and the absence of one is deliberate, because a
 *       resettable latch has an unanswerable race (who resets it, and what about
 *       the thread still in {@code await}?). A barrier resets itself the instant
 *       the last party arrives.</li>
 *   <li><b>Are you waiting for events, or for each other?</b> A latch's waiters
 *       and its counters are different threads and there can be any number of
 *       each — one thread can count down five times. A barrier's parties are the
 *       waiters: arriving <em>is</em> waiting, and the count is fixed at
 *       construction because it has to know when everyone is present.</li>
 * </ol>
 *
 * <h2>You have been running a latch since topic 1</h2>
 * Open {@code support/Stress.java}. It uses both latch idioms, and every demo in
 * this lab has been standing on them:
 * <ul>
 *   <li><b>The start gate</b> — {@code CountDownLatch gate = new CountDownLatch(1)};
 *       every worker calls {@code gate.await()}, the main thread calls
 *       {@code gate.countDown()} once, and all of them are released within
 *       microseconds. Without it, thread 1 finishes its whole loop before thread
 *       8 exists and no race ever reproduces. <b>One counter, many waiters.</b></li>
 *   <li><b>Wait for N workers</b> — {@code CountDownLatch done = new CountDownLatch(threads)};
 *       every worker counts down in a {@code finally}, the main thread awaits.
 *       <b>Many counters, one waiter.</b></li>
 * </ul>
 * Same class, opposite directions, and between them they cover most of what
 * people reach for a barrier to do.
 *
 * <h2>The thing that actually bites: a latch does not reset</h2>
 * This demo runs the same six workers through five rounds twice: once with a
 * latch, once with a barrier. Round one is identical. From round two the latch
 * version has no barrier at all — {@code countDown()} on a zero count is a no-op
 * and {@code await()} on a zero count returns immediately — so workers sail
 * through and the per-round tally, which must always be six, is whatever the
 * fastest worker happened to see. It does not throw, it does not hang, and it
 * does not warn you. It just silently stops synchronising.
 *
 * <h2>BrokenBarrierException is a feature</h2>
 * A barrier's contract is all-or-nothing, so if one party never arrives, the
 * others must not wait forever on a rendezvous that can no longer happen. When a
 * waiting party is interrupted or times out, the barrier is marked <b>broken</b>
 * and every other party — present and future — gets a
 * {@link BrokenBarrierException} rather than a silent hang. Compare that with
 * D21, where the equivalent latch mistake gives you no exception and no
 * diagnosis at all. A barrier fails loudly on purpose; {@code reset()} puts it
 * back into service once you have dealt with the missing party.
 */
public final class D19_LatchVersusBarrier {

    private static final int WORKERS = 6;
    private static final int ROUNDS = 5;

    public static void main(String[] args) throws Exception {
        theTwoLatchIdioms();
        latchCannotBeReused();
        barrierResetsItself();
        brokenBarrier();
        phaserBriefly();

        Log.takeaway("""
                One table, not four APIs:

                                 waiting for EVENTS        waiting for PARTIES
                  ONE-SHOT       CountDownLatch            (latch as a start gate)
                  REUSABLE       Semaphore (permits)       CyclicBarrier / Phaser

                CountDownLatch: a one-way counter. Counters and waiters are
                different threads, in any numbers. It never resets, and the price
                of forgetting that is not an exception — it is round 2 quietly
                having no barrier at all, measured above.

                CyclicBarrier: N parties rendezvous; arriving IS waiting; the count
                is fixed; it resets itself the moment the last party lands, and its
                barrier action runs exactly once per round, on the last arriver,
                while everyone else is still parked. If a party goes missing the
                barrier BREAKS and everyone finds out — the opposite of D21.

                Phaser: a barrier whose party count can change at runtime. Know it
                exists, reach for CyclicBarrier first.""");
    }

    // ── 1. The two latch idioms you have been using all along ──────────────
    private static void theTwoLatchIdioms() throws InterruptedException {
        Log.section("THE TWO LATCH IDIOMS — both already in support/Stress.java");

        CountDownLatch gate = new CountDownLatch(1);         // one counter, many waiters
        CountDownLatch done = new CountDownLatch(WORKERS);   // many counters, one waiter
        AtomicInteger startedBeforeRelease = new AtomicInteger();

        for (int w = 0; w < WORKERS; w++) {
            Thread worker = new Thread(() -> {
                try {
                    startedBeforeRelease.incrementAndGet();
                    gate.await();                            // park until released
                    TimeUnit.MILLISECONDS.sleep(20);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();                        // ALWAYS, or the main thread hangs
                }
            }, "gated-" + w);
            worker.setDaemon(true);
            worker.start();
        }

        TimeUnit.MILLISECONDS.sleep(100);
        Log.log("%d workers exist and every one is parked in gate.await(); gate count = %d",
                startedBeforeRelease.get(), gate.getCount());
        long start = System.nanoTime();
        gate.countDown();                                    // START GATE: releases all 6
        done.await();                                        // WAIT FOR N: until all 6 finish
        Log.log("one countDown() released all %d within microseconds; all finished %d ms later",
                WORKERS, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        Log.log("gate count = %d, done count = %d — both stuck at zero, permanently.",
                gate.getCount(), done.getCount());
        Log.log("countDown() in a FINALLY is the rule that matters: a worker that");
        Log.log("throws before counting down leaves the awaiting thread parked for");
        Log.log("good, and that hang has no thread-dump diagnosis (D21).");
    }

    // ── 2. Reuse a latch and the barrier silently disappears ───────────────
    private static void latchCannotBeReused() throws InterruptedException {
        Log.section("REUSING A LATCH — rounds 2..N have no barrier at all");

        CountDownLatch roundDone = new CountDownLatch(WORKERS);   // created ONCE. That is the bug.
        AtomicInteger arrived = new AtomicInteger();
        int[] tallies = new int[ROUNDS];
        CountDownLatch allFinished = new CountDownLatch(WORKERS);

        for (int w = 0; w < WORKERS; w++) {
            final int id = w;
            Thread worker = new Thread(() -> {
                try {
                    for (int round = 0; round < ROUNDS; round++) {
                        TimeUnit.MILLISECONDS.sleep(5L * id);     // stagger: worker 0 is always first
                        arrived.incrementAndGet();
                        roundDone.countDown();                    // no-op once it hits zero
                        roundDone.await();                        // returns instantly once at zero
                        if (id == 0) {
                            tallies[round] = arrived.getAndSet(0);
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allFinished.countDown();
                }
            }, "latch-worker-" + w);
            worker.setDaemon(true);
            worker.start();
        }
        allFinished.await();

        for (int round = 0; round < ROUNDS; round++) {
            Log.log("round %d: %d of %d workers had arrived when the round was tallied %s",
                    round + 1, tallies[round], WORKERS,
                    tallies[round] == WORKERS ? "" : "   <-- WRONG");
        }
        Log.log("Round 1 works, because the latch was still counting down from %d.", WORKERS);
        Log.log("After that the count is zero, countDown() does nothing and await()");
        Log.log("returns immediately, so there is no rendezvous left — just six");
        Log.log("threads running freely while the code still LOOKS synchronised.");
        Log.log("No exception. No hang. That is what makes it expensive.");
    }

    // ── 3. A barrier resets itself, and has an action ──────────────────────
    private static void barrierResetsItself() throws InterruptedException {
        Log.section("CyclicBarrier — resets itself, and runs an action between rounds");

        AtomicInteger arrived = new AtomicInteger();
        int[] tallies = new int[ROUNDS];
        AtomicInteger round = new AtomicInteger();

        // The barrier action runs on the LAST thread to arrive, once per round,
        // while every other party is still parked. That window is the only place
        // in the round where no worker is running, which makes it the right — and
        // the only safe — place to merge or swap the round's shared state.
        CyclicBarrier barrier = new CyclicBarrier(WORKERS, () -> {
            int r = round.getAndIncrement();
            tallies[r] = arrived.getAndSet(0);
        });

        CountDownLatch allFinished = new CountDownLatch(WORKERS);
        for (int w = 0; w < WORKERS; w++) {
            final int id = w;
            Thread worker = new Thread(() -> {
                try {
                    for (int r = 0; r < ROUNDS; r++) {
                        TimeUnit.MILLISECONDS.sleep(5L * id);
                        arrived.incrementAndGet();
                        barrier.await();          // parks until ALL WORKERS are here
                    }
                } catch (InterruptedException | BrokenBarrierException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    allFinished.countDown();
                }
            }, "barrier-worker-" + w);
            worker.setDaemon(true);
            worker.start();
        }
        allFinished.await();

        for (int r = 0; r < ROUNDS; r++) {
            Log.log("round %d: %d of %d workers had arrived when the round was tallied",
                    r + 1, tallies[r], WORKERS);
        }
        Log.log("Every round exact, with no reset call anywhere: the barrier trips");
        Log.log("when the last party arrives and is immediately armed again.");
        Log.log("The barrier action ran %d times — once per round, and never", round.get());
        Log.log("concurrently with a worker, because every other party is still");
        Log.log("parked when it runs. That is the only instant in the round when");
        Log.log("nothing else is touching the shared state, which is exactly why");
        Log.log("merging or swapping buffers belongs there and nowhere else.");
    }

    // ── 4. A missing party breaks the barrier, loudly ──────────────────────
    /**
     * Three parties are required and only two ever show up. One of the two waits
     * with a deadline and gives up; that single give-up is what marks the barrier
     * broken, and the other party — waiting with no deadline at all, which would
     * otherwise be a permanent hang — is woken with an exception.
     */
    private static void brokenBarrier() throws InterruptedException {
        Log.section("BrokenBarrierException — all-or-nothing, announced");

        CyclicBarrier barrier = new CyclicBarrier(3);
        CountDownLatch reported = new CountDownLatch(2);

        Thread patient = new Thread(() -> {
            try {
                barrier.await();                 // no deadline: would hang forever alone
                Log.log("party-0 got past the barrier — unexpected");
            } catch (BrokenBarrierException e) {
                Log.log("party-0 waited with NO deadline and was woken by "
                        + "BrokenBarrierException instead of hanging");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                reported.countDown();
            }
        }, "party-0");

        Thread impatient = new Thread(() -> {
            try {
                barrier.await(150, TimeUnit.MILLISECONDS);
                Log.log("party-1 got past the barrier — unexpected");
            } catch (TimeoutException e) {
                Log.log("party-1 timed out after 150ms — THIS is what marks the barrier broken");
            } catch (InterruptedException | BrokenBarrierException e) {
                Thread.currentThread().interrupt();
            } finally {
                reported.countDown();
            }
        }, "party-1");

        patient.setDaemon(true);
        impatient.setDaemon(true);
        patient.start();
        impatient.start();
        Log.log("2 of 3 parties arrive; the third never shows up.");

        reported.await();
        Log.log("barrier.isBroken() = %b, waiting parties = %d",
                barrier.isBroken(), barrier.getNumberWaiting());
        barrier.reset();
        Log.log("after reset(): isBroken() = %b — back in service", barrier.isBroken());
        Log.log("This is the opposite of a latch. The barrier knows its contract is");
        Log.log("all-or-nothing, so the moment it becomes unsatisfiable it tells");
        Log.log("EVERY party rather than leaving them parked — including the party");
        Log.log("that asked for no deadline. Compare D21, where the same mistake");
        Log.log("with a latch produces silence and a thread dump with nothing in it.");
    }

    // ── 5. Phaser, briefly ─────────────────────────────────────────────────
    /**
     * Registration happens on the main thread before each phase, so the counts
     * printed below are deterministic rather than a race between the logger and
     * the workers.
     */
    private static void phaserBriefly() throws InterruptedException {
        Log.section("Phaser — a barrier whose party count can change (know it exists)");

        Phaser phaser = new Phaser(1);       // the main thread registers itself
        Log.log("start:   %d registered party (just main), phase %d",
                phaser.getRegisteredParties(), phaser.getPhase());

        startPhaseWorker(phaser, "A", 3);    // register() runs HERE, on main
        startPhaseWorker(phaser, "B", 3);
        Log.log("A and B joined: %d registered parties", phaser.getRegisteredParties());

        phaser.arriveAndAwaitAdvance();      // phase 0 rendezvous — 3 parties
        Log.log("phase 0 done -> now phase %d, %d parties",
                phaser.getPhase(), phaser.getRegisteredParties());

        startPhaseWorker(phaser, "C", 2);    // a party appears MID-RUN
        Log.log("C joined late: %d registered parties", phaser.getRegisteredParties());

        phaser.arriveAndAwaitAdvance();      // phase 1 — 4 parties
        Log.log("phase 1 done -> now phase %d, %d parties",
                phaser.getPhase(), phaser.getRegisteredParties());

        phaser.arriveAndAwaitAdvance();      // phase 2 — 4 parties, then all leave
        TimeUnit.MILLISECONDS.sleep(100);    // let A, B and C actually deregister
        Log.log("phase 2 done -> now phase %d, %d party remains (A, B and C left)",
                phaser.getPhase(), phaser.getRegisteredParties());

        phaser.arriveAndDeregister();        // main leaves too
        Log.log("phaser terminated = %b (a phaser terminates when the last party leaves)",
                phaser.isTerminated());

        Log.log("That is the whole difference. CyclicBarrier fixes its party count");
        Log.log("at construction and cannot change it; a Phaser lets parties call");
        Log.log("register() and arriveAndDeregister() between phases, and a party");
        Log.log("that leaves simply lowers the bar for the next phase instead of");
        Log.log("breaking it. Genuinely useful when the number of participants is");
        Log.log("discovered at runtime — a recursive decomposition, a crawl that");
        Log.log("spawns new workers per level — and overkill everywhere else.");
        Log.log("Reach for CyclicBarrier first, and for a Phaser only when you can");
        Log.log("name the party that has to join or leave late.");
    }

    private static void startPhaseWorker(Phaser phaser, String name, int phases) {
        phaser.register();                   // deterministic: before the thread starts
        Thread worker = new Thread(() -> {
            for (int phase = 0; phase < phases; phase++) {
                phaser.arriveAndAwaitAdvance();
            }
            phaser.arriveAndDeregister();    // leave without breaking anything
        }, "phase-worker-" + name);
        worker.setDaemon(true);
        worker.start();
    }

    private D19_LatchVersusBarrier() {
    }
}
