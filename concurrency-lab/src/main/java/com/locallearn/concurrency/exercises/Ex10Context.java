package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.RequestContext;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * <b>EXERCISE 10 — confine the correlation id to its own thread, and clean it
 * up.</b> See {@code t06shared.D18_CopyOrConfine}.
 *
 * <p>Three defects, each with its own test, and all three are things that
 * have shipped in real filters:
 * <ol>
 *   <li><b>Not confined at all.</b> A {@code static String} is shared by
 *       every thread in the JVM, so two concurrent requests overwrite each
 *       other's id. Thread confinement is the fix, and {@link ThreadLocal}
 *       is how you express it.</li>
 *   <li><b>Cleanup is not in a {@code finally}.</b> When the body throws,
 *       the unbind is skipped and the value stays attached to the thread.
 *       On a pooled thread that means the next request — a different user —
 *       inherits it. This is precisely why the MDC rule in
 *       {@code ../01-foundations/07-logging-mdc-correlation-ids.md} is
 *       {@code MDC.clear()} in a {@code finally}, non-negotiable.</li>
 *   <li><b>Nesting is not restored.</b> Clearing on exit is wrong when a
 *       scope was nested inside another: the outer request's id must come
 *       back, not vanish. Save the previous value before setting, and put it
 *       back afterwards.</li>
 * </ol>
 *
 * <p>One more rule that no test here can see but every reviewer should:
 * when you do clear, use {@code remove()}, never {@code set(null)}.
 * {@code set(null)} leaves the entry in the thread's map holding a null —
 * the slot is never reclaimed, and on a pool thread that lives forever, so
 * does the entry. D18 proves the difference with an {@code initialValue}.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex10Context   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex10'                                    # the grade
 * </pre>
 */
public final class Ex10Context implements RequestContext {

    // TODO broken: one field for the whole JVM. Every thread shares it.
    private static String correlationId;

    @Override
    public void runWithCorrelationId(String id, Runnable body) {
        correlationId = id;
        body.run();
        // TODO broken: not in a finally, so a throwing body skips it — and
        // TODO broken: clears instead of restoring, so nesting loses the outer id.
        correlationId = null;
    }

