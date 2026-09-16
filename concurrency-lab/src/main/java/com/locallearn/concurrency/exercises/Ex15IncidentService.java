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

/*
 * EXERCISE 15 — the capstone: one service, four unrelated bugs
 *
 * THE SCENARIO
 *   A small request service of the kind you would find behind an HTTP endpoint.
 *   handle runs a request on a worker pool and answers with the tenant it was
 *   served for; transfer moves money between two ledgers, each guarded by its
 *   own lock; a background reaper drains an audit queue. Callers arrive
 *   concurrently from many threads — in the checker, Stress's workers stand in
 *   for your web container's request threads.
 *
 * FOUR SEPARATE CONCERNS IN ONE CLASS
 *   Unlike every other exercise here, this one does not combine variations on a
 *   single mechanism. It combines four unrelated failure modes, one per area,
 *   and they are fixed in four different ways. If two of your fixes look the
 *   same, one diagnosis is wrong:
 *   1. Lock ordering — in transfer (topics 1, 3, 4).
 *   2. Thread-pool starvation — in handle (topics 5, 8).
 *   3. Thread-local lifecycle on a pooled thread — in handle (topic 6).
 *   4. Waiting versus polling — in reap (topic 5).
 *
 * WHAT IS WRONG RIGHT NOW
 *   Four lines are wrong. Each one produces a different symptom:
 *   1. Some transfers hang; the rest of the service keeps working. transfer
 *      locks fromLedger first and toLedger second, so the acquisition order
 *      depends on the arguments. A transfer 3→7 and a transfer 7→3
 *      running at once each hold the lock the other needs: a circular wait that
 *      never breaks. Both stuck threads report WAITING, not BLOCKED, because
 *      these are ReentrantLocks.
 *   2. Under concurrent load the service stops completely and stays stopped.
 *      handle submits a task to pool and that task submits validate to the SAME
 *      pool, then blocks on validation.get(). With workers outer tasks each
 *      occupying a thread and each waiting for an inner task that is still
 *      queued behind them, no thread is ever free to run a validation. No
 *      deadlock detector reports it: there is no cycle of lock OWNERSHIP, only
 *      threads waiting for work that cannot be scheduled.
 *   3. Nothing hangs and some answers are wrong. handle sets CURRENT_TENANT and
 *      never removes it. The pooled thread keeps that value after the task
 *      ends, so the next request on that thread — one that carried no tenant at
 *      all — reads the previous caller's tenant and answers under it. Which
 *      tenant leaks depends on which one used the thread last, which is why
 *      this never reproduces in staging.
 *   4. A core is pinned while the service is idle. reap calls
 *      auditQueue.poll(), which returns null at once when the queue is empty,
 *      so the reaper loops flat out with nothing to do. It shows as RUNNABLE in
 *      a dump, which is exactly what a thread doing real work looks like.
 *
 * YOUR TASK
 *   1. transfer(int, int, long) — take the two locks in an order that does not
 *      depend on the direction of the transfer, so no two callers can build a
 *      cycle. The balance updates must stay atomic with respect to each other.
 *   2. handle(String, String) — stop a pool task from waiting on another task
 *      submitted to the same pool. Either run the validation inline, or give it
 *      an executor that is not the one the caller is occupying.
 *   3. handle(String, String) — clear CURRENT_TENANT when the task finishes, on
 *      every path including the failing one, so nothing survives into the next
 *      task on that thread.
 *   4. reap() — wait for the next audit entry instead of asking for it in a
 *      loop, and still exit when shutdown() interrupts the reaper.
 *
 * RULES
 *   1. Do not fix the stall by enlarging the pool. More workers only delays the
 *      point at which all of them are waiting on each other.
 *   2. Keep the service's threads named with THREAD_PREFIX; the CPU check
 *      attributes idle CPU time by that prefix.
 *   3. Requests must still be served concurrently — a single global lock around
 *      handle would stop the leaks and fail the throughput checks.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The four checks are:
 *   1. 320 requests from 16 concurrent callers all complete inside 4 seconds;
 *   2. two trials of 16 callers x 1,000 bidirectional transfer pairs finish,
 *      and ledgerTotal() is unchanged — no money created or destroyed;
 *   3. 200 requests carrying no tenant all come back as ANONYMOUS;
 *   4. the service's threads burn almost no CPU across an idle second.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex15IncidentService
 *
 * HINT
 *   Each failure prints a live evidence block built with Dump: the two deadlock
 *   detectors, a census of thread states, and the frame in YOUR code where each
 *   thread stopped. Read it the way you would read a 3am incident — two
 *   families of thread matter, incident-* (the service's own) and stress-* (the
 *   callers), and a triage that looks only at the service's threads misses a
 *   deadlock reached through a public method.
 *   Two of these four are invisible to a thread dump: a leaked tenant shows up
 *   only by comparing what you sent with what came back, and a spinning thread
 *   looks exactly like a working one.
 *
 * SEE ALSO
 *   Docs — read this first: docs/02-concurrency/10-diagnostics-incident.md,
 *     section "EXERCISE 15".
 *   Demo t10diagnostics.D30_TheIncident shows all four failures live; run
 *   t10diagnostics.D31_DiagnosingTheIncident only after you have made your own
 *   diagnosis. Reference solution: solutions/Solutions.java.
 */
