package com.locallearn.concurrency.t10diagnostics;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.t10diagnostics.D30_TheIncident.BrokenService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * DEMO 31 — Every instrument applied to every symptom, including the times it
 * finds nothing.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t10diagnostics.D31_DiagnosingTheIncident}
 *
 * <p>Run D30 first and try to diagnose the four symptoms yourself. This demo is
 * the answer key, and reading an answer key before attempting the questions is
 * the single most reliable way to feel like you have learned something while
 * not having learned it.
 *
 * <p>The structure is deliberately repetitive: for each of D30's four symptoms
 * it runs the <em>same</em> four instruments — the dump census, the deadlock
 * detectors, per-thread CPU time, and the pool's own gauges — and reports what
 * each one said. The value is in the <b>misses</b>. Across the four symptoms
 * these instruments return sixteen answers and only six of them are useful, and
 * knowing in advance which six is the difference between a twenty-minute triage
 * and a four-hour one.
 *
 * <h2>The one sentence to take away</h2>
 * {@code findDeadlockedThreads()} returning {@code null} is <b>not</b> evidence
 * that you do not have a hang. It is evidence that your hang is not a lock
 * cycle — which rules out roughly one family of causes and leaves three
 * (exhausted pool, uncounted latch, unfed queue), each of which has to be
 * diagnosed from thread <em>stacks</em> and from your own gauges instead.
 */
public final class D31_DiagnosingTheIncident {

    public static void main(String[] args) throws Exception {
        if (!Dump.enableCpuTime()) {
            Log.log("this JVM cannot measure per-thread CPU time; results will be incomplete");
        }

        symptomA_partialHang();
        symptomB_totalHang();
        symptomC_wrongAnswers();
        symptomD_pinnedCore();

        Log.section("THE SCOREBOARD");
        Log.log("                        symptom A   symptom B   symptom C   symptom D");
        Log.log("  dump census             names it    names it    CLEAN       misleads");
        Log.log("  deadlock detectors      NAMES IT    SILENT      CLEAN       n/a");
        Log.log("  per-thread CPU time     ~0 ms       ~0 ms       CLEAN       NAMES IT");
        Log.log("  pool gauges             n/a         NAMES IT    CLEAN       n/a");
        Log.log("  comparing in to out     n/a         n/a         NAMES IT    confirms");
        Log.log("");
        Log.log("Only symptom A is solved by a tool. B needs a tool plus a stack plus a");
        Log.log("gauge. C is invisible to every runtime instrument that exists and is");
        Log.log("found only by an assertion you wrote in advance. D needs CPU time AND a");
        Log.log("progress counter, because either one alone is ambiguous.");

        Log.takeaway("""
                The instrument tells you WHERE a thread stopped. It never tells you
                WHY, and for a whole family of failures it does not even tell you
                that anything stopped. Diagnosis is reading the stack you got and
                knowing which mechanism produces that stack — which is what topics
                1 through 9 were for. Now fix it: Ex15.""");
    }

    // ══════════════════════════════════════════════════════════ SYMPTOM A
    private static void symptomA_partialHang() throws Exception {
        Log.section("SYMPTOM A — two requests never return, the rest of the service is fine");
        BrokenService service = new BrokenService("desk-a", 4);

        CountDownLatch gate = new CountDownLatch(1);
        startCaller("request-ledger-fwd", gate, () -> service.transfer(0, 1, 100));
        startCaller("request-ledger-rev", gate, () -> service.transfer(1, 0, 100));
        gate.countDown();
        TimeUnit.MILLISECONDS.sleep(1_500);

        instrument1(" the two stuck threads are WAITING, on a ReentrantLock, and the dump"
                    + " names who holds it", "request-ledger-");
        instrument2("FOUND IT. Both threads, both locks, both owners.", "request-ledger-");
        instrument3("the stuck threads burn no CPU at all — they are parked, not looping",
                "request-ledger-", 500);

        Log.log("");
        Log.log("VERDICT: a lock-ordering deadlock (topic 4). transfer(0,1) takes lock 0");
        Log.log("then lock 1; transfer(1,0) takes them in the opposite order. The bug is");
        Log.log("never 'two locks' — it is 'two locks in two different orders'.");
        Log.log("Note the state word: WAITING, not BLOCKED, because ReentrantLock parks");
        Log.log("on AQS. If you had grepped this dump for BLOCKED you would have found");
        Log.log("nothing and concluded there was no deadlock (topic 1's trap).");
        service.shutdown();
    }

