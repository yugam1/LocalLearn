package com.locallearn.concurrency.t07coordination;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * DEMO 20 — A {@link Semaphore} counts <b>permits to use a resource</b>, not
 * events that have happened. That one sentence separates it from
 * {@code CountDownLatch}, and getting it backwards is the usual mistake.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t07coordination.D20_SemaphorePermits}
 *
 * <h2>Where it sits in the 2×2</h2>
 * Reusable, and about resources rather than parties. A latch counts down to zero
 * once and stops; a semaphore's count goes down on {@code acquire} and back up
 * on {@code release}, forever. It is the only member of this family whose count
 * moves in both directions, which is exactly why it is the one you use to cap
 * concurrency.
 *
 * <h2>It is not a lock, and the difference is not pedantry</h2>
 * A lock has an <b>owner</b>. The thread that acquired it is the thread that
 * must release it, {@code synchronized} enforces that structurally, and a
 * reentrant lock lets the owner re-enter.
 *
 * <p>A semaphore has none of that. Permits are anonymous tokens:
 * <ul>
 *   <li>Thread A may {@code acquire} and thread B may {@code release}. This is a
 *       feature — it is how a producer hands a slot to a consumer.</li>
 *   <li>It is <b>not reentrant</b>. A thread holding the last permit that calls
 *       {@code acquire()} again blocks against itself, permanently, with no
 *       deadlock report (D21).</li>
 *   <li>{@code release()} without a matching {@code acquire()} <b>creates a
 *       permit out of nothing</b>. There is no bookkeeping to catch it, and this
 *       demo shows the count climbing above the number the semaphore was
 *       constructed with. A double-release in an error path is how a
 *       "concurrency limit" quietly stops limiting anything.</li>
 * </ul>
 *
 * <h2>The rule that is worth more than the API</h2>
 * <pre>{@code
 * semaphore.acquire();
 * try {
 *     return doWork();
 * } finally {
 *     semaphore.release();   // EVERY exit path, including the exceptional ones
 * }
 * }</pre>
 * A {@code release()} placed after the work instead of in a {@code finally} is
 * skipped whenever the work throws — and a permit that is never released is gone
 * for the lifetime of the process. Every exception permanently shrinks the pool,
 * so the failure is cumulative: the system does not break at the first error, it
 * degrades one permit at a time until the last one goes and everything stops.
 * This demo watches a four-permit pool die that way.
 */
public final class D20_SemaphorePermits {

    private static final int LIMIT = 4;
    private static final int CALLERS = 32;

    public static void main(String[] args) throws Exception {
        permitsCapConcurrency();
        leakingPermitsOnTheExceptionPath();
        permitsAreNotOwned();
        tryAcquireIsLoadShedding();
        fairnessCosts();

        Log.takeaway("""
                A Semaphore caps how many threads may be doing something at once.
                Not how many have finished (that is a latch) and not who owns what
                (that is a lock).

                Three facts that decide whether your code works:

                1. release() goes in a FINALLY. Measured above: four exceptions on
                   the non-finally path permanently destroyed all four permits of a
                   four-permit pool, and every later caller waits forever. The
                   damage is cumulative and irreversible, and the system dies one
                   permit at a time rather than all at once — so the graph you get
                   paged on is a slow slide, not a cliff.

                2. Permits are anonymous. Any thread may release, release() with no
                   acquire() mints a permit from nothing, and acquire() is NOT
                   reentrant — a thread can block against itself forever.

                3. Prefer tryAcquire(timeout) at a service boundary. acquire()
                   blocks (topic 5's "block"); tryAcquire() returning false lets
                   you shed load deliberately (topic 5's "drop") instead of
                   growing an invisible queue of parked threads.""");
    }

