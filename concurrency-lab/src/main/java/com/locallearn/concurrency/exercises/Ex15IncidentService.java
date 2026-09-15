package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.IncidentService;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;
import com.locallearn.concurrency.t10diagnostics.Dump;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * <b>EXERCISE 15 — the incident.</b> Demos: {@code t10diagnostics.D30_TheIncident}
 * and {@code t10diagnostics.D31_DiagnosingTheIncident}.
 *
 * <p>This is the capstone, and it is deliberately unlike every other
 * exercise in this package. The others name their mechanism in the heading, so
 * you always know which chapter the fix comes from. Production never does
 * that. Here you get a service, four failing checks, and four
 * <em>symptoms</em> — and which of topics 1 to 9 applies to each is the
 * thing you are being examined on.
 *
 * <p>The service below is about seventy lines and looks entirely reasonable.
 * Four lines are wrong:
 *
 * <ol>
 *   <li><b>Two requests hang; the rest of the service keeps working.</b> The
 *       JVM will tell you what this one is if you ask it the right question.
 *       Both stuck threads report {@code WAITING}, which is worth
 *       remembering before you go grepping for {@code BLOCKED}.</li>
 *   <li><b>Under concurrent load the service stops completely and stays
 *       stopped</b> — and nothing reports a deadlock, because there is no
 *       cycle of lock <em>ownership</em> anywhere. Look at where the worker
 *       threads are parked and at what the pool's own queue is doing. Ask
 *       yourself what those threads are waiting <em>for</em>, and who was
 *       supposed to do it.</li>
 *   <li><b>Nothing hangs and some answers are wrong.</b> Requests that carry
 *       no tenant come back attributed to somebody else's tenant. No thread
 *       dump, deadlock report, or CPU measurement will ever show you this;
 *       only comparing what you sent with what came back. Ask what a pooled
 *       thread still carries after it finishes a task.</li>
 *   <li><b>A core is pinned while the service is idle.</b> The thread doing
 *       it is {@code RUNNABLE}, which is exactly what a thread doing useful
 *       work looks like, so the state word cannot help you. The verb you
 *       chose can.</li>
 * </ol>
 *
 * <p>Run {@code D30_TheIncident} to watch all four, then
 * {@code D31_DiagnosingTheIncident} only once you have made your own
 * diagnosis. Each of the four defects came from a different topic; if you
 * find yourself fixing two of them the same way, one of the diagnoses is
 * wrong.
 *
 * <p>Each failure below prints a live evidence block built with {@link Dump} —
 * the deadlock detectors, a census of thread states, and the frame in
 * <em>your</em> code where each thread stopped. That is deliberate: a failure
 * here should read like the first thing you would have typed at a real
 * incident, so the habit transfers.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex15IncidentService   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex15'                                            # the grade
 * </pre>
 */
public final class Ex15IncidentService implements IncidentService {

    /**
     * The per-request context. This is {@code SecurityContextHolder}, it is
     * SLF4J's {@code MDC}, and it is in every service you will ever work on:
     * set once at the entry point so that code three layers down does not
     * need a tenant parameter it does not care about.
     */
    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private final ThreadPoolExecutor pool;
    private final BlockingQueue<String> auditQueue = new LinkedBlockingQueue<>();
    private final Thread reaper;
    private final long[] ledgers = new long[LEDGERS];
    private final ReentrantLock[] locks = new ReentrantLock[LEDGERS];
    private final AtomicLong completed = new AtomicLong();
    private volatile boolean running = true;

    public Ex15IncidentService(int workers) {
        AtomicInteger seq = new AtomicInteger();
        this.pool = new ThreadPoolExecutor(
                workers, workers, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                runnable -> {
                    Thread thread = new Thread(runnable,
                            THREAD_PREFIX + "worker-" + seq.getAndIncrement());
                    thread.setDaemon(true);     // a wedged pool must not outlive the test JVM
                    return thread;
                });
        for (int i = 0; i < LEDGERS; i++) {
            ledgers[i] = INITIAL_BALANCE;
            locks[i] = new ReentrantLock();
        }
        this.reaper = new Thread(this::reap, THREAD_PREFIX + "reaper");
        this.reaper.setDaemon(true);
        this.reaper.start();
    }

