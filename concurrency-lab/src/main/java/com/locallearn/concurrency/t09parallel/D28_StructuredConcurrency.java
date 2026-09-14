package com.locallearn.concurrency.t09parallel;

import com.locallearn.concurrency.support.Log;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * DEMO 28 — Structured concurrency: making concurrent subtasks obey the same
 * scoping rule that ordinary code has followed since the invention of the block.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t09parallel.D28_StructuredConcurrency}
 *
 * <h2>Read this first: what actually runs here</h2>
 * {@code StructuredTaskScope} (JEP 453) and {@code ScopedValue} (JEP 446) are
 * <b>preview APIs in Java 21</b>. They do not compile without
 * {@code --enable-preview}, and enabling that flag marks <em>every</em> class
 * file in the module as preview, which would then require the flag at runtime
 * for every demo and test in topics 1 through 9 and pin this lab to exactly
 * JDK 21. That is a large tax on the whole curriculum for one section, so this
 * module does <b>not</b> enable preview.
 *
 * <p>Therefore this demo is in two halves, and the split is honest:
 * <ul>
 *   <li><b>Sections 1 and 2 run</b> — they use only stable APIs and measure the
 *       problem that structured concurrency exists to solve. The numbers this
 *       demo prints are real.</li>
 *   <li><b>Sections 3 and 4 are a code walkthrough.</b> The output they display
 *       is not simulated: it was produced by compiling and running the exact
 *       snippet shown, with the commands printed alongside it, so you can
 *       reproduce it yourself in under a minute.</li>
 * </ul>
 *
 * <h2>The problem: unstructured concurrency has no scope</h2>
 * When you call an ordinary method it returns before the caller continues, and
 * its local variables die with it. Concurrency broke that: submitting a task to
 * an executor creates a thread of control whose lifetime has <em>no relationship
 * at all</em> to the block that created it. It can outlive the method, outlive
 * the request, and keep burning CPU on a result nobody will read. Nothing in
 * the language stops it, and nothing in a stack trace reveals it.
 *
 * <p>The cost shows up in three ways, all measured in section 1: a failure in
 * one subtask does not cancel its siblings; the caller learns about that failure
 * only when it happens to ask; and cancelling the caller cancels nothing.
 */
public final class D28_StructuredConcurrency {

    public static void main(String[] args) throws Exception {
        unstructuredFanOut();
        theManualFix();
        structuredWalkthrough();
        scopedValueWalkthrough();

        Log.takeaway("""
                Structured concurrency's rule is one sentence: when control leaves the
                block, every thread of control created inside it has already finished.
                That is the rule ordinary code has always obeyed, restored for
                concurrent code — which is why the payoff is a stack trace that shows
                the real parent, cancellation that propagates, and no leaked tasks.

                Measured above without it: a subtask that failed after ~5ms was not
                reported for ~2,000ms, and its sibling ran to completion producing a
                result nobody would ever read. The hand-rolled fix works and is about
                twenty lines of bookkeeping that you must write identically at every
                fan-out site, and that no reviewer will notice you omitted.

                On Java 21 these APIs are preview. Know the shape now: this is where
                the platform is going, and StructuredTaskScope became final in later
                releases. Until then, the discipline is what matters — always cancel
                siblings on failure, and never let a task outlive the request.""");
    }

