package LongestPeak;

import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
LONGEST PEAK

Platform: AlgoExpert / Coding Interview
Difficulty: MEDIUM

Problem:
Write a function that takes an array of integers and returns the LENGTH of 
the longest peak in the array.

Peak Definition:
A peak is a sequence of adjacent integers that:
1. STRICTLY INCREASES until it reaches a tip (highest value)
2. Then STRICTLY DECREASES afterwards
3. Requires at LEAST 3 integers

Valid Peaks:
✓ [1, 4, 10, 2] → peak (increases to 10, then decreases)
✓ [0, 10, 6, 5, -1, -3] → peak (increases to 10, decreases to -3)

NOT Peaks:
✗ [4, 0, 10] → no increasing part (starts at 4, drops to 0)
✗ [1, 2, 2, 0] → not strictly increasing (2, 2 is plateau)
✗ [1, 2, 3] → no decreasing part
✗ [3, 2, 1] → no increasing part

Sample Input:
array = [1, 2, 3, 3, 4, 0, 10, 6, 5, -1, -3, 2, 3]

Analysis:
- [1, 2, 3]: increases but no decrease → not a peak
- [3, 3]: plateau → not a peak
- [3, 4, 0]: too short, but is a peak (length 3)
- [0, 10, 6, 5, -1, -3]: increases to 10, decreases to -3 → PEAK! length 6 ✓
- [2, 3]: increases but no decrease → not a peak

Sample Output:
6  // Peak is: 0, 10, 6, 5, -1, -3

Constraints:
- 0 <= array.length <= 10^5
- -10^9 <= array[i] <= 10^9
- Return 0 if no peak exists
*/

// ============= SOLUTION CLASS =============
class Solution {
    /**
     * Find the length of the longest peak in the array.
     * 
     * @param array Array of integers
     * @return Length of longest peak (0 if no peak exists)
     */
    public int longestPeak(int[] array) {
        // TODO: Implement your solution here

        // Hints:
        // 1. Iterate through array looking for peak tips
        // 2. A tip is where array[i-1] < array[i] > array[i+1]
        // 3. From each tip, expand left (while decreasing) and right (while decreasing)
        // 4. Calculate peak length = left expansion + 1 + right expansion
        // 5. Track maximum peak length

        // Algorithm:
        // for i from 1 to n-2:
        // if array[i-1] < array[i] and array[i] > array[i+1]:
        // // Found a tip!
        // expand left while strictly increasing
        // expand right while strictly decreasing
        // calculate length
        // update max

        return 0;
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
        System.out.println("LONGEST PEAK");
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
                () -> solution.longestPeak(new int[] { 1, 2, 3, 3, 4, 0, 10, 6, 5, -1, -3, 2, 3 }),
                6,
                "Sample: peak [0,10,6,5,-1,-3] length 6");

        // Test 2: Simple peak
        test(
                () -> solution.longestPeak(new int[] { 1, 2, 3, 2, 1 }),
                5,
                "Simple: [1,2,3,2,1] → peak length 5");

        // Test 3: Minimum peak
        test(
                () -> solution.longestPeak(new int[] { 1, 3, 2 }),
                3,
                "Minimum: [1,3,2] → peak length 3");

        // Test 4: No peak - only increasing
        test(
                () -> solution.longestPeak(new int[] { 1, 2, 3, 4, 5 }),
                0,
                "No peak: only increasing → 0");

        // Test 5: No peak - only decreasing
        test(
                () -> solution.longestPeak(new int[] { 5, 4, 3, 2, 1 }),
                0,
                "No peak: only decreasing → 0");

        // Test 6: Multiple peaks
        test(
                () -> solution.longestPeak(new int[] { 1, 2, 1, 3, 4, 3, 2, 1 }),
                6,
                "Multiple peaks: longest is [3,4,3,2,1] → 5 or [1,3,4,3,2,1] → 6");

        // Test 7: Plateau breaks peak
        test(
                () -> solution.longestPeak(new int[] { 1, 2, 2, 1 }),
                0,
                "Plateau: [1,2,2,1] not strictly increasing → 0");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 8: Empty array
            test(
                    () -> solution.longestPeak(new int[] {}),
                    0,
                    "Edge: Empty array → 0");

            // Test 9: Single element
            test(
                    () -> solution.longestPeak(new int[] { 5 }),
                    0,
                    "Edge: Single element → 0");