public final class Ex15IncidentService implements IncidentService {

    /*
     * The per-request context. This is SecurityContextHolder, it is SLF4J's
     * MDC, and it is in every service you will ever work on: set once at the
     * entry point so that code three layers down does not need a tenant
     * parameter it does not care about.
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

    /*
     * Serves one request on a pool thread. Two guarantees, and this method
     * breaks both. It must answer with the tenant THIS call passed in (or
     * ANONYMOUS when it passed none), and it must not park a pool thread on
     * work that only another pool thread could do — with a fixed pool, that is
     * a stall no timeout ever clears.
     */
    @Override
    public String handle(String tenant, String request) throws Exception {
        Future<String> outer = pool.submit(() -> {
            // WRONG: the value is set on a POOLED thread and never removed, so it
            // outlives the task. The next request to land on this thread — including
            // one that passed tenant == null — reads whatever the last caller left.
            // TODO clear CURRENT_TENANT when this task ends, on every exit path
            // (try/finally), so the thread carries nothing into the next task.
            if (tenant != null) {
                CURRENT_TENANT.set(tenant);
            }

            // Validation is farmed out so it can run "in parallel", and the
            // request waits for the verdict before answering.
            // WRONG: this task is running ON pool and submits to pool, then blocks on
            // the result. Once all `workers` threads are here, every inner task sits in
            // the queue behind them and nothing can ever run it — the pool starves
            // itself. No deadlock detector sees it; there is no lock cycle, only
            // threads waiting for work that cannot be scheduled.
            // TODO stop this task from depending on a second task in the same pool:
            // call validate(request) inline, or submit it to a different executor.
            Future<String> validation = pool.submit(() -> validate(request));
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

    /*
     * Moves money between two ledgers. Both balance updates have to land
     * together, so both locks must be held at once — that part is right. What
     * it must also guarantee is that two callers can never end up each holding
     * the lock the other wants. Here the order comes from the arguments, so
     * 3->7 and 7->3 running at the same time close a cycle and both park
     * forever, holding their locks; anything that later needs those ledgers
     * (including ledgerTotal()) joins the queue.
     */
    @Override
    public void transfer(int fromLedger, int toLedger, long amount) {
        if (fromLedger == toLedger) {
            return;
        }
        // WRONG: acquisition order follows the direction of the transfer, so the order
        // differs between callers. Two opposite transfers over the same pair deadlock.
        // TODO impose one global order on the two locks that every caller agrees on —
        // lock the lower ledger index first, then the higher — so no cycle can form.
        // Keep both held while both balances are updated.
        locks[fromLedger].lock();
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

    /*
     * Drains the audit queue in the background. Must consume no CPU while the
     * queue is empty: this thread lives for the whole life of the service, so
     * a loop that keeps asking rather than waiting holds a core for as long as
     * the process runs — and it reports RUNNABLE, indistinguishable in a dump
     * from a thread doing work.
     */
    private void reap() {
        while (running) {
            // WRONG: poll() returns null the instant the queue is empty, so this loop
            // spins as fast as the CPU allows whenever there is nothing to audit.
            // TODO block until an entry arrives (take()), and handle the
            // InterruptedException that shutdown() sends as the signal to leave the
            // loop — a parked thread cannot notice the `running` flag on its own.
            String entry = auditQueue.poll();
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

    /*
     * How long a stress run gets before the checker calls it a hang. Two of
     * these four defects stop the service permanently rather than throwing, so
     * every run here is bounded: a checker that waits for a wedged service
     * becomes the incident instead of reporting it.
     */
    private static final int HANG_BUDGET_SECONDS = 4;

    /*
     * Trials, because a lock cycle is a race: one clean run proves nothing.
     */
    private static final int TRANSFER_TRIALS = 2;

    /*
     * Wall-clock window over which an idle service's CPU use is measured.
     */
    private static final long IDLE_WINDOW_MILLIS = 1_000;

    /*
     * How many stopped-at frames an evidence block prints per thread family.
     */
    private static final int FRAMES_SHOWN = 5;

    /*
     * The caller threads — Stress's workers, standing in for your web
     * container's request threads. For a deadlock reached through a public
     * method it is these, not the pool, that are stuck, and a triage that
     * looks only at the service's own threads finds nothing.
     */
    private static final String CALLER_THREADS = "stress-";

    /*
     * The service's own threads.
     */
    private static final String SERVICE_THREADS = THREAD_PREFIX;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 15 — the incident", "ExerciseTests$Ex15")
                .reading("docs/02-concurrency/10-diagnostics-incident.md § \"EXERCISE 15\"");

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

    /*
     * The evidence block. This is what you would have collected by hand at
     * 3am, and it is built from exactly the calls D29 demonstrates: the two
     * deadlock detectors, then a census of the threads that matter, then the
     * frame in this lab's own code where each of them stopped. Two families of
     * thread matter, and forgetting either one is a classic triage mistake.
     * incident-* are the service's own threads. stress-* are the callers — the
     * equivalent of your web container's request threads — and for a lock-
     * ordering deadlock reached through a public method it is the CALLERS that
     * are stuck, not the pool.
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

    /*
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