    // ── 1. The thing it is actually for ────────────────────────────────────
    private static void permitsCapConcurrency() {
        Log.section("PERMITS CAP CONCURRENCY — " + CALLERS + " callers, " + LIMIT + " permits");

        Semaphore limiter = new Semaphore(LIMIT);
        AtomicInteger inFlight = new AtomicInteger();
        AtomicInteger peakInFlight = new AtomicInteger();
        AtomicInteger completed = new AtomicInteger();

        Stress.run(CALLERS, 20, i -> {
            try {
                limiter.acquire();
                try {
                    peakInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
                    Stress.sleep(1);
                    inFlight.decrementAndGet();
                    completed.incrementAndGet();
                } finally {
                    limiter.release();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });

        Log.log("%,d calls completed, peak concurrency observed: %d (limit %d)",
                completed.get(), peakInFlight.get(), LIMIT);
        Log.log("permits left: %d — every acquire was matched", limiter.availablePermits());
        Log.log("Note what did NOT happen: nothing was rejected and nothing was");
        Log.log("lost. The other 28 callers were parked in acquire(), which is");
        Log.log("topic 5's \"block\" answer. That is a choice, and its cost is 28");
        Log.log("threads sitting in memory doing nothing — see tryAcquire below.");
    }

    // ── 2. The bug that kills the pool ─────────────────────────────────────
    private static void leakingPermitsOnTheExceptionPath() throws InterruptedException {
        Log.section("THE PERMIT LEAK — release() after the work instead of in a finally");

        Semaphore limiter = new Semaphore(LIMIT);
        Log.log("starting permits: %d", limiter.availablePermits());

        for (int attempt = 1; attempt <= LIMIT; attempt++) {
            try {
                limiter.acquire();
                if (true) {                       // the work throws, as work does
                    throw new IllegalStateException("downstream call failed");
                }
                limiter.release();                // BUG: never reached
            } catch (IllegalStateException e) {
                Log.log("call %d threw (%s) — permits now: %d",
                        attempt, e.getMessage(), limiter.availablePermits());
            }
        }

        Log.log("the pool is now permanently empty: %d permits", limiter.availablePermits());
        boolean gotOne = limiter.tryAcquire(500, TimeUnit.MILLISECONDS);
        Log.log("a healthy call now waits for a permit... tryAcquire(500ms) = %b", gotOne);
        Log.log("With acquire() instead of tryAcquire(timeout) that call would park");
        Log.log("forever, and so would every call after it. The service stops");
        Log.log("serving while the process stays up, the health check stays green,");
        Log.log("and a thread dump reports NO deadlock (D21).");
        Log.log("Four exceptions destroyed a four-permit pool. The failure is");
        Log.log("cumulative and irreversible — permits do not come back.");

        Log.log("--- the same four failures with release() in a finally ---");
        Semaphore correct = new Semaphore(LIMIT);
        for (int attempt = 1; attempt <= LIMIT; attempt++) {
            try {
                correct.acquire();
                try {
                    throw new IllegalStateException("downstream call failed");
                } finally {
                    correct.release();            // every exit path
                }
            } catch (IllegalStateException e) {
                // the caller still sees the failure; the pool does not pay for it
            }
        }
        Log.log("permits after four identical failures: %d — untouched",
                correct.availablePermits());
    }

    // ── 3. Permits are tokens, not ownership ───────────────────────────────
    private static void permitsAreNotOwned() throws InterruptedException {
        Log.section("PERMITS ARE ANONYMOUS TOKENS — not ownership");

        Semaphore semaphore = new Semaphore(2);
        Log.log("constructed with 2 permits: availablePermits() = %d", semaphore.availablePermits());

        semaphore.release();
        semaphore.release();
        Log.log("after two bare release() calls with no acquire(): %d permits",
                semaphore.availablePermits());
        Log.log("  ^ the semaphore minted two permits out of nothing. It has no");
        Log.log("    record of who acquired what, so it cannot possibly object.");
        Log.log("    One double-release in an error path and your \"limit of 4\" is");
        Log.log("    a limit of 5, then 6, and nothing ever tells you.");

        Semaphore handoff = new Semaphore(0);
        CountDownLatch consumerDone = new CountDownLatch(1);
        Thread consumer = new Thread(() -> {
            try {
                handoff.acquire();               // acquired HERE...
                Log.log("consumer acquired a permit that the producer released");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                consumerDone.countDown();
            }
        }, "permit-consumer");
        consumer.setDaemon(true);
        consumer.start();
        TimeUnit.MILLISECONDS.sleep(50);
        handoff.release();                       // ...released THERE. Perfectly legal.
        consumerDone.await();
        Log.log("acquire in one thread, release in another: legal, and the point —");
        Log.log("it is how a semaphore signals \"a slot just became available\".");
        Log.log("A lock could not do this; ownership is what a lock is.");
        Log.log("The flip side: acquire() is NOT reentrant. A thread holding the");
        Log.log("last permit that acquires again blocks against itself, forever,");
        Log.log("and no deadlock detector will ever report it (D21).");
    }

    // ── 4. Block or shed — topic 5's trichotomy again ──────────────────────
    private static void tryAcquireIsLoadShedding() {
        Log.section("acquire() vs tryAcquire() — block or shed, at the boundary");

        Semaphore limiter = new Semaphore(LIMIT);
        AtomicInteger admitted = new AtomicInteger();
        AtomicInteger shed = new AtomicInteger();

        Stress.run(CALLERS, 20, i -> {
            if (limiter.tryAcquire()) {          // no waiting at all
                try {
                    admitted.incrementAndGet();
                    Stress.sleep(1);
                } finally {
                    limiter.release();
                }
            } else {
                shed.incrementAndGet();          // "503, try again" — a DECISION
            }
        });

        int total = admitted.get() + shed.get();
        Log.log("admitted %,d, shed %,d of %,d calls (%.0f%% shed)",
                admitted.get(), shed.get(), total, 100.0 * shed.get() / total);
        Log.log("This is the same three-way choice as topic 5, at a different");
        Log.log("layer. acquire() = BLOCK: nobody is turned away, and the queue of");
        Log.log("waiting callers is made of parked threads you cannot see or");
        Log.log("measure. tryAcquire() = DROP: a countable, deliberate rejection");
        Log.log("you can put on a dashboard and return as a 503. tryAcquire with a");
        Log.log("timeout is the middle ground and is usually the right default at a");
        Log.log("service boundary: wait a bounded time, then shed.");
    }

    // ── 5. Fairness costs, exactly as it did for ReentrantLock ─────────────
    private static void fairnessCosts() throws InterruptedException {
        Log.section("FAIRNESS — same trade-off as D10's ReentrantLock");

        // Warm up before measuring; the first pass is mostly JIT compilation.
        hammer(new Semaphore(1, false), 8, 20_000);
        hammer(new Semaphore(1, true), 8, 20_000);

        long nonFair = hammer(new Semaphore(1, false), 8, 50_000);
        long fair = hammer(new Semaphore(1, true), 8, 50_000);

        Log.log("8 threads x 50,000 acquire/release on a 1-permit semaphore:");
        Log.log("  non-fair: %,6d ms", nonFair);
        Log.log("  fair:     %,6d ms   (%.0fx slower)", fair, (double) fair / Math.max(1, nonFair));
        Log.log("Same mechanism as D10: fairness forbids barging, so a thread that");
        Log.log("is already running cannot take a free permit if someone is queued");
        Log.log("ahead of it. Every hand-off therefore becomes a park/unpark pair —");
        Log.log("a syscall — rather than a few nanoseconds of CAS. Use fair only");
        Log.log("when you have MEASURED starvation, not when it sounds nicer.");
    }

    private static long hammer(Semaphore semaphore, int threads, int perThread)
            throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        for (int t = 0; t < threads; t++) {
            Thread worker = new Thread(() -> {
                try {
                    gate.await();
                    for (int i = 0; i < perThread; i++) {
                        semaphore.acquire();
                        try {
                            Thread.onSpinWait();
                        } finally {
                            semaphore.release();
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            }, "permit-hammer-" + t);
            worker.setDaemon(true);
            worker.start();
        }
        long start = System.nanoTime();
        gate.countDown();
        done.await();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private D20_SemaphorePermits() {
    }
}
