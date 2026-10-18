/*
 * ============================================================================
 *  NETWORK PATH CONFIGURATION ITERATOR
 *  Source: Interview-style design problem (Iterator + DFS / backtracking)
 *  Difficulty: Medium
 * ============================================================================
 *
 *  PROBLEM
 *  -------
 *  A network is modelled as a directed graph. Every router has an integer ID,
 *  and every directed edge carries a non-negative latency. The graph is given
 *  as Map<Integer, List<Edge>>, where graph.get(u) lists the outgoing edges of
 *  router u. A router with no outgoing edges may be missing from the map.
 *
 *  Implement NetworkPathIterator, an Iterator<List<Integer>> that returns
 *  every valid path from sourceRouter to destinationRouter, one per next().
 *
 *  A path is VALID when it:
 *    - starts at sourceRouter and ends at destinationRouter,
 *    - follows directed edges of the graph,
 *    - visits no router more than once (no cycles),
 *    - has total latency <= latencyLimit.
 *
 *  Iterator behaviour:
 *    - hasNext() returns whether another valid path is available
 *      (calling it repeatedly must not skip paths).
 *    - next() returns the next valid path as a List<Integer>.
 *    - next() throws NoSuchElementException when no path remains.
 *
 *  Ordering: paths are returned in lexicographical order of router IDs
 *  (first differing router decides, smaller ID first; a proper prefix
 *  comes before any longer path that extends it).
 *
 *  If several parallel edges connect the same ordered pair of routers, a
 *  router sequence is still ONE path. It is reported once, provided some
 *  choice of edges keeps it within the latency limit.
 *
 *  If sourceRouter == destinationRouter, the single-router path [source]
 *  has latency 0 and is valid exactly when latencyLimit >= 0.
 *
 *  KEY DEFINITIONS (graph from the sample below, latencyLimit = 7)
 *  ---------------------------------------------------------------
 *    ✓ [1, 2, 3, 4]   latency 2 + 1 + 3 = 6 <= 7
 *    ✓ [1, 3, 4]      latency 4 + 3 = 7 <= 7 (equal to the limit is allowed)
 *    ✗ [1, 2, 4]      latency 2 + 7 = 9 > 7
 *    ✗ [1, 4]         no edge 1 -> 4
 *    ✗ [1, 2, 1, ...] router 1 repeats (cycle)
 *
 *  Lexicographic order examples:
 *    [1, 2, 9]  before  [1, 3, 9]      (2 < 3 at index 1)
 *    [1, 2]     before  [1, 3, 2]      (2 < 3 at index 1)
 *    [0, -3, 5] before  [0, -1, 5]     (-3 < -1; IDs may be negative)
 *
 *  SAMPLE
 *  ------
 *    Graph:
 *      1 -> 2 (2)    1 -> 3 (4)    2 -> 3 (1)    2 -> 4 (7)    3 -> 4 (3)
 *    sourceRouter = 1, destinationRouter = 4, latencyLimit = 7
 *
 *    Analysis: the three simple paths from 1 to 4 are [1,2,3,4] (6),
 *    [1,2,4] (9) and [1,3,4] (7). [1,2,4] exceeds the limit.
 *
 *    Output (successive next() calls):
 *      [1, 2, 3, 4]
 *      [1, 3, 4]
 *    then hasNext() == false and next() throws NoSuchElementException.
 *
 *  CONSTRAINTS
 *  -----------
 *    - 0 <= number of routers <= 15
 *    - 0 <= number of edges <= 200
 *    - Router IDs fit in int and may be negative
 *    - 0 <= latency <= 10^9
 *    - Integer.MIN_VALUE <= latencyLimit <= Integer.MAX_VALUE
 *    - Path sums can exceed the int range
 *
 *  RUN
 *  ---
 *    javac NetworkPathConfigurationIterator.java && java Judge
 * ============================================================================
 */

import java.util.*;
import java.util.function.Supplier;

class Solution {

    public static class Edge {
        int to;
        int latency;

        public Edge(int to, int latency) {
            this.to = to;
            this.latency = latency;
        }
    }

    /**
     * Iterates over every cycle-free path from sourceRouter to destinationRouter
     * whose total latency is at most latencyLimit, in lexicographic order of
     * router IDs.
     */
    public static class NetworkPathIterator implements Iterator<List<Integer>> {

