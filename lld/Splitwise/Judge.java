import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
465. Optimal Account Balancing (Hard)

You are given an array of transactions where transactions[i] = [fromi, toi, amounti] 
indicates that the person with ID = fromi gave amounti $ to the person with ID = toi.

Return the minimum number of transactions required to settle the debt.

Constraints:
- 1 <= transactions.length <= 8
- transactions[i].length == 3
- 0 <= fromi, toi < 12
- fromi != toi
- 1 <= amounti <= 100
*/

// ============= SOLUTION CLASS =============
class Solution {
    /**
     * Returns the minimum number of transactions required to settle all debts.
     * 
     * @param transactions Array where transactions[i] = [from, to, amount]
     *                     meaning person 'from' gave 'amount' to person 'to'
     * @return Minimum number of transactions needed
     */
    public int minTransfers(int[][] transactions) {
        Map<Integer, Integer> balances = new HashMap<>();
        for (var trans : transactions) {
            balances.put(trans[0], balances.getOrDefault(trans[0], 0) - trans[2]);
            balances.put(trans[1], balances.getOrDefault(trans[1], 0) + trans[2]);
        }

        List<Integer> allPartyDue = new ArrayList<>();
        for (var e : balances.entrySet()) {
            if (e.getValue() != 0) {
                allPartyDue.add(e.getValue());
            }
        }
        System.out.println(allPartyDue.toString());
        return backtrackingTransfers(0, allPartyDue);
    }

    int backtrackingTransfers(int start, List<Integer> dues) {
        while (start < dues.size() && dues.get(start) == 0)
            start++;
        if (start == dues.size())
            return 0;

        int transfers = Integer.MAX_VALUE;

        for (int i = start + 1; i < dues.size(); i++) {
            if (dues.get(start) * dues.get(i) < 0) {
                int curr = dues.get(i);
                dues.set(i, curr + dues.get(start));
                transfers = Math.min(transfers, 1 + backtrackingTransfers(start + 1, dues));
                dues.set(i, curr);
                if (curr + dues.get(start) == 0)
                    break;
            }
        }

        return transfers == Integer.MAX_VALUE ? 0 : transfers;
    }
}

// ============= JUDGE CLASS =============
class Judge {
    private static final boolean CHECK_FULL = true; // Set to true for comprehensive testing

    private static int passedTests = 0;
    private static int totalTests = 0;

    public static void main(String[] args) {
        Solution solution = new Solution();

        System.out.println("=".repeat(60));
        System.out.println("465. OPTIMAL ACCOUNT BALANCING");
        System.out.println("Difficulty: HARD");
        System.out.println("Mode: " + (CHECK_FULL ? "FULL TEST (Edge Cases + Performance)" : "BASIC TEST"));
        System.out.println("=".repeat(60));

        // Run basic tests
        runBasicTests(solution);

        // Run full tests if flag is enabled
        if (CHECK_FULL) {
            System.out.println("\n" + "=".repeat(60));
            System.out.println("RUNNING FULL TEST SUITE");
            System.out.println("=".repeat(60));
            runEdgeCaseTests(solution);
            runPerformanceTests(solution);
        }

        // Print summary
        printSummary();
    }