    // ══════════════════════════════════════════════════════════ SYMPTOM B
    private static void symptomB_totalHang() throws Exception {
        Log.section("SYMPTOM B — the whole service stops, and stays stopped");
        int workers = 4;
        BrokenService service = new BrokenService("desk-b", workers);
        service.armOccupancyGate(workers);              // see D30: makes it reproduce every run

        CountDownLatch gate = new CountDownLatch(1);
        for (int i = 0; i < workers * 2; i++) {
            int n = i;
            startCaller("load-b-" + n, gate, () -> service.handle("acme", "burst-" + n));
        }
        gate.countDown();
        TimeUnit.MILLISECONDS.sleep(1_500);

        // The tool you would reach for FIRST is the one that has nothing to say.
        instrument2("NOTHING. And the service is completely stopped.", "desk-b-");
        Log.log("");
        Log.log("(Unscoped, this call still reports symptom A's ledger deadlock, which is");
        Log.log(" real and unrelated. Triage means asking about the threads you are");
        Log.log(" actually investigating, or you will keep re-solving yesterday's bug.)");
        Log.log("");
        Log.log("That silence is a fact, not an absence of facts: there is no cycle of");
        Log.log("lock OWNERSHIP here, so the hang is one of the kinds the detector");
        Log.log("cannot see. Keep going — the stacks still know.");

        instrument1("every worker is parked in FutureTask.get, called from handle()",
                "desk-b-worker-");
        instrument3("and every one of them is burning zero CPU: parked, not spinning",
                "desk-b-worker-", 500);
        instrument4(service);

        Log.log("");
        Log.log("VERDICT: pool exhaustion (topics 5 and 8). Every worker is blocked on a");
        Log.log("Future whose task is sitting in the queue of the very pool it is");
        Log.log("blocking. active=%d of %d workers, and %d tasks queued that can never be",
                service.activeWorkers(), workers, service.queueDepth());
        Log.log("scheduled because no worker will ever be free to take one. The queue is");
        Log.log("unbounded, so nothing was rejected and nothing threw — it simply stopped.");
        Log.log("This is the lesson: 'no deadlock reported' and 'not deadlocked' are");
        Log.log("different statements, and only the first one is what the tool said.");
        service.shutdown();
        TimeUnit.MILLISECONDS.sleep(200);               // let the interrupted callers report
    }

    // ══════════════════════════════════════════════════════════ SYMPTOM C
    private static void symptomC_wrongAnswers() throws Exception {
        Log.section("SYMPTOM C — nothing hangs, and some answers belong to someone else");
        BrokenService service = new BrokenService("desk-c", 4);

        for (int i = 0; i < 50; i++) {
            service.handle("tenant-" + (i % 5), "order-" + i);
        }
        List<String> systemAnswers = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            systemAnswers.add(service.handle(null, "system-sweep-" + i));
        }

        instrument2("NOTHING — and this time there genuinely is no hang.", "desk-c-");
        instrument1("every worker is idle and parked in the pool's own take(), which is"
                    + " exactly what a healthy pool looks like", "desk-c-worker-");
        instrument3("no CPU is being burned by the workers either", "desk-c-worker-", 500);
        Log.log("");
        Log.log("Every instrument reports a healthy service. It IS a healthy service by");
        Log.log("every definition a runtime instrument can express.");

