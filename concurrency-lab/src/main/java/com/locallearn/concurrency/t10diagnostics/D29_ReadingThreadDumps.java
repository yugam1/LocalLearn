package com.locallearn.concurrency.t10diagnostics;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DEMO 29 — The diagnostic toolkit, calibrated against threads whose state you
 * already know.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t10diagnostics.D29_ReadingThreadDumps}
 *
 * <p>Every other demo in this lab shows you a mechanism misbehaving. This one
 * shows you the <b>instruments</b>, and it does it the only honest way: by
 * parking threads in states we chose in advance and then asking the tools what
 * they see. When a tool disagrees with the truth we planted, that disagreement
 * is the lesson — and there are four of them here.
 *
 * <h2>Part 1 — the six states, and the two that lie</h2>
 * Six threads are parked deliberately, one per state, and one extra: a thread
 * blocked reading from a real TCP socket. The census reports it
 * {@code RUNNABLE}, because the JVM cannot see an OS-level I/O wait — it only
 * knows it has not returned from a native call. A dump full of {@code RUNNABLE}
 * threads is <em>not</em> evidence that your CPUs are busy. Read the frames.
 *
 * <p>The second lie is {@code BLOCKED}. It means one thing and one thing only:
 * queued for an intrinsic {@code synchronized} monitor. A {@code ReentrantLock}
 * that will never be released shows {@code WAITING}, because
 * {@code AbstractQueuedSynchronizer} parks through {@code LockSupport} instead
 * of queueing on a monitor. Grep a dump for {@code BLOCKED}, find none, and you
 * have ruled out nothing.
 *
 * <h2>Part 2 — the two detectors, and the gap between them</h2>
 * Three deadlocks are planted one after another, and both detectors are run
 * after each:
 * <ol>
 *   <li>a {@code synchronized} cycle — both detectors find it;</li>
 *   <li>a {@code ReentrantLock} cycle — only {@code findDeadlockedThreads()}
 *       finds it, {@code findMonitorDeadlockedThreads()} still reports the
 *       first pair and nothing more;</li>
 *   <li>a {@code Semaphore} permit cycle — <b>neither</b> finds it. The count
 *       does not move. Two threads are parked forever and the JVM believes
 *       everything is fine.</li>
 * </ol>
 * That third result is the most important line of output in topic 10. Automatic
 * deadlock detection covers lock <em>ownership</em>, and a semaphore permit has
 * no owner — nor does a latch, nor a future, nor a queue. Topic 4 warned you;
 * here it is, measured.
 *
 * <h2>Part 3 — per-thread CPU time finds the spinner</h2>
 * Three threads that all look equally alive in a dump: one spinning, one
 * sleeping, one parked. Only {@code ThreadMXBean.getThreadCpuTime} tells them
 * apart, and it is the only instrument that does.
 *
 * <h2>Part 4 — thread counts find a leak</h2>
 * Live, peak, and total-ever-started. Live going up tells you there is a leak;
 * total-started going up at the same rate tells you it is still happening
 * rather than having happened once at boot.
 */
public final class D29_ReadingThreadDumps {

    public static void main(String[] args) throws Exception {
        if (!Dump.enableCpuTime()) {
            Log.log("this JVM cannot measure per-thread CPU time; part 3 will be empty");
        }

        theSixStates();
        theTwoDetectors();
        findTheSpinner();
        findTheLeak();
        Dump.jcmdHint();

        Log.takeaway("""
                Three instruments, and each has a blind spot you must know:
                  * the dump tells you WHERE every thread stopped, but RUNNABLE
                    includes threads blocked on I/O and BLOCKED means monitors only;
                  * findDeadlockedThreads() covers monitors and AQS locks and
                    NOTHING else — semaphores, latches and futures hang silently;
                  * per-thread CPU time is the only way to tell a busy thread from
                    a spinning one, and neither a dump nor a deadlock report will
                    ever show you a ThreadLocal carrying the wrong tenant.
                Now go and run D30, which is broken in four ways and will not tell
                you which.""");
    }

