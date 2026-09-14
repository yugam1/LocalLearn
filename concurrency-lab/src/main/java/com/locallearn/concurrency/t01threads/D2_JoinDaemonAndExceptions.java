package com.locallearn.concurrency.t01threads;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

/**
 * DEMO 2 — Three things about threads that bite people in production:
 * {@code join()}, daemon threads, and where an exception on a thread goes.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t01threads.D2_JoinDaemonAndExceptions}
 *
 * <h2>1. join() is the only built-in "wait for this thread"</h2>
 * {@code t.join()} blocks the caller until {@code t} terminates. It is the
 * primitive underneath every higher-level "wait for completion" —
 * {@code Future.get()}, {@code CompletableFuture.join()},
 * {@code ExecutorService.awaitTermination()}. Note it is <em>uninterruptible-safe</em>:
 * it throws {@link InterruptedException}, so it is a cancellation point.
 *
 * <h2>2. Daemon threads do not keep the JVM alive</h2>
 * The JVM exits when the last <em>non-daemon</em> thread finishes. A daemon
 * thread is killed mid-instruction at that moment — no finally block, no
 * shutdown hook of its own, no flush. This is why you must never do
 * "write the buffer to disk" work on a daemon thread, and why Spring's
 * {@code ThreadPoolTaskExecutor} defaults to non-daemon threads plus
 * {@code setWaitForTasksToCompleteOnShutdown(true)}.
 *
 * <h2>3. An exception on a thread does not reach the thread that started it</h2>
 * This surprises everyone once. {@code main} cannot catch what {@code worker}
 * throws — there is no call-stack relationship between them. The exception goes
 * to the thread's {@link Thread.UncaughtExceptionHandler}; with no handler set,
 * it prints to stderr and the thread dies silently as far as your logic is
 * concerned. In a thread pool it is worse: the pool swallows it into the
 * {@code Future}, so if nobody calls {@code get()}, the error vanishes entirely.
 * That is exactly why {@code AsyncConfig} in order-service registers an
 * {@code AsyncUncaughtExceptionHandler} (see docs/phase2_task8.md).
 */
public final class D2_JoinDaemonAndExceptions {

    public static void main(String[] args) throws Exception {
        joinDemo();
        uncaughtExceptionDemo();
        daemonDemo();
    }

    private static void joinDemo() throws InterruptedException {
        Log.section("join() — main waits for the worker");
        Thread worker = new Thread(() -> {
            Log.log("working...");
            Stress.sleep(300);
            Log.log("done");
        }, "joinable");

        worker.start();
        Log.log("started worker; without join() main would race ahead right now");
        worker.join();
        Log.log("join() returned, so worker is guaranteed TERMINATED: %s", worker.getState());
    }

    private static void uncaughtExceptionDemo() throws InterruptedException {
        Log.section("An exception on another thread never reaches your catch block");

        Thread unhandled = new Thread(() -> {
            throw new IllegalStateException("inventory service unreachable");
        }, "no-handler");

        try {
            unhandled.start();
            unhandled.join();
            Log.log("main's try/catch saw nothing — the throw happened on another stack");
        } catch (IllegalStateException impossible) {
            Log.log("this line is unreachable: %s", impossible);
        }

        Thread handled = new Thread(() -> {
            throw new IllegalStateException("inventory service unreachable");
        }, "with-handler");
        handled.setUncaughtExceptionHandler((t, e) ->
                Log.log("handler caught from %s: %s", t.getName(), e.getMessage()));
        handled.start();
        handled.join();

        Log.takeaway("""
                Always give pooled threads an uncaught-exception handler (or a
                ThreadFactory that sets one). Otherwise a failing task is a
                stack trace on stderr at best, and complete silence at worst.""");
    }

    private static void daemonDemo() throws InterruptedException {
        Log.section("Daemon threads are killed when the last non-daemon thread exits");

        Thread daemon = new Thread(() -> {
            try {
                for (int i = 1; i <= 100; i++) {
                    Log.log("daemon tick %d (it intends to run 100 times)", i);
                    Stress.sleep(100);
                }
            } finally {
                // You will NOT see this line. The JVM does not unwind daemon
                // threads on exit — finally blocks simply never run.
                Log.log("daemon cleanup — never printed");
            }
        }, "daemon-logger");
        daemon.setDaemon(true);       // must be set BEFORE start(), else IllegalThreadStateException
        daemon.start();

        Stress.sleep(350);
        Log.takeaway("""
                main is about to return. The daemon is mid-loop and will be killed
                where it stands — no finally, no flush. Count the ticks above: it
                got nowhere near 100. Never put cleanup or I/O on a daemon thread.""");
    }
}