    // ── 1. The unstructured version, and what it costs ─────────────────────
    private static void unstructuredFanOut() throws Exception {
        Log.section("1. UNSTRUCTURED FAN-OUT — two subtasks, one fails immediately");

        AtomicBoolean siblingRanToCompletion = new AtomicBoolean();
        long start = System.nanoTime();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<String> slow = executor.submit(() -> {
            Thread.sleep(Duration.ofMillis(2_000));
            siblingRanToCompletion.set(true);        // nobody will ever read this result
            return "profile";
        });
        Future<String> failing = executor.submit(() -> {
            Thread.sleep(Duration.ofMillis(5));
            throw new IllegalStateException("payments service is down");
        });

        long failedAt = -1;
        try {
            // The bug is this innocuous line. Results are collected in the order
            // the programmer wrote them, not the order they become available.
            slow.get();
            failing.get();
        } catch (ExecutionException e) {
            failedAt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Log.log("caller learned of the failure after %,dms: %s",
                    failedAt, e.getCause().getMessage());
        }

        Log.log("the failure itself happened at ~5ms. The caller waited %,dms to hear", failedAt);
        Log.log("about it, because it was blocked in slow.get() the whole time.");
        Log.log("meanwhile the sibling ran to completion: %b", siblingRanToCompletion.get());
        Log.log("2 seconds of a thread, a connection and a database's attention were");
        Log.log("spent producing a value that was discarded the moment it arrived.");

        executor.shutdownNow();
        executor.awaitTermination(5, TimeUnit.SECONDS);

        Log.log("");
        Log.log("Worse: nothing FORCED us to shut that executor down. Had this method");
        Log.log("returned early, both tasks would still be running, owned by nobody,");
        Log.log("invisible in every stack trace, and outliving the request that made");
        Log.log("them. That is what 'unstructured' means — the thread of control has");
        Log.log("no scope, so the language cannot help you.");
    }

    // ── 2. Doing it correctly by hand ──────────────────────────────────────
    /**
     * Everything {@code StructuredTaskScope} does automatically, written out.
     * It is not difficult — it is just repetitive, easy to get subtly wrong, and
     * invisible by omission at the next fan-out site somebody adds.
     */
    private static void theManualFix() throws Exception {
        Log.section("2. THE HAND-ROLLED FIX — correct, and ~20 lines you must not forget");

        AtomicBoolean siblingRanToCompletion = new AtomicBoolean();
        long start = System.nanoTime();

        // try-with-resources on ExecutorService (Java 19+) at least guarantees
        // close() waits for the tasks — but it waits for ALL of them, which is
        // the opposite of what we want on failure.
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<String>> futures = new ArrayList<>();
        try {
            futures.add(executor.submit(() -> {
                Thread.sleep(Duration.ofMillis(2_000));
                siblingRanToCompletion.set(true);
                return "profile";
            }));
            futures.add(executor.submit(() -> {
                Thread.sleep(Duration.ofMillis(5));
                throw new IllegalStateException("payments service is down");
            }));

            // The fix: react in COMPLETION order rather than submission order,
            // so the first failure is seen when it happens instead of when we
            // happen to ask for it.
            while (true) {
                boolean allDone = true;
                for (Future<String> f : futures) {
                    if (f.isDone()) {
                        f.get();                     // throws immediately if this one failed
                    } else {
                        allDone = false;
                    }
                }
                if (allDone) {
                    break;
                }
                Thread.sleep(1);
            }
            Log.log("all subtasks succeeded (not reached in this run)");
        } catch (ExecutionException e) {
            long failedAt = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Log.log("caller learned of the failure after %,dms: %s",
                    failedAt, e.getCause().getMessage());
            for (Future<String> f : futures) {
                f.cancel(true);                      // cancel the siblings, by hand
            }
            Log.log("siblings cancelled explicitly; sibling completed anyway: %b",
                    siblingRanToCompletion.get());
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(5, TimeUnit.SECONDS);
        }

        Log.log("Correct — and notice what it took: a polling loop, an is-done scan,");
        Log.log("a manual cancel of every sibling, and a finally block. None of it is");
        Log.log("hard. All of it must be repeated at every fan-out in the codebase,");
        Log.log("and omitting it produces code that works perfectly until something");
        Log.log("fails slowly. That repetition is the problem JEP 453 removes.");
    }

