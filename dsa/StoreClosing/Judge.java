package StoreClosing;

import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
STORE CLOSING TIME PENALTY

Platform: LeetCode 2483 variant
Difficulty: MEDIUM
Link: https://leetcode.com/problems/minimum-penalty-for-a-shop/

Background:
Store records hourly customer presence: 'Y' (customers) or 'N' (empty)
Example: "Y Y N Y" = customers at hours 0,1,3; empty at hour 2

Closing time: 0 to n (0=never open, n=always open)

Penalty = +1 for each:
- Open hour with NO customers (N)
- Closed hour WITH customers (Y)

=============================================================================
PART 1: COMPUTE PENALTY
=============================================================================
Input: log (space-separated Y/N), closing_time
Output: penalty value

Example: "Y Y N Y", closing=2
Hours 0-1 (open): Y,Y → 0 penalty
Hour 2 (closed): N → 0 penalty  
Hour 3 (closed): Y → 1 penalty
Total: 1

=============================================================================
PART 2: FIND BEST CLOSING TIME
=============================================================================
Input: log
Output: closing_time with minimum penalty (smallest if tie)

Example: "Y Y N Y"
closing=0: 3, closing=1: 2, closing=2: 1, closing=3: 2, closing=4: 1
Min=1 at closing={2,4} → return 2

=============================================================================
PART 3: AGGREGATE LOGS
=============================================================================
Parse multiple logs with BEGIN/END markers.

Valid log: BEGIN [Y/N tokens] END
Rules:
- Nested BEGIN invalidates ENTIRE sequence → []
- No END = incomplete → []
- Can span multiple lines
- Ignore garbage

Example: "BEGIN Y Y END BEGIN N N END" → [2, 0]
Example: "BEGIN Y BEGIN N END" → [] (nested invalid)
Example: "BEGIN Y Y" → [] (no END)
*/

// ============= SOLUTION CLASS =============
class Solution {
    /**
     * PART 1: Compute penalty for given closing time
     * 
     * @param log         Space-separated string of 'Y'/'N'
     * @param closingTime Hour to close (0 to n)
     * @return Penalty value
     */
    public int computePenalty(String log, int closingTime) {
        String[] logs = log.split(" ");
        // TODO: Implement Part 1
        // Rules:
        // - Split log by spaces to get array of Y/N
        // - For each hour < closingTime: if 'N', penalty++
        // - For each hour >= closingTime: if 'Y', penalty++
        int sum = 0;
        for (int i = 0; i < logs.length; i++) {
            if (i < closingTime) {
                sum += logs[i].equals("N") ? 1 : 0;
            } else {
                sum += logs[i].equals("Y") ? 1 : 0;
            }
        }
        return sum;
    }

    /**
     * PART 2: Find closing time with minimum penalty
     * 
     * @param log Space-separated string of 'Y'/'N'
     * @return Best closing time (smallest if tie)
     */
    public int findBestClosingTime(String log) {
        if (log.length() == 0)
            return 0;
        String[] arr = log.split(" ");
        int cPenalty = 0, minPenalty = 0;
        int idx = 0;
        for (int i = 0; i < arr.length; i++) {
            minPenalty += arr[i].equals("Y") ? 1 : 0;
        }

        cPenalty = minPenalty;
        for (int i = 0; i < arr.length; i++) {
            if (arr[i].equals("N"))
                cPenalty++;
            else
                cPenalty--;

            if (cPenalty < minPenalty) {
                minPenalty = cPenalty;
                idx = i + 1;
            }
        }
        return idx;
    }