            // Test 10: Two elements
            test(
                    () -> solution.longestPeak(new int[] { 1, 2 }),
                    0,
                    "Edge: Two elements → 0 (need 3 minimum)");

            // Test 11: Peak at start
            test(
                    () -> solution.longestPeak(new int[] { 5, 4, 3, 2, 1, 2 }),
                    0,
                    "Edge: Starts decreasing (no increase first) → 0");

            // Test 12: Peak at end
            test(
                    () -> solution.longestPeak(new int[] { 1, 2, 3, 4, 5, 4 }),
                    0,
                    "Edge: Only one decrease at end → 0 (need full down)");

            // Test 13: Valid peak at end
            test(
                    () -> solution.longestPeak(new int[] { 1, 2, 3, 4, 5, 4, 3 }),
                    5,
                    "Edge: Peak at end [3,4,5,4,3] → 5");

            // Test 14: Negative numbers
            test(
                    () -> solution.longestPeak(new int[] { -3, -2, -1, 0, -1, -2 }),
                    6,
                    "Edge: Negative numbers peak → 6");

            // Test 15: Large peak
            test(
                    () -> solution.longestPeak(new int[] { 1, 2, 3, 4, 5, 6, 7, 8, 9, 8, 7, 6, 5, 4, 3, 2, 1 }),
                    17,
                    "Edge: Large peak → 17");

            // Test 16: Multiple small peaks
            test(
                    () -> solution.longestPeak(new int[] { 1, 3, 2, 4, 3, 5, 4 }),
                    3,
                    "Edge: Multiple small peaks → 3");

