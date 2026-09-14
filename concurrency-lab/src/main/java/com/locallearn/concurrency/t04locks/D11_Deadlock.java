package com.locallearn.concurrency.t04locks;

import com.locallearn.concurrency.support.Log;
import com.locallearn.concurrency.support.Stress;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

/**
 * DEMO 11 — Deadlock on demand, detected from inside the JVM, then fixed three
 * different ways.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t04locks.D11_Deadlock}
 *
 * <h2>Coffman's four conditions</h2>
 * A deadlock needs <b>all four</b> simultaneously. Break any one and it cannot
 * happen — which is exactly how each fix below works:
 * <ol>
 *   <li><b>Mutual exclusion</b> — the resource cannot be shared.</li>
 *   <li><b>Hold and wait</b> — a thread holds one lock while requesting another.</li>
 *   <li><b>No preemption</b> — a lock cannot be taken away from its holder.</li>
 *   <li><b>Circular wait</b> — A waits on B, B waits on A.</li>
 * </ol>
 *
 * <h2>The classic trigger: transfer(a, b) racing transfer(b, a)</h2>
 * Thread 1 locks Alice then wants Bob. Thread 2 locks Bob then wants Alice.
 * Neither will ever let go. Both threads park forever, holding a connection, a
 * request thread, and a transaction open.
 *
 * <p><b>Watch the thread state in the output — it is WAITING, not BLOCKED.</b>
 * That trips people up when reading dumps:
 * <ul>
 *   <li>A {@code synchronized} deadlock shows {@code BLOCKED} with
 *       {@code - waiting to lock <0x...>}, because the thread is queued on a
 *       JVM monitor.</li>
 *   <li>A {@code ReentrantLock} deadlock shows {@code WAITING} with
 *       {@code - parking to wait for <0x...j.u.c.locks.ReentrantLock$NonfairSync>},
 *       because {@code AbstractQueuedSynchronizer} parks via
 *       {@code LockSupport.park} instead.</li>
 * </ul>
 * Same deadlock, different state word. If you go looking for BLOCKED threads
 * and find none, you have not ruled out a deadlock — check for parked threads
 * on AQS sync objects too. Both kinds are found by
 * {@code findDeadlockedThreads()}; only monitors are found by the narrower
 * {@code findMonitorDeadlockedThreads()}.
 *
 * <h2>Diagnosing it in production</h2>
 * <ol>
 *   <li>{@code jcmd <pid> Thread.print} (or {@code jstack <pid>}). The JVM
 *       finds monitor deadlocks itself and prints
 *       {@code Found one Java-level deadlock:} with both stacks.</li>
 *   <li>Programmatically: {@link ThreadMXBean#findDeadlockedThreads()} — this
 *       demo uses it, and it is worth wiring into an Actuator health indicator.</li>
 * </ol>
 * Caveat: automatic detection covers monitors and {@code AbstractQueuedSynchronizer}
 * locks. A deadlock via {@code Semaphore} permits or two threads each waiting
 * on the other's {@code CountDownLatch} is invisible to it — you get a hang
 * with no diagnosis.
 *
 * <h2>The three fixes</h2>
 * <ul>
 *   <li><b>Lock ordering</b> (breaks circular wait) — acquire in a globally
 *       consistent order, e.g. ascending account id. The standard fix.
 *       Cheap, no timeouts, no retries.</li>
 *   <li><b>tryLock with backoff</b> (breaks hold-and-wait) — if the second lock
 *       is unavailable, release the first, sleep a random jitter, retry. Use
 *       when a total order genuinely does not exist.</li>
 *   <li><b>Don't hold two locks</b> (breaks it at the root) — one coarser lock,
 *       or make the operation atomic in the database instead.</li>
 * </ul>
 */
public final class D11_Deadlock {

    public static void main(String[] args) throws Exception {
        demonstrateDeadlock();
        Log.section("Fix 1 — global lock ordering");
        runTransfers(D11_Deadlock::transferOrdered);
        Log.section("Fix 2 — tryLock with backoff");
        runTransfers(D11_Deadlock::transferWithTryLock);

        Log.takeaway("""
                Lock ordering is the fix you should reach for first: it has no
                retries, no timeouts, and no tuning. Any total order works as
                long as EVERY code path uses the same one — identity hash,
                primary key, whatever. The bug is never "two locks", it is
                "two locks in two different orders".""");
    }

    static final class Account {
        final int id;
        final ReentrantLock lock = new ReentrantLock();
        int balance;

        Account(int id, int balance) {
            this.id = id;
            this.balance = balance;
        }
    }

