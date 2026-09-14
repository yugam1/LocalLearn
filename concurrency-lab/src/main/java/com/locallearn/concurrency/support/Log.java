package com.locallearn.concurrency.support;

/**
 * Tiny console logger for the demos. Deliberately not SLF4J: every demo in this
 * lab should be runnable with nothing but a JDK, and the thread name is the one
 * piece of context that matters here, so it is always printed.
 */
public final class Log {

    private static final long START = System.nanoTime();

    private Log() {
    }

    /** Prints {@code [ +12ms] [thread-name] message}. */
    public static void log(String format, Object... args) {
        long elapsedMillis = (System.nanoTime() - START) / 1_000_000L;
        System.out.printf("[%+5dms] [%-18s] %s%n",
                elapsedMillis, Thread.currentThread().getName(), String.format(format, args));
    }

    /** Section header, so a demo's output reads as distinct steps. */
    public static void section(String title) {
        System.out.println();
        System.out.println("=== " + title + " ===");
    }

    /** The punchline of a demo — what you were supposed to notice. */
    public static void takeaway(String format, Object... args) {
        System.out.println();
        System.out.println(">>> " + String.format(format, args));
    }
}