    /**
     * PART 3: Parse aggregate logs and find best closing times
     * 
     * @param aggregateLog String containing multiple logs with BEGIN/END markers
     * @return List of best closing times for each valid log
     */
    public List<Integer> getBestClosingTimes(String aggregateLog) {
        aggregateLog = aggregateLog.replaceAll("\n", " ");
        long beginCount = Arrays.stream(aggregateLog.split(" ")).filter(l -> l.equals("BEGIN")).count();
        long endCount = Arrays.stream(aggregateLog.split(" ")).filter(l -> l.equals("END")).count();
        if (endCount == 0 || beginCount == 0)
            return new ArrayList<>();
        String[] logs = aggregateLog.split("END");

        List<String> validLogs = new ArrayList<>();
        for (String log : logs) {
            long count = Arrays.stream(log.split(" ")).filter(l -> l.equals("BEGIN")).count();
            if (count != 1) {
                continue;
            } else {
                validLogs.add(log.split("BEGIN")[1].trim());
            }
        }
        List<Integer> res = new ArrayList<>();

        for (int i = 0; i < validLogs.size(); i++) {
            res.add(findBestClosingTime(validLogs.get(i)));
        }
        return res;
    }
}

// ============= JUDGE CLASS =============
class Judge {
    private static final int PART = 3;
    private static final boolean CHECK_FULL = true;
    private static final int[] SELECTED_TESTS = {};

    private static int passedTests = 0;
    private static int totalTests = 0;
    private static int currentTestNumber = 0;
    private static Set<Integer> selectedTestSet = new HashSet<>();

    public static void main(String[] args) {
        Solution solution = new Solution();

        for (int testNum : SELECTED_TESTS) {
            selectedTestSet.add(testNum);
        }

        System.out.println("=".repeat(70));
        System.out.println("STORE CLOSING TIME PENALTY");
        System.out.println("=".repeat(70));
        System.out.println("Testing: PART " + PART);
        System.out.println("Mode: " + (CHECK_FULL ? "FULL TEST" : "BASIC TEST"));
        if (SELECTED_TESTS.length > 0) {
            System.out.println("Selected: " + Arrays.toString(SELECTED_TESTS));
        }
        System.out.println("=".repeat(70));

        switch (PART) {
            case 1:
                runPart1Tests(solution);
                break;
            case 2:
                runPart2Tests(solution);
                break;
            case 3:
                runPart3Tests(solution);
                break;
        }

        printSummary();
    }

    // ============= PART 1 TESTS =============
    private static void runPart1Tests(Solution solution) {
        System.out.println("\n=== PART 1: COMPUTE PENALTY ===\n");

        testInt(() -> solution.computePenalty("Y Y N Y", 2), 1, "closing=2");
        testInt(() -> solution.computePenalty("N N Y Y", 0), 2, "closing=0, miss 2 Y's");
        testInt(() -> solution.computePenalty("Y Y Y Y", 4), 0, "closing=4, all served");
        testInt(() -> solution.computePenalty("N N N N", 2), 2, "waste 2 N's");
        testInt(() -> solution.computePenalty("Y N Y N", 3), 1, "waste N(1)");

        if (CHECK_FULL) {
            testInt(() -> solution.computePenalty("", 0), 0, "Empty log");
            testInt(() -> solution.computePenalty("Y", 0), 1, "Single Y, closed");
            testInt(() -> solution.computePenalty("Y", 1), 0, "Single Y, open");
            testInt(() -> solution.computePenalty("N N N", 3), 3, "All N wasted");
        }
    }

    // ============= PART 2 TESTS =============
    private static void runPart2Tests(Solution solution) {
        System.out.println("\n=== PART 2: FIND BEST CLOSING TIME ===\n");

        // "Y Y N Y": penalties=[3,2,1,2,1] → min=1 at {2,4} → return 2
        testInt(() -> solution.findBestClosingTime("Y Y N Y"), 2, "Y Y N Y → 2");
        testInt(() -> solution.findBestClosingTime("N N N N"), 0, "All N → 0");
        testInt(() -> solution.findBestClosingTime("Y Y Y Y"), 4, "All Y → 4");

        // "N Y N Y": penalties=[2,3,2,3,2] → min=2 at {0,2,4} → return 0
        testInt(() -> solution.findBestClosingTime("N Y N Y"), 0, "N Y N Y → 0");

        // "Y N Y N": penalties=[2,1,2,1,2] → min=1 at {1,3} → return 1
        testInt(() -> solution.findBestClosingTime("Y N Y N"), 1, "Y N Y N → 1");

        if (CHECK_FULL) {
            testInt(() -> solution.findBestClosingTime(""), 0, "Empty → 0");
            testInt(() -> solution.findBestClosingTime("Y"), 1, "Single Y → 1");
            testInt(() -> solution.findBestClosingTime("N"), 0, "Single N → 0");
            testInt(() -> solution.findBestClosingTime("Y N"), 1, "Y N → 1");
        }
    }

