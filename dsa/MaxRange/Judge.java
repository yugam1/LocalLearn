package MaxRange;

import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
LARGEST RANGE

Platform: AlgoExpert / Coding Interview
Difficulty: MEDIUM

Problem:
Write a function that takes an array of integers and returns a pair representing 
the LARGEST RANGE of consecutive numbers contained in the array.

Output:
- Array of length 2: [start, end]
- Represents consecutive values {start, start+1, ..., end}
- Example: [2, 6] represents {2, 3, 4, 5, 6} (length 5)

Rules:
- Numbers in input don't need to be sorted
- Numbers don't need to be adjacent in the array
- You may assume exactly ONE largest range exists
- If multiple ranges have same length, any is acceptable

Example:
Input:  [1, 11, 3, 0, 15, 5, 2, 4, 10, 7, 12, 6]

Consecutive sequences:
- {0, 1, 2, 3, 4, 5, 6, 7} → length 8 ✓ LARGEST
- {10, 11, 12} → length 3
- {15} → length 1

Output: [0, 7]

Constraints:
- 1 <= array.length <= 10^5
- -10^9 <= array[i] <= 10^9
- Array may contain duplicates
- Exactly one largest range (no ties in length)
*/

// ============= SOLUTION CLASS =============
class Solution {
    /**
     * Find the largest range of consecutive numbers in the array.
     * 
     * @param array Array of integers
     * @return Array of [start, end] representing the largest consecutive range
     */
    public int[] largestRange(int[] array) {
        System.out.println(Arrays.toString(array));
        return new int[] { 0, 0 };
    }
}

// ============= JUDGE CLASS =============
class Judge {
    private static final boolean CHECK_FULL = false;
    private static final int[] SELECTED_TESTS = { 1 };

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
        System.out.println("LARGEST RANGE");
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

        // Test 1: Sample from problem
        test(
                () -> solution.largestRange(new int[] { 1, 11, 3, 0, 15, 5, 2, 4, 10, 7, 12, 6 }),
                new int[] { 0, 7 },
                "Sample: [1,11,3,0,15,5,2,4,10,7,12,6] → [0,7]");

        // Test 2: Simple consecutive sequence
        test(
                () -> solution.largestRange(new int[] { 4, 2, 1, 3 }),
                new int[] { 1, 4 },
                "Simple: [4,2,1,3] → [1,4]");

        // Test 3: Already sorted
        test(
                () -> solution.largestRange(new int[] { 1, 2, 3, 4, 5 }),
                new int[] { 1, 5 },
                "Sorted: [1,2,3,4,5] → [1,5]");

        // Test 4: Multiple ranges, one largest
        test(
                () -> solution.largestRange(new int[] { 1, 2, 4, 5, 6 }),
                new int[] { 4, 6 },
                "Multiple ranges: [1,2,4,5,6] → [4,6]");

        // Test 5: Single element
        test(
                () -> solution.largestRange(new int[] { 5 }),
                new int[] { 5, 5 },
                "Single element: [5] → [5,5]");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 6: Negative numbers
            test(
                    () -> solution.largestRange(new int[] { -3, -2, -1, 0, 1 }),
                    new int[] { -3, 1 },
                    "Edge: Negative numbers [-3,-2,-1,0,1] → [-3,1]");

            // Test 7: Duplicates
            test(
                    () -> solution.largestRange(new int[] { 1, 1, 2, 3, 3, 4 }),
                    new int[] { 1, 4 },
                    "Edge: Duplicates [1,1,2,3,3,4] → [1,4]");

            // Test 8: Large gaps
            test(
                    () -> solution.largestRange(new int[] { 100, 200, 1, 2, 3 }),
                    new int[] { 1, 3 },
                    "Edge: Large gaps → [1,3]");

            // Test 9: All same number
            test(
                    () -> solution.largestRange(new int[] { 5, 5, 5, 5 }),
                    new int[] { 5, 5 },
                    "Edge: All same → [5,5]");

            // Test 10: Reverse order
            test(
                    () -> solution.largestRange(new int[] { 10, 9, 8, 7, 6 }),
                    new int[] { 6, 10 },
                    "Edge: Reverse order → [6,10]");

            // Test 11: Two elements
            test(
                    () -> solution.largestRange(new int[] { 5, 6 }),
                    new int[] { 5, 6 },
                    "Edge: Two consecutive → [5,6]");

            // Test 12: Two non-consecutive
            test(
                    () -> solution.largestRange(new int[] { 5, 10 }),
                    new int[] { 5, 5 }, // or [10, 10] - both valid
                    "Edge: Two non-consecutive → [5,5]");

