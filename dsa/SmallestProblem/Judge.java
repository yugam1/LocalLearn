import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
SMALLEST DIFFERENCE

Platform: AlgoExpert / Coding Interview
Difficulty: MEDIUM

Problem:
Write a function that takes two non-empty arrays of integers and finds the pair 
of numbers (one from each array) whose absolute difference is closest to zero.

Return an array containing these two numbers, with the number from the first 
array in the first position.

You can assume there will only be ONE pair with the smallest difference.

Example:
arrayOne = [-1, 5, 10, 20, 28, 3]
arrayTwo = [26, 134, 135, 15, 17]

All pairs and their differences:
- |28 - 26| = 2  ← MINIMUM
- |20 - 17| = 3
- |10 - 15| = 5
- |20 - 15| = 5
- ...

Output: [28, 26]

Constraints:
- 1 <= arrayOne.length, arrayTwo.length <= 10^5
- -10^9 <= arrayOne[i], arrayTwo[i] <= 10^9
- Both arrays are non-empty
- Exactly one pair with minimum difference
*/

// ============= SOLUTION CLASS =============
class Solution {
    /**
     * Find the pair with smallest absolute difference.
     * 
     * @param arrayOne First array of integers
     * @param arrayTwo Second array of integers
     * @return [num from arrayOne, num from arrayTwo] with smallest difference
     */
    public int[] smallestDifference(int[] arrayOne, int[] arrayTwo) {
        // TODO: Implement your solution here

        // APPROACH 1 - Brute Force O(n*m):
        // - Try all pairs
        // - Track minimum difference and corresponding pair

        // APPROACH 2 - Optimal O(n log n + m log m):
        // - Sort both arrays
        // - Use two pointers
        // - Move pointer with smaller value to reduce gap

        return new int[] { 0, 0 };
    }
}

