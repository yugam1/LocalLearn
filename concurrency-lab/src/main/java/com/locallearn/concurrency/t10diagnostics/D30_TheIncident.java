package com.locallearn.concurrency.t10diagnostics;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DEMO 30 — The incident. A service with four planted defects, and a demo that
 * shows you only the <b>symptoms</b>.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t10diagnostics.D30_TheIncident}
 * <br>Or, to hold the wedged JVM open so you can dump it yourself:
 * {@code java -cp target/classes com.locallearn.concurrency.t10diagnostics.D30_TheIncident hold 60}
 *
 * <p>Every other page in this curriculum announces its mechanism in the title,
 * so you always know which tool to reach for. Production never does that. This
 * demo removes the label. Four acts, four different symptoms, and nothing in
 * the output names a cause. Work each one out before you open
 * {@code 10-diagnostics-incident.md}, and before you run D31.
 *
 * <h2>What you are looking at</h2>
 * {@link BrokenService} is a small request-processing service of the sort every
 * backend contains: a fixed worker pool, a per-request tenant context, a couple
 * of internal ledgers guarded by locks, and a background thread that drains an
 * audit queue. It is about eighty lines. Four of them are wrong, each drawn
 * from a different earlier topic, and none of them is obviously wrong on
 * inspection — which is the point.
 *
 * <h2>The four symptoms</h2>
 * <ol>
 *   <li><b>Act A — partial hang.</b> Two requests never return. The rest of the
 *       service keeps working. Threads are stuck; something reports it.</li>
 *   <li><b>Act B — total hang.</b> Under concurrent load the service stops
 *       completing anything at all, and stays stopped. <em>No tool reports a
 *       deadlock.</em> That silence is itself a clue, not an absence of one.</li>
 *   <li><b>Act C — no hang, wrong answers.</b> Every request returns, promptly,
 *       with a 200. Some of them are attributed to the wrong tenant. No thread
 *       dump in the world will show you this.</li>
 *   <li><b>Act D — a core at 100%, no work in flight.</b> The service is idle
 *       and the machine is not.</li>
 * </ol>
 *
 * <p>Acts A and B deliberately leave their services wedged and do not shut them
 * down. That is so the {@code hold} mode gives you a JVM containing both
 * failures at once — a realistic dump, where you have to work out which stuck
 * threads belong to which problem.
 */
public final class D30_TheIncident {

    public static void main(String[] args) throws Exception {
        if (!Dump.enableCpuTime()) {
            Log.log("this JVM cannot measure per-thread CPU time; Act D will be empty");
        }

        BrokenService deskA = actA_partialHang();
        BrokenService deskB = actB_totalHang();
        actC_wrongAnswers();
        actD_idleCpu();

        Log.section("THE BRIEF");
        Log.log("Four symptoms, four different defects, each from a different topic:");
        Log.log("  A. two requests hang; the rest of the service is fine");
        Log.log("  B. every request hangs, and nothing reports a deadlock");
        Log.log("  C. nothing hangs; some answers are wrong");
        Log.log("  D. a core is pinned while the service is idle");
        Log.log("");
        Log.log("Diagnose each one before reading. The services from acts A and B are");
        Log.log("still wedged in this JVM right now — take a dump and look.");
        Dump.jcmdHint();

        long holdSeconds = holdSeconds(args);
        if (holdSeconds > 0) {
            Log.log("");
            Log.log("holding for %d seconds — dump this pid NOW", holdSeconds);
            TimeUnit.SECONDS.sleep(holdSeconds);
        }

        // Reference the wedged services so nothing can be garbage-collected out
        // from under the dump you are about to take.
        Log.log("(desk-a completed %d, desk-b completed %d — neither is going anywhere)",
                deskA.completed(), deskB.completed());

        Log.takeaway("""
                Symptoms are not causes. "It hangs" is four different bugs in this
                one service, and the tool that finds the first is silent on the
                second, blind to the third, and irrelevant to the fourth. Run D31
                to see each instrument applied to each symptom — including the
                three times it finds nothing.""");
    }

