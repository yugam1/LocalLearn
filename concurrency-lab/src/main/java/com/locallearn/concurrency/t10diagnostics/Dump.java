package com.locallearn.concurrency.t10diagnostics;

import java.lang.management.LockInfo;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * A thread dump in thirty lines, plus the three other things {@link ThreadMXBean}
 * can tell you that {@code jcmd} cannot.
 *
 * <p>This is not a utility class bolted on to make the demos shorter. It is the
 * toolkit of topic 10, written out so you can see that every one of these
 * "production diagnostics" is a handful of calls on a JMX bean that has shipped
 * in every JDK since 5. If you can write this class you can write the health
 * indicator that pages you at 3am, and you will understand exactly what it can
 * and cannot see.
 *
 * <p>The four capabilities, and the symptom each one answers:
 * <ul>
 *   <li>{@link #census(String, String)} — <b>where is every thread stopped?</b>
 *       State word plus the top stack frames. This is what a thread dump is.</li>
 *   <li>{@link #deadlockReport()} — <b>is it a lock cycle?</b> Both
 *       {@code findDeadlockedThreads()} and the narrower
 *       {@code findMonitorDeadlockedThreads()}, because the difference between
 *       them is a diagnosis.</li>
 *   <li>{@link #cpuMillisByName(String)} — <b>which thread is burning a core?</b>
 *       Per-thread CPU time, the only tool that separates "busy" from "spinning
 *       pointlessly".</li>
 *   <li>{@link #threadCounts()} — <b>are we leaking threads?</b> Live, peak and
 *       total-ever-started, which together say whether the leak is ongoing.</li>
 * </ul>
 */
public final class Dump {

    private static final ThreadMXBean THREADS = ManagementFactory.getThreadMXBean();

    private Dump() {
    }

    // ── 1. The dump itself ─────────────────────────────────────────────────

    /**
     * Prints one line per live thread whose name starts with {@code namePrefix},
     * in the shape a real dump uses: name, state, the lock it is waiting on (if
     * any) and who owns that lock, then the top few frames.
     *
     * <p>Read the frames, not the state word. {@code RUNNABLE} in particular is
     * a liar — see the class documentation of
     * {@link D29_ReadingThreadDumps} and the state table in topic 1.
     */
    public static void census(String title, String namePrefix) {
        System.out.println();
        System.out.println("--- " + title + " (threads named '" + namePrefix + "*') ---");
        // Depth 12: enough to get past the j.u.c. plumbing and reach the frame
        // in YOUR code that actually decided to block.
        for (ThreadInfo info : THREADS.getThreadInfo(THREADS.getAllThreadIds(), 12)) {
            if (info == null || !info.getThreadName().startsWith(namePrefix)) {
                continue;
            }
            System.out.printf("  \"%s\" %s%s%n",
                    info.getThreadName(), info.getThreadState(), lockSuffix(info));
            StackTraceElement[] stack = info.getStackTrace();
            for (int i = 0; i < Math.min(3, stack.length); i++) {
                System.out.printf("        at %s%n", stack[i]);
            }
        }
    }

    private static String lockSuffix(ThreadInfo info) {
        LockInfo lock = info.getLockInfo();
        if (lock == null) {
            return "";
        }
        String owner = info.getLockOwnerName() == null
                ? "" : " owned by \"" + info.getLockOwnerName() + "\"";
        // A monitor prints as "waiting to lock"; an AQS lock prints as "parking
        // to wait for". Same idea, two different words in a real dump, which is
        // exactly why grepping for one of them misses the other.
        String verb = info.getThreadState() == Thread.State.BLOCKED
                ? " - waiting to lock " : " - parking to wait for ";
        return verb + "<" + lock + ">" + owner;
    }

    /** Counts live threads whose name starts with {@code namePrefix}, by state. */
    public static Map<Thread.State, Integer> statesByName(String namePrefix) {
        Map<Thread.State, Integer> counts = new LinkedHashMap<>();
        for (ThreadInfo info : THREADS.getThreadInfo(THREADS.getAllThreadIds(), 0)) {
            if (info != null && info.getThreadName().startsWith(namePrefix)) {
                counts.merge(info.getThreadState(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /**
     * The deepest frame of {@code thread} that still belongs to this lab —
     * i.e. the line of <em>your</em> code that chose to block. In a real dump
     * this is the frame you are looking for, and it is never the top one.
     */
    public static String ownFrame(String threadName) {
        for (ThreadInfo info : THREADS.getThreadInfo(THREADS.getAllThreadIds(), 40)) {
            if (info == null || !info.getThreadName().equals(threadName)) {
                continue;
            }
            for (StackTraceElement frame : info.getStackTrace()) {
                if (frame.getClassName().startsWith("com.locallearn")) {
                    return frame.toString();
                }
            }
            return info.getStackTrace().length == 0 ? "<no stack>" : info.getStackTrace()[0].toString();
        }
        return "<thread gone>";
    }

    // ── 2. Deadlock detection, both flavours ───────────────────────────────

    /**
     * Runs both detectors and reports them side by side, because the
     * <em>difference</em> is information:
     * <ul>
     *   <li>{@code findMonitorDeadlockedThreads()} sees only {@code synchronized}
     *       monitors.</li>
     *   <li>{@code findDeadlockedThreads()} sees monitors <b>and</b>
     *       {@code AbstractQueuedSynchronizer} locks ({@code ReentrantLock},
     *       {@code ReentrantReadWriteLock}).</li>
     *   <li>Neither sees a cycle built from {@code Semaphore} permits, mutual
     *       {@code CountDownLatch} awaits, or {@code Future.get()}. Those hang
     *       with no report at all — which is topic 10's central lesson.</li>
     * </ul>
     */
    /**
     * Runs both detectors. With no arguments the report is unscoped, exactly as
     * {@code jcmd Thread.print} would give it to you. Pass one or more name
     * prefixes to report only threads whose name starts with one of them.
     *
     * <p>That filter is not a convenience — it is what triage actually looks
     * like. A real JVM has more than one problem at a time, and a deadlock you
     * already know about will keep appearing in every report you run while
     * chasing the next one. Scoping the question to the threads you are
     * currently asking about is how you stop answering the wrong question. So
     * that nothing is hidden, a scoped report still counts the deadlocked
     * threads it filtered out and says so on the last line.
     */
    public static String deadlockReport(String... namePrefixes) {
        long[] all = THREADS.findDeadlockedThreads();
        long[] any = matching(all, namePrefixes);
        long[] monitorsOnly = matching(THREADS.findMonitorDeadlockedThreads(), namePrefixes);

        StringBuilder report = new StringBuilder();
        if (namePrefixes.length > 0) {
            report.append("(scoped to threads named ").append(String.join("*, ", namePrefixes))
                  .append("*)\n");
        }
        report.append("findDeadlockedThreads()        -> ").append(describe(any)).append('\n');
        report.append("findMonitorDeadlockedThreads() -> ").append(describe(monitorsOnly));
        if (any == null) {
            report.append("\nNo lock cycle. That does NOT mean 'no hang': a pool waiting on "
                          + "its own queue, a latch nobody counts down, or a semaphore cycle "
                          + "are all invisible here.");
        }
        int outside = (all == null ? 0 : all.length) - (any == null ? 0 : any.length);
        if (outside > 0) {
            report.append("\n(").append(outside).append(" further deadlocked thread(s) exist "
                    + "outside this scope. Unrelated to the question being asked here — but "
                    + "never assume that without looking.)");
        }
        return report.toString();
    }

    /** Keeps only the ids whose thread name starts with one of {@code namePrefixes}. */
    private static long[] matching(long[] threadIds, String... namePrefixes) {
        if (threadIds == null || namePrefixes.length == 0) {
            return threadIds;
        }
        long[] kept = new long[threadIds.length];
        int size = 0;
        for (ThreadInfo info : THREADS.getThreadInfo(threadIds, 0)) {
            if (info != null && startsWithAny(info.getThreadName(), namePrefixes)) {
                kept[size++] = info.getThreadId();
            }
        }
        if (size == 0) {
            return null;                                // same "nothing found" shape as the API
        }
        long[] exact = new long[size];
        System.arraycopy(kept, 0, exact, 0, size);
        return exact;
    }

    private static boolean startsWithAny(String name, String... prefixes) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private static String describe(long[] threadIds) {
        if (threadIds == null) {
            return "null (nothing found)";
        }
        StringBuilder text = new StringBuilder(threadIds.length + " threads: ");
        // lockedMonitors/lockedSynchronizers = true is what makes the report
        // name the OWNER of each contended lock, which is the whole diagnosis.
        ThreadInfo[] infos = THREADS.getThreadInfo(threadIds, true, true);
        for (int i = 0; i < infos.length; i++) {
            if (i > 0) {
                text.append("; ");
            }
            text.append('"').append(infos[i].getThreadName()).append("\" ")
                .append(infos[i].getThreadState())
                .append(" on <").append(infos[i].getLockInfo()).append('>')
                .append(" held by \"").append(infos[i].getLockOwnerName()).append('"');
        }
        return text.toString();
    }

    // ── 3. Per-thread CPU time — the only way to find a spinner ────────────

    /**
     * CPU milliseconds consumed by each live thread whose name starts with
     * {@code namePrefix}. Call twice around a known wall-clock interval and
     * subtract: a thread whose CPU time grows as fast as the wall clock is
     * running flat out. If it is also making no progress, you have found your
     * busy-wait.
     */
    public static Map<String, Long> cpuMillisByName(String namePrefix) {
        Map<String, Long> cpu = new LinkedHashMap<>();
        for (ThreadInfo info : THREADS.getThreadInfo(THREADS.getAllThreadIds(), 0)) {
            if (info == null || !info.getThreadName().startsWith(namePrefix)) {
                continue;
            }
            long nanos = THREADS.getThreadCpuTime(info.getThreadId());   // -1 if dead
            cpu.put(info.getThreadName(),
                    nanos < 0 ? -1L : TimeUnit.NANOSECONDS.toMillis(nanos));
        }
        return cpu;
    }

    /** Must be on before {@link #cpuMillisByName} means anything. */
    public static boolean enableCpuTime() {
        if (!THREADS.isThreadCpuTimeSupported()) {
            return false;
        }
        THREADS.setThreadCpuTimeEnabled(true);
        return true;
    }

    /**
     * Prints the CPU burned by each matching thread across {@code windowMillis}
     * of wall clock, as an absolute figure and as a percentage of one core.
     * Anything near 100% while the system makes no progress is a spinner.
     */
    public static void cpuOverWindow(String namePrefix, long windowMillis) throws InterruptedException {
        Map<String, Long> before = cpuMillisByName(namePrefix);
        TimeUnit.MILLISECONDS.sleep(windowMillis);
        Map<String, Long> after = cpuMillisByName(namePrefix);

        System.out.println();
        System.out.printf("--- CPU burned by '%s*' over %d ms of wall clock ---%n",
                namePrefix, windowMillis);
        for (Map.Entry<String, Long> entry : after.entrySet()) {
            long start = before.getOrDefault(entry.getKey(), 0L);
            long burned = Math.max(0, entry.getValue() - start);
            System.out.printf("  %-28s %5d ms CPU  (%3d%% of one core)  %s%n",
                    entry.getKey(), burned, burned * 100 / Math.max(1, windowMillis),
                    burned * 100 / Math.max(1, windowMillis) > 50 ? "<-- SPINNING" : "");
        }
    }

    // ── 4. Thread counts — the leak detector ───────────────────────────────

    /** Live / peak / total-ever-started. Growing live + growing total = a leak in progress. */
    public static String threadCounts() {
        return String.format("live=%d peak=%d totalStarted=%d daemon=%d",
                THREADS.getThreadCount(), THREADS.getPeakThreadCount(),
                THREADS.getTotalStartedThreadCount(), THREADS.getDaemonThreadCount());
    }

    // ── 5. The command you actually type at 3am ────────────────────────────

    /** Prints this JVM's pid and the two commands that dump it. */
    public static void jcmdHint() {
        long pid = ProcessHandle.current().pid();
        System.out.println();
        System.out.println("--- this JVM is pid " + pid + ". From another terminal: ---");
        System.out.println("    jcmd " + pid + " Thread.print      # the full dump, deadlock section included");
        System.out.println("    jstack " + pid + "                 # the same thing, older tool");
        System.out.println("    jcmd " + pid + " Thread.print -l   # adds ownable synchronizers (ReentrantLock holders)");
    }

    /** Names of live threads starting with {@code namePrefix}. */
    public static List<String> namesStartingWith(String namePrefix) {
        List<String> names = new ArrayList<>();
        for (ThreadInfo info : THREADS.getThreadInfo(THREADS.getAllThreadIds(), 0)) {
            if (info != null && info.getThreadName().startsWith(namePrefix)) {
                names.add(info.getThreadName());
            }
        }
        return names;
    }
}
