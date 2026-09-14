package com.locallearn.concurrency.t09parallel;

import com.locallearn.concurrency.support.Log;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

/**
 * DEMO 26 — Parallel streams: a {@link ForkJoinPool} with the fork/join hidden.
 * Which means every one of D25's rules still applies, plus one new hazard that
 * is invisible in the source code.
 *
 * <p>Run it: {@code ./mvnw -q compile exec:java -Dexec.mainClass=com.locallearn.concurrency.t09parallel.D26_ParallelStreamsAndTheCommonPool}
 *
 * <h2>What {@code .parallel()} actually does</h2>
 * It splits the source with a {@link java.util.Spliterator} and submits the
 * pieces to {@link ForkJoinPool#commonPool()}. Three consequences follow
 * immediately, and all three surprise people:
 * <ol>
 *   <li><b>It is CPU-bound divide-and-conquer, so the source must split well.</b>
 *       An array or an {@code ArrayList} splits in O(1) — halve the index range.
 *       A {@code LinkedList} has no index; splitting it means walking it, so the
 *       split costs as much as the work.</li>
 *   <li><b>The pool is JVM-wide and shared.</b> {@code commonPool()} is one pool
 *       for the whole process — every parallel stream, every
 *       {@code CompletableFuture} with no executor argument, and any library
 *       inside your dependencies that uses either. It has {@code cores - 1}
 *       workers and there is no bulkhead between users.</li>
 *   <li><b>Therefore blocking inside a parallel stream is a JVM-wide outage,
 *       not a local slowdown.</b> A worker parked on a socket is a worker that is
 *       not stealing, and there are only {@code cores - 1} of them.</li>
 * </ol>
 *
 * <h2>The rule</h2>
 * {@code .parallel()} pays off when <b>N × cost-per-element</b> is large, the
 * source splits cheaply, and the operation neither blocks nor shares mutable
 * state. Miss any of those and it ranges from pointless to catastrophic. The
 * usual rule of thumb — worth it above roughly 10,000 cheap elements — is a
 * starting point, and section 1 shows where it actually lands here.
 */
public final class D26_ParallelStreamsAndTheCommonPool {

    private static final AtomicLong SINK = new AtomicLong();

    public static void main(String[] args) throws Exception {
        Log.log("commonPool parallelism = %d   (cores - 1)",
                ForkJoinPool.commonPool().getParallelism());

        sizeAndCost();
        boxing();
        badSplits();
        commonPoolStarvation();

        Log.takeaway("""
                A parallel stream is not a speed switch, it is a scheduling decision
                you are making in one word. It wins only when there is enough total
                work to amortise the splitting, the source splits cheaply, and the
                per-element operation is pure CPU.

                The starvation measurement is the one to remember. commonPool is
                JVM-wide with cores-1 workers and no bulkhead, so ONE parallel stream
                doing blocking IO stalls every other parallel stream in the process --
                including ones inside libraries you did not write, on data you have
                never heard of. Topic 8 taught bulkheads for exactly this reason, and
                commonPool is the one pool in the JVM you cannot bulkhead.

                So: never block inside a parallel stream. If the work blocks, it is
                not divide-and-conquer CPU work at all -- it is IO work, and IO work
                belongs on virtual threads (D27).""");
    }

    // ── 1. Size and cost: where the crossover actually is ──────────────────
    private static void sizeAndCost() {
        Log.section("1. WHEN IT WINS — the crossover is about TOTAL work, not element count");
        Log.log("%-14s %14s %14s %14s %10s", "elements", "work/element", "sequential",
                "parallel", "speedup");

        for (int n : new int[]{100, 10_000, 1_000_000}) {
            for (int perElement : new int[]{1, 1_000}) {
                long seq = time(() -> SINK.addAndGet(
                        IntStream.range(0, n).mapToLong(i -> mix(i, perElement)).sum()));
                long par = time(() -> SINK.addAndGet(
                        IntStream.range(0, n).parallel().mapToLong(i -> mix(i, perElement)).sum()));
                Log.log("%-14s %14s %12dus %12dus %9.2fx",
                        String.format("%,d", n),
                        perElement == 1 ? "trivial" : "1,000 iters",
                        seq, par, seq / (double) Math.max(par, 1));
            }
        }
        Log.log("Small N with trivial work: parallel LOSES — you paid for splitting,");
        Log.log("task objects and a join tree to save nothing. Raise either the element");
        Log.log("count or the per-element cost and the same call starts winning.");
    }

    // ── 2. Boxing ──────────────────────────────────────────────────────────
    /**
     * {@code IntStream} carries primitives; {@code Stream<Integer>} carries
     * pointers to heap objects. The parallel version of the boxed stream has to
     * chase those pointers from several threads at once, so boxing does not just
     * cost allocation — it costs the cache locality that made splitting
     * worthwhile in the first place.
     */
    private static void boxing() {
        Log.section("2. BOXING — the same sum, primitives vs objects");
        int n = 5_000_000;

        long primitiveSeq = time(() -> SINK.addAndGet(LongStream.range(0, n).sum()));
        long primitivePar = time(() -> SINK.addAndGet(LongStream.range(0, n).parallel().sum()));
        long boxedSeq = time(() -> SINK.addAndGet(
                LongStream.range(0, n).boxed().mapToLong(Long::longValue).sum()));
        long boxedPar = time(() -> SINK.addAndGet(
                LongStream.range(0, n).boxed().parallel().mapToLong(Long::longValue).sum()));

        Log.log("%-28s %12s %12s", "", "sequential", "parallel");
        Log.log("%-28s %10dus %10dus", "LongStream (primitives)", primitiveSeq, primitivePar);
        Log.log("%-28s %10dus %10dus", "Stream<Long> (boxed)", boxedSeq, boxedPar);
        Log.log("Boxing costs %,dus sequentially and %,dus in parallel — an allocation",
                boxedSeq - primitiveSeq, boxedPar - primitivePar);
        Log.log("and a pointer chase per element. Use IntStream/LongStream/DoubleStream.");
    }

