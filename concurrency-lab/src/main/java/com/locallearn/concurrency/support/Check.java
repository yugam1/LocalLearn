package com.locallearn.concurrency.support;

import java.util.ArrayList;
import java.util.List;

/**
 * A three-method test harness, so every exercise can be a file you run with
 * {@code java} and get an answer in seconds.
 *
 * <p>The JUnit contracts in {@code src/test} remain the authority: they run more
 * trials, they run the identical assertions against the reference solutions, and
 * they are what "done" means. This class exists for the loop before that — edit,
 * run, see the number, edit again — without paying Maven's startup on every
 * attempt. Each exercise's {@code main} prints the {@code ./mvnw} command that
 * grades it for real.
 *
 * <p>Deliberately not JUnit: JUnit is test-scoped, and an exercise you can only
 * run through a build tool is one you run less often.
 *
 * <pre>
 * public static void main(String[] args) {
 *     Check check = Check.named("Exercise 1 — lost updates", "ExerciseTests$Ex1");
 *     check.that("every increment is counted", () -> {
 *         Counter c = new Ex01Counter();
 *         Stress.run(8, 100_000, i -> c.increment());
 *         Check.equal(c.count(), 800_000, "increments were lost to a race");
 *     });
 *     System.exit(check.finish());
 * }
 * </pre>
 */
public final class Check {

    private final String title;
    private final String mavenTest;
    private final List<String> failures = new ArrayList<>();
    private int passed;

    private Check(String title, String mavenTest) {
        this.title = title;
        this.mavenTest = mavenTest;
    }

    /**
     * @param title     what this exercise is, e.g. {@code "Exercise 1 — lost updates"}
     * @param mavenTest the JUnit nested class that grades it, e.g. {@code "ExerciseTests$Ex1"}
     */
    public static Check named(String title, String mavenTest) {
        Check check = new Check(title, mavenTest);
        System.out.println();
        System.out.println("═══ " + title + " ".repeat(Math.max(1, 66 - title.length())) + "═══");
        return check;
    }

    /**
     * Runs one named check. It passes unless it throws — from a {@link #require}
     * or {@link #equal} helper, or from the code under test blowing up.
     *
     * <p>A {@link StackOverflowError} or {@link OutOfMemoryError} is rethrown
     * rather than recorded: at that point the JVM is not in a state where the
     * next check would mean anything.
     */
    public Check that(String what, Body body) {
        long startNanos = System.nanoTime();
        try {
            body.run();
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            passed++;
            System.out.printf("  PASS  %-58s %,6d ms%n", what, millis);
        } catch (StackOverflowError | OutOfMemoryError fatal) {
            throw fatal;
        } catch (Throwable failure) {
            long millis = (System.nanoTime() - startNanos) / 1_000_000L;
            String reason = failure instanceof AssertionError
                    ? failure.getMessage()
                    : failure.getClass().getSimpleName() + ": " + failure.getMessage();
            failures.add(what + " — " + reason);
            System.out.printf("  FAIL  %-58s %,6d ms%n", what, millis);
            reason.lines().forEach(line -> System.out.println("          " + line));
            if (!(failure instanceof AssertionError)) {
                // An unexpected exception: the stack trace is the useful part.
                failure.printStackTrace(System.out);
            }
        }
        return this;
    }

    /** Fails the current check unless {@code condition} holds. */
    public static void require(boolean condition, String format, Object... args) {
        if (!condition) {
            throw new AssertionError(String.format(format, args));
        }
    }

    /**
     * Fails the current check unless {@code actual == expected}, reporting both
     * numbers and the shortfall — in a concurrency lab the size of the gap is
     * usually the diagnosis.
     */
    public static void equal(long actual, long expected, String format, Object... args) {
        if (actual != expected) {
            throw new AssertionError(String.format(
                    "expected %,d but got %,d (off by %,+d) — %s",
                    expected, actual, actual - expected, String.format(format, args)));
        }
    }

    /**
     * Prints the summary and returns a process exit code, so
     * {@code System.exit(check.finish())} makes the shell agree with the output.
     *
     * @return 0 if everything passed, 1 otherwise
     */
    public int finish() {
        System.out.println();
        if (failures.isEmpty()) {
            System.out.printf("✅  %d/%d passed — now prove it properly:%n", passed, passed);
            System.out.printf("      ./mvnw test -Dtest='%s'%n", mavenTest);
            return 0;
        }
        System.out.printf("❌  %d/%d passed, %d failed:%n", passed, passed + failures.size(), failures.size());
        failures.forEach(failure -> System.out.println("      · " + failure));
        System.out.println();
        System.out.println("    That is the starting line, not a problem. Fix the TODOs above, rerun.");
        return 1;
    }

    /** One check's body. Allowed to throw anything; throwing is how it fails. */
    @FunctionalInterface
    public interface Body {
        void run() throws Exception;
    }
}