        /**
         * @param graph             adjacency map: router -> outgoing edges (may omit sinks)
         * @param sourceRouter      first router of every path
         * @param destinationRouter last router of every path
         * @param latencyLimit      maximum allowed total latency (inclusive)
         */
        public NetworkPathIterator(Map<Integer, List<Edge>> graph,
                                   int sourceRouter,
                                   int destinationRouter,
                                   int latencyLimit) {
            // TODO: Implement your solution here
        }

        /** @return true if another valid path remains */
        @Override
        public boolean hasNext() {
            // TODO: Implement your solution here
            return false;
        }

        /**
         * @return the next valid path in lexicographic order
         * @throws NoSuchElementException if no path remains
         */
        @Override
        public List<Integer> next() {
            // TODO: Implement your solution here
            return null;
        }

        // HINT 1: Exploring neighbours in increasing ID order makes a depth-first
        //         search discover paths already in lexicographic order.
        // HINT 2: Precomputing all paths works, but a true iterator does the search
        //         lazily, pausing after each path it finds and resuming later.
        // HINT 3: hasNext() can "look ahead" and cache the next path; next() then
        //         just hands out the cached one.
        // HINT 4: Latencies are non-negative, so once a partial path is over the
        //         limit, no extension of it can come back under it.
        // HINT 5: Reaching the destination ends a path: going further would need
        //         to revisit the destination. Watch for parallel edges and for sums
        //         that overflow an int.
    }
}

class Judge {

    private static final boolean CHECK_FULL = false;
    private static final int[] SELECTED_TESTS = {};  // run only these test numbers when non-empty

    private static int passed = 0;
    private static int total = 0;

    public static void main(String[] args) {
        System.out.println("==============================================================");
        System.out.println("  Network Path Configuration Iterator   [Medium]");
        System.out.println("  Mode: " + (CHECK_FULL ? "FULL (basic + edge cases)" : "BASIC"));
        System.out.println("  Selected tests: "
                + (SELECTED_TESTS.length == 0 ? "ALL" : Arrays.toString(SELECTED_TESTS)));
        System.out.println("==============================================================\n");
        runTests();
        printSummary();
    }

    // ---------------------------------------------------------------- helpers

    private static final int[][] SAMPLE = {
            {1, 2, 2}, {1, 3, 4}, {2, 3, 1}, {2, 4, 7}, {3, 4, 3}
    };

    /** Builds a graph from {from, to, latency} triples, preserving the given edge order. */
    private static Map<Integer, List<Solution.Edge>> graph(int[]... edges) {
        Map<Integer, List<Solution.Edge>> g = new LinkedHashMap<>();
        for (int[] e : edges) {
            g.computeIfAbsent(e[0], k -> new ArrayList<>()).add(new Solution.Edge(e[1], e[2]));
        }
        return g;
    }

    private static List<List<Integer>> drain(Map<Integer, List<Solution.Edge>> g,
                                             int src, int dst, int limit) {
        Iterator<List<Integer>> it = new Solution.NetworkPathIterator(g, src, dst, limit);
        List<List<Integer>> out = new ArrayList<>();
        while (it.hasNext()) {
            out.add(it.next());
            if (out.size() > 100_000) throw new IllegalStateException("too many paths (infinite loop?)");
        }
        return out;
    }

    private static List<List<Integer>> paths(int[]... ps) {
        List<List<Integer>> out = new ArrayList<>();
        for (int[] p : ps) {
            List<Integer> l = new ArrayList<>();
            for (int x : p) l.add(x);
            out.add(l);
        }
        return out;
    }

    private static boolean selected(int n) {
        if (SELECTED_TESTS.length == 0) return true;
        for (int t : SELECTED_TESTS) if (t == n) return true;
        return false;
    }

    private static void test(int n, String desc, Object expected, Supplier<Object> actual) {
        if (!selected(n)) return;
        total++;
        Object got;
        try {
            got = actual.get();
        } catch (Exception e) {
            got = "Exception: " + e;
        }
        if (Objects.equals(expected, got)) {
            passed++;
            System.out.println("✓ PASS [#" + n + "]: " + desc);
        } else {
            System.out.println("✗ FAIL [#" + n + "]: " + desc);
            System.out.println("    Expected: " + expected);
            System.out.println("    Got:      " + got);
        }
    }