    // ============= PART 3 TESTS =============
    private static void runPart3Tests(Solution solution) {
        System.out.println("\n=== PART 3: AGGREGATE LOGS ===\n");

        // "Y Y N Y" → best=2
        testList(() -> solution.getBestClosingTimes("BEGIN Y Y N Y END"),
                Arrays.asList(2), "Single valid log");

        // "Y Y" → 2, "N N" → 0
        testList(() -> solution.getBestClosingTimes("BEGIN Y Y END garbage BEGIN N N END"),
                Arrays.asList(2, 0), "Two logs with garbage");

        // Nested BEGIN invalidates
        testList(() -> solution.getBestClosingTimes("BEGIN Y BEGIN N END"),
                Arrays.asList(), "Nested BEGIN → []");

        // No END = incomplete
        testList(() -> solution.getBestClosingTimes("BEGIN Y Y"),
                Arrays.asList(), "No END → []");

        // Empty log "" → 0, "Y" → 1
        testList(() -> solution.getBestClosingTimes("BEGIN END BEGIN Y END"),
                Arrays.asList(0, 1), "Empty log + valid");

        if (CHECK_FULL) {
            testList(() -> solution.getBestClosingTimes("BEGIN Y END BEGIN N END BEGIN Y Y END"),
                    Arrays.asList(1, 0, 2), "Three logs");

            testList(() -> solution.getBestClosingTimes("BEGIN\nY Y\nN Y\nEND"),
                    Arrays.asList(2), "Multi-line");

            testList(() -> solution.getBestClosingTimes("garbage text"),
                    Arrays.asList(), "Only garbage");

            // Multiple nested → all invalid
            testList(() -> solution.getBestClosingTimes("BEGIN Y BEGIN N BEGIN Y Y END"),
                    Arrays.asList(), "Multiple nested → []");

            testList(() -> solution.getBestClosingTimes("Y Y END BEGIN N END"),
                    Arrays.asList(0), "END without BEGIN");
        }
    }

    // ============= HELPER METHODS =============
    private static void testInt(TestSupplierInt supplier, int expected, String desc) {
        currentTestNumber++;
        if (SELECTED_TESTS.length > 0 && !selectedTestSet.contains(currentTestNumber))
            return;

        totalTests++;
        try {
            int result = supplier.get();
            if (result == expected) {
                passedTests++;
                System.out.printf("✓ PASS [#%d]: %s%n", currentTestNumber, desc);
            } else {
                System.out.printf("✗ FAIL [#%d]: %s%n", currentTestNumber, desc);
                System.out.println("  Expected: " + expected + ", Got: " + result);
            }
        } catch (Exception e) {
            System.out.printf("✗ ERROR [#%d]: %s - %s%n", currentTestNumber, desc, e.getMessage());
        }
    }

    private static void testList(TestSupplierList supplier, List<Integer> expected, String desc) {
        currentTestNumber++;
        if (SELECTED_TESTS.length > 0 && !selectedTestSet.contains(currentTestNumber))
            return;

        totalTests++;
        try {
            List<Integer> result = supplier.get();
            if (Objects.equals(result, expected)) {
                passedTests++;
                System.out.printf("✓ PASS [#%d]: %s%n", currentTestNumber, desc);
            } else {
                System.out.printf("✗ FAIL [#%d]: %s%n", currentTestNumber, desc);
                System.out.println("  Expected: " + expected + ", Got: " + result);
            }
        } catch (Exception e) {
            System.out.printf("✗ ERROR [#%d]: %s - %s%n", currentTestNumber, desc, e.getMessage());
        }
    }