    @Override
    public String handle(String tenant, String request) throws Exception {
        Future<String> outer = pool.submit(() -> {
            if (tenant != null) {
                CURRENT_TENANT.set(tenant);                 // TODO symptom 3
            }

            // Validation is farmed out so it can run "in parallel", and the
            // request waits for the verdict before answering.
            Future<String> validation = pool.submit(() -> validate(request));   // TODO symptom 2
            validation.get();

            String effective = CURRENT_TENANT.get();
            if (effective == null) {
                effective = ANONYMOUS;
            }
            auditQueue.add(effective + "/" + request);
            completed.incrementAndGet();
            return "tenant=" + effective + "|req=" + request;
        });
        return outer.get();
    }

    private String validate(String request) {
        return request.isEmpty() ? "rejected" : "accepted";
    }

    @Override
    public void transfer(int fromLedger, int toLedger, long amount) {
        if (fromLedger == toLedger) {
            return;
        }
        locks[fromLedger].lock();                           // TODO symptom 1
        try {
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
            String entry = auditQueue.poll();               // TODO symptom 4
            if (entry != null) {
                // pretend to write it somewhere
                entry.length();
            }
        }
    }

    @Override
    public long ledgerTotal() {
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

    @Override
    public long completed() {
        return completed.get();
    }

    @Override
    public void shutdown() {
        running = false;
        reaper.interrupt();
        pool.shutdownNow();
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int WORKERS = 4;

    /**
     * How long a stress run gets before the checker calls it a hang. Two of
     * these four defects stop the service permanently rather than throwing, so
     * every run here is bounded: a checker that waits for a wedged service
     * becomes the incident instead of reporting it.
     */
    private static final int HANG_BUDGET_SECONDS = 4;

    /** Trials, because a lock cycle is a race: one clean run proves nothing. */
    private static final int TRANSFER_TRIALS = 2;

    /** Wall-clock window over which an idle service's CPU use is measured. */
    private static final long IDLE_WINDOW_MILLIS = 1_000;

    /** How many stopped-at frames an evidence block prints per thread family. */
    private static final int FRAMES_SHOWN = 5;

    /**
     * The caller threads — {@link Stress}'s workers, standing in for your web
     * container's request threads. For a deadlock reached through a public
     * method it is these, not the pool, that are stuck, and a triage that looks
     * only at the service's own threads finds nothing.
     */
    private static final String CALLER_THREADS = "stress-";

    /** The service's own threads. */
    private static final String SERVICE_THREADS = THREAD_PREFIX;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 15 — the incident", "ExerciseTests$Ex15");

        // ── Symptom 2: the service stops completely under load ─────────────
        check.that("keeps completing requests when many callers arrive at once", () -> {
            int callers = 16;
            int perCaller = 20;
            long expected = (long) callers * perCaller;
            IncidentService service = new Ex15IncidentService(WORKERS);
            try {
                boolean finished = Stress.run(callers, perCaller,
                        i -> handleOrFail(service, "tenant-" + (i % 4), "req-" + i),
                        HANG_BUDGET_SECONDS);

                Check.require(finished,
                        "the service stopped completing requests and never resumed: %d of %d "
                        + "finished in %ds, and it has not moved since. Nothing reports a "
                        + "deadlock because there is no cycle of lock OWNERSHIP here — look "
                        + "instead at where the worker threads are parked, what they are "
                        + "waiting FOR, and which thread was supposed to provide it "
                        + "(topics 5 and 8).%s",
                        service.completed(), expected, HANG_BUDGET_SECONDS,
                        evidence(SERVICE_THREADS));

                Check.equal(service.completed(), expected,
                        "requests were accepted and then silently lost (topic 5)");
            } finally {
                shutdownQuietly(service);
            }
        });

        // ── Symptom 1: two requests hang, the rest keeps working ───────────
        check.that("bidirectional transfers neither hang nor lose money — %d trials"
                .formatted(TRANSFER_TRIALS), () -> {
            for (int trial = 1; trial <= TRANSFER_TRIALS; trial++) {
                IncidentService service = new Ex15IncidentService(WORKERS);
                try {
                    boolean finished = Stress.run(16, 1_000, i -> {
                        // Both directions over the same pairs — the pattern that
                        // makes an argument-dependent acquisition order into a
                        // circular one.
                        int a = i % IncidentService.LEDGERS;
                        int b = (i * 7 + 1) % IncidentService.LEDGERS;
                        service.transfer(a, b, 1);
                        service.transfer(b, a, 1);
                    }, HANG_BUDGET_SECONDS);

                    // This check must come first and must stop the trial. The
                    // stuck threads still hold their locks, so ledgerTotal()
                    // would join the queue behind them and never return.
                    Check.require(finished,
                            "trial %d of %d: the transfers stopped making progress and the "
                            + "stuck threads are parked on a lock that another stuck thread "
                            + "holds. Note their state word before you go looking for it in a "
                            + "dump (topics 1 and 4).%s",
                            trial, TRANSFER_TRIALS, evidence(CALLER_THREADS, SERVICE_THREADS));

                    Check.equal(service.ledgerTotal(),
                            IncidentService.LEDGERS * IncidentService.INITIAL_BALANCE,
                            "trial %d of %d: money was created or destroyed. The two balance "
                            + "updates inside a transfer have to land as one indivisible step "
                            + "(topic 3)", trial, TRANSFER_TRIALS);
                } finally {
                    shutdownQuietly(service);
                }
            }
        });

        // ── Symptom 3: nothing hangs, some answers are wrong ───────────────
        check.that("a request's tenant never leaks into an unrelated later request", () -> {
            int tenantRequests = 200;
            int systemRequests = 200;
            IncidentService service = new Ex15IncidentService(WORKERS);
            try {
                List<String> answers = new ArrayList<>();
                boolean finished = Stress.run(1, 1, ignored -> {
                    try {
                        // Ordinary traffic first, so every pooled thread has
                        // served a real tenant at least once.
                        for (int i = 0; i < tenantRequests; i++) {
                            service.handle("tenant-" + (i % 5), "order-" + i);
                        }
                        // Then the unauthenticated system traffic every service
                        // has: health probes, cache warmers, scheduled sweeps.
                        // These belong to nobody, and the service must say so.
                        for (int i = 0; i < systemRequests; i++) {
                            answers.add(service.handle(null, "system-sweep-" + i));
                        }
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                }, HANG_BUDGET_SECONDS * 2);

                Check.require(finished,
                        "the service stopped responding before the tenant check could run — "
                        + "fix the stall first.%s", evidence(SERVICE_THREADS));

                List<String> contaminated = answers.stream()
                        .filter(answer -> !answer.contains("tenant=" + IncidentService.ANONYMOUS))
                        .toList();

                // Assert on the COUNT, not the list: printing two hundred wrong
                // answers would bury the sentence that explains them.
                Check.require(contaminated.isEmpty(),
                        "%d of %d requests that carried NO tenant came back attributed to "
                        + "somebody else's tenant. For example %s — it should have said "
                        + "tenant=%s. Nothing hung and nothing threw; the service simply "
                        + "answered for the wrong customer, and no thread dump, deadlock "
                        + "report or CPU measurement would ever have shown you that. Ask what "
                        + "a pooled thread is still carrying when the next task lands on it, "
                        + "and note WHICH tenant leaks: whichever one used that thread last, "
                        + "which is why this never reproduces in staging (topic 6).",
                        contaminated.size(), answers.size(),
                        contaminated.isEmpty() ? "(none)" : contaminated.get(0),
                        IncidentService.ANONYMOUS);
            } finally {
                shutdownQuietly(service);
            }
        });

        // ── Symptom 4: a core is pinned while the service is idle ──────────
        check.that("an idle service burns no CPU — %,d ms with nothing to do"
                .formatted(IDLE_WINDOW_MILLIS), () -> {
            Check.require(Dump.enableCpuTime(),
                    "this JVM cannot measure per-thread CPU time, so this check cannot run");

            IncidentService service = new Ex15IncidentService(WORKERS);
            try {
                service.handle("acme", "warm-up");          // bring the threads into being
                TimeUnit.MILLISECONDS.sleep(200);

                Map<String, Long> before = Dump.cpuMillisByName(THREAD_PREFIX);
                Check.require(!before.isEmpty(),
                        "expected live threads named '%s*'; the contract requires that prefix "
                        + "so CPU time can be attributed to them", THREAD_PREFIX);

                TimeUnit.MILLISECONDS.sleep(IDLE_WINDOW_MILLIS);
                Map<String, Long> after = Dump.cpuMillisByName(THREAD_PREFIX);

                long idleCpuMillis = 0;
                StringBuilder perThread = new StringBuilder();
                for (Map.Entry<String, Long> entry : after.entrySet()) {
                    long burned = Math.max(0, entry.getValue() - before.getOrDefault(entry.getKey(), 0L));
                    idleCpuMillis += burned;
                    perThread.append("%n  %-28s %,5d ms CPU (%d%% of one core)"
                            .formatted(entry.getKey(), burned, burned * 100 / IDLE_WINDOW_MILLIS));
                }

                // Parked threads use almost none; the allowance is per thread so
                // that a bigger pool is not penalised for existing.
                Check.require(idleCpuMillis < 200L * after.size(),
                        "the service had nothing to do for %,d ms and its %d threads burned "
                        + "%,d ms of CPU doing it. Parked threads use almost none, so a figure "
                        + "near one whole core means a thread is looping instead of waiting. "
                        + "It is RUNNABLE in a dump, which is exactly what a thread doing real "
                        + "work looks like, so the state word will not find it for you — the "
                        + "verb it calls will (topic 5).%s",
                        IDLE_WINDOW_MILLIS, after.size(), idleCpuMillis, perThread);
            } finally {
                shutdownQuietly(service);
            }
        });

        System.exit(check.finish());
    }

    // ── checker helpers ────────────────────────────────────────────────────

    /**
     * The evidence block. This is what you would have collected by hand at 3am,
     * and it is built from exactly the calls D29 demonstrates: the two deadlock
     * detectors, then a census of the threads that matter, then the frame in
     * this lab's own code where each of them stopped.
     *
     * <p>Two families of thread matter, and forgetting either one is a classic
     * triage mistake. {@code incident-*} are the service's own threads.
     * {@code stress-*} are the callers — the equivalent of your web container's
     * request threads — and for a lock-ordering deadlock reached through a
     * public method it is the <em>callers</em> that are stuck, not the pool.
     */
    private static String evidence(String... prefixes) {
        StringBuilder text = new StringBuilder("\n\n--- evidence ---------------------------------\n");
        text.append(Dump.deadlockReport(prefixes)).append('\n');
        for (String prefix : prefixes) {
            text.append(prefix).append("* states: ").append(Dump.statesByName(prefix)).append('\n');
            List<String> names = Dump.namesStartingWith(prefix);
            // A sample of the frames, not all of them. Sixteen identical stack
            // tops say nothing the first two did not, and burying the census
            // under them is how real dumps get skimmed instead of read.
            for (String name : names.subList(0, Math.min(names.size(), FRAMES_SHOWN))) {
                text.append("  \"").append(name).append("\" stopped at ")
                    .append(Dump.ownFrame(name)).append('\n');
            }
            if (names.size() > FRAMES_SHOWN) {
                text.append("  (").append(names.size() - FRAMES_SHOWN)
                    .append(" more ").append(prefix).append("* threads not listed)\n");
            }
        }
        text.append("----------------------------------------------");
        return text.toString();
    }

    private static void handleOrFail(IncidentService service, String tenant, String request) {
        try {
            service.handle(tenant, request);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            throw new IllegalStateException("handle() threw: " + e, e);
        }
    }

    /**
     * Best-effort cleanup. A wedged service left running would keep a spinning
     * thread alive and skew the CPU measurement of every later check, so this
     * runs even when a check has already failed.
     */
    private static void shutdownQuietly(IncidentService service) {
        try {
            service.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // the implementation under test is broken; that is what the checks are for
        }
    }
}