    // ------------------------------------------------------------------ tests

    private static void runTests() {
        System.out.println("--- Basic tests ---");

        test(1, "Sample: limit 7 keeps [1,2,3,4] and [1,3,4]",
                paths(new int[]{1, 2, 3, 4}, new int[]{1, 3, 4}),
                () -> drain(graph(SAMPLE), 1, 4, 7));

        test(2, "Sample graph, limit 9 returns all three paths in lexicographic order",
                paths(new int[]{1, 2, 3, 4}, new int[]{1, 2, 4}, new int[]{1, 3, 4}),
                () -> drain(graph(SAMPLE), 1, 4, 9));

        test(3, "Sample graph, limit 5 leaves no valid path",
                paths(),
                () -> drain(graph(SAMPLE), 1, 4, 5));

        test(4, "Unsorted adjacency list still yields sorted output",
                paths(new int[]{1, 2, 9}, new int[]{1, 3, 9}, new int[]{1, 5, 9}),
                () -> drain(graph(new int[]{1, 5, 1}, new int[]{1, 2, 1}, new int[]{1, 3, 1},
                        new int[]{5, 9, 1}, new int[]{2, 9, 1}, new int[]{3, 9, 1}), 1, 9, 10));

        test(5, "Cycles in the graph are never followed",
                paths(new int[]{1, 2, 3, 4}),
                () -> drain(graph(new int[]{1, 2, 1}, new int[]{2, 1, 1}, new int[]{2, 3, 1},
                        new int[]{3, 1, 1}, new int[]{3, 4, 1}), 1, 4, 100));

        test(6, "Direct edge [1,2] comes before longer [1,3,2]",
                paths(new int[]{1, 2}, new int[]{1, 3, 2}),
                () -> drain(graph(new int[]{1, 2, 5}, new int[]{1, 3, 1}, new int[]{3, 2, 1}), 1, 2, 10));

        test(7, "next() after exhaustion throws NoSuchElementException",
                "NoSuchElementException",
                () -> {
                    Iterator<List<Integer>> it = new Solution.NetworkPathIterator(graph(SAMPLE), 1, 4, 7);
                    it.next();
                    it.next();
                    try {
                        Object extra = it.next();
                        return "no exception, returned " + extra;
                    } catch (NoSuchElementException e) {
                        return "NoSuchElementException";
                    }
                });

        test(8, "Repeated hasNext() calls do not skip the first path",
                paths(new int[]{1, 2, 3, 4}).get(0),
                () -> {
                    Iterator<List<Integer>> it = new Solution.NetworkPathIterator(graph(SAMPLE), 1, 4, 7);
                    for (int i = 0; i < 5; i++) it.hasNext();
                    return it.next();
                });

        if (CHECK_FULL) {
            System.out.println("\n--- Edge-case tests ---");

            test(9, "Source equals destination, limit 0: single path [5]",
                    paths(new int[]{5}),
                    () -> drain(graph(new int[]{5, 6, 1}, new int[]{6, 5, 1}), 5, 5, 0));

            test(10, "Source equals destination, negative limit: no path",
                    paths(),
                    () -> drain(graph(new int[]{5, 6, 1}, new int[]{6, 5, 1}), 5, 5, -1));

            test(11, "Empty graph: no path",
                    paths(),
                    () -> drain(new HashMap<>(), 1, 2, 100));

            test(12, "Source missing from the map (no outgoing edges): no path",
                    paths(),
                    () -> drain(graph(new int[]{2, 3, 1}), 1, 3, 100));

            test(13, "Latency exactly equal to the limit is accepted",
                    paths(new int[]{1, 2, 3}),
                    () -> drain(graph(new int[]{1, 2, 3}, new int[]{2, 3, 4}), 1, 3, 7));

            test(14, "Zero-latency edges with limit 0 return both paths",
                    paths(new int[]{1, 2, 3}, new int[]{1, 3}),
                    () -> drain(graph(new int[]{1, 3, 0}, new int[]{1, 2, 0}, new int[]{2, 3, 0}), 1, 3, 0));

            test(15, "Negative router IDs are ordered numerically",
                    paths(new int[]{0, -3, -1, 5}, new int[]{0, -3, 5}, new int[]{0, -1, 5}, new int[]{0, 2, 5}),
                    () -> drain(graph(new int[]{0, -3, 1}, new int[]{0, 2, 1}, new int[]{0, -1, 1},
                            new int[]{-3, 5, 1}, new int[]{2, 5, 1}, new int[]{-1, 5, 1},
                            new int[]{-3, -1, 1}), 0, 5, 10));

            test(16, "Parallel duplicate edges: path reported once using the cheapest edge",
                    paths(new int[]{1, 2, 3}),
                    () -> drain(graph(new int[]{1, 2, 10}, new int[]{1, 2, 2}, new int[]{1, 2, 2},
                            new int[]{2, 3, 1}), 1, 3, 5));

            test(17, "Calling next() without hasNext() returns all paths in order",
                    paths(new int[]{1, 2, 3, 4}, new int[]{1, 2, 4}, new int[]{1, 3, 4}),
                    () -> {
                        Iterator<List<Integer>> it = new Solution.NetworkPathIterator(graph(SAMPLE), 1, 4, 9);
                        List<List<Integer>> out = new ArrayList<>();
                        out.add(it.next());
                        out.add(it.next());
                        out.add(it.next());
                        return out;
                    });

            test(18, "Sum above int range is rejected (no overflow)",
                    paths(),
                    () -> drain(graph(new int[]{1, 2, 1_000_000_000}, new int[]{2, 3, 1_000_000_000},
                            new int[]{3, 4, 1_000_000_000}), 1, 4, 2_000_000_000));

            test(19, "Larger input: complete directed graph on 7 routers has 326 paths 1->7",
                    326,
                    () -> {
                        List<int[]> edges = new ArrayList<>();
                        for (int u = 1; u <= 7; u++)
                            for (int v = 1; v <= 7; v++)
                                if (u != v) edges.add(new int[]{u, v, 1});
                        return drain(graph(edges.toArray(new int[0][])), 1, 7, 100).size();
                    });
        }
    }

