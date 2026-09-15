package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.Bank;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.lang.management.ManagementFactory;

/**
 * <b>EXERCISE 5 — transfer money without deadlocking.</b> See {@code t04locks.D11_Deadlock}.
 *
 * <p>The test runs many threads transferring in both directions between the
 * same accounts. As written it deadlocks within milliseconds, and the test
 * fails on a timeout.
 *
 * <p>Two things must hold when it finishes: no deadlock, and
 * {@link #totalMoney()} unchanged — money is neither created nor destroyed.
 * Note that simply removing the locks fixes the deadlock and breaks the
 * second property, so you cannot cheat your way past this one.
 *
 * <p>Hint: the bug is not "two locks". It is "two locks acquired in two
 * different orders". Fix the order, or stop holding one while waiting for
 * the other.
 *
 * <p>This is the first exercise that fails by <em>stopping</em> rather than by
 * printing a wrong number, so the checker below asks the JVM what it sees when
 * a trial runs out of time — {@code ThreadMXBean.findDeadlockedThreads()}, the
 * same call D11 demonstrates. Learn to reach for it: it is what you have in
 * production when a service goes quiet instead of erroring.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex05Bank   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex5'                                  # the grade
 * </pre>
 */
public final class Ex05Bank implements Bank {

    private final long[] balances;
    private final Object[] locks;

    public Ex05Bank(int accounts, long initialBalance) {
        balances = new long[accounts];
        locks = new Object[accounts];
        for (int i = 0; i < accounts; i++) {
            balances[i] = initialBalance;
            locks[i] = new Object();
        }
    }

    @Override
    public void transfer(int fromAccount, int toAccount, long amount) {
        // TODO broken: lock order depends on the arguments, so transfer(1,2)
        // racing transfer(2,1) produces a circular wait.
        synchronized (locks[fromAccount]) {
            synchronized (locks[toAccount]) {
                balances[fromAccount] -= amount;
                balances[toAccount] += amount;
            }
        }
    }

    @Override
    public long balance(int account) {
        synchronized (locks[account]) {
            return balances[account];
        }
    }

    @Override
    public long totalMoney() {
        long total = 0;
        for (int i = 0; i < balances.length; i++) {
            total += balance(i);
        }
        return total;
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int ACCOUNTS = 6;
    private static final long INITIAL = 1_000_000L;
    private static final int THREADS = 16;
    private static final int PER_THREAD = 2_000;
    private static final int TRIALS = 10;
    private static final int TRIAL_TIMEOUT_SECONDS = 4;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 5 — deadlock-free transfers", "ExerciseTests$Ex5");

        check.that("transfers neither deadlock nor lose money — %d trials"
                .formatted(TRIALS), () -> {
            for (int trial = 1; trial <= TRIALS; trial++) {
                Bank bank = new Ex05Bank(ACCOUNTS, INITIAL);

                // The timeout variant, not the throwing one: a deadlocked run
                // never comes back, and a checker that hangs tells you nothing.
                // We want it to give up and then say what it saw.
                boolean completed = Stress.run(THREADS, PER_THREAD, i -> {
                    // Pairs in BOTH directions. This is the exact pattern that
                    // produces a circular wait when the lock order depends on
                    // the arguments.
                    int a = i % ACCOUNTS;
                    int b = (i * 7 + 1) % ACCOUNTS;
                    if (a != b) {
                        bank.transfer(a, b, 1);
                        bank.transfer(b, a, 1);
                    }
                }, TRIAL_TIMEOUT_SECONDS);

                Check.require(completed,
                        "trial %d of %d: transfers did not finish within %ds.%n%s",
                        trial, TRIALS, TRIAL_TIMEOUT_SECONDS, deadlockReport());

                // The other half of the contract. Dropping the locks would get
                // you past the line above and fail this one.
                Check.equal(bank.totalMoney(), ACCOUNTS * INITIAL,
                        "trial %d of %d: money was created or destroyed. The two "
                        + "balance updates inside a transfer must happen as one "
                        + "atomic step.", trial, TRIALS);
            }
        });

        check.that("a single transfer moves the right amount", () -> {
            Bank bank = new Ex05Bank(3, 100);
            bank.transfer(0, 1, 30);
            Check.equal(bank.balance(0), 70, "the debited account is wrong");
            Check.equal(bank.balance(1), 130, "the credited account is wrong");
            Check.equal(bank.totalMoney(), 300, "the total is wrong");
        });

        System.exit(check.finish());
    }

    /**
     * Asks the JVM whether it can see the deadlock, and puts the answer in the
     * failure message. Same {@code ThreadMXBean} call as D11 — worth knowing by
     * heart, because it is the fastest way to turn "the service is hung" into
     * "these two threads are waiting on each other".
     */
    private static String deadlockReport() {
        long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
        if (deadlocked == null) {
            return "The JVM reports no deadlock, so this is a livelock or simply very "
                   + "slow progress. Threads spinning in a retry loop without jitter "
                   + "look exactly like this.";
        }
        StringBuilder report = new StringBuilder(
                "The JVM found a deadlock involving " + deadlocked.length + " threads:\n");
        for (var info : ManagementFactory.getThreadMXBean().getThreadInfo(deadlocked, true, true)) {
            report.append("  ").append(info.getThreadName())
                  .append(" is ").append(info.getThreadState())
                  .append(" waiting on a lock held by '").append(info.getLockOwnerName())
                  .append("'\n");
        }
        report.append("That is a circular wait: the lock order depends on the arguments, "
                      + "so transfer(a,b) and transfer(b,a) grab them in opposite orders.");
        return report.toString();
    }
}