        long wrong = systemAnswers.stream().filter(a -> !a.contains("tenant=anonymous")).count();
        Log.log("");
        Log.log("--- the only instrument that works: compare the input to the output ---");
        Log.log("  50 requests were sent with NO tenant. They must answer tenant=anonymous.");
        Log.log("  %d of them did not.", wrong);
        Log.log("  first: %s", systemAnswers.stream()
                .filter(a -> !a.contains("tenant=anonymous")).findFirst().orElse("(none)"));
        Log.log("");
        Log.log("VERDICT: a ThreadLocal left on a pooled thread (topic 6). The context is");
        Log.log("set when a request has a tenant and never cleared, so the thread goes");
        Log.log("back into the pool still wearing the last tenant it served, and the next");
        Log.log("task to land on it inherits an identity that is not its own.");
        Log.log("");
        Log.log("Notice which tenant leaks: whichever one happened to use that thread last.");
        Log.log("So the wrong answer is different on every run and on every thread, which");
        Log.log("is why this reproduces beautifully in production and never in staging.");
        Log.log("This is the failure mode to fear. A hang pages you; wrong data invoices");
        Log.log("the wrong customer for six weeks and nobody notices.");
        service.shutdown();
    }

    // ══════════════════════════════════════════════════════════ SYMPTOM D
    private static void symptomD_pinnedCore() throws Exception {
        Log.section("SYMPTOM D — a core at 100% while the service is idle");
        BrokenService service = new BrokenService("desk-d", 4);
        service.handle("acme", "warm-up");
        TimeUnit.MILLISECONDS.sleep(200);

        instrument1("the reaper is RUNNABLE — which is EXACTLY what a thread doing useful"
                    + " work looks like. The state word cannot tell them apart", "desk-d-reaper");

        long auditedBefore = service.audited();
        instrument3("but the CPU time can", "desk-d-", 1_000);
        long auditedAfter = service.audited();

        Log.log("");
        Log.log("and the progress counter over that same second: %d -> %d audit entries",
                auditedBefore, auditedAfter);
        Log.log("");
        Log.log("VERDICT: a busy-wait (topic 5). poll() returns null immediately when the");
        Log.log("queue is empty, so a loop around it is a spin. A full core consumed and");
        Log.log("zero work done. It passes every correctness test ever written and shows");
        Log.log("up only on the infrastructure bill — which is why Ex7, Ex8 and Ex15 all");
        Log.log("assert on CPU time.");
        Log.log("");
        Log.log("The two instruments are only conclusive TOGETHER: high CPU alone is a");
        Log.log("busy service, and a flat counter alone is an idle one. High CPU AND a");
        Log.log("flat counter is a thread that is working hard at nothing.");
        service.shutdown();
    }

    // ══════════════════════════════════════════════════ the four instruments

    /** Instrument 1 — the thread dump. Where is every thread stopped? */
    private static void instrument1(String expectation, String namePrefix) {
        Log.log("");
        Log.log("--- instrument 1: the dump (jcmd Thread.print / Dump.census) ---");
        Log.log("    %s", expectation);
        Dump.census("census", namePrefix);
        Map<Thread.State, Integer> states = Dump.statesByName(namePrefix);
        Log.log("    states: %s", states);
    }

    /**
     * Instrument 2 — the deadlock detectors. Is it a lock cycle?
     *
     * <p>Scoped to the threads of the symptom under investigation. Symptom A's
     * deadlock is never repaired — those two threads are parked in
     * {@code ReentrantLock.lock()}, which is not interruptible, so they stay
     * deadlocked for the life of the JVM. Without the filter they would show up
     * in symptoms B, C and D as well and drown out the answer, which is exactly
     * what a known-but-unfixed issue does to you during a real incident.
     */
    private static void instrument2(String expectation, String namePrefix) {
        Log.log("");
        Log.log("--- instrument 2: findDeadlockedThreads(), scoped to '%s*' ---", namePrefix);
        Log.log("    %s", expectation);
        System.out.println("    " + Dump.deadlockReport(namePrefix).replace("\n", "\n    "));
    }

    /** Instrument 3 — per-thread CPU time. Busy, or spinning? */
    private static void instrument3(String expectation, String namePrefix, long windowMillis)
            throws InterruptedException {
        Log.log("");
        Log.log("--- instrument 3: per-thread CPU time ---");
        Log.log("    %s", expectation);
        Dump.cpuOverWindow(namePrefix, windowMillis);
    }

    /** Instrument 4 — the pool's own gauges. Nobody else can give you these. */
    private static void instrument4(BrokenService service) throws InterruptedException {
        Log.log("");
        Log.log("--- instrument 4: the pool's own gauges (ThreadPoolExecutor) ---");
        Log.log("    sampled twice, one second apart. A healthy pool's queue drains.");
        Log.log("    t+0s: active=%d queued=%d completed=%d",
                service.activeWorkers(), service.queueDepth(), service.completed());
        TimeUnit.SECONDS.sleep(1);
        Log.log("    t+1s: active=%d queued=%d completed=%d   <-- identical. Frozen.",
                service.activeWorkers(), service.queueDepth(), service.completed());
        Log.log("    Export these three numbers as metrics. They are the only instrument");
        Log.log("    that distinguishes 'the pool is busy' from 'the pool is dead', and");
        Log.log("    no thread dump will ever compute them for you.");
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    private static void startCaller(String name, CountDownLatch gate, ThrowingRunnable body) {
        Thread thread = new Thread(() -> {
            try {
                gate.await();
                body.run();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                Log.log("%s failed: %s", name, e);
            }
        }, name);
        thread.setDaemon(true);
        thread.start();
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    private D31_DiagnosingTheIncident() {
    }
}