    // ── 3. Sources that split badly ────────────────────────────────────────
    private static void badSplits() {
        Log.section("3. THE SOURCE MUST SPLIT — ArrayList vs LinkedList");
        int n = 1_000_000;

        List<Integer> arrayList = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            arrayList.add(i);
        }
        List<Integer> linkedList = new LinkedList<>(arrayList);

        long arraySeq = time(() -> SINK.addAndGet(arrayList.stream().mapToLong(i -> mix(i, 20)).sum()));
        long arrayPar = time(() -> SINK.addAndGet(arrayList.parallelStream().mapToLong(i -> mix(i, 20)).sum()));
        long linkedSeq = time(() -> SINK.addAndGet(linkedList.stream().mapToLong(i -> mix(i, 20)).sum()));
        long linkedPar = time(() -> SINK.addAndGet(linkedList.parallelStream().mapToLong(i -> mix(i, 20)).sum()));

        Log.log("%-20s %12s %12s %10s", "source", "sequential", "parallel", "speedup");
        Log.log("%-20s %10dus %10dus %9.2fx", "ArrayList", arraySeq, arrayPar,
                arraySeq / (double) Math.max(arrayPar, 1));
        Log.log("%-20s %10dus %10dus %9.2fx", "LinkedList", linkedSeq, linkedPar,
                linkedSeq / (double) Math.max(linkedPar, 1));
        Log.log("ArrayList splits by halving an index range: O(1), perfectly balanced.");
        Log.log("LinkedList has no index. Its spliterator must WALK the list to split");
        Log.log("it, so the splitting traverses the same nodes the computation does.");
    }

    // ── 4. The trap: one blocking stream stalls the whole JVM ──────────────
    /**
     * The measurement that matters most on this page. A background parallel
     * stream does something entirely reasonable-looking — a blocking call per
     * element — and an <em>unrelated</em>, purely computational parallel stream
     * elsewhere in the JVM slows to a crawl, because both are queued on the same
     * {@code cores - 1} workers.
     */
    private static void commonPoolStarvation() throws Exception {
        Log.section("4. COMMON-POOL STARVATION — the hazard you cannot see in the source");

        // Baseline: an unrelated CPU-bound parallel stream on a quiet JVM.
        long quiet = time(() -> SINK.addAndGet(
                IntStream.range(0, 200_000).parallel().mapToLong(i -> mix(i, 500)).sum()));
        Log.log("victim stream on a QUIET jvm:    %,7dus", quiet);

        // Now occupy the common pool with blocking work. Note there is nothing
        // wrong with this code locally — it is just slow, as blocking IO is.
        int blockers = ForkJoinPool.commonPool().getParallelism() * 4;
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch started = new CountDownLatch(1);
        Thread hog = new Thread(() -> {
            started.countDown();
            IntStream.range(0, blockers).parallel().forEach(i -> {
                try {
                    release.await();                 // stands in for a socket read
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }, "blocking-stream");
        hog.setDaemon(true);
        hog.start();
        started.await();
        TimeUnit.MILLISECONDS.sleep(300);            // let the blockers occupy the workers

        Log.log("now %d tasks are BLOCKED inside a parallel stream elsewhere in the JVM",
                blockers);
        long starved = time(() -> SINK.addAndGet(
                IntStream.range(0, 200_000).parallel().mapToLong(i -> mix(i, 500)).sum()));
        Log.log("the SAME victim stream now:      %,7dus   -> %.1fx slower",
                starved, starved / (double) Math.max(quiet, 1));

        release.countDown();
        hog.join(TimeUnit.SECONDS.toMillis(10));

        long recovered = time(() -> SINK.addAndGet(
                IntStream.range(0, 200_000).parallel().mapToLong(i -> mix(i, 500)).sum()));
        Log.log("after the blockers finish:       %,7dus   (recovered)", recovered);
        Log.log("");
        Log.log("The victim stream shares no data, no lock and no code with the blocking");
        Log.log("one. They share a POOL, chosen for them by the word 'parallel'. This is");
        Log.log("the anti-bulkhead: topic 8 taught one pool per concern, and commonPool");
        Log.log("is a single pool shared by every concern in the process.");
        Log.log("Note it does not deadlock: ForkJoinPool compensates by adding workers,");
        Log.log("which is why this is a slow degradation you will debug at 3am rather");
        Log.log("than a clean failure you would have caught in staging.");
    }

    // ── helpers ────────────────────────────────────────────────────────────

    /** {@code iterations} of a serial-dependency arithmetic chain: real CPU work. */
    private static long mix(int seed, int iterations) {
        long x = 0x9E3779B97F4A7C15L ^ seed;
        for (int i = 0; i < iterations; i++) {
            x ^= x << 13;
            x ^= x >>> 7;
            x ^= x << 17;
        }
        return x;
    }

    /** Warms up, then returns MICROseconds for one measured run. */
    private static long time(Runnable body) {
        for (int i = 0; i < 3; i++) {
            body.run();
        }
        long start = System.nanoTime();
        body.run();
        return TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - start);
    }

    private D26_ParallelStreamsAndTheCommonPool() {
    }
}
