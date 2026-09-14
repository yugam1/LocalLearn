package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.RequestContext;
import com.locallearn.concurrency.support.Stress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 10 contract — thread confinement and its cleanup obligation.
 *
 * <p>Three tests, three separate defects, deliberately not collapsible into one:
 * <ul>
 *   <li>{@link #confinesTheIdToTheThreadThatBoundIt()} — the value is shared
 *       rather than confined. Only concurrency exposes it.</li>
 *   <li>{@link #doesNotLeakOntoTheNextRequestOnAPooledThread()} — cleanup is not
 *       in a {@code finally}. Only an exception exposes it, and only a pooled
 *       thread shows what it costs.</li>
 *   <li>{@link #restoresTheEnclosingContextWhenScopesNest()} — cleanup clears
 *       instead of restoring. Only nesting exposes it.</li>
 * </ul>
 */
public abstract class RequestContextContract {

    protected abstract RequestContext newContext();

    @Test
    @Timeout(60)
    @DisplayName("16 threads each see only their own correlation id")
    void confinesTheIdToTheThreadThatBoundIt() {
        RequestContext context = newContext();
        AtomicLong wrongReads = new AtomicLong();
        AtomicReference<String> firstMismatch = new AtomicReference<>();

        Stress.run(16, 500, i -> {
            String mine = Thread.currentThread().getName() + "-request-" + i;
            context.runWithCorrelationId(mine, () -> {
                // Read it several times with work in between, so a competing
                // thread has a window to overwrite a shared field.
                for (int read = 0; read < 5; read++) {
                    Thread.onSpinWait();
                    String seen = context.currentCorrelationId();
                    if (!mine.equals(seen)) {
                        wrongReads.incrementAndGet();
                        firstMismatch.compareAndSet(null, "expected " + mine + " but saw " + seen);
                    }
                }
            });
        });

        assertThat(wrongReads.get())
                .as("a request read a correlation id that was not its own %,d times "
                    + "(first: %s). The id is being stored somewhere all threads share, "
                    + "so two concurrent requests overwrite each other. Confine it to the "
                    + "thread that bound it: a ThreadLocal is a map hanging off the Thread "
                    + "itself, so there is nothing to share and nothing to coordinate. "
                    + "See D18",
                    wrongReads.get(), firstMismatch.get())
                .isZero();
    }

    @Test
    @Timeout(60)
    @DisplayName("a request that throws does not leave its id on the pooled thread")
    void doesNotLeakOntoTheNextRequestOnAPooledThread() throws Exception {
        RequestContext context = newContext();

        // ONE thread, so request 2 is guaranteed to land on the thread request 1
        // used. A real pool does this whenever it is not saturated, which is
        // most of the time — the bug is merely less reproducible there.
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

            // Request 2: a different user, on the same pooled thread, binding
            // nothing at all.
            AtomicReference<String> inherited = new AtomicReference<>();
            pool.submit(() -> inherited.set(context.currentCorrelationId()))
                    .get(10, TimeUnit.SECONDS);

            assertThat(inherited.get())
                    .as("the next request on the same pooled thread inherited the "
                        + "correlation id '%s', which belongs to a different user. The "
                        + "previous request threw, so the cleanup placed AFTER the body "
                        + "never ran — and a pooled thread outlives the request by design, "
                        + "so it carried the value along. Unbind in a finally, on every "
                        + "exit path. This is the MDC.clear() rule from "
                        + "01-foundations/07 and the leak from D18",
                        inherited.get())
                    .isNull();

            // And the same again after a request that completes normally.
            pool.submit(() -> context.runWithCorrelationId("tenant-globex", () -> { }))
                    .get(10, TimeUnit.SECONDS);
            AtomicReference<String> afterCleanRequest = new AtomicReference<>();
            pool.submit(() -> afterCleanRequest.set(context.currentCorrelationId()))
                    .get(10, TimeUnit.SECONDS);

            assertThat(afterCleanRequest.get())
                    .as("even after a request that completed normally, the pooled thread "
                        + "still holds '%s'", afterCleanRequest.get())
                    .isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a nested scope restores the enclosing id rather than clearing it")
    void restoresTheEnclosingContextWhenScopesNest() {
        RequestContext context = newContext();
        AtomicReference<String> insideInner = new AtomicReference<>();
        AtomicReference<String> afterInner = new AtomicReference<>();

        context.runWithCorrelationId("outer", () -> {
            assertThat(context.currentCorrelationId()).isEqualTo("outer");
            context.runWithCorrelationId("inner", () -> insideInner.set(context.currentCorrelationId()));
            afterInner.set(context.currentCorrelationId());
        });

        assertThat(insideInner.get()).isEqualTo("inner");

        assertThat(afterInner.get())
                .as("after the nested scope exited, the enclosing request's id was '%s' "
                    + "instead of 'outer'. Unbinding must RESTORE what was bound before, "
                    + "not clear the slot — save the previous value, and on the way out "
                    + "put it back (removing only when there was no previous value). "
                    + "Clearing unconditionally destroys the outer request's context, "
                    + "which is how a correlation id disappears halfway through a trace",
                    afterInner.get())
                .isEqualTo("outer");

        assertThat(context.currentCorrelationId())
                .as("after the outermost scope exited, the thread still holds '%s' — it "
                    + "must be unbound, and with remove() rather than set(null): "
                    + "set(null) leaves the entry in the thread's map for the life of the "
                    + "thread, which on a pool thread is forever (D18)",
                    context.currentCorrelationId())
                .isNull();
    }
}
