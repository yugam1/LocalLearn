package com.locallearn.concurrency.t01threads;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

/**
 * DEMO 1 — Watch a thread move through all six {@link Thread.State} values.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t01threads.D1_LifecycleAndStates}
 *
 * <p>The states are not trivia — they are what you read in a thread dump when
 * production is wedged. Knowing that BLOCKED means "waiting for a monitor
 * another thread holds" while WAITING means "someone must call notify/unpark"
 * is the difference between finding a deadlock in 30 seconds and guessing.
 *
 * <table>
 *   <caption>The six states</caption>
 *   <tr><th>State</th><th>Meaning</th><th>What it looks like in jstack</th></tr>
 *   <tr><td>NEW</td><td>Constructed, {@code start()} not yet called</td><td>never appears</td></tr>
 *   <tr><td>RUNNABLE</td><td>Executing, or waiting for a CPU core, <em>or blocked on I/O</em></td><td>{@code java.lang.Thread.State: RUNNABLE}</td></tr>
 *   <tr><td>BLOCKED</td><td>Waiting to acquire a {@code synchronized} monitor</td><td>{@code - waiting to lock <0x...>}</td></tr>
 *   <tr><td>WAITING</td><td>{@code wait()} / {@code join()} / {@code park()} with no timeout</td><td>{@code - parking to wait for <0x...>}</td></tr>
 *   <tr><td>TIMED_WAITING</td><td>Same, but with a deadline ({@code sleep}, {@code wait(ms)})</td><td>{@code TIMED_WAITING (sleeping)}</td></tr>
 *   <tr><td>TERMINATED</td><td>{@code run()} returned or threw</td><td>never appears</td></tr>
 * </table>
 *
 * <p><b>The trap most people miss:</b> a thread blocked on a socket read is
 * RUNNABLE, not BLOCKED. The JVM cannot see OS-level I/O waits. So a dump full
 * of RUNNABLE threads does <em>not</em> mean your CPUs are busy — check the
 * stack frames, not the state word.
 */
public final class D1_LifecycleAndStates {

    /** The monitor used to force a thread into BLOCKED and WAITING. */
    private static final Object LOCK = new Object();

    public static void main(String[] args) throws Exception {
        Log.section("NEW — constructed but not started");
        Thread worker = new Thread(D1_LifecycleAndStates::workerBody, "worker");
        Log.log("worker state = %s", worker.getState());

        Log.section("TIMED_WAITING — sleeping with a deadline");
        worker.start();
        Stress.sleep(100);                       // let it reach the sleep
        Log.log("worker state = %s  (it is inside Thread.sleep)", worker.getState());

        Log.section("BLOCKED — wants a monitor that main() is holding");
        synchronized (LOCK) {
            // worker wakes from sleep, tries to enter synchronized(LOCK), and cannot.
            Stress.sleep(400);
            Log.log("worker state = %s  (main holds LOCK)", worker.getState());
        }                                        // releasing LOCK lets worker in

        Log.section("WAITING — inside LOCK.wait(), needs someone to notify");
        Stress.sleep(200);
        Log.log("worker state = %s  (it called LOCK.wait())", worker.getState());

        synchronized (LOCK) {
            LOCK.notifyAll();                    // hand it back
        }

        Log.section("TERMINATED — run() returned");
        worker.join();                           // main WAITS here until worker finishes
        Log.log("worker state = %s", worker.getState());

        Log.takeaway("""
                RUNNABLE is the liar in this list: a thread stuck on a socket read
                also reports RUNNABLE, because the JVM cannot see OS I/O waits.
                When diagnosing a hang, read the stack frames, not the state word.""");
    }

    private static void workerBody() {
        Stress.sleep(300);                       // → TIMED_WAITING
        synchronized (LOCK) {                    // → BLOCKED while main holds it
            Log.log("acquired LOCK, about to wait()");
            try {
                LOCK.wait();                     // → WAITING, and releases the monitor
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            Log.log("woken by notifyAll(), exiting");
        }
    }
}