    @Override
    public String currentCorrelationId() {
        return correlationId;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int THREADS = 16;
    private static final int REQUESTS_PER_THREAD = 500;
    private static final int READS_PER_REQUEST = 5;
    private static final int TRIALS = 3;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 10 — thread-confined request context", "ExerciseTests$Ex10");

        check.that("%d threads each see only their own id — %d trials".formatted(THREADS, TRIALS), () -> {
            // Trials, because a shared field is only wrong when two requests
            // actually overlap, and one polite interleaving would look clean.
            for (int trial = 1; trial <= TRIALS; trial++) {
                RequestContext context = new Ex10Context();
                AtomicLong wrongReads = new AtomicLong();
                AtomicReference<String> firstMismatch = new AtomicReference<>();

                Stress.run(THREADS, REQUESTS_PER_THREAD, i -> {
                    String mine = Thread.currentThread().getName() + "-request-" + i;
                    context.runWithCorrelationId(mine, () -> {
                        // Read it several times with work in between, so a
                        // competing thread has a window to overwrite a shared
                        // field.
                        for (int read = 0; read < READS_PER_REQUEST; read++) {
                            Thread.onSpinWait();
                            String seen = context.currentCorrelationId();
                            if (!mine.equals(seen)) {
                                wrongReads.incrementAndGet();
                                firstMismatch.compareAndSet(null,
                                        "expected " + mine + " but saw " + seen);
                            }
                        }
                    });
                });

                Check.equal(wrongReads.get(), 0,
                        "trial %d of %d: a request read a correlation id that was not its "
                        + "own (first: %s). The id is being stored somewhere all threads "
                        + "share, so two concurrent requests overwrite each other. Confine "
                        + "it to the thread that bound it: a ThreadLocal is a map hanging "
                        + "off the Thread itself, so there is nothing to share and nothing "
                        + "to coordinate. See D18",
                        trial, TRIALS, firstMismatch.get());
            }
        });

        check.that("a request that throws does not leave its id on the pooled thread", () -> {
            RequestContext context = new Ex10Context();

            // ONE thread, so request 2 is guaranteed to land on the thread
            // request 1 used. A real pool does this whenever it is not
            // saturated, which is most of the time — the bug is merely less
            // reproducible there.
            ExecutorService pool = Executors.newFixedThreadPool(1, runnable -> {
                Thread thread = new Thread(runnable, "request-worker");
                thread.setDaemon(true);
                return thread;
            });
            try {
                // Request 1: binds an id, and its body fails, as request bodies do.
                pool.submit(() -> {
                    try {
                        context.runWithCorrelationId("tenant-acme", () -> {
                            throw new IllegalStateException("downstream call failed");
                        });
                    } catch (IllegalStateException expected) {
                        // the caller handles it; the thread must not keep the id
                    }
                }).get(10, TimeUnit.SECONDS);

                // Request 2: a different user, on the same pooled thread,
                // binding nothing at all.
                AtomicReference<String> inherited = new AtomicReference<>();
                pool.submit(() -> inherited.set(context.currentCorrelationId()))
                        .get(10, TimeUnit.SECONDS);

                Check.require(inherited.get() == null,
                        "the next request on the same pooled thread inherited the "
                        + "correlation id '%s', which belongs to a different user. The "
                        + "previous request threw, so the cleanup placed AFTER the body "
                        + "never ran — and a pooled thread outlives the request by design, "
                        + "so it carried the value along. Unbind in a finally, on every "
                        + "exit path. This is the MDC.clear() rule from "
                        + "01-foundations/07 and the leak from D18",
                        inherited.get());

                // And the same again after a request that completes normally.
                pool.submit(() -> context.runWithCorrelationId("tenant-globex", () -> { }))
                        .get(10, TimeUnit.SECONDS);
                AtomicReference<String> afterCleanRequest = new AtomicReference<>();
                pool.submit(() -> afterCleanRequest.set(context.currentCorrelationId()))
                        .get(10, TimeUnit.SECONDS);

                Check.require(afterCleanRequest.get() == null,
                        "even after a request that completed normally, the pooled thread "
                        + "still holds '%s'", afterCleanRequest.get());
            } finally {
                pool.shutdownNow();
            }
        });

        check.that("a nested scope restores the enclosing id rather than clearing it", () -> {
            RequestContext context = new Ex10Context();
            AtomicReference<String> insideOuter = new AtomicReference<>();
            AtomicReference<String> insideInner = new AtomicReference<>();
            AtomicReference<String> afterInner = new AtomicReference<>();

            context.runWithCorrelationId("outer", () -> {
                insideOuter.set(context.currentCorrelationId());
                context.runWithCorrelationId("inner",
                        () -> insideInner.set(context.currentCorrelationId()));
                afterInner.set(context.currentCorrelationId());
            });

            Check.require("outer".equals(insideOuter.get()),
                    "inside the outer scope the id was '%s', not 'outer'", insideOuter.get());
            Check.require("inner".equals(insideInner.get()),
                    "inside the nested scope the id was '%s', not 'inner'", insideInner.get());

            Check.require("outer".equals(afterInner.get()),
                    "after the nested scope exited, the enclosing request's id was '%s' "
                    + "instead of 'outer'. Unbinding must RESTORE what was bound before, "
                    + "not clear the slot — save the previous value, and on the way out put "
                    + "it back (removing only when there was no previous value). Clearing "
                    + "unconditionally destroys the outer request's context, which is how a "
                    + "correlation id disappears halfway through a trace",
                    afterInner.get());

            Check.require(context.currentCorrelationId() == null,
                    "after the outermost scope exited, the thread still holds '%s' — it must "
                    + "be unbound, and with remove() rather than set(null): set(null) leaves "
                    + "the entry in the thread's map for the life of the thread, which on a "
                    + "pool thread is forever (D18)",
                    context.currentCorrelationId());
        });

        System.exit(check.finish());
    }
}