            // Test 17: Peak with duplicates nearby
            test(
                    () -> solution.longestPeak(new int[] { 1, 1, 3, 2, 1 }),
                    4,
                    "Edge: Duplicate before peak [1,3,2,1] → 4");
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
 * OPTIMAL APPROACH - O(n):
 * ========================
 * 
 * int longestPeak(int[] array) {
 * if (array.length < 3) return 0;
 * 
 * int longestPeakLength = 0;
 * 
 * // Check each potential peak tip (not first or last element)
 * for (int i = 1; i < array.length - 1; i++) {
 * boolean isPeak = array[i - 1] < array[i] && array[i] > array[i + 1];
 * 
 * if (!isPeak) continue;
 * 
 * // Found a peak tip! Expand left and right
 * int leftIdx = i - 2;
 * while (leftIdx >= 0 && array[leftIdx] < array[leftIdx + 1]) {
 * leftIdx--;
 * }
 * 
 * int rightIdx = i + 2;
 * while (rightIdx < array.length && array[rightIdx] < array[rightIdx - 1]) {
 * rightIdx++;
 * }
 * 
 * // Calculate peak length
 * int currentPeakLength = rightIdx - leftIdx - 1;
 * longestPeakLength = Math.max(longestPeakLength, currentPeakLength);
 * }
 * 
 * return longestPeakLength;
 * }
 * 
 * KEY INSIGHTS:
 * -------------
 * 1. Only check indices that can be peak tips (not edges)
 * 2. A tip satisfies: array[i-1] < array[i] > array[i+1]
 * 3. From tip, expand left (while strictly increasing going backward)
 * 4. From tip, expand right (while strictly decreasing going forward)
 * 5. Peak length = rightIdx - leftIdx - 1
 * 
 * EXAMPLE WALKTHROUGH:
 * ====================
 * Input: [1, 2, 3, 3, 4, 0, 10, 6, 5, -1, -3, 2, 3]
 * [0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10,11,12] (indices)
 * 
 * Check i=1 (value 2):
 * - isPeak? 1 < 2 < 3? NO (2 < 3, not >)
 * 
 * Check i=2 (value 3):
 * - isPeak? 2 < 3 > 3? NO (3 not > 3)
 * 
 * Check i=3 (value 3):
 * - isPeak? 3 < 3? NO
 * 
 * Check i=4 (value 4):
 * - isPeak? 3 < 4 > 0? YES! ✓ Found tip
 * - Expand left from i=4:
 * leftIdx = 2: array[2]=3 < array[3]=3? NO, stop at leftIdx=3
 * - Expand right from i=4:
 * rightIdx = 6: array[6]=10 > array[5]=0? NO, stop at rightIdx=5
 * - Peak length = 5 - 3 - 1 = 1? Wait, that's wrong...
 * 
 * Let me recalculate:
 * Actually for i=4 (value 4):
 * - leftIdx starts at i-2 = 2
 * - Check: array[2]=3 < array[3]=3? NO (3 not < 3), stop
 * - leftIdx = 2 (one position before start of increase)
 * - rightIdx starts at i+2 = 6
 * - Check: array[6]=10 < array[5]=0? NO (10 not < 0), stop
 * - rightIdx = 5 (one position after end of decrease)
 * - Length = 5 - 2 - 1 = 2? Still wrong...
 * 
 * Actually the expansion logic:
 * - Start leftIdx at i-2, expand while array[leftIdx] < array[leftIdx+1]
 * - This finds the leftmost element of the increasing part
 * - Start rightIdx at i+2, expand while array[rightIdx] < array[rightIdx-1]
 * - This finds the rightmost element of the decreasing part
 * - Length = rightIdx - leftIdx - 1
 * 
 * For i=4:
 * - leftIdx starts at 2, array[2]=3 < array[3]=3? NO, stop at leftIdx=2
 * - After loop leftIdx=2, so increasing part starts at leftIdx+1=3
 * - rightIdx starts at 6, array[6]=10 < array[5]=0? NO, stop at rightIdx=5
 * - After loop rightIdx=5, so decreasing part ends at rightIdx-1=4
 * - Peak is from index 3 to 4, length = 5-2-1=2, elements are [3,4,0] → length
 * 3
 * 
 * Hmm, let me check i=6 (value 10):
 * - isPeak? 0 < 10 > 6? YES! ✓
 * - Expand left from i=6:
 * leftIdx = 4: array[4]=4 < array[5]=0? NO, stop
 * After loop leftIdx=4, increasing part starts at 5
 * - Expand right from i=6:
 * rightIdx = 8: array[8]=5 < array[7]=6? YES, continue
 * rightIdx = 9: array[9]=-1 < array[8]=5? YES, continue
 * rightIdx = 10: array[10]=-3 < array[9]=-1? YES, continue
 * rightIdx = 11: array[11]=2 < array[10]=-3? NO (2 > -3), stop
 * After loop rightIdx=11
 * - Peak from leftIdx+1=5 to rightIdx-1=10
 * - Length = 11 - 4 - 1 = 6 ✓
 * 
 * That matches!
 * 
 * STEP-BY-STEP FOR i=6 (tip value 10):
 * -------------------------------------
 * Initial state: i=6, array[i]=10
 * Check: array[5]=0 < array[6]=10 > array[7]=6? YES (is peak tip)
 * 
 * Expand left:
 * leftIdx = i-2 = 4
 * while (leftIdx >= 0 && array[leftIdx] < array[leftIdx+1]):
 * Check leftIdx=4: array[4]=4 < array[5]=0? NO (4 not < 0)
 * Stop, leftIdx=4
 * 
 * Expand right:
 * rightIdx = i+2 = 8
 * while (rightIdx < length && array[rightIdx] < array[rightIdx-1]):
 * Check rightIdx=8: array[8]=5 < array[7]=6? YES, rightIdx++
 * Check rightIdx=9: array[9]=-1 < array[8]=5? YES, rightIdx++
 * Check rightIdx=10: array[10]=-3 < array[9]=-1? YES, rightIdx++
 * Check rightIdx=11: array[11]=2 < array[10]=-3? NO (2 > -3), stop
 * Final rightIdx=11
 * 
 * Peak length = rightIdx - leftIdx - 1 = 11 - 4 - 1 = 6
 * Peak elements: indices 5,6,7,8,9,10 → values [0,10,6,5,-1,-3]
 * 
 * EDGE CASES:
 * -----------
 * 1. Empty array → 0
 * 2. Array length < 3 → 0
 * 3. Only increasing → 0
 * 4. Only decreasing → 0
 * 5. Plateau (equal values) → not strictly increasing
 * 6. Peak at boundaries → still valid if has both increase and decrease
 * 7. Multiple peaks → return longest
 * 8. Negative numbers → works same way
 * 9. Single peak → return its length
 * 
 * TIME COMPLEXITY: O(n) - each element visited at most 3 times (check tip,
 * expand left, expand right)
 * SPACE COMPLEXITY: O(1) - only variables
 * 
 * COMMON MISTAKES:
 * ----------------
 * 1. Not checking strictly increasing/decreasing (using <= instead of <)
 * 2. Forgetting minimum 3 elements requirement
 * 3. Not handling edge indices properly
 * 4. Counting tip twice in length calculation
 * 5. Not continuing after finding a peak (might be a longer one later)
 */