    // ══════════════════════════════════════════════════ PART 1 — the states
    private static void theSixStates() throws Exception {
        Log.section("PART 1 — one thread per state, and what the tools say about it");

        Object monitor = new Object();
        ReentrantLock aqsLock = new ReentrantLock();
        CountDownLatch neverCountedDown = new CountDownLatch(1);

        // Holders: take the lock and keep it, so the next thread cannot have it.
        start("state-holder-monitor", () -> {
            synchronized (monitor) {
                LockSupport.park();                     // hold it forever
            }
        });
        start("state-holder-aqslock", () -> {
            aqsLock.lock();
            LockSupport.park();                         // hold it forever
        });
        Stress.sleep(100);                              // let the holders acquire

        start("state-runnable-spin", () -> {
            long sink = 0;
            while (true) {
                sink += System.nanoTime();
                if (sink == Long.MIN_VALUE) {
                    System.out.print("");               // never; stops the JIT eliding it
                }
            }
        });
        startSocketReader("state-runnable-socket");
        start("state-blocked-monitor", () -> {
            synchronized (monitor) {                    // BLOCKED: intrinsic monitor
                Log.log("unreachable");
            }
        });
        start("state-waiting-aqslock", aqsLock::lock);  // WAITING: AQS parks, not BLOCKED
        start("state-waiting-latch", () -> await(neverCountedDown));
        start("state-timed-sleeping", () -> Stress.sleep(600_000));

        Stress.sleep(400);                              // let everyone reach its resting place

        Dump.census("the census — read the FRAMES, not the state word", "state-");

        Log.log("");
        Log.log("Two of those lines are lies you must be able to spot:");
        Log.log("  'state-runnable-socket' reports RUNNABLE and is doing nothing at all.");
        Log.log("     The JVM cannot see an OS-level I/O wait; it only knows the native");
        Log.log("     read has not returned. A dump full of RUNNABLE proves nothing about CPU.");
        Log.log("  'state-waiting-aqslock' reports WAITING, not BLOCKED, even though it is");
        Log.log("     queued for a lock. BLOCKED means an intrinsic monitor and nothing else;");
        Log.log("     ReentrantLock parks through LockSupport. Grep for BLOCKED and you will");
        Log.log("     miss every ReentrantLock in the system.");
    }

    /**
     * A genuine TCP read, not a simulation: a loopback server accepts one
     * connection and never writes, so the reader blocks in a native
     * {@code recv}. This is the shape of every JDBC call, every HTTP client
     * call, and every one of them reports {@code RUNNABLE}.
     */
    private static void startSocketReader(String name) throws Exception {
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread accepter = new Thread(() -> {
            try {
                server.accept();                        // hold the connection open, send nothing
                LockSupport.park();
            } catch (Exception e) {
                Log.log("accepter failed: %s", e);
            }
        }, "state-socket-server");
        accepter.setDaemon(true);
        accepter.start();

        Socket client = new Socket(InetAddress.getLoopbackAddress(), server.getLocalPort());
        start(name, () -> {
            try (InputStream in = client.getInputStream()) {
                in.read();                              // blocks forever, reports RUNNABLE
            } catch (Exception e) {
                Log.log("socket reader failed: %s", e);
            }
        });
    }