    // ══════════════════════════════════════════════════════════════ ACT A
    /** Symptom: two requests never come back. Everything else still works. */
    private static BrokenService actA_partialHang() throws Exception {
        Log.section("ACT A — two requests never return (partial hang)");
        BrokenService service = new BrokenService("desk-a", 4);

        CountDownLatch gate = new CountDownLatch(1);
        Thread one = caller("request-ledger-fwd", () -> {
            awaitQuietly(gate);
            service.transfer(0, 1, 100);
        });
        Thread two = caller("request-ledger-rev", () -> {
            awaitQuietly(gate);
            service.transfer(1, 0, 100);
        });
        gate.countDown();

        one.join(TimeUnit.SECONDS.toMillis(3));
        two.join(TimeUnit.SECONDS.toMillis(1));

        Log.log("3 seconds after the two transfers started:");
        Log.log("  request-ledger-fwd alive=%b state=%s", one.isAlive(), one.getState());
        Log.log("  request-ledger-rev alive=%b state=%s", two.isAlive(), two.getState());

        // And the service is otherwise healthy, which is what makes this so
        // confusing in production: the health check passes.
        String stillWorks = service.handle("acme", "unrelated-request");
        Log.log("meanwhile an unrelated request still succeeds: %s", stillWorks);

        // But the damage is not contained to those two threads. Anything that
        // needs the ledgers they are holding now joins the queue of the doomed,
        // which is how two stuck threads become an outage twenty minutes later.
        Thread reporting = caller("request-ledger-report", service::ledgerTotal);
        reporting.join(TimeUnit.SECONDS.toMillis(2));
        Log.log("a third, unrelated request that merely READS the ledgers: alive=%b state=%s",
                reporting.isAlive(), reporting.getState());
        Log.log("two stuck threads, and now three. This is how a deadlock you could");
        Log.log("have ignored at 09:00 is an outage by 09:20.");
        return service;                                 // left wedged on purpose
    }

    // ══════════════════════════════════════════════════════════════ ACT B
    /** Symptom: under load, the whole service stops. Permanently. */
    private static BrokenService actB_totalHang() throws Exception {
        Log.section("ACT B — under concurrent load the service stops completely");
        int workers = 4;
        int concurrentRequests = 8;
        BrokenService service = new BrokenService("desk-b", workers);

        Log.log("warm-up: one request at a time works perfectly");
        for (int i = 0; i < 3; i++) {
            Log.log("  %s", service.handle("acme", "warmup-" + i));
        }

        // Make the race a certainty rather than a likelihood. Without this, the
        // act reproduced in only 4 of 7 runs on my machine: if a worker happens
        // to submit its sub-task while a second worker is still idle, that idle
        // worker runs the sub-task and the request completes. The gate below
        // holds the first `workers` requests until all of them are provably
        // occupying a worker thread, which is the state production reaches on
        // its own under any real burst. Same technique as D15, which waits for a
        // consumer to reach WAITING before flipping its flag.
        service.armOccupancyGate(workers);

        Log.log("now %d concurrent requests against %d workers:", concurrentRequests, workers);
        CountDownLatch gate = new CountDownLatch(1);
        List<Thread> callers = new ArrayList<>();
        for (int i = 0; i < concurrentRequests; i++) {
            int n = i;
            callers.add(caller("load-b-" + n, () -> {
                awaitQuietly(gate);
                service.handle("acme", "burst-" + n);
            }));
        }
        long before = service.completed();
        gate.countDown();

        for (int second = 1; second <= 3; second++) {
            TimeUnit.SECONDS.sleep(1);
            Log.log("  t+%ds: completed %d of %d, pool active=%d queued=%d",
                    second, service.completed() - before, concurrentRequests,
                    service.activeWorkers(), service.queueDepth());
        }
        long finished = callers.stream().filter(t -> !t.isAlive()).count();
        Log.log("%d of %d caller threads have returned. The counter has not moved since t+0.",
                finished, concurrentRequests);
        Log.log("nothing is running. nothing is scheduled to run. nothing will ever run.");
        return service;                                 // left wedged on purpose
    }

