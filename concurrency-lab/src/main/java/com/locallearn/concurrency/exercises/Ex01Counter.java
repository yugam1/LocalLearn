package com.locallearn.concurrency.exercises;

import java.util.concurrent.atomic.AtomicLong;

import com.locallearn.concurrency.api.Contracts.Counter;
import com.locallearn.concurrency.support.Check;
import com.locallearn.concurrency.support.Stress;

/**
 * <b>EXERCISE 1 — fix the lost updates.</b> Demo: {@code t03atomicity.D7_LostUpdates}.
 *
 * <p>{@code value++} is read-modify-write: three steps (load, add, store) that
 * another thread can interleave with. Two threads read 41, both store 42, and
 * one increment vanished. Make the increment atomic.
 *
 * <p>Hint: there are three valid answers here ({@code AtomicLong},
 * {@code synchronized}, {@code LongAdder}). Try all three — then look at D9's
 * benchmark and decide which you would actually ship for a metrics counter, and
 * which for an ID generator.
 *
 * <p>Note that {@code volatile} is <em>not</em> one of the three. D7 shows a
 * volatile counter losing updates just as badly as a plain one; if that still
 * feels surprising, run D7 before you start.
 *
 * <pre>
 * ./mvnw -q compile
 * java -cp target/classes com.locallearn.concurrency.exercises.Ex01Counter   # fast loop
 * ./mvnw test -Dtest='ExerciseTests$Ex1'                                     # the grade
 * </pre>
 */
public final class Ex01Counter implements Counter {

    private AtomicLong value;

    public Ex01Counter(){
        value = new AtomicLong(0L);
    }

    @Override
    public  void increment() {
        value.incrementAndGet();             // TODO broken: not atomic
    }

    @Override
    public  long count() {
        return (long)value.get();               // TODO broken: not safely published
    }

    // ═══════════════════════════════════════════════════════════════════════
    //  Below here is the checker, not the exercise. You do not need to edit it.
    // ═══════════════════════════════════════════════════════════════════════

    private static final int THREADS = 8;
    private static final int PER_THREAD = 100_000;
    private static final int TRIALS = 10;

    public static void main(String[] args) {
        Check check = Check.named("Exercise 1 — lost updates", "ExerciseTests$Ex1");

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