    private static void printSummary() {
        System.out.println("\n==============================================================");
        System.out.println("  Result: " + passed + "/" + total + " passed");
        if (!CHECK_FULL) System.out.println("  Tip: set CHECK_FULL = true to run edge-case tests.");
        System.out.println("  Tip: list test numbers in SELECTED_TESTS to run only those.");
        System.out.println("==============================================================");
    }
}

/*
 * ============================================================================
 *  SPOILER BELOW
 * ============================================================================
 *
 *  OPTIMAL APPROACH: lazy iterative DFS (backtracking) with a look-ahead buffer
 *
 *  public static class NetworkPathIterator implements Iterator<List<Integer>> {
 *      private final Map<Integer, TreeMap<Integer, Long>> adj = new HashMap<>();
 *      private final int destination;
 *      private final long limit;
 *      private final Deque<Iterator<Map.Entry<Integer, Long>>> stack = new ArrayDeque<>();
 *      private final Deque<Long> costs = new ArrayDeque<>();
 *      private final List<Integer> path = new ArrayList<>();
 *      private final Set<Integer> onPath = new HashSet<>();
 *      private List<Integer> buffered;
 *      private boolean exhausted;
 *
 *      public NetworkPathIterator(Map<Integer, List<Edge>> graph, int sourceRouter,
 *                                 int destinationRouter, int latencyLimit) {
 *          this.destination = destinationRouter;
 *          this.limit = latencyLimit;
 *          for (Map.Entry<Integer, List<Edge>> e : graph.entrySet()) {
 *              if (e.getValue() == null) continue;
 *              TreeMap<Integer, Long> nbrs = new TreeMap<>();      // sorted by router ID
 *              for (Edge edge : e.getValue())
 *                  nbrs.merge(edge.to, (long) edge.latency, Math::min); // parallel edges: keep cheapest
 *              adj.put(e.getKey(), nbrs);
 *          }
 *          if (limit < 0) { exhausted = true; return; }
 *          path.add(sourceRouter);
 *          onPath.add(sourceRouter);
 *          costs.push(0L);
 *          if (sourceRouter == destinationRouter) {
 *              buffered = new ArrayList<>(path);                    // nothing can extend it
 *          } else {
 *              stack.push(neighbours(sourceRouter));
 *          }
 *      }
 *
 *      private Iterator<Map.Entry<Integer, Long>> neighbours(int u) {
 *          TreeMap<Integer, Long> m = adj.get(u);
 *          return m == null ? Collections.emptyIterator() : m.entrySet().iterator();
 *      }
 *
 *      private List<Integer> advance() {
 *          while (!stack.isEmpty()) {
 *              Iterator<Map.Entry<Integer, Long>> it = stack.peek();
 *              if (!it.hasNext()) {                                 // backtrack
 *                  stack.pop();
 *                  costs.pop();
 *                  onPath.remove(path.remove(path.size() - 1));
 *                  continue;
 *              }
 *              Map.Entry<Integer, Long> e = it.next();
 *              int v = e.getKey();
 *              long cost = costs.peek() + e.getValue();
 *              if (cost > limit || onPath.contains(v)) continue;     // prune
 *              if (v == destination) {
 *                  List<Integer> found = new ArrayList<>(path);
 *                  found.add(v);
 *                  return found;                                    // pause here
 *              }
 *              path.add(v);
 *              onPath.add(v);
 *              costs.push(cost);
 *              stack.push(neighbours(v));
 *          }
 *          return null;
 *      }
 *
 *      @Override
 *      public boolean hasNext() {
 *          if (buffered == null && !exhausted) {
 *              buffered = advance();
 *              if (buffered == null) exhausted = true;
 *          }
 *          return buffered != null;
 *      }
 *
 *      @Override
 *      public List<Integer> next() {
 *          if (!hasNext()) throw new NoSuchElementException();
 *          List<Integer> result = buffered;
 *          buffered = null;
 *          return result;
 *      }
 *  }
 *
 *  KEY INSIGHTS
 *  ------------
 *  - DFS that tries neighbours in ascending ID order emits paths in exact
 *    lexicographic order, so no sorting of results is needed.
 *  - An explicit stack of neighbour iterators lets the search pause after each
 *    found path and resume exactly where it left off (true laziness).
 *  - hasNext() computes and caches the next path; next() consumes the cache.
 *    This makes repeated hasNext() calls safe.
 *  - Non-negative latencies allow pruning the moment a partial sum exceeds the
 *    limit. Using long for sums avoids int overflow.
 *  - Collapsing parallel edges to their minimum latency reports each router
 *    sequence once, and the cheapest edge is the only one that matters.
 *
 *  WALKTHROUGH (sample, limit 7)
 *  -----------------------------
 *    Start: path [1], cost 0. Neighbours of 1 in order: 2 (2), 3 (4).
 *    Take 2: cost 2, path [1,2]. Neighbours of 2: 3 (1), 4 (7).
 *    Take 3: cost 3, path [1,2,3]. Neighbours of 3: 4 (3).
 *    Take 4: cost 6 <= 7 and 4 is the destination -> emit [1,2,3,4].
 *    Router 3 has no more neighbours: backtrack to [1,2].
 *    Try 4: cost 2 + 7 = 9 > 7 -> pruned. Router 2 is done: backtrack to [1].
 *    Take 3: cost 4, path [1,3]. Neighbours of 3: 4 (3).
 *    Take 4: cost 7 <= 7 and 4 is the destination -> emit [1,3,4].
 *    Every iterator is exhausted: hasNext() returns false.
 *
 *  COMPLEXITY
 *  ----------
 *    Time:  O(E log E) to build sorted adjacency, plus O(P * V) over the whole
 *           iteration, where P is the number of explored partial paths; each
 *           step pushes or pops one frame and each emitted path is copied in O(V).
 *    Space: O(V + E) for the adjacency and O(V) for the stack, path and set,
 *           since the search keeps only the current path, never all results.
 *
 *  COMMON MISTAKES
 *  ---------------
 *  - Iterating neighbours in input order instead of sorted order.
 *  - hasNext() advancing the search every time it is called, which skips paths.
 *  - Continuing the DFS past the destination (any extension would revisit it).
 *  - Forgetting to unmark a router when backtracking, which hides valid paths.
 *  - Summing latencies in int, so a huge sum wraps negative and passes the check.
 *  - Calling graph.get(u) on a sink router missing from the map and hitting null.
 *  - Returning the internal path list instead of a copy, so later mutation
 *    corrupts paths already handed to the caller.
 * ============================================================================
 */