    // ══════════════════════════════════════════════════════════════ ACT C
    /** Symptom: nothing hangs. Some answers are simply wrong. */
    private static void actC_wrongAnswers() throws Exception {
        Log.section("ACT C — every request succeeds; some answers belong to someone else");
        BrokenService service = new BrokenService("desk-c", 4);
        int tenantRequests = 200;
        int systemRequests = 200;

        // Phase 1: ordinary traffic from real tenants.
        for (int i = 0; i < tenantRequests; i++) {
            service.handle("tenant-" + (i % 5), "order-" + i);
        }

        // Phase 2: the unauthenticated system traffic every service has — health
        // probes, cache warmers, scheduled sweeps. These belong to NO tenant, and
        // the service is supposed to say so.
        int wrong = 0;
        String firstWrong = null;
        for (int i = 0; i < systemRequests; i++) {
            String answer = service.handle(null, "system-sweep-" + i);
            if (!answer.contains("tenant=anonymous")) {
                wrong++;
                if (firstWrong == null) {
                    firstWrong = answer;
                }
            }
        }

        Log.log("%d tenant requests, then %d requests that belong to NO tenant.", tenantRequests, systemRequests);
        Log.log("every one of the %d returned successfully and quickly.", tenantRequests + systemRequests);
        Log.log("of the %d that should have said tenant=anonymous, %d did not.", systemRequests, wrong);
        if (firstWrong != null) {
            Log.log("first wrong answer: %s", firstWrong);
        }
        Log.log("");
        Log.log("No thread is stuck. No lock is contended. No CPU is pinned. A thread");
        Log.log("dump of this JVM is completely clean, and a system sweep just read");
        Log.log("another customer's books. Which instrument finds this one?");
        service.shutdown();
    }

    // ══════════════════════════════════════════════════════════════ ACT D
    /** Symptom: the service is idle and a core is not. */
    private static void actD_idleCpu() throws Exception {
        Log.section("ACT D — idle service, busy machine");
        BrokenService service = new BrokenService("desk-d", 4);
        service.handle("acme", "warm-up");              // bring the worker threads into being
        TimeUnit.MILLISECONDS.sleep(200);

        Log.log("the service has nothing to do for the next second. Watch the CPU:");
        Dump.cpuOverWindow("desk-d-", 1_000);
        Log.log("");
        Log.log("zero requests in flight, one core gone. In a dump that thread is");
        Log.log("RUNNABLE, which looks exactly like a thread doing useful work.");
        service.shutdown();
    }

    // ══════════════════════════════════════════════════════════════════════
    /**
     * The service under investigation. Four defects, each from a different
     * topic, each of a kind that survives code review because the line looks
     * ordinary. They are marked in the source only so this file stays readable
     * — in D31 and in the exercise you get them unmarked.
     */
    static final class BrokenService {

        static final int LEDGERS = 4;
        static final long INITIAL = 1_000_000L;

        /**
         * The request context: set once at the entry point so that code three
         * layers down does not have to take a tenant parameter it does not
         * care about. This is {@code SecurityContextHolder}, it is SLF4J's
         * {@code MDC}, and it is in every service you will ever work on.
         */
        private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

        /**
         * Milliseconds a transfer spends holding its first lock before reaching
         * for the second. In production this window is sub-microsecond and the
         * failure is a matter of <em>when</em>, not <em>if</em>; here it is
         * widened so the act reproduces on every single run rather than one run
         * in a thousand. Same technique as D11.
         */
        private static final long LOCK_WINDOW_MILLIS = 250;

        private final ThreadPoolExecutor pool;
        private final BlockingQueue<String> auditQueue = new LinkedBlockingQueue<>();
        private final Thread reaper;
        private final long[] ledgers = new long[LEDGERS];
        private final ReentrantLock[] locks = new ReentrantLock[LEDGERS];
        private final AtomicLong completed = new AtomicLong();
        private final AtomicLong audited = new AtomicLong();
        private volatile boolean running = true;

        /**
         * Demo scaffolding, <b>not</b> one of the defects. When armed, each
         * request waits at the start of its task until {@code workers} requests
         * are simultaneously occupying worker threads. That is a state a busy
         * production pool reaches by itself many times a second; forcing it here
         * is what turns Act B from "reproduces most runs" into "reproduces every
         * run", exactly as D15 forces its consumer to be parked before flipping
         * the stop flag. Remove it and the defect is still there — you would
         * just have to be unlucky in a subtler way to see it.
         */
        private volatile CountDownLatch occupancyGate;

