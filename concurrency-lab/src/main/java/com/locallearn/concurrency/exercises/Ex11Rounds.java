package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.RoundSync;
import com.locallearn.concurrency.support.Check;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/*
 * EXERCISE 11 — rounds that wait for everyone, every round
 *
 * THE SCENARIO
 *   A fixed crew of worker threads processes work in lockstep rounds: every
 *   worker does its slice of round N, they all meet, someone records how many
 *   showed up, and only then does anyone start round N+1. Think of a simulation
 *   tick or a batch job that must not begin the next phase until the previous
 *   one is complete everywhere. runAll(rounds) starts the workers; tallies()
 *   reports one head count per round, and each one must equal the worker count.
 *
 * WHAT IS WRONG RIGHT NOW
 *   The rendezvous is a single java.util.concurrent.CountDownLatch built once
 *   in the constructor. Round 1 works. By round 2 its count is already zero,
 *   and a latch at zero stays at zero forever: countDown() is a no-op and
 *   await() returns instantly. From then on there is no rendezvous at all — the
 *   workers run free while the code still reads as if it synchronises. Nothing
 *   throws, nothing hangs; the tallies simply stop being workers, because
 *   worker 0 reads and resets arrived while the others are still incrementing
 *   it.
 *
 * YOUR TASK
 *   1. roundDone field — replace the one-shot latch with a rendezvous that re-
 *      arms itself after each round: java.util.concurrent.CyclicBarrier,
 *      constructed with the number of parties and a barrier action.
 *   2. The constructor — build it with workers parties, and pass the per-round
 *      tally (the arrived.getAndSet(0) currently done by worker 0) as the
 *      barrier action.
 *   3. runAll(int) — each worker should arrive once per round with a single
 *      await() call, and the if (id == 0) tally block goes away because the
 *      barrier action replaces it.
 *
 * RULES
 *   No busy-wait. while (arrived.get() < workers) { } passes every correctness
 *   check here and burns a core per waiting worker; the third test measures
 *   per-thread CPU time and rejects it, the same way Ex7 and Ex8 did. The tally
 *   must also run at the one instant nothing else is touching arrived — that is
 *   what a barrier action gives you: it runs on the last party to arrive while
 *   all the others are still parked. Tallying anywhere else races the next
 *   round's starters.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 20 rounds × 6 workers, 3 trials: one tally per round, each equal to 6.
 *   2. The same with workers deliberately staggered ~5 ms apart, so fast
 *      workers would lap slow ones if the barrier had evaporated.
 *   3. Workers waiting at the barrier burn near-zero CPU — they park, not spin.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex11Rounds
 *
 * HINT
 *   A latch has no reset() by design — one-shot is the whole point of it. The
 *   reusable half of that pair is CyclicBarrier. This defect is the expensive
 *   kind in production because it has no symptom of its own: threads alive,
 *   nothing thrown, code reading correctly, and one number downstream quietly
 *   wrong.
 *
 * SEE ALSO
 *   Docs — read this first: docs/02-concurrency/07-coordination.md, section
 *     "EXERCISE 11".
 *   Demo t07coordination.D19_LatchVersusBarrier shows the failure live.
 *   Reference solution: solutions/Solutions.java.
 */