    // ══════════════════════════════════════════════ PART 2 — the detectors
    private static void theTwoDetectors() {
        Log.section("PART 2 — findDeadlockedThreads() vs findMonitorDeadlockedThreads()");

        Log.log("baseline, before any deadlock:");
        System.out.println(indent(Dump.deadlockReport()));

        // ── 2a. a synchronized cycle ───────────────────────────────────────
        Object monA = new Object();
        Object monB = new Object();
        start("mon-deadlock-A", () -> lockBothMonitors(monA, monB));
        start("mon-deadlock-B", () -> lockBothMonitors(monB, monA));
        Stress.sleep(600);                              // both hold one and want the other
        Log.log("");
        Log.log("2a. after a synchronized (monitor) deadlock — BOTH detectors see it:");
        System.out.println(indent(Dump.deadlockReport()));

        // ── 2b. a ReentrantLock cycle ──────────────────────────────────────
        ReentrantLock lockA = new ReentrantLock();
        ReentrantLock lockB = new ReentrantLock();
        start("aqs-deadlock-A", () -> lockBoth(lockA, lockB));
        start("aqs-deadlock-B", () -> lockBoth(lockB, lockA));
        Stress.sleep(600);
        Log.log("");
        Log.log("2b. after ALSO adding a ReentrantLock deadlock — the counts diverge:");
        System.out.println(indent(Dump.deadlockReport()));
        Log.log("findDeadlockedThreads() now reports four threads; the monitor-only");
        Log.log("variant still reports two. That difference IS the diagnosis: it tells");
        Log.log("you the second cycle is on an AQS lock, and its threads say WAITING.");

        // ── 2c. a Semaphore cycle — invisible ──────────────────────────────
        Semaphore permitA = new Semaphore(1);
        Semaphore permitB = new Semaphore(1);
        start("sem-deadlock-A", () -> acquireBoth(permitA, permitB));
        start("sem-deadlock-B", () -> acquireBoth(permitB, permitA));
        Stress.sleep(600);
        Log.log("");
        Log.log("2c. after ALSO adding a Semaphore permit cycle — the counts DO NOT MOVE:");
        System.out.println(indent(Dump.deadlockReport()));
        Dump.census("but the threads are certainly stuck", "sem-deadlock-");
        Log.log("");
        Log.log("Two threads parked forever and the JVM reports nothing wrong. Automatic");
        Log.log("detection follows lock OWNERSHIP, and a semaphore permit has no owner —");
        Log.log("neither does a latch, a future, or an empty queue. This is the blind spot");
        Log.log("topic 4 warned about, and it is where topic 10's incident lives.");
    }

    private static void lockBothMonitors(Object first, Object second) {
        synchronized (first) {
            Stress.sleep(250);                          // widen the window: reproduces every run
            synchronized (second) {
                Log.log("unreachable");
            }
        }
    }

    private static void lockBoth(ReentrantLock first, ReentrantLock second) {
        first.lock();
        Stress.sleep(250);
        second.lock();                                  // never returns
        Log.log("unreachable");
    }

    private static void acquireBoth(Semaphore first, Semaphore second) {
        try {
            first.acquire();
            Stress.sleep(250);
            second.acquire();                           // never returns, and nobody notices
            Log.log("unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ══════════════════════════════════════════════ PART 3 — find the spinner
    private static void findTheSpinner() throws Exception {
        Log.section("PART 3 — per-thread CPU time separates 'busy' from 'spinning'");

        start("cpu-spinner", () -> {
            long sink = 0;
            while (true) {
                sink += System.nanoTime();              // a poll() loop in disguise
                if (sink == Long.MIN_VALUE) {
                    System.out.print("");
                }
            }
        });
        start("cpu-sleeper", () -> Stress.sleep(600_000));
        start("cpu-parked", LockSupport::park);
        Stress.sleep(100);

        Dump.cpuOverWindow("cpu-", 500);
        Log.log("");
        Log.log("In a dump, cpu-spinner is RUNNABLE and cpu-sleeper is TIMED_WAITING, so");
        Log.log("you could guess. But a thread doing genuine work is ALSO RUNNABLE, and a");
        Log.log("busy-wait on poll() is RUNNABLE inside library code that looks reasonable.");
        Log.log("CPU time is the only instrument that answers 'is this thread earning its");
        Log.log("core?' — which is exactly what Ex7, Ex8 and Ex15 assert on.");
    }

    // ══════════════════════════════════════════════ PART 4 — find the leak
    private static void findTheLeak() {
        Log.section("PART 4 — thread counts find a leak");

        Log.log("before: %s", Dump.threadCounts());
        for (int i = 0; i < 50; i++) {
            // The classic leak: a thread per request, and the request never ends.
            // In production this is an unbounded pool, or `new Thread(...)` in a
            // handler, or a library that starts a connection watchdog per client.
            start("leaked-request-" + i, LockSupport::park);
        }
        Stress.sleep(200);
        Log.log("after 50 'requests': %s", Dump.threadCounts());
        Log.log("");
        Log.log("live climbing tells you there IS a leak. totalStarted climbing at the");
        Log.log("same rate tells you it is still happening rather than a one-off at boot.");
        Log.log("peak is what you compare against after a fix — it never goes down.");
    }

    // ── plumbing ───────────────────────────────────────────────────────────

    /** Every thread here is a daemon: all of them are designed never to finish. */
    private static Thread start(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String indent(String text) {
        return "    " + text.replace("\n", "\n    ");
    }

    private D29_ReadingThreadDumps() {
    }
}
