package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.RoundSync;
import com.locallearn.concurrency.support.Check;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * <b>EXERCISE 11 — make the rounds actually synchronise.</b> See
 * {@code t07coordination.D19_LatchVersusBarrier}.
 *
 * <p>The starting code uses one {@link java.util.concurrent.CountDownLatch},
 * created once in the constructor, as a per-round barrier. Round 1 works
 * perfectly. From round 2 onwards the latch's count is already zero, so
 * {@code countDown()} does nothing and {@code await()} returns immediately —
 * there is no rendezvous left at all, and the workers run free while the
 * code still looks synchronised. Nothing throws and nothing hangs; the
 * tallies simply stop being 6.
 *
 * <p>A latch is <b>one-shot</b>. It counts down to zero and stays there
 * forever, and there is no {@code reset()} by design. What you want is the
 * other half of the 2×2 — a reusable rendezvous of a fixed set of parties —
 * which is {@link java.util.concurrent.CyclicBarrier}.
 *
 * <p>Use the <b>barrier action</b> for the tally. It runs on the last thread
 * to arrive, once per round, while every other party is still parked, which
 * makes it the only instant in the round when nothing else is touching the
 * shared state. Tallying anywhere else — even with a correct barrier — races
 * with the workers starting the next round.
 *
 * <p>And do not "fix" this with {@code while (arrived.get() < workers) { }}.
 * It passes the correctness tests and burns a core per waiting worker; the
 * third test measures per-thread CPU time and rejects it, exactly as Ex7's
 * and Ex8's CPU assertions did.
 *
 * <p>Worth noticing while you are in here: this is the failure mode that costs
 * the most time in production, because it produces no symptom of its own. The
 * threads are alive, nothing throws, the code reads correctly, and the only
 * evidence is a number downstream that is quietly wrong.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex11Rounds   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex11'                                   # the grade
 * </pre>
 */
public final class Ex11Rounds
        implements com.locallearn.concurrency.api.Contracts.RoundSync {

    private final int workers;
    private final java.util.function.IntConsumer roundWork;
    private final java.util.List<Integer> tallies =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final java.util.concurrent.atomic.AtomicInteger arrived =
            new java.util.concurrent.atomic.AtomicInteger();

    // TODO broken: created ONCE. A latch cannot be reused — after round 1
    // TODO broken: its count is zero, so there is no barrier at all.
    private final java.util.concurrent.CountDownLatch roundDone;

    public Ex11Rounds(int workers, java.util.function.IntConsumer roundWork) {
        this.workers = workers;
        this.roundWork = roundWork;
        this.roundDone = new java.util.concurrent.CountDownLatch(workers);
    }

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
                        roundDone.countDown();      // TODO broken: no-op once at zero
                        roundDone.await();          // TODO broken: returns instantly once at zero
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

    /** Only ever reached if the rendezvous has hung, so it can afford to be generous. */
    private static final long PATIENCE_MILLIS = 15_000;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 11 — a reusable rendezvous", "ExerciseTests$Ex11");

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

    /**
     * Runs the rounds on a daemon thread and refuses to wait forever. A broken
     * rendezvous can fail in either direction — no barrier at all, or a barrier
     * some parties never reach — so the checker has to be able to report a hang
     * rather than become one.
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

    /** Worker threads other than worker 0, which is busy doing the slow work. */
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
