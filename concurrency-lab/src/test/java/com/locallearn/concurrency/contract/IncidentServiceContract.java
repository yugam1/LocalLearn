package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.IncidentService;
import com.locallearn.concurrency.support.Stress;
import com.locallearn.concurrency.t10diagnostics.Dump;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 15 contract — the capstone incident.
 *
 * <p>Four tests, one per planted defect, and each fails for a reason drawn from
 * a different earlier topic. Every failure message names the <b>symptom</b> and
 * points at the topic whose mechanism produces it, and none of them names the
 * fix — working that out from the evidence is the entire exercise, and a
 * message that gave it away would turn a diagnosis into a transcription.
 *
 * <p>Each message also carries a live evidence block produced by {@link Dump},
 * the same toolkit D29 teaches: the deadlock detectors, the state census, and
 * the frame in <em>your</em> code where each thread stopped. That is deliberate.
 * A failing test in this file should read like the first thing you would have
 * typed at a real incident, so that the habit transfers.
 */
public abstract class IncidentServiceContract {

    private static final int WORKERS = 4;

    protected abstract IncidentService newService(int workers);

    // ── Defect 2: pool exhaustion (topics 5 + 8) ───────────────────────────
    @Test
    @Timeout(180)
    @DisplayName("keeps completing requests when many callers arrive at once")
    void servesConcurrentRequestsWithoutStalling() throws Exception {
        int callers = 16;
        int perCaller = 20;
        int expected = callers * perCaller;
        IncidentService service = newService(WORKERS);
        try {
            boolean finished = Stress.run(callers, perCaller,
                    i -> handleOrFail(service, "tenant-" + (i % 4), "req-" + i), 25);

            assertThat(finished)
                    .as("the service stopped completing requests and never resumed: %d of %d "
                        + "finished in 25s, and it has not moved since. Nothing reports a "
                        + "deadlock because there is no cycle of lock OWNERSHIP here — look "
                        + "instead at where the worker threads are parked, what they are "
                        + "waiting FOR, and which thread was supposed to provide it "
                        + "(topics 5 and 8).%s",
                        service.completed(), expected, evidence(SERVICE_THREADS))
                    .isTrue();

            assertThat(service.completed())
                    .as("%d requests were submitted and %d completed — %d were accepted and "
                        + "then silently lost (topic 5)",
                        expected, service.completed(), expected - service.completed())
                    .isEqualTo(expected);
        } finally {
            shutdownQuietly(service);
        }
    }

    // ── Defect 1: lock-ordering deadlock (topic 4) ─────────────────────────
    @Test
    @Timeout(180)
    @DisplayName("concurrent bidirectional ledger transfers neither hang nor lose money")
    void bidirectionalTransfersDoNotHang() throws Exception {
        IncidentService service = newService(WORKERS);
        try {
            boolean finished = Stress.run(16, 2_000, i -> {
                // Both directions over the same pairs — the pattern that makes
                // an argument-dependent acquisition order into a circular one.
                int a = i % IncidentService.LEDGERS;
                int b = (i * 7 + 1) % IncidentService.LEDGERS;
                service.transfer(a, b, 1);
                service.transfer(b, a, 1);
            }, 25);

            assertThat(finished)
                    .as("the transfers stopped making progress and the stuck threads are "
                        + "parked on a lock that another stuck thread holds. Note their state "
                        + "word before you go looking for it in a dump (topics 1 and 4).%s",
                        evidence(CALLER_THREADS, SERVICE_THREADS))
                    .isTrue();

            assertThat(service.ledgerTotal())
                    .as("money was created or destroyed: the total is %d and must always be "
                        + "%d. The two balance updates inside a transfer have to land as one "
                        + "indivisible step (topic 3)",
                        service.ledgerTotal(),
                        IncidentService.LEDGERS * IncidentService.INITIAL_BALANCE)
                    .isEqualTo(IncidentService.LEDGERS * IncidentService.INITIAL_BALANCE);
        } finally {
            shutdownQuietly(service);
        }
    }