// ============= JUDGE CLASS =============
class Judge {
    private static final boolean CHECK_FULL = false;
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
        System.out.println("SMALLEST DIFFERENCE");
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
                () -> solution.smallestDifference(
                        new int[] { -1, 5, 10, 20, 28, 3 },
                        new int[] { 26, 134, 135, 15, 17 }),
                new int[] { 28, 26 },
                "Sample: [28, 26] with diff = 2");

        // Test 2: Simple case
        test(
                () -> solution.smallestDifference(
                        new int[] { 1, 2, 3 },
                        new int[] { 5, 6, 7 }),
                new int[] { 3, 5 },
                "Simple: [3, 5] with diff = 2");

        // Test 3: Exact match (diff = 0)
        test(
                () -> solution.smallestDifference(
                        new int[] { 1, 5, 10 },
                        new int[] { 10, 20, 30 }),
                new int[] { 10, 10 },
                "Exact match: [10, 10] with diff = 0");

        // Test 4: Negative numbers
        test(
                () -> solution.smallestDifference(
                        new int[] { -5, -2, 0, 3 },
                        new int[] { -3, 1, 4 }),
                new int[] { -2, -3 },
                "Negative: [-2, -3] with diff = 1");

        // Test 5: Single element arrays
        test(
                () -> solution.smallestDifference(
                        new int[] { 10 },
                        new int[] { 20 }),
                new int[] { 10, 20 },
                "Single elements: [10, 20] with diff = 10");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 6: Large arrays
            test(
                    () -> solution.smallestDifference(
                            new int[] { 1, 100, 200, 300, 400, 500 },
                            new int[] { 99, 101, 199, 201 }),
                    new int[] { 100, 99 },
                    "Edge: [100, 99] with diff = 1");

            // Test 7: All negative
            test(
                    () -> solution.smallestDifference(
                            new int[] { -10, -5, -1 },
                            new int[] { -8, -3, -2 }),
                    new int[] { -5, -3 },
                    "Edge: All negative [-5, -3] with diff = 2");

            // Test 8: Mixed positive and negative
            test(
                    () -> solution.smallestDifference(
                            new int[] { -10, 0, 10 },
                            new int[] { -11, -1, 9 }),
                    new int[] { -10, -11 },
                    "Edge: Mixed [-10, -11] with diff = 1");

            // Test 9: Unsorted arrays
            test(
                    () -> solution.smallestDifference(
                            new int[] { 100, 10, 50, 30 },
                            new int[] { 5, 45, 25, 35 }),
                    new int[] { 30, 25 },
                    "Edge: Unsorted [30, 25] with diff = 5");

            // Test 10: Large difference everywhere except one pair
            test(
                    () -> solution.smallestDifference(
                            new int[] { 1000, 2000, 3000, 42 },
                            new int[] { 5000, 6000, 41 }),
                    new int[] { 42, 41 },
                    "Edge: [42, 41] with diff = 1");

            // Test 11: Same number appears in both arrays
            test(
                    () -> solution.smallestDifference(
                            new int[] { 5, 10, 15, 20 },
                            new int[] { 15, 25, 30 }),
                    new int[] { 15, 15 },
                    "Edge: Same number [15, 15] with diff = 0");
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
 * APPROACH 1 - BRUTE FORCE O(n × m):
 * ===================================
 * int[] smallestDifference(int[] arrayOne, int[] arrayTwo) {
 * int minDiff = Integer.MAX_VALUE;
 * int[] result = new int[2];
 * 
 * for (int num1 : arrayOne) {
 * for (int num2 : arrayTwo) {
 * int diff = Math.abs(num1 - num2);
 * if (diff < minDiff) {
 * minDiff = diff;
 * result[0] = num1;
 * result[1] = num2;
 * }
 * }
 * }
 * 
 * return result;
 * }
 * 
 * TIME: O(n × m)
 * SPACE: O(1)
 * 
 * APPROACH 2 - OPTIMAL (TWO POINTERS) O(n log n + m log m):
 * ==========================================================
 * int[] smallestDifference(int[] arrayOne, int[] arrayTwo) {
 * // Sort both arrays
 * Arrays.sort(arrayOne);
 * Arrays.sort(arrayTwo);
 * 
 * int i = 0, j = 0;
 * int minDiff = Integer.MAX_VALUE;
 * int[] result = new int[2];
 * 
 * // Two pointer approach
 * while (i < arrayOne.length && j < arrayTwo.length) {
 * int num1 = arrayOne[i];
 * int num2 = arrayTwo[j];
 * int diff = Math.abs(num1 - num2);
 * 
 * if (diff < minDiff) {
 * minDiff = diff;
 * result[0] = num1;
 * result[1] = num2;
 * }
 * 
 * // Move pointer with smaller value to potentially reduce gap
 * if (num1 < num2) {
 * i++;
 * } else if (num1 > num2) {
 * j++;
 * } else {
 * // Exact match (diff = 0), can't do better
 * return result;
 * }
 * }
 * 
 * return result;
 * }
 * 
 * TIME: O(n log n + m log m) - sorting dominates
 * SPACE: O(1) if sorting in-place, O(n + m) if using extra space
 * 
 * KEY INSIGHT - Two Pointers:
 * ---------------------------
 * After sorting, use two pointers starting at the beginning of each array.
 * 
 * If num1 < num2:
 * - To reduce difference, need to increase num1
 * - Move pointer i forward in arrayOne
 * 
 * If num1 > num2:
 * - To reduce difference, need to increase num2
 * - Move pointer j forward in arrayTwo
 * 
 * If num1 == num2:
 * - Perfect match (diff = 0)
 * - Can't do better, return immediately
 * 
 * EXAMPLE WALKTHROUGH (TWO POINTERS):
 * ====================================
 * arrayOne = [-1, 5, 10, 20, 28, 3]
 * arrayTwo = [26, 134, 135, 15, 17]
 * 
 * Step 1: Sort
 * arrayOne = [-1, 3, 5, 10, 20, 28]
 * arrayTwo = [15, 17, 26, 134, 135]
 * 
 * Step 2: Two pointers
 * i=0, j=0: num1=-1, num2=15, diff=16, -1<15 → i++
 * i=1, j=0: num1=3, num2=15, diff=12, 3<15 → i++
 * i=2, j=0: num1=5, num2=15, diff=10, 5<15 → i++
 * i=3, j=0: num1=10, num2=15, diff=5, 10<15 → i++
 * i=4, j=0: num1=20, num2=15, diff=5, 20>15 → j++
 * i=4, j=1: num1=20, num2=17, diff=3, 20>17 → j++
 * i=4, j=2: num1=20, num2=26, diff=6, 20<26 → i++
 * i=5, j=2: num1=28, num2=26, diff=2, 28>26 → j++ (new minimum!)
 * i=5, j=3: num1=28, num2=134, diff=106, 28<134 → i++
 * i=6: out of bounds, stop
 * 
 * Best pair: [28, 26] with diff = 2
 * 
 * EDGE CASES:
 * -----------
 * 1. Single element in each array
 * 2. Exact match exists (diff = 0)
 * 3. All negative numbers
 * 4. Mixed positive and negative
 * 5. Large arrays
 * 6. Already sorted arrays
 * 7. Reverse sorted arrays
 * 8. Same number in both arrays
 * 9. Large differences everywhere except one pair
 * 
 * TIME COMPLEXITY:
 * ----------------
 * Brute Force: O(n × m)
 * Optimal: O(n log n + m log m)
 * 
 * SPACE COMPLEXITY:
 * -----------------
 * O(1) if sorting in-place
 * O(n + m) if creating sorted copies
 * 
 * COMMON MISTAKES:
 * ----------------
 * 1. Forgetting to sort arrays first (for optimal approach)
 * 2. Not handling negative numbers correctly
 * 3. Integer overflow with large differences (use Math.abs carefully)
 * 4. Wrong pointer movement logic
 * 5. Not returning immediately when diff = 0 found
 */