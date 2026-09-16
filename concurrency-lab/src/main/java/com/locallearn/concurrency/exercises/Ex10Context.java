package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.RequestContext;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/*
 * EXERCISE 10 — a correlation id that belongs to one thread
 *
 * THE SCENARIO
 *   This is the servlet filter that stamps a correlation id onto a request so
 *   every log line from that request carries it. The web container calls
 *   runWithCorrelationId(id, body) around the whole request, and any code
 *   deeper in the stack calls currentCorrelationId() to read it back. The
 *   threads are pooled: the same worker thread serves request after request,
 *   for different users.
 *
 * WHAT IS WRONG RIGHT NOW
 *   Three separate defects, one per test:
 *   1. The id lives in a static field — one slot for the entire JVM. Request A
 *      on thread 1 writes "acme", request B on thread 2 writes "globex", and
 *      now A reads "globex" out of its own scope.
 *   2. The cleanup sits after body.run() with no finally. A body that throws
 *      skips it, so the id stays attached and the next request on that pooled
 *      thread — a different user — reads it.
 *   3. The cleanup clears instead of restoring. An inner scope nested inside an
 *      outer one wipes the slot on exit, so the outer request's id is gone for
 *      the rest of its own body.
 *
 * YOUR TASK
 *   1. correlationId field — must give each thread its own slot instead of one
 *      shared slot. A ThreadLocal is a map hanging off the Thread object
 *      itself, so there is nothing shared and nothing to lock.
 *   2. runWithCorrelationId(String, Runnable) — read and save whatever was
 *      bound before, bind id, run the body, and in a finally put the saved
 *      value back (removing the entry when there was no previous value).
 *   3. currentCorrelationId() — returns this thread's id, or null when this
 *      thread has none bound.
 *
 * RULES
 *   Unbind with remove(), never set(null). set(null) leaves the entry in the
 *   thread's map holding a null, so the slot is never reclaimed — and on a pool
 *   thread, which lives for the life of the process, neither is the entry.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 16 threads × 500 requests, 3 trials: no request ever reads another's id.
 *   2. A request whose body throws leaves nothing behind on its pooled thread,
 *      and neither does one that completes normally.
 *   3. A nested scope restores the enclosing id on exit; the outermost one
 *      unbinds.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex10Context
 *
 * HINT
 *   This is the same rule as MDC.clear() in a finally from
 *   ../01-foundations/07-logging-mdc-correlation-ids.md — non-negotiable there
 *   for exactly the reason test 2 measures here.
 *
 * SEE ALSO
 *   Demo t06shared.D18_CopyOrConfine shows the failure live, and proves the
 *   remove() vs set(null) difference with an initialValue. Reference solution:
 *   solutions/Solutions.java.
 */
public final class Ex10Context implements RequestContext {

    // WRONG: one `static` slot for the whole JVM. Every thread reads and writes the
    // same String reference, so two requests running at once overwrite each other.
    // TODO give each thread its own slot: hold a ThreadLocal<String> here instead of a
    // TODO bare String, so the value is reachable only from the thread that bound it.
    private static String correlationId;

    /*
     * Binds id for the duration of body, and must leave the thread exactly as
     * it found it — whatever id was bound before is what must be bound after,
     * on every exit path including a thrown exception. If it does not, a
     * pooled thread carries one request's id into the next request, which
     * belongs to someone else, and a nested scope destroys its caller's id
     * halfway through a trace.
     */
    @Override
    public void runWithCorrelationId(String id, Runnable body) {
        // WRONG: nothing remembers what was bound before this scope, so the exit path
        // below has no previous value to restore.
        // TODO read the currently bound id into a local BEFORE overwriting it, then
        // TODO bind `id` into this thread's own slot.
        correlationId = id;
        body.run();
        // WRONG: two defects on the unbind. (1) It is not in a `finally`, so a body that
        // throws jumps straight past it and the id stays on the thread — and a pooled
        // thread outlives the request, so the next user inherits it. (2) It clears rather
        // than restoring, so an inner scope wipes the enclosing request's id.
        // TODO move the unbind into a `finally` around body.run(), and make it restore
        // TODO the saved previous value — calling remove() only when there was none.
        correlationId = null;
    }

    /*
     * Returns the id bound to THIS thread, or null when this thread has none.
     */
    @Override
    public String currentCorrelationId() {
        // TODO read from this thread's own slot rather than the shared field.
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
