package com.locallearn.concurrency.exercises;

import java.util.concurrent.atomic.AtomicLong;

import com.locallearn.concurrency.api.Contracts.Counter;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

/*
 * EXERCISE 1 — a counter that loses increments
 *
 * THE SCENARIO
 *   A request counter inside a service. Every handler thread calls increment()
 *   once per request; a metrics endpoint calls count() to publish the total.
 *   Eight threads hit it at once, and the number it reports is the number
 *   people alert on and bill from.
 *
 * WHAT IS WRONG RIGHT NOW
 *   Nothing as the file stands: the code below is your own fix and the checks
 *   pass. The defect this exercise is built around is a plain long value;
 *   value++;. That one line is three machine steps — load, add, store — and
 *   another thread can run between any two of them. Two threads load 41, both
 *   add one, both store 42, and one request is missing from the total. Nothing
 *   throws; the count is quietly low, and lower the busier the service gets.
 *
 * YOUR TASK
 *   1. increment() — make load/add/store one indivisible step, so no second
 *      thread can slip between the read and the write.
 *   2. count() — return the total of every increment that has finished, not a
 *      stale copy a reader thread cached earlier.
 *
 * RULES
 *   volatile is not one of the answers. It makes each read and each write
 *   visible, but it does not join the three steps of value++ into one, so a
 *   volatile counter loses updates just as badly as a plain one. D7 shows that
 *   live.
 *
 * DONE WHEN
 *   Running this file prints all PASS and exits 0. The checks are:
 *   1. 8 threads x 100,000 increments, 10 trials, land on exactly 800,000 every
 *      time — one trial can get lucky, ten do not;
 *   2. 1,000 increments on a single thread still read back as 1,000 — this
 *      catches a fix that is atomic and also wrong, e.g. counting by the wrong
 *      amount.
 *
 * HOW TO RUN
 *   Press Run in VS Code (Code Runner, Ctrl/Cmd+Alt+N) with this file open, or:
 *     cd concurrency-lab
 *     ./run.sh Ex01Counter
 *
 * HINT
 *   Three implementations are correct here: AtomicLong, synchronized, and
 *   LongAdder. Try all three, then read D9's benchmark and decide which one you
 *   would ship for a metrics counter and which for an ID generator.
 *
 * SEE ALSO
 *   Docs — read this first: docs/02-concurrency/03-atomicity-races-cas.md,
 *     sections "`count++` is three operations" and "Compare-And-Swap".
 *   Demo t03atomicity.D7_LostUpdates shows the failure live. Reference
 *   solution: solutions/Solutions.java.
 */
public final class Ex01Counter implements Counter {

    private AtomicLong value;

    public Ex01Counter(){
        value = new AtomicLong(0L);
    }

    /*
     * Must guarantee that every call adds exactly one to the total, no matter
     * how many threads call it at the same moment. If the read, the add and
     * the store can be interleaved by another thread, increments silently
     * disappear under load.
     */
    @Override
    public  void increment() {
        value.incrementAndGet();             // one indivisible read-add-store
    }

    /*
     * Must return a value that includes every increment already finished on
     * any thread. A field a reader can cache would let this report a total
     * from minutes ago.
     */
    @Override
    public  long count() {
        return (long)value.get();               // volatile read: no stale copy
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int THREADS = 8;
    private static final int PER_THREAD = 100_000;
    private static final int TRIALS = 10;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 1 — lost updates", "ExerciseTests$Ex1")
                .reading("docs/02-concurrency/03-atomicity-races-cas.md § \"`count++` is three operations\"");

        check.that("every increment counted — %d threads x %,d, %d trials"
                .formatted(THREADS, PER_THREAD, TRIALS), () -> {
            long expected = (long) THREADS * PER_THREAD;
            // Trials, not one run: a broken counter occasionally gets lucky and
            // lands on the right number, and one passing run would then read as
            // proof. Ten runs do not get lucky together.
            for (int trial = 1; trial <= TRIALS; trial++) {
                Counter counter = new Ex01Counter();
                Stress.run(THREADS, PER_THREAD, i -> counter.increment());
                Check.equal(counter.count(), expected,
                        "trial %d of %d: increments were lost to a read-modify-write race",
                        trial, TRIALS);
            }
        });

        check.that("a single thread still counts correctly", () -> {
            Counter counter = new Ex01Counter();
            for (int i = 0; i < 1_000; i++) {
                counter.increment();
            }
            // Here to catch the other failure mode: an "atomic" fix that is
            // atomic and also wrong, e.g. incrementing by the wrong amount.
            Check.equal(counter.count(), 1_000, "uncontended counting is broken");
        });

        System.exit(check.finish());
    }
}