    private static void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("SUMMARY - PART " + PART);
        System.out.println("=".repeat(70));
        System.out.printf("Passed: %d/%d%n", passedTests, totalTests);
        if (passedTests == totalTests) {
            System.out.println("✓ All tests passed! 🎉");
        }
    }

    @FunctionalInterface
    interface TestSupplierInt {
        int get() throws Exception;
    }

    @FunctionalInterface
    interface TestSupplierList {
        List<Integer> get() throws Exception;
    }
}

// ============= ALGORITHM HINTS =============
/*
 * PART 1 - COMPUTE PENALTY:
 * --------------------------
 * int computePenalty(String log, int closingTime) {
 * if (log.isEmpty()) return 0;
 * String[] hours = log.split(" ");
 * int penalty = 0;
 * 
 * for (int i = 0; i < hours.length; i++) {
 * if (i < closingTime && hours[i].equals("N")) {
 * penalty++; // Open but empty
 * } else if (i >= closingTime && hours[i].equals("Y")) {
 * penalty++; // Closed but had customers
 * }
 * }
 * return penalty;
 * }
 * 
 * PART 2 - FIND BEST (OPTIMIZED O(n)):
 * -------------------------------------
 * int findBestClosingTime(String log) {
 * if (log.isEmpty() || log.trim().isEmpty()) return 0; // ← CRITICAL
 * 
 * String[] hours = log.split(" ");
 * 
 * // Start with closing=0 (all closed)
 * int penalty = 0;
 * for (String h : hours) {
 * if (h.equals("Y")) penalty++;
 * }
 * 
 * int minPenalty = penalty;
 * int bestTime = 0;
 * 
 * for (int i = 0; i < hours.length; i++) {
 * // Open hour i
 * if (hours[i].equals("N")) penalty++; // Waste it
 * else penalty--; // Serve customer
 * 
 * if (penalty < minPenalty) {
 * minPenalty = penalty;
 * bestTime = i + 1;
 * }
 * }
 * return bestTime;
 * }
 * 
 * PART 3 - PARSE LOGS:
 * --------------------
 * List<Integer> getBestClosingTimes(String aggregateLog) {
 * List<Integer> results = new ArrayList<>();
 * StringBuilder currentLog = new StringBuilder();
 * boolean inLog = false;
 * boolean invalidated = false;
 * 
 * String[] tokens = aggregateLog.split("\\s+");
 * 
 * for (String token : tokens) {
 * if (token.equals("BEGIN")) {
 * if (inLog) {
 * invalidated = true; // NESTED! Invalidate entire sequence
 * } else {
 * inLog = true;
 * invalidated = false;
 * currentLog = new StringBuilder();
 * }
 * }
 * else if (token.equals("END")) {
 * if (inLog && !invalidated) {
 * // Valid log - process it
 * int best = findBestClosingTime(currentLog.toString().trim());
 * results.add(best);
 * }
 * // Reset
 * inLog = false;
 * invalidated = false;
 * currentLog = new StringBuilder();
 * }
 * else if (inLog && !invalidated) {
 * if (token.equals("Y") || token.equals("N")) {
 * if (currentLog.length() > 0) currentLog.append(" ");
 * currentLog.append(token);
 * }
 * }
 * }
 * 
 * return results;
 * }
 * 
 * KEY RULES:
 * - Nested BEGIN → invalidated=true → ignore until END
 * - Only process when: inLog && !invalidated && END seen
 * - Empty log (BEGIN END) → findBestClosingTime("") → 0
 * 
 * EXAMPLES:
 * "BEGIN Y BEGIN N END" → invalidated at 2nd BEGIN → []
 * "BEGIN Y BEGIN N BEGIN Y Y END" → invalidated → []
 * "BEGIN END BEGIN Y END" → ["", "Y"] → [0, 1]
 */