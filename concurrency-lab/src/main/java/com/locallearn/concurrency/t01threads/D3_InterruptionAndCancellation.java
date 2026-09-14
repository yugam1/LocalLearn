package com.locallearn.concurrency.t01threads;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.util.concurrent.atomic.AtomicLong;

/**
 * DEMO 3 — Interruption is a <em>request</em>, not a kill. Most cancellation
 * bugs come from not believing that sentence.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t01threads.D3_InterruptionAndCancellation}
 *
 * <h2>The mechanism</h2>
 * Every thread carries one boolean: the <b>interrupt flag</b>.
 * <ul>
 *   <li>{@code t.interrupt()} sets it. That is all it does to a running thread.</li>
 *   <li>Blocking methods that declare {@link InterruptedException} —
 *       {@code sleep}, {@code wait}, {@code join}, {@code BlockingQueue.take},
 *       {@code Lock.lockInterruptibly}, {@code Future.get} — notice the flag,
 *       <b>clear it</b>, and throw.</li>
 *   <li>Pure-CPU code notices nothing. It must poll
 *       {@link Thread#isInterrupted()} itself.</li>
 * </ul>
 *
 * <h2>The two rules</h2>
 * <ol>
 *   <li><b>Never swallow {@link InterruptedException}.</b> Catching it and
 *       carrying on destroys the only cancellation signal the thread will ever
 *       get — and because the throw already cleared the flag, nobody upstream
 *       can recover it.</li>
 *   <li>If you cannot propagate it, <b>restore the flag</b>:
 *       {@code Thread.currentThread().interrupt();} then return. That is why
 *       every catch block in this lab does exactly that.</li>
 * </ol>
 *
 * <p>{@code Thread.stop()} was the alternative and it was removed for good:
 * it threw an asynchronous exception at an arbitrary bytecode, which could
 * leave a {@code synchronized} block with a half-updated object and the lock
 * released. There is no safe forcible kill in Java. Cooperative cancellation
 * is the only option, which makes these rules load-bearing.
 */
public final class D3_InterruptionAndCancellation {

    public static void main(String[] args) throws Exception {
        swallowingIsABug();
        restoringTheFlagWorks();
        cpuBoundMustPoll();
    }

    private static void swallowingIsABug() throws InterruptedException {
        Log.section("BUG: catching InterruptedException and continuing");

        Thread stubborn = new Thread(() -> {
            while (true) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    // The throw ALREADY cleared the flag. By not rethrowing and
                    // not restoring it, this thread has erased the cancellation
                    // request. isInterrupted() is false again on the next loop.
                    Log.log("caught interrupt, flag is now %s — and ignoring it",
                            Thread.currentThread().isInterrupted());
                }
            }
        }, "stubborn");
        stubborn.setDaemon(true);                // else this demo never exits
        stubborn.start();

        Stress.sleep(150);
        stubborn.interrupt();
        Stress.sleep(300);
        Log.log("after interrupt(), stubborn is still %s — cancellation failed",
                stubborn.getState());
    }

    private static void restoringTheFlagWorks() throws InterruptedException {
        Log.section("CORRECT: restore the flag and exit the loop");

        Thread cooperative = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    Thread.sleep(100);
                    Log.log("still working");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();   // restore for whoever asks next
                Log.log("interrupted mid-sleep; flag restored, unwinding");
            } finally {
                Log.log("finally block runs — resources released");
            }
        }, "cooperative");
        cooperative.start();

        Stress.sleep(250);
        cooperative.interrupt();
        cooperative.join();
        Log.log("cooperative is %s, and its interrupt flag survived as %s",
                cooperative.getState(), cooperative.isInterrupted());
    }

    private static void cpuBoundMustPoll() throws InterruptedException {
        Log.section("CPU-bound work is deaf to interrupt() unless it polls");

        AtomicLong iterations = new AtomicLong();

        Thread deaf = new Thread(() -> {
            // No blocking call anywhere, so nothing ever throws
            // InterruptedException. This loop would run forever.
            while (true) {
                iterations.incrementAndGet();
            }
        }, "deaf-cpu");
        deaf.setDaemon(true);
        deaf.start();
        Stress.sleep(200);
        deaf.interrupt();
        Stress.sleep(200);
        Log.log("deaf thread is %s after interrupt() — %,d iterations and counting",
                deaf.getState(), iterations.get());

        Thread polling = new Thread(() -> {
            long n = 0;
            while (!Thread.currentThread().isInterrupted()) {   // the fix: poll
                n++;
            }
            Log.log("polling thread noticed the flag after %,d iterations", n);
        }, "polling-cpu");
        polling.start();
        Stress.sleep(200);
        polling.interrupt();
        polling.join();

        Log.takeaway("""
                Rule of thumb: a long-running loop needs `while (!isInterrupted())`,
                and every catch of InterruptedException needs either `throw` or
                `Thread.currentThread().interrupt()`. There is no third option.""");
    }
}