public final class Ex11Rounds
        implements com.locallearn.concurrency.api.Contracts.RoundSync {

    private final int workers;
    private final java.util.function.IntConsumer roundWork;
    private final java.util.List<Integer> tallies =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger arrived =
            new java.util.concurrent.atomic.AtomicInteger();

    // WRONG: a CountDownLatch is one-shot. This one is created once and used as if it
    // re-armed each round; after round 1 its count is zero and stays there, so every
    // later round has no rendezvous at all.
    // TODO change this field's type to a rendezvous that resets itself after each
    // TODO round — CyclicBarrier — and update the constructor below to match.
    private final java.util.concurrent.CountDownLatch roundDone;

    public Ex11Rounds(int workers, java.util.function.IntConsumer roundWork) {
        this.workers = workers;
        this.roundWork = roundWork;
        // TODO build the barrier with `workers` parties AND a barrier action that does
        // TODO the per-round tally: tallies.add(arrived.getAndSet(0)). The action runs on
        // TODO the last party to arrive, while the rest are still parked — the only
        // TODO moment in the round when nothing else is touching `arrived`.
        this.roundDone = new java.util.concurrent.CountDownLatch(workers);
    }

    /*
     * Runs rounds rounds and returns only once every worker has finished all
     * of them. Each round must be a real rendezvous: no worker may begin round
     * N+1 until all workers have finished round N, and exactly one tally must
     * be recorded per round while every worker is still waiting. If either
     * guarantee slips, fast workers lap slow ones and the tallies come out
     * below workers — with no exception, no hang, and nothing in a thread dump
     * to point at.
     */
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
                        // WRONG: countDown()/await() is the latch protocol. Once the
                        // count hits zero in round 1, countDown() does nothing and
                        // await() returns without waiting for anyone.
                        // TODO replace both lines with one CyclicBarrier.await(), which
                        // TODO parks this worker until all parties have arrived and then
                        // TODO re-arms for the next round.
                        roundDone.countDown();
                        roundDone.await();
                        // WRONG: worker 0 tallies while the other workers may already be
                        // incrementing `arrived` for the next round, so the count it
                        // reads is whatever happened to be there.
                        // TODO delete this block — the barrier action passed to the
                        // TODO constructor records the tally at the safe instant instead.
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

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int WORKERS = 6;
    private static final int ROUNDS = 20;
    private static final int TRIALS = 3;

    /*
     * Only ever reached if the rendezvous has hung, so it can afford to be
     * generous.
     */
    private static final long PATIENCE_MILLIS = 15_000;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 11 — a reusable rendezvous", "ExerciseTests$Ex11")
                .reading("docs/02-concurrency/07-coordination.md § \"EXERCISE 11\"");

        check.that("every one of %d rounds waits for all %d workers — %d trials"
                .formatted(ROUNDS, WORKERS, TRIALS), () -> {
            // Trials, because a rendezvous that has stopped rendezvousing still
            // produces the right tally whenever the threads happen not to drift
            // apart. Three runs of twenty rounds do not all get lucky.
            for (int trial = 1; trial <= TRIALS; trial++) {
                RoundSync sync = new Ex11Rounds(WORKERS, id -> Thread.onSpinWait());
                List<Integer> tallies = runAllWithin(sync, ROUNDS);
                assertTallies(tallies, ROUNDS, WORKERS,
                        "trial %d of %d".formatted(trial, TRIALS));
            }
        });

        check.that("the barrier holds when workers arrive at very different times", () -> {
            int rounds = 10;
            // Worker 0 is always first and worker 5 is always last by ~25ms. A
            // rendezvous that has quietly stopped rendezvousing cannot survive
            // this: the fast workers lap the slow ones within a round or two.
            RoundSync sync = new Ex11Rounds(WORKERS, id -> sleepQuietly(5L * id));
            List<Integer> tallies = runAllWithin(sync, rounds);
            assertTallies(tallies, rounds, WORKERS, "staggered workers");
        });

        check.that("workers waiting at the barrier park instead of spinning", () -> {
            // This one does not catch the planted defect — it catches the
            // tempting wrong FIX. A spin loop on `arrived` satisfies every
            // assertion above and costs a core per waiting worker, which shows
            // up only on the infrastructure bill.
            ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
            Check.require(threadMx.isThreadCpuTimeSupported(),
                    "this JVM cannot measure per-thread CPU time");
            threadMx.setThreadCpuTimeEnabled(true);

            int spinCheckWorkers = 4;
            long slowWorkerMillis = 1_000;

            // One round. Worker 0 takes a full second; workers 1..3 arrive at
            // once and then have nothing to do but wait for it.
            RoundSync sync = new Ex11Rounds(spinCheckWorkers, id -> {
                if (id == 0) {
                    sleepQuietly(slowWorkerMillis);
                }
            });

            Thread runner = startRunner(sync, 1);

            TimeUnit.MILLISECONDS.sleep(200);       // let the workers start and park
            List<Long> waitingWorkerIds = waitingWorkerThreadIds(threadMx);
            Check.require(!waitingWorkerIds.isEmpty(),
                    "expected live worker threads named 'round-worker-*' (worker 0 is "
                    + "excluded because it is the one doing the slow work)");

            long cpuBefore = totalCpuNanos(threadMx, waitingWorkerIds);
            TimeUnit.MILLISECONDS.sleep(600);       // 600ms with nothing to do but wait
            long cpuAfter = totalCpuNanos(threadMx, waitingWorkerIds);
            runner.join(PATIENCE_MILLIS);

            long waitingCpuMillis = TimeUnit.NANOSECONDS.toMillis(cpuAfter - cpuBefore);
            Check.require(waitingCpuMillis < 120L * waitingWorkerIds.size(),
                    "the %d workers waiting at the barrier burned %,d ms of CPU across "
                    + "600 ms of wall clock. A parked thread uses almost none; this much "
                    + "means a spin loop such as `while (arrived.get() < workers) { }`. "
                    + "That passes every correctness check above and costs a core per "
                    + "waiting worker — the same defect Ex7 and Ex8 measured. "
                    + "CyclicBarrier.await() parks.",
                    waitingWorkerIds.size(), waitingCpuMillis);
        });

        System.exit(check.finish());
    }

    // ── checker helpers ────────────────────────────────────────────────────

    private static void assertTallies(List<Integer> tallies, int rounds, int workers,
                                      String context) {
        Check.equal(tallies.size(), rounds,
                "%s: expected exactly one tally per round. The round tally must run "
                + "once per round, and it must run while every worker is still "
                + "waiting — a CyclicBarrier's barrier ACTION does both, because it "
                + "runs on the last party to arrive while the others are still "
                + "parked (D19)", context);

        for (int round = 0; round < tallies.size(); round++) {
            Check.require(tallies.get(round) == workers,
                    "%s: round %d was tallied with %d of %d workers present. "
                    + "Tallies so far: %s. A CountDownLatch is ONE-SHOT: once its "
                    + "count reaches zero, countDown() is a no-op and await() returns "
                    + "immediately, so from round 2 onward there is no rendezvous at "
                    + "all — the workers run free while the code still looks "
                    + "synchronised. Nothing throws and nothing hangs. Use a "
                    + "CyclicBarrier, which re-arms itself the instant the last party "
                    + "arrives (D19)",
                    context, round + 1, tallies.get(round), workers, tallies);
        }
    }

    /*
     * Runs the rounds on a daemon thread and refuses to wait forever. A broken
     * rendezvous can fail in either direction — no barrier at all, or a
     * barrier some parties never reach — so the checker has to be able to
     * report a hang rather than become one.
     */
    private static List<Integer> runAllWithin(RoundSync sync, int rounds)
            throws InterruptedException {
        Thread runner = startRunner(sync, rounds);
        runner.join(PATIENCE_MILLIS);
        Check.require(!runner.isAlive(),
                "runAll(%d) had not returned after %,d ms. A rendezvous can hang as "
                + "well as evaporate: if some parties are still waiting for a party "
                + "that has already moved on, nobody is ever released (D19, D21)",
                rounds, PATIENCE_MILLIS);
        return sync.tallies();
    }

    private static Thread startRunner(RoundSync sync, int rounds) {
        Thread runner = new Thread(() -> {
            try {
                sync.runAll(rounds);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "round-sync-runner");
        runner.setDaemon(true);     // so a hung rendezvous cannot wedge this JVM
        runner.start();
        return runner;
    }

    /*
     * Worker threads other than worker 0, which is busy doing the slow work.
     */
    private static List<Long> waitingWorkerThreadIds(ThreadMXBean threadMx) {
        List<Long> ids = new ArrayList<>();
        for (ThreadInfo info : threadMx.getThreadInfo(threadMx.getAllThreadIds())) {
            if (info != null
                    && info.getThreadName().startsWith("round-worker-")
                    && !info.getThreadName().equals("round-worker-0")) {
                ids.add(info.getThreadId());
            }
        }
        return ids;
    }

    private static long totalCpuNanos(ThreadMXBean threadMx, List<Long> threadIds) {
        long total = 0;
        for (long id : threadIds) {
            long cpu = threadMx.getThreadCpuTime(id);
            if (cpu > 0) {                          // -1 once the thread is gone
                total += cpu;
            }
        }
        return total;
    }

    private static void sleepQuietly(long millis) {
        if (millis <= 0) {
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
