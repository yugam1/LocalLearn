package MaxSecurity;

import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
SMART CITIES SECURITY UNITS (Amazon HackerRank)

Platform: Amazon OA / HackerRank
Difficulty: MEDIUM

Problem:
In Amazon's Smart Cities Management System, each city has a population and 
some cities are equipped with security units.

Given:
- population[i]: number of inhabitants in city i (1-indexed)
- unit: binary string where unit[i]='1' means city i has a security unit

Relocation Rule:
- A security unit at city i (where i > 1) can be moved one step LEFT to city i-1
- Each unit can be moved AT MOST ONCE
- If moved, city i loses its unit and city i-1 gains one
- City 1's security unit CANNOT be moved further left

Protection:
A city is protected if it has a security unit AFTER all relocations.

Task:
Determine the MAXIMUM population that can be protected by optimally relocating 
the security units.

Note: Uses 1-based indexing for cities.

Example:
n = 6
population = [20, 10, 9, 30, 20, 19]
unit = "011011"

Cities with units initially: 2, 3, 5, 6 (1-indexed)

Relocation Table:
┌─────────┬──────────┬──────────┬───────────────┐
│ Moved   │ Previous │ New      │ Safe          │
│ Index   │ Config   │ Config   │ Inhabitants   │
├─────────┼──────────┼──────────┼───────────────┤
│ 2       │ 011011   │ 101011   │ 20+9+20+19=68 │
│ 3       │ 101011   │ 110011   │ 20+10+20+19=69│
│ 5       │ 110011   │ 110101   │ 20+10+30+19=79│
│ 6       │ 110101   │ 110110   │ 20+10+30+20=80│
└─────────┴──────────┴──────────┴───────────────┘

Maximum: 80

Sample Input:
population = [10, 5, 8, 9, 6]
unit = "01101"

Sample Output:
80

Constraints:
- 1 <= n <= 10^5
- 1 <= population[i] <= 10^4
- unit is a binary string of length n consisting of '0's and '1's
- 1-based indexing
*/

// ============= SOLUTION CLASS =============
class Solution {
    /**
     * Find maximum population that can be protected by optimal unit relocation.
     * 
     * @param population List of populations for each city (1-indexed)
     * @param unit       Binary string indicating initial unit placement
     * @return Maximum protected population
     */
    public int moveUnits(List<Integer> population, String unit) {
        // TODO: Implement your solution here

        // Approach:
        // 1. Greedy: Move units to cities with higher populations
        // 2. For each city with a unit (from right to left):
        // - Check if moving left increases protected population
        // - If city i-1 has higher population and no unit, consider moving
        // 3. Or use DP to try all possible move combinations
        int n = population.size();
        boolean[] hasUnit = new boolean[n];
        for (int i = 0; i < n; i++) {
            if (unit.charAt(i) == '1') {
                if (i > 0 && !hasUnit[i - 1] && population.get(i - 1) > population.get(i)) {
                    hasUnit[i - 1] = true;
                } else {
                    hasUnit[i] = true;
                }
            }
        }

        int res = 0;
        for (int i = 0; i < n; i++) {
            if (hasUnit[i])
                res += population.get(i);
        }
        return res;
    }
}

// ============= JUDGE CLASS =============
class Judge {
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
        System.out.println("SMART CITIES SECURITY UNITS (Amazon)");
        System.out.println("Difficulty: MEDIUM");
        System.out.println("=".repeat(70));
        System.out.println("Mode: " + (CHECK_FULL ? "FULL TEST" : "BASIC TEST"));
        if (SELECTED_TESTS.length > 0) {
            System.out.println("Selected: " + Arrays.toString(SELECTED_TESTS));
        }
        System.out.println("=".repeat(70));