    // ── Defect 3: ThreadLocal leak on pooled threads (topic 6) ─────────────
    @Test
    @Timeout(180)
    @DisplayName("a request's tenant never leaks into an unrelated later request")
    void oneRequestsTenantNeverLeaksIntoAnother() throws Exception {
        int tenantRequests = 200;
        int systemRequests = 200;
        IncidentService service = newService(WORKERS);
        try {
            List<String> answers = new ArrayList<>();
            boolean finished = Stress.run(1, 1, ignored -> {
                try {
                    // Ordinary traffic first, so every pooled thread has served
                    // a real tenant at least once.
                    for (int i = 0; i < tenantRequests; i++) {
                        service.handle("tenant-" + (i % 5), "order-" + i);
                    }
                    // Then the unauthenticated system traffic every service has:
                    // health probes, cache warmers, scheduled sweeps. These
                    // belong to nobody, and the service must say so.
                    for (int i = 0; i < systemRequests; i++) {
                        answers.add(service.handle(null, "system-sweep-" + i));
                    }
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }, 60);

            assertThat(finished)
                    .as("the service stopped responding before the tenant check could "
                        + "run — fix the stall first.%s", evidence(SERVICE_THREADS))
                    .isTrue();

            List<String> contaminated = answers.stream()
                    .filter(answer -> !answer.contains("tenant=" + IncidentService.ANONYMOUS))
                    .toList();

            // Assert on the COUNT, not the list: a list assertion would print all
            // two hundred wrong answers and bury the sentence that explains them.
            assertThat(contaminated.size())
                    .as("%d of %d requests that carried NO tenant came back attributed to "
                        + "somebody else's tenant. For example %s — it should have said "
                        + "tenant=%s. Nothing hung and nothing threw; the service simply "
                        + "answered for the wrong customer, and no thread dump, deadlock "
                        + "report or CPU measurement would ever have shown you that. Ask what "
                        + "a pooled thread is still carrying when the next task lands on it, "
                        + "and note WHICH tenant leaks: whichever one used that thread last, "
                        + "which is why this never reproduces in staging (topic 6).",
                        contaminated.size(), answers.size(),
                        contaminated.isEmpty() ? "(none)" : contaminated.get(0),
                        IncidentService.ANONYMOUS)
                    .isZero();
        } finally {
            shutdownQuietly(service);
        }
    }

    // ── Defect 4: busy-wait (topic 5) ──────────────────────────────────────
    @Test
    @Timeout(180)
    @DisplayName("an idle service burns no CPU")
    void idleServiceBurnsNoCpu() throws Exception {
        ThreadMXBean threadMx = ManagementFactory.getThreadMXBean();
        assertThat(threadMx.isThreadCpuTimeSupported())
                .as("this JVM cannot measure per-thread CPU time").isTrue();
        threadMx.setThreadCpuTimeEnabled(true);

        IncidentService service = newService(WORKERS);
        try {
            service.handle("acme", "warm-up");          // bring the threads into being
            TimeUnit.MILLISECONDS.sleep(200);

            List<Long> serviceThreads = serviceThreadIds(threadMx);
            assertThat(serviceThreads)
                    .as("expected live threads named '%s*'; the contract requires that prefix "
                        + "so CPU time can be attributed to them",
                        IncidentService.THREAD_PREFIX)
                    .isNotEmpty();

            long cpuBefore = totalCpuNanos(threadMx, serviceThreads);
            TimeUnit.MILLISECONDS.sleep(1_000);         // a full second with nothing to do
            long cpuAfter = totalCpuNanos(threadMx, serviceThreads);

            long idleCpuMillis = TimeUnit.NANOSECONDS.toMillis(cpuAfter - cpuBefore);
            assertThat(idleCpuMillis)
                    .as("the service had nothing to do for 1000 ms and its %d threads burned "
                        + "%d ms of CPU doing it. Parked threads use almost none, so a figure "
                        + "near one whole core means a thread is looping instead of waiting. "
                        + "It is RUNNABLE in a dump, which is exactly what a thread doing real "
                        + "work looks like, so the state word will not find it for you — the "
                        + "verb it calls will (topic 5).",
                        serviceThreads.size(), idleCpuMillis)
                    .isLessThan(200L * serviceThreads.size());
        } finally {
            shutdownQuietly(service);
        }
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /**
     * The evidence block. This is what you would have collected by hand at 3am,
     * and it is built from exactly the calls D29 demonstrates: the two deadlock
     * detectors unscoped (which is how you would really run them), then a census
     * of the threads that matter here.
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
            for (String name : Dump.namesStartingWith(prefix)) {
                text.append("  \"").append(name).append("\" stopped at ")
                    .append(Dump.ownFrame(name)).append('\n');
            }
        }
        text.append("----------------------------------------------");
        return text.toString();
    }

    /** The service's own threads. */
    private static final String SERVICE_THREADS = IncidentService.THREAD_PREFIX;

    /**
     * The caller threads — {@link Stress}'s workers, standing in for your web
     * container's request threads. For a deadlock reached through a public
     * method it is these, not the pool, that are stuck, and a triage that looks
     * only at the service's own threads finds nothing.
     */
    private static final String CALLER_THREADS = "stress-";

    private static List<Long> serviceThreadIds(ThreadMXBean threadMx) {
        List<Long> ids = new ArrayList<>();
        for (ThreadInfo info : threadMx.getThreadInfo(threadMx.getAllThreadIds())) {
            if (info != null && info.getThreadName().startsWith(IncidentService.THREAD_PREFIX)) {
                ids.add(info.getThreadId());
            }
        }
        return ids;
    }

    private static long totalCpuNanos(ThreadMXBean threadMx, List<Long> threadIds) {
        long total = 0;
        for (long id : threadIds) {
            long cpu = threadMx.getThreadCpuTime(id);
            if (cpu > 0) {                              // -1 once the thread is gone
                total += cpu;
            }
        }
        return total;
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
     * thread alive and skew the CPU measurement of every later test, so this
     * runs even when the assertions have already failed.
     */
    private static void shutdownQuietly(IncidentService service) {
        try {
            service.shutdown();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            // the implementation under test is broken; that is what the assertions are for
        }
    }
}
