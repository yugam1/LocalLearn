package com.locallearn.concurrency.exercises;

import com.locallearn.concurrency.api.Contracts.Bank;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

import java.lang.management.ManagementFactory;

/*
 * EXERCISE 5 — move money between accounts without deadlocking
 *
 * THE SCENARIO
 *   A ledger with one balance and one lock per account. Request threads call
 *   transfer(int, int, long) constantly, in every direction: the same pair of
 *   accounts is being debited one way and credited the other way at the same
 *   moment. Each transfer must move the money as one step — no observer may
 *   ever see it gone from one account and not yet in the other.
 *
 * WHAT IS WRONG RIGHT NOW
 *   The lock order is taken from the argument order. transfer(1, 2, …) takes
 *   locks[1] then locks[2]; transfer(2, 1, …) takes them in the opposite order.
 *   Run both at once and each thread ends up holding the lock the other one is
 *   waiting for. Neither can make progress and neither ever times out — this is
 *   a circular wait, and it happens within milliseconds under the checker's
 *   load.
 *
 * YOUR TASK
 *   1. transfer(int, int, long) — acquire the two account locks in an order
 *      that does not depend on which argument is "from" and which is "to" (for
 *      example, always the lower account index first), while both balance
 *      updates still happen with both locks held.
 *
 * RULES
 *   Deleting the locks removes the deadlock and fails the money check instead,
 *   so that shortcut is closed. Do not change the signatures, and leave
 *   balance(int) and totalMoney() as they are — they are already correct. Watch
 *   the self-transfer case if your fix locks the same monitor twice.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 10 trials of 16 threads x 2,000 two-way transfers each finish inside a
 *      4-second budget — no deadlock;
 *   2. totalMoney() is still accounts x initial balance afterwards — no money
 *      created or destroyed;
 *   3. one isolated transfer debits and credits the right amounts.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex05Bank
 *
 * HINT
 *   The bug is not "two locks". It is "two locks acquired in two different
 *   orders". Impose a global order on the locks, or stop holding one while
 *   waiting for the other. When a trial times out the checker asks the JVM what
 *   it sees, via ThreadMXBean.findDeadlockedThreads() — that call is what you
 *   have in production when a service goes quiet instead of erroring.
 *
 * SEE ALSO
 *   Docs — read this first:
 *     docs/02-concurrency/04-locks-deadlock-conditions.md, section "Deadlock".
 *   Demo t04locks.D11_Deadlock shows the failure live. Reference solution:
 *   solutions/Solutions.java.
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

    /*
     * Must guarantee that the debit and the credit happen together, under both
     * account locks, and that two transfers between the same pair of accounts
     * can never block each other forever. Lose the first and money appears or
     * vanishes; lose the second and the whole service stops answering with no
     * exception anywhere to explain it.
     */
    @Override
    public void transfer(int fromAccount, int toAccount, long amount) {
        // WRONG: these two lines lock in argument order. transfer(1,2) grabs locks[1]
        // then waits for locks[2] while transfer(2,1) holds locks[2] and waits for
        // locks[1] — a circular wait that neither thread ever leaves.
        // TODO lock the two accounts in a fixed global order (e.g. lower index first)
        // instead of "from" then "to", keeping both updates inside both locks.
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
        Check check = Check.named("Exercise 5 — deadlock-free transfers", "ExerciseTests$Ex5")
                .reading("docs/02-concurrency/04-locks-deadlock-conditions.md § \"Deadlock\"");

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

    /*
     * Asks the JVM whether it can see the deadlock, and puts the answer in the
     * failure message. Same ThreadMXBean call as D11 — worth knowing by heart,
     * because it is the fastest way to turn "the service is hung" into "these
     * two threads are waiting on each other".
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