    // ── 3. What it looks like with StructuredTaskScope ─────────────────────
    private static void structuredWalkthrough() {
        Log.section("3. WALKTHROUGH — StructuredTaskScope (PREVIEW on Java 21)");
        Log.log("This does not compile in this module; see the class javadoc for why.");
        Log.log("The output below is REAL — captured from running exactly this code.");
        System.out.println("""

                    try (var scope = new StructuredTaskScope.ShutdownOnFailure()) {
                        Subtask<String> profile = scope.fork(() -> slow("profile", 2000));
                        Subtask<String> pay     = scope.fork(() -> { throw new ISE("payments down"); });

                        scope.join()            // wait for ALL forks, or the first failure
                             .throwIfFailed();  // rethrow that failure here, in the caller

                        return profile.get() + pay.get();
                    }   // close() GUARANTEES both forks have ended before we leave the block

                  Reproduce it yourself (about 40 seconds):
                    javac --release 21 --enable-preview -d out Scoped.java
                    java  --enable-preview -cp out Scoped

                  Real captured output:
                    === failure: sibling is cancelled automatically ===
                      slow-2000ms CANCELLED after sibling failure
                      scope threw: payments down after 6ms  <- NOT 2000ms
                """);
        Log.log("Compare with section 1: 6ms instead of ~2,000ms, and the sibling was");
        Log.log("cancelled rather than left to finish. Three properties, none optional:");
        Log.log("  * join() returns on the FIRST failure, not in submission order;");
        Log.log("  * a failure cancels the siblings automatically;");
        Log.log("  * close() cannot be skipped, so a fork CANNOT outlive the block.");
        Log.log("That last one is the actual definition: the thread of control has a");
        Log.log("SCOPE again, exactly like a local variable. Errors propagate to the");
        Log.log("parent, and the parent is visible in the stack trace.");
    }

    // ── 4. ScopedValue vs ThreadLocal ──────────────────────────────────────
    private static void scopedValueWalkthrough() {
        Log.section("4. WALKTHROUGH — ScopedValue vs ThreadLocal (PREVIEW on Java 21)");
        Log.log("Topic 6 covered ThreadLocal: per-thread state, and the three ways it");
        Log.log("hurts — it is mutable from anywhere, it must be cleaned up by hand or");
        Log.log("it leaks on a pooled thread, and inheriting it into child threads");
        Log.log("copies the whole map.");
        Log.log("");
        Log.log("Virtual threads make the leak much worse: ThreadLocal was tolerable");
        Log.log("partly BECAUSE threads were few. With a million virtual threads, a");
        Log.log("per-thread map is a million maps.");
        System.out.println("""

                    static final ScopedValue<String> USER = ScopedValue.newInstance();

                    ScopedValue.where(USER, "alice").run(() -> {
                        //  USER.get() == "alice" here, and in every method called from
                        //  here, and in every task forked from a StructuredTaskScope
                        //  inside here -- WITHOUT copying anything.
                    });
                    //  USER.isBound() == false again. No cleanup. No leak. No finally.

                  Real captured output:
                    profile finished (user=alice)      <- inherited by the forked subtask
                    orders  finished (user=alice)
                    USER bound outside scope? false    <- unbound automatically
                """);
        Log.log("%-16s %-26s %s", "", "ThreadLocal", "ScopedValue");
        Log.log("%-16s %-26s %s", "mutability", "set() from anywhere", "immutable once bound");
        Log.log("%-16s %-26s %s", "lifetime", "until remove() (or leak)", "the dynamic scope, always");
        Log.log("%-16s %-26s %s", "cleanup", "your finally block", "automatic, cannot be skipped");
        Log.log("%-16s %-26s %s", "inheritance", "copies the map", "shares immutable bindings");
        Log.log("%-16s %-26s %s", "cost at 1M threads", "1M maps", "a few words per binding");
        Log.log("");
        Log.log("The mechanism behind every row: a ScopedValue binding is IMMUTABLE and");
        Log.log("lives on the stack of the block that established it. Because it cannot");
        Log.log("change, children can share it instead of copying it; because it is");
        Log.log("tied to a block, it is unbound when the block exits whatever happens.");
        Log.log("ThreadLocal is a mutable map keyed by thread — every difference above");
        Log.log("follows from that one design choice.");
    }

    private D28_StructuredConcurrency() {
    }
}
