package com.locallearn.concurrency.contract;

import com.locallearn.concurrency.api.Contracts.Bank;
import com.locallearn.concurrency.support.Stress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.management.ManagementFactory;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * EXERCISE 5 contract — see {@code t04locks.D11_Deadlock}.
 *
 * <p>Two properties, and you need both: no deadlock <em>and</em> money
 * conserved. Deleting the locks satisfies the first and breaks the second,
 * which is the cheat this test exists to block.
 */
public abstract class BankContract {

    private static final int ACCOUNTS = 6;
    private static final long INITIAL = 1_000_000L;

    protected abstract Bank newBank(int accounts, long initialBalance);

    @Test
    @Timeout(120)
    @DisplayName("concurrent bidirectional transfers neither deadlock nor lose money")
    void transfersAreSafe() {
        for (int trial = 1; trial <= 20; trial++) {
            Bank bank = newBank(ACCOUNTS, INITIAL);

            boolean completed = Stress.run(16, 2_000, i -> {
                // Deliberately pick pairs in BOTH directions. This is the exact
                // pattern that produces a circular wait when the lock order
                // depends on the arguments.
                int a = i % ACCOUNTS;
                int b = (i * 7 + 1) % ACCOUNTS;
                if (a != b) {
                    bank.transfer(a, b, 1);
                    bank.transfer(b, a, 1);
                }
            }, 15);

            assertThat(completed)
                    .as("trial %d: transfers did not finish within 15s.%n%s", trial, deadlockReport())
                    .isTrue();

            assertThat(bank.totalMoney())
                    .as("trial %d: money was created or destroyed. The two balance "
                        + "updates inside a transfer must happen as one atomic step.", trial)
                    .isEqualTo(ACCOUNTS * INITIAL);
        }
    }

    @Test
    @Timeout(30)
    @DisplayName("a single transfer moves the right amount")
    void singleTransferIsCorrect() {
        Bank bank = newBank(3, 100);
        bank.transfer(0, 1, 30);
        assertThat(bank.balance(0)).isEqualTo(70);
        assertThat(bank.balance(1)).isEqualTo(130);
        assertThat(bank.totalMoney()).isEqualTo(300);
    }

    /**
     * Asks the JVM whether it can see the deadlock, and includes the answer in
     * the failure message — the same {@code ThreadMXBean} call demonstrated in
     * D11, which is worth knowing for production triage.
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
        ManagementFactory.getThreadMXBean().getThreadInfo(deadlocked, true, true);
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