    // ============= BASIC TESTS =============
    private static void runBasicTests(Solution solution) {
        System.out.println("\n--- Basic Tests (From Problem Description) ---");

        // Test Case 1: [[0,1,10],[2,0,5]]
        // Person 0 gave 10 to person 1 and received 5 from person 2
        // Person 1 received 10 from person 0
        // Person 2 gave 5 to person 0
        // Net: person 0: -10+5=-5, person 1: +10, person 2: -5
        // Solution: person 1 gives 5 to person 0 and person 1 gives 5 to person 2 (2
        // transactions)
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 10 }, { 2, 0, 5 } }),
                2,
                "Example 1: [[0,1,10],[2,0,5]]");

        // Test Case 2: [[0,1,10],[1,0,1],[1,2,5],[2,0,5]]
        // Net: person 0: -10+1+5=-4, person 1: +10-1-5=+4, person 2: +5-5=0
        // Solution: person 1 gives 4 to person 0 (1 transaction)
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 10 }, { 1, 0, 1 }, { 1, 2,
                        5 }, { 2, 0, 5 } }),
                1,
                "Example 2: [[0,1,10],[1,0,1],[1,2,5],[2,0,5]]");
    }

    // ============= EDGE CASE TESTS =============
    private static void runEdgeCaseTests(Solution solution) {
        System.out.println("\n--- Edge Case Tests ---");

        // Single transaction (already minimal)
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 10 } }),
                1,
                "Edge: Single transaction");

        // All already balanced (person 0 gives to 1, person 2 gives to 0)
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 10 }, { 1, 2, 10 }, { 2, 0, 10 } }),
                0,
                "Edge: Circular transactions (all balanced)");

        // Three people, simple case
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 5 }, { 0, 2, 5 } }),
                2,
                "Edge: One person owes to two people");

        // Complex case with 4 people
        // Person 0 -> 1: 10, Person 2 -> 1: 5, Person 3 -> 0: 5
        // Net: 0: -10+5=-5, 1: +10+5=+15, 2: -5, 3: -5
        // Optimal: 1->0: 5, 1->2: 5, 1->3: 5 (3 transactions)
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 10 }, { 2, 1, 5 }, { 3, 0, 5 } }),
                3,
                "Edge: 4 people complex debt");

        // Multiple transactions between same pair
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 5 }, { 0, 1, 5 }, { 1, 0, 3 } }),
                1,
                "Edge: Multiple transactions same pair");

        // Large amounts
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 100 }, { 2, 0, 100 } }),
                1,
                "Edge: Large amounts");

        // Five people scenario
        // 0->1: 1, 0->2: 1, 0->3: 1, 0->4: 1
        // Net: 0: -4, 1: +1, 2: +1, 3: +1, 4: +1
        // Optimal: 4 people each give 1 to person 0 (4 transactions)
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 1 }, { 0, 2, 1 }, { 0, 3, 1 }, { 0, 4, 1 } }),
                4,
                "Edge: One person owes multiple people");

        // Tricky case: can be optimized
        // 0->1: 10, 1->2: 10, 2->3: 10
        // Net: 0: -10, 1: +10-10=0, 2: +10-10=0, 3: +10
        // Optimal: 0->3: 10 (1 transaction)
        test(
                () -> solution.minTransfers(new int[][] { { 0, 1, 10 }, { 1, 2, 10 }, { 2, 3, 10 } }),
                1,
                "Edge: Chain transactions (can be optimized)");
    }

    // ============= PERFORMANCE TESTS =============
    private static void runPerformanceTests(Solution solution) {
        System.out.println("\n--- Performance Tests ---");

        // Test 1: Maximum people (12) with complex debts
        int[][] maxPeopleTest = {
                { 0, 1, 10 }, { 1, 2, 10 }, { 2, 3, 10 }, { 3, 4, 10 }, { 4, 5, 10 },
                { 5, 6, 10 }, { 6, 7, 10 }, { 7, 8, 10 }, { 8, 9, 10 }, { 9, 10, 10 },
                { 10, 11, 10 }, { 11, 0, 10 }
        };

        long startTime = System.nanoTime();
        int result1 = solution.minTransfers(maxPeopleTest);
        long endTime = System.nanoTime();
        long duration1 = (endTime - startTime) / 1_000_000;

        System.out.printf("✓ Max people (12) circular: %d ms (result: %d transactions)%n",
                duration1, result1);

        // Test 2: Maximum transactions (8) with worst case
        int[][] maxTransactions = {
                { 0, 1, 10 }, { 0, 2, 10 }, { 0, 3, 10 }, { 0, 4, 10 },
                { 5, 0, 10 }, { 6, 0, 10 }, { 7, 0, 10 }, { 8, 0, 10 }
        };

        startTime = System.nanoTime();
        int result2 = solution.minTransfers(maxTransactions);
        endTime = System.nanoTime();
        long duration2 = (endTime - startTime) / 1_000_000;

        System.out.printf("✓ Max transactions (8): %d ms (result: %d transactions)%n",
                duration2, result2);

        // Performance evaluation
        long maxDuration = Math.max(duration1, duration2);
        if (maxDuration > 1000) {
            System.out.println("  ⚠ WARNING: Solution may be too slow");
        } else if (maxDuration > 100) {
            System.out.println("  ⚠ Performance could be optimized");
        } else {
            System.out.println("  ✓ Performance excellent");
        }

        // Test 3: Stress test with multiple runs
        System.out.println("\n--- Stress Test (100 iterations) ---");
        startTime = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            solution.minTransfers(new int[][] { { 0, 1, 10 }, { 2, 0, 5 } });
        }
        endTime = System.nanoTime();
        long avgDuration = (endTime - startTime) / 100_000_000;

        System.out.printf("✓ Average time per call: %d ms%n", avgDuration);
    }

    // ============= HELPER METHODS =============
    private static <T> void test(TestSupplier<T> supplier, T expected, String description) {
        totalTests++;
        try {
            T result = supplier.get();
            boolean passed = Objects.deepEquals(result, expected);

            if (passed) {
                passedTests++;
                System.out.println("✓ PASS: " + description);
            } else {
                System.out.println("✗ FAIL: " + description);
                System.out.println("  Expected: " + formatOutput(expected));
                System.out.println("  Got:      " + formatOutput(result));
            }
        } catch (Exception e) {
            System.out.println("✗ ERROR: " + description);
            System.out.println("  Exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static String formatOutput(Object obj) {
        if (obj instanceof int[]) {
            return Arrays.toString((int[]) obj);
        } else if (obj instanceof int[][]) {
            return Arrays.deepToString((int[][]) obj);
        } else if (obj instanceof String[]) {
            return Arrays.toString((String[]) obj);
        } else if (obj instanceof List) {
            return obj.toString();
        }
        return String.valueOf(obj);
    }

    private static void printSummary() {
        System.out.println("\n" + "=".repeat(60));
        System.out.println("TEST SUMMARY");
        System.out.println("=".repeat(60));
        System.out.printf("Passed: %d/%d tests%n", passedTests, totalTests);

        if (passedTests == totalTests) {
            System.out.println("✓ All tests passed! 🎉");
        } else {
            System.out.printf("✗ %d test(s) failed%n", totalTests - passedTests);
        }

        if (!CHECK_FULL) {
            System.out.println("\nℹ Set CHECK_FULL = true for comprehensive testing");
        }
    }

    @FunctionalInterface
    interface TestSupplier<T> {
        T get() throws Exception;
    }
}

// ============= ALGORITHM HINTS =============
/*
 * APPROACH:
 * 1. Calculate net balance for each person:
 * - If person gives money: balance decreases
 * - If person receives money: balance increases
 * 
 * 2. Filter out people with zero balance (already settled)
 * 
 * 3. Use backtracking to find minimum transactions:
 * - Try to settle debts between people with opposite sign balances
 * - Recursively find the minimum transactions needed
 * 
 * EXAMPLE WALKTHROUGH (Test Case 1):
 * transactions = [[0,1,10],[2,0,5]]
 * 
 * Step 1: Calculate balances
 * - Person 0: gave 10 to 1, received 5 from 2 → balance = -10 + 5 = -5
 * - Person 1: received 10 from 0 → balance = +10
 * - Person 2: gave 5 to 0 → balance = -5
 * 
 * Step 2: Non-zero balances = [-5, +10, -5]
 * 
 * Step 3: Settle debts (minimum 2 transactions):
 * - Transaction 1: Person 1 gives 5 to Person 0 → balances: [0, +5, -5]
 * - Transaction 2: Person 1 gives 5 to Person 2 → balances: [0, 0, 0]
 * 
 * TIME COMPLEXITY: O(n!) in worst case due to backtracking
 * SPACE COMPLEXITY: O(n) for storing balances and recursion stack
 * 
 * OPTIMIZATION TIPS:
 * - Prune branches early if current solution exceeds best found
 * - Match positive and negative balances greedily when possible
 * - Sort balances to try larger amounts first
 */