        runTests(solution);
        printSummary();
    }

    // ============= TESTS =============
    private static void runTests(Solution solution) {
        System.out.println("\n--- Basic Tests ---");

        // Test 1: Example from problem description
        test(
                () -> solution.moveUnits(
                        Arrays.asList(20, 10, 9, 30, 20, 19),
                        "011011"),
                80,
                "Sample 0: Optimal relocations → 80");

        // Test 2: No units to move
        test(
                () -> solution.moveUnits(
                        Arrays.asList(10, 20, 30),
                        "100"),
                10,
                "No benefit from moving → 10");

        // Test 3: Move all units left
        test(
                () -> solution.moveUnits(
                        Arrays.asList(100, 50, 20, 10),
                        "0111"),
                170,
                "Move all to higher populations → 170");

        // Test 4: No units
        test(
                () -> solution.moveUnits(
                        Arrays.asList(10, 20, 30),
                        "000"),
                0,
                "No units → 0");

        // Test 5: All cities have units
        test(
                () -> solution.moveUnits(
                        Arrays.asList(10, 20, 30, 40),
                        "1111"),
                100,
                "All have units, no moves → 100");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 6: Single city
            test(
                    () -> solution.moveUnits(
                            Arrays.asList(50),
                            "1"),
                    50,
                    "Edge: Single city with unit → 50");

            // Test 7: Single city no unit
            test(
                    () -> solution.moveUnits(
                            Arrays.asList(50),
                            "0"),
                    0,
                    "Edge: Single city no unit → 0");

            // Test 8: Decreasing populations
            test(
                    () -> solution.moveUnits(
                            Arrays.asList(100, 80, 60, 40, 20),
                            "00111"),
                    180,
                    "Edge: Move to higher pops (left) → 240");

            // Test 9: Increasing populations
            test(
                    () -> solution.moveUnits(
                            Arrays.asList(10, 20, 30, 40, 50),
                            "11100"),
                    60,
                    "Edge: Units at low pops, can't move right → 60");
        }
    }

    // ============= HELPER METHODS =============
    private static void test(TestSupplier supplier, int expected, String desc) {
        currentTestNumber++;
        if (SELECTED_TESTS.length > 0 && !selectedTestSet.contains(currentTestNumber))
            return;

        totalTests++;
        try {
            int result = supplier.get();
            boolean passed = result == expected;

            if (passed) {
                passedTests++;
                System.out.printf("✓ PASS [#%d]: %s%n", currentTestNumber, desc);
            } else {
                System.out.printf("✗ FAIL [#%d]: %s%n", currentTestNumber, desc);
                System.out.println("  Expected: " + expected + ", Got: " + result);
            }
        } catch (Exception e) {
            System.out.printf("✗ ERROR [#%d]: %s - %s%n", currentTestNumber, desc, e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("TEST SUMMARY");
        System.out.println("=".repeat(70));
        System.out.printf("Passed: %d/%d tests%n", passedTests, totalTests);

        if (passedTests == totalTests) {
            System.out.println("✓ All tests passed! 🎉");
        } else {
            System.out.printf("✗ %d test(s) failed%n", totalTests - passedTests);
        }

        if (!CHECK_FULL) {
            System.out.println("\nℹ Set CHECK_FULL = true for edge cases");
        }
        if (SELECTED_TESTS.length == 0) {
            System.out.println("ℹ Set SELECTED_TESTS = new int[]{1,2,3} to run specific tests");
        }
    }

    @FunctionalInterface
    interface TestSupplier {
        int get() throws Exception;
    }
}

// ============= ALGORITHM HINTS =============
/*
 * GREEDY APPROACH - O(n):
 * =======================
 * 
 * Key Insight:
 * Move units from lower population cities to higher population cities (when
 * possible).
 * 
 * Algorithm:
 * 1. Convert unit string to boolean array (1-indexed for clarity)
 * 2. Scan from RIGHT to LEFT (cities n down to 2):
 * - If city i has a unit AND city i-1 has higher population and no unit:
 * Move unit from i to i-1
 * Update configuration
 * 3. Calculate total protected population
 * 
 * int moveUnits(List<Integer> population, String unit) {
 * int n = population.size();
 * boolean[] hasUnit = new boolean[n + 1]; // 1-indexed
 * 
 * // Parse unit string (0-indexed in string, 1-indexed in cities)
 * for (int i = 0; i < n; i++) {
 * hasUnit[i + 1] = unit.charAt(i) == '1';
 * }
 * 
 * // Greedy: move units to higher population cities
 * for (int i = n; i >= 2; i--) {
 * if (hasUnit[i] && !hasUnit[i - 1]) {
 * // Check if moving benefits us
 * if (population.get(i - 1) > population.get(i)) {
 * hasUnit[i] = false;
 * hasUnit[i - 1] = true;
 * }
 * }
 * }
 * 
 * // Calculate protected population
 * int total = 0;
 * for (int i = 1; i <= n; i++) {
 * if (hasUnit[i]) {
 * total += population.get(i - 1); // population is 0-indexed!
 * }
 * }
 * 
 * return total;
 * }
 * 
 * EXAMPLE WALKTHROUGH:
 * ====================
 * population = [20, 10, 9, 30, 20, 19] (0-indexed)
 * unit = "011011"
 * 
 * Initial configuration (1-indexed):
 * City 1: pop=20, unit=0
 * City 2: pop=10, unit=1
 * City 3: pop=9, unit=1
 * City 4: pop=30, unit=0
 * City 5: pop=20, unit=1
 * City 6: pop=19, unit=1
 * 
 * Process from right to left:
 * i=6: hasUnit[6]=true, hasUnit[5]=true → can't move (5 already has unit)
 * i=5: hasUnit[5]=true, hasUnit[4]=false, pop[4]=30 > pop[5]=20 → MOVE!
 * → Config: "011101"
 * 
 * i=4: hasUnit[4]=true, hasUnit[3]=false, pop[3]=9 < pop[4]=30 → DON'T MOVE
 * 
 * i=3: hasUnit[3]=true, hasUnit[2]=false, pop[2]=10 > pop[3]=9 → MOVE!
 * → Config: "110101"
 * 
 * i=2: hasUnit[2]=true, hasUnit[1]=false, pop[1]=20 > pop[2]=10 → MOVE!
 * → Config: "100101"
 * 
 * Final: "100101"
 * Protected cities: 1, 4, 6
 * Total: 20 + 30 + 19 = 69
 * 
 * Wait, that's not 80. Let me reconsider...
 * 
 * Actually, looking at the table in the image, they're showing different move
 * sequences.
 * 
 * The issue is that this is not just greedy - we need to consider ALL possible
 * moves
 * and find the optimal combination. This might require dynamic programming or
 * trying different sequences.
 * 
 * ALTERNATIVE APPROACH - Try All Sequences:
 * Since each unit can move at most once, and we have limited units, we could:
 * 1. Use BFS/DFS to try different move sequences
 * 2. Track visited configurations
 * 3. Find maximum protected population
 * 
 * This is more complex than simple greedy!
 * 
 * CORRECT APPROACH:
 * The problem likely requires trying different orderings of moves to find
 * optimal.
 * Looking at the expected output of 80 vs user's 68, suggests greedy might not
 * always work.
 * 
 * TIME COMPLEXITY: O(n × 2^k) where k = number of units (worst case)
 * Or O(n²) with better pruning
 */