    private static void demonstrateDeadlock() throws Exception {
        Log.section("Deadlocking two threads on purpose");

        Account alice = new Account(1, 1_000);
        Account bob = new Account(2, 1_000);

        Thread t1 = new Thread(() -> transferDeadlocking(alice, bob, 100), "transfer-A-to-B");
        Thread t2 = new Thread(() -> transferDeadlocking(bob, alice, 200), "transfer-B-to-A");
        t1.setDaemon(true);
        t2.setDaemon(true);
        t1.start();
        t2.start();

        Stress.sleep(1_000);

        ThreadMXBean threads = ManagementFactory.getThreadMXBean();
        long[] deadlocked = threads.findDeadlockedThreads();

        if (deadlocked == null) {
            Log.log("no deadlock detected this run — rerun; the window is small but real");
            return;
        }

        Log.log("ThreadMXBean.findDeadlockedThreads() found %d threads:", deadlocked.length);
        for (ThreadInfo info : threads.getThreadInfo(deadlocked, true, true)) {
            Log.log("  %-18s state=%-8s waiting on <%s> owned by '%s'",
                    info.getThreadName(),
                    info.getThreadState(),
                    info.getLockInfo(),
                    info.getLockOwnerName());
        }
        Log.log("this is what `jcmd <pid> Thread.print` reports as "
                + "\"Found one Java-level deadlock\" — and note the state is WAITING, "
                + "not BLOCKED, because ReentrantLock parks on AQS rather than "
                + "queueing on a monitor. A synchronized deadlock would show BLOCKED.");
    }

    /** The bug: lock order depends on the arguments, so two calls can disagree. */
    private static void transferDeadlocking(Account from, Account to, int amount) {
        from.lock.lock();
        try {
            Log.log("locked account %d, now reaching for account %d", from.id, to.id);
            Stress.sleep(200);          // widen the window so it reproduces every run
            to.lock.lock();             // <-- both threads wait here, forever
            try {
                from.balance -= amount;
                to.balance += amount;
            } finally {
                to.lock.unlock();
            }
        } finally {
            from.lock.unlock();
        }
    }

    /** Fix 1: impose a total order on locks — always lower id first. */
    private static void transferOrdered(Account from, Account to, int amount) {
        Account first = from.id < to.id ? from : to;
        Account second = from.id < to.id ? to : from;

        first.lock.lock();
        try {
            second.lock.lock();
            try {
                from.balance -= amount;
                to.balance += amount;
            } finally {
                second.lock.unlock();
            }
        } finally {
            first.lock.unlock();
        }
        // If ids can collide (e.g. you are ordering by System.identityHashCode),
        // add a third "tie-breaker" lock held around the whole acquisition.
    }

    /** Fix 2: never hold-and-wait — back off and retry if the second lock is busy. */
    private static void transferWithTryLock(Account from, Account to, int amount) {
        while (true) {
            if (from.lock.tryLock()) {
                try {
                    if (to.lock.tryLock()) {
                        try {
                            from.balance -= amount;
                            to.balance += amount;
                            return;
                        } finally {
                            to.lock.unlock();
                        }
                    }
                } finally {
                    from.lock.unlock();     // release BEFORE sleeping, or you
                                            // have reinvented hold-and-wait
                }
            }
            // Random jitter, not a fixed delay: with a fixed delay two threads
            // can retry in lockstep forever — that is livelock, not deadlock.
            // Both threads stay RUNNABLE and make no progress, so a thread dump
            // shows nothing wrong. Jitter breaks the symmetry.
            Stress.sleep(1 + (long) (Math.random() * 5));
        }
    }

    private interface Transfer {
        void apply(Account from, Account to, int amount);
    }

    private static void runTransfers(Transfer transfer) {
        Account alice = new Account(1, 1_000_000);
        Account bob = new Account(2, 1_000_000);
        AtomicInteger completed = new AtomicInteger();

        long t0 = System.nanoTime();
        Stress.run(8, 5_000, i -> {
            // Half the threads go A→B and half B→A on every iteration, which is
            // exactly the pattern that deadlocks the broken version instantly.
            if (i % 2 == 0) {
                transfer.apply(alice, bob, 1);
            } else {
                transfer.apply(bob, alice, 1);
            }
            completed.incrementAndGet();
        });

        long total = alice.balance + bob.balance;
        Log.log("%,d transfers in %,d ms; total money = %,d (%s) — no deadlock",
                completed.get(),
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - t0),
                total,
                total == 2_000_000 ? "conserved" : "*** LOST MONEY ***");
    }
}