        BrokenService(String tag, int workers) {
            AtomicInteger seq = new AtomicInteger();
            this.pool = new ThreadPoolExecutor(
                    workers, workers, 0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    runnable -> {
                        Thread thread = new Thread(runnable, tag + "-worker-" + seq.getAndIncrement());
                        thread.setDaemon(true);         // this JVM must still be able to exit
                        return thread;
                    });
            for (int i = 0; i < LEDGERS; i++) {
                ledgers[i] = INITIAL;
                locks[i] = new ReentrantLock();
            }
            this.reaper = new Thread(this::reap, tag + "-reaper");
            this.reaper.setDaemon(true);
            this.reaper.start();
        }

        /** Handles one request. {@code tenant} is null for unauthenticated system traffic. */
        String handle(String tenant, String request) throws Exception {
            Future<String> outer = pool.submit(() -> {
                // ── DEFECT 3 (topic 6) ──────────────────────────────────────
                // The context is installed for the duration of the request, and
                // the thread goes back in the pool still wearing it.
                if (tenant != null) {
                    CURRENT_TENANT.set(tenant);
                }

                CountDownLatch armed = occupancyGate;   // demo scaffolding, see the field
                if (armed != null) {
                    armed.countDown();
                    armed.await();
                }

                // ── DEFECT 2 (topics 5 + 8) ─────────────────────────────────
                // Validation is farmed out so it can run "in parallel", and the
                // request waits for its result.
                Future<String> validation = pool.submit(() -> validate(request));
                String verdict = validation.get();

                String effective = CURRENT_TENANT.get();
                if (effective == null) {
                    effective = "anonymous";
                }
                auditQueue.add(effective + "/" + request);
                completed.incrementAndGet();
                return "tenant=" + effective + "|req=" + request + "|" + verdict;
            });
            return outer.get();
        }

        private String validate(String request) {
            return request.isEmpty() ? "rejected" : "accepted";
        }

        /** Moves money between two internal ledgers. Called on request threads. */
        void transfer(int fromLedger, int toLedger, long amount) {
            // ── DEFECT 1 (topic 4) ──────────────────────────────────────────
            locks[fromLedger].lock();
            try {
                Stress.sleep(LOCK_WINDOW_MILLIS);       // see LOCK_WINDOW_MILLIS
                locks[toLedger].lock();
                try {
                    ledgers[fromLedger] -= amount;
                    ledgers[toLedger] += amount;
                } finally {
                    locks[toLedger].unlock();
                }
            } finally {
                locks[fromLedger].unlock();
            }
        }

        /** Drains the audit queue in the background. */
        private void reap() {
            while (running) {
                // ── DEFECT 4 (topic 5) ──────────────────────────────────────
                String entry = auditQueue.poll();
                if (entry != null) {
                    audited.incrementAndGet();
                }
            }
        }

        /** Demo scaffolding — see {@link #occupancyGate}. */
        void armOccupancyGate(int workers) {
            occupancyGate = new CountDownLatch(workers);
        }

        long completed() {
            return completed.get();
        }

        long audited() {
            return audited.get();
        }

        int activeWorkers() {
            return pool.getActiveCount();
        }

        int queueDepth() {
            return pool.getQueue().size();
        }

        long ledgerTotal() {
            long total = 0;
            for (int i = 0; i < LEDGERS; i++) {
                locks[i].lock();
                try {
                    total += ledgers[i];
                } finally {
                    locks[i].unlock();
                }
            }
            return total;
        }

        void shutdown() {
            running = false;
            reaper.interrupt();
            pool.shutdownNow();
        }
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static Thread caller(String name, ThrowingRunnable body) {
        Thread thread = new Thread(() -> {
            try {
                body.run();
            } catch (Exception e) {
                Log.log("%s failed: %s", name, e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static void awaitQuietly(CountDownLatch gate) {
        try {
            gate.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static long holdSeconds(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) {
            if ("hold".equals(args[i])) {
                return Long.parseLong(args[i + 1]);
            }
        }
        return 0;
    }

    private D30_TheIncident() {
    }
}