            // Test 13: Large consecutive range
            test(
                    () -> solution.largestRange(new int[] { 0, -1, 1, -2, 2, -3, 3, -4, 4, -5, 5 }),
                    new int[] { -5, 5 },
                    "Edge: Range [-5,5] scrambled");
        }
    }

    // ============= HELPER METHODS =============
    private static void test(TestSupplier supplier, int[] expected, String desc) {
        currentTestNumber++;
        if (SELECTED_TESTS.length > 0 && !selectedTestSet.contains(currentTestNumber))
            return;

        totalTests++;
        try {
            int[] result = supplier.get();
            boolean passed = Arrays.equals(result, expected);

            if (passed) {
                passedTests++;
                System.out.printf("✓ PASS [#%d]: %s%n", currentTestNumber, desc);
            } else {
                System.out.printf("✗ FAIL [#%d]: %s%n", currentTestNumber, desc);
                System.out.println("  Expected: " + Arrays.toString(expected));
                System.out.println("  Got:      " + Arrays.toString(result));
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
        int[] get() throws Exception;
    }
}

// ============= ALGORITHM HINTS =============
/*
 * APPROACH 1 - OPTIMAL O(n):
 * ==========================
 * Use HashSet to track numbers and mark visited ranges.
 * 
 * int[] largestRange(int[] array) {
 * if (array.length == 0) return new int[]{0, 0};
 * if (array.length == 1) return new int[]{array[0], array[0]};
 * 
 * // Add all numbers to set
 * Set<Integer> numSet = new HashSet<>();
 * for (int num : array) {
 * numSet.add(num);
 * }
 * 
 * int longestLength = 0;
 * int bestStart = 0;
 * int bestEnd = 0;
 * 
 * // Track visited to avoid recounting
 * Set<Integer> visited = new HashSet<>();
 * 
 * for (int num : array) {
 * if (visited.contains(num)) continue;
 * 
 * // Start of a potential range
 * int currentStart = num;
 * int currentEnd = num;
 * 
 * // Expand left
 * while (numSet.contains(currentStart - 1)) {
 * currentStart--;
 * visited.add(currentStart);
 * }
 * 
 * // Expand right
 * while (numSet.contains(currentEnd + 1)) {
 * currentEnd++;
 * visited.add(currentEnd);
 * }
 * 
 * visited.add(num);
 * 
 * // Check if this is the longest range
 * int currentLength = currentEnd - currentStart + 1;
 * if (currentLength > longestLength) {
 * longestLength = currentLength;
 * bestStart = currentStart;
 * bestEnd = currentEnd;
 * }
 * }
 * 
 * return new int[]{bestStart, bestEnd};
 * }
 * 
 * TIME: O(n) - each number visited at most twice
 * SPACE: O(n) - HashSet storage
 * 
 * APPROACH 2 - OPTIMIZED (NO EXTRA VISITED SET):
 * ===============================================
 * int[] largestRange(int[] array) {
 * Set<Integer> numSet = new HashSet<>();
 * for (int num : array) numSet.add(num);
 * 
 * int longestLength = 0;
 * int bestStart = 0, bestEnd = 0;
 * 
 * for (int num : array) {
 * // Skip if this number is not the start of a sequence
 * if (numSet.contains(num - 1)) continue;
 * 
 * // num is the start of a potential sequence
 * int currentEnd = num;
 * while (numSet.contains(currentEnd + 1)) {
 * currentEnd++;
 * }
 * 
 * int length = currentEnd - num + 1;
 * if (length > longestLength) {
 * longestLength = length;
 * bestStart = num;
 * bestEnd = currentEnd;
 * }
 * }
 * 
 * return new int[]{bestStart, bestEnd};
 * }
 * 
 * KEY INSIGHT: Only start counting from the beginning of a sequence!
 * If num-1 exists, skip num (it will be counted when we process num-1)
 * 
 * APPROACH 3 - SORTING O(n log n):
 * =================================
 * int[] largestRange(int[] array) {
 * if (array.length == 1) return new int[]{array[0], array[0]};
 * 
 * Arrays.sort(array);
 * 
 * int longestLength = 1;
 * int bestStart = array[0], bestEnd = array[0];
 * 
 * int currentStart = array[0];
 * int currentLength = 1;
 * 
 * for (int i = 1; i < array.length; i++) {
 * if (array[i] == array[i-1]) {
 * // Duplicate, skip
 * continue;
 * }
 * else if (array[i] == array[i-1] + 1) {
 * // Consecutive
 * currentLength++;
 * }
 * else {
 * // Gap found, check if current range is longest
 * if (currentLength > longestLength) {
 * longestLength = currentLength;
 * bestStart = currentStart;
 * bestEnd = array[i-1];
 * }
 * // Start new range
 * currentStart = array[i];
 * currentLength = 1;
 * }
 * }
 * 
 * // Check last range
 * if (currentLength > longestLength) {
 * bestStart = currentStart;
 * bestEnd = array[array.length - 1];
 * }
 * 
 * return new int[]{bestStart, bestEnd};
 * }
 * 
 * TIME: O(n log n) - sorting
 * SPACE: O(1) or O(n) depending on sort
 * 
 * EXAMPLE WALKTHROUGH (APPROACH 2):
 * ==================================
 * Input: [1, 11, 3, 0, 15, 5, 2, 4, 10, 7, 12, 6]
 * 
 * Step 1: Add to HashSet
 * numSet = {0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 12, 15}
 * 
 * Step 2: Process each number
 * - num=1: numSet.contains(0)? YES → skip (0 is start)
 * - num=11: numSet.contains(10)? YES → skip (10 is start)
 * - num=3: numSet.contains(2)? YES → skip
 * - num=0: numSet.contains(-1)? NO → START OF SEQUENCE!
 * Expand right: 0,1,2,3,4,5,6,7
 * Length = 8
 * Update: bestStart=0, bestEnd=7
 * - num=15: numSet.contains(14)? NO → START
 * Expand right: 15
 * Length = 1 (not longer than 8)
 * - num=5: numSet.contains(4)? YES → skip
 * - ... (continue, all others skipped)
 * - num=10: numSet.contains(9)? NO → START
 * Expand right: 10,11,12
 * Length = 3 (not longer than 8)
 * 
 * Result: [0, 7]
 * 
 * EDGE CASES:
 * -----------
 * 1. Single element → [num, num]
 * 2. All consecutive → [min, max]
 * 3. No consecutive pairs → [any element, same element]
 * 4. Duplicates → handle properly
 * 5. Negative numbers → works same way
 * 6. Large gaps between numbers
 * 7. Reverse sorted array
 * 
 * TIME COMPLEXITY: O(n) with HashSet approach
 * SPACE COMPLEXITY: O(n) for HashSet
 */