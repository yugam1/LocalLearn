import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
LONGEST SUBSEQUENCE WHICH IS SUBSTRING

Platform: Coding Interview
Difficulty: MEDIUM-HARD

Problem:
Find the length of the longest subsequence of one string which is a substring 
of another string.

Definitions:
- Subsequence: Characters from a string in same order, but not necessarily consecutive
  Example: "ace" is a subsequence of "abcde"
  
- Substring: Consecutive characters from a string
  Example: "bcd" is a substring of "abcde", but "ace" is NOT

Task:
Given two strings A and B, find the longest subsequence of A that appears as 
a substring in B.

Example 1:
A = "abcde"
B = "ace"

Subsequences of A that are substrings of B:
- "a" ✓ (substring of B at index 0)
- "c" ✓ (substring of B at index 1)
- "e" ✓ (substring of B at index 2)
- "ac" ✓ (substring of B at indices 0-1)
- "ce" ✓ (substring of B at indices 1-2)
- "ace" ✓ (substring of B at indices 0-2)

Longest: "ace" → length 3
Output: 3

Example 2:
A = "dynamic"
B = "nam"

Subsequences of A that are substrings of B:
- "n" ✓
- "a" ✓
- "m" ✓
- "na" ✓
- "am" ✓
- "nam" ✓

Longest: "nam" → length 3
Output: 3

Example 3:
A = "programming"
B = "gaming"

Substrings of B: "g", "a", "m", "i", "n", "ga", "am", "mi", "in", "gam", "ami", "min", "gami", "amin", "gamin", "gaming"

Which appear as subsequences of A?
- "g" ✓
- "a" ✓
- "m" ✓
- "i" ✓
- "n" ✓
- "ga" ✓
- "am" ✓
- "mi" ✓
- "in" ✓
- "gam" ✓
- "ami" ✓
- "min" ✓
- "gami" ✓
- "amin" ✓
- "gamin" ✓

Longest: "gamin" → length 5
Output: 5

Constraints:
- 1 <= A.length, B.length <= 1000
- Strings contain lowercase English letters only
- Return 0 if no common characters
*/

// ============= SOLUTION CLASS =============
class Solution {
    /**
     * Find length of longest subsequence of A which is substring of B.
     * 
     * @param A First string (take subsequence from this)
     * @param B Second string (check if it's a substring of this)
     * @return Length of longest such sequence
     */
    public int longestSubsequenceSubstring(String A, String B) {
        int m = A.length();
        int n = B.length();
        int res = 0;
        for (int l = 0; l < n; l++) {
            int ai = 0;
            for (int r = l; r < n; r++) {
                char yend = B.charAt(r);
                while (ai < m && A.charAt(ai) != yend) {
                    ai++;
                }

                if (ai < m && A.charAt(ai) == yend) {
                    res = Math.max(res, r - l + 1);

                } else {
                    break;
                }
            }
        }
        return res;
    }

}

// ============= JUDGE CLASS =============
class Judge {
    private static final boolean CHECK_FULL = true;
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
        System.out.println("LONGEST SUBSEQUENCE WHICH IS SUBSTRING");
        System.out.println("Difficulty: MEDIUM-HARD");
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

        // Test 1: Example from description
        test(
                () -> solution.longestSubsequenceSubstring("abcde", "yace"),
                3,
                "Example 1: 'ace' is subseq of 'abcde' and substring of 'ace' → 3");

        // Test 2: Example 2
        test(
                () -> solution.longestSubsequenceSubstring("dynamic", "nam"),
                3,
                "Example 2: 'nam' appears in both → 3");

        // Test 3: Example 3
        test(
                () -> solution.longestSubsequenceSubstring("programming", "gaming"),
                6,
                "Example 3: 'gaming' → 6");

        // Test 4: Full match
        test(
                () -> solution.longestSubsequenceSubstring("abc", "abc"),
                3,
                "Full match: both strings same → 3");

        // Test 5: No common characters
        test(
                () -> solution.longestSubsequenceSubstring("abc", "xyz"),
                0,
                "No common chars → 0");

        // Test 6: Single character
        test(
                () -> solution.longestSubsequenceSubstring("hello", "e"),
                1,
                "Single char match → 1");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 7: B is longer than A
            test(
                    () -> solution.longestSubsequenceSubstring("ab", "abcdefg"),
                    2,
                    "Edge: B longer, full A matches → 2");

            // Test 8: Repeated characters
            test(
                    () -> solution.longestSubsequenceSubstring("aaabbb", "ab"),
                    2,
                    "Edge: Repeated chars 'ab' → 2");

            // Test 9: All same character
            test(
                    () -> solution.longestSubsequenceSubstring("aaaa", "aa"),
                    2,
                    "Edge: All same char 'aa' → 2");

            // Test 10: Reverse order
            test(
                    () -> solution.longestSubsequenceSubstring("abcde", "edcba"),
                    1,
                    "Edge: Reverse order, only single chars match → 1");

            // Test 11: Complex pattern
            test(
                    () -> solution.longestSubsequenceSubstring("aabbccdd", "abcd"),
                    4,
                    "Edge: Pattern 'abcd' → 4");

            // Test 12: Long substring of B
            test(
                    () -> solution.longestSubsequenceSubstring("abcdefghij", "cdefg"),
                    5,
                    "Edge: Long substring 'cdefg' → 5");
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
 * APPROACH 1 - CHECK ALL SUBSTRINGS OF B: O(m² × n)
 * ==================================================
 * 
 * int longestSubsequenceSubstring(String A, String B) {
 * int maxLength = 0;
 * 
 * // Generate all substrings of B
 * for (int i = 0; i < B.length(); i++) {
 * for (int j = i + 1; j <= B.length(); j++) {
 * String substring = B.substring(i, j);
 * 
 * // Check if this substring is a subsequence of A
 * if (isSubsequence(A, substring)) {
 * maxLength = Math.max(maxLength, substring.length());
 * }
 * }
 * }
 * 
 * return maxLength;
 * }
 * 
 * boolean isSubsequence(String text, String pattern) {
 * int i = 0, j = 0;
 * 
 * while (i < text.length() && j < pattern.length()) {
 * if (text.charAt(i) == pattern.charAt(j)) {
 * j++;
 * }
 * i++;
 * }
 * 
 * return j == pattern.length();
 * }
 * 
 * TIME: O(m² × n) where m = B.length(), n = A.length()
 * - m² substrings of B
 * - Each subsequence check takes O(n)
 * 
 * SPACE: O(m) for substring storage
 * 
 * APPROACH 2 - OPTIMIZED DP: O(n × m²)
 * =====================================
 * Same complexity but potentially better cache locality.
 * 
 * For each substring of B (O(m²)):
 * Check if it's subsequence of A (O(n))
 * 
 * Can't improve beyond this without advanced techniques.
 * 
 * APPROACH 3 - EARLY TERMINATION:
 * ================================
 * int longestSubsequenceSubstring(String A, String B) {
 * int maxLength = 0;
 * 
 * // Try longer substrings first for early termination
 * for (int len = B.length(); len >= 1; len--) {
 * if (len <= maxLength) break; // Early termination
 * 
 * for (int i = 0; i <= B.length() - len; i++) {
 * String substring = B.substring(i, i + len);
 * if (isSubsequence(A, substring)) {
 * return len; // Found longest, return immediately
 * }
 * }
 * }
 * 
 * return 0;
 * }
 * 
 * This tries longer substrings first, so we can return as soon as we find a
 * match.
 * 
 * EXAMPLE WALKTHROUGH:
 * ====================
 * A = "abcde"
 * B = "ace"
 * 
 * Generate all substrings of B:
 * Length 1: "a", "c", "e"
 * Length 2: "ac", "ce"
 * Length 3: "ace"
 * 
 * Check each as subsequence of A:
 * 
 * "ace" (length 3):
 * - Looking for 'a' in "abcde" → found at index 0
 * - Looking for 'c' in "bcde" → found at index 1
 * - Looking for 'e' in "de" → found at index 1
 * - All found → "ace" is a subsequence of "abcde" ✓
 * 
 * Max length = 3
 * 
 * EXAMPLE 2 - "dynamic" vs "nam":
 * A = "dynamic"
 * B = "nam"
 * 
 * Substrings of B:
 * Length 1: "n", "a", "m"
 * Length 2: "na", "am"
 * Length 3: "nam"
 * 
 * Check "nam" (length 3):
 * - Looking for 'n' in "dynamic" → found at index 3
 * - Looking for 'a' in "amic" → found at index 0
 * - Looking for 'm' in "mic" → found at index 0
 * - All found → "nam" is subsequence of "dynamic" ✓
 * 
 * Max length = 3
 * 
 * EDGE CASES:
 * -----------
 * 1. No common characters → 0
 * 2. A == B → return length
 * 3. B is substring of A → return B.length()
 * 4. Single character strings
 * 5. Empty strings (if allowed) → 0
 * 6. All same character
 * 7. Reverse strings
 * 8. B longer than A
 * 
 * TIME COMPLEXITY:
 * ----------------
 * Brute Force: O(m² × n)
 * With Early Termination: Best case O(m × n), Worst case O(m² × n)
 * 
 * SPACE COMPLEXITY: O(m) for substring storage
 * 
 * OPTIMIZATION NOTES:
 * -------------------
 * - Start with longest substrings first (early termination)
 * - Can use rolling hash for substring generation (but still need subsequence
 * check)
 * - Subsequence check is already optimal at O(n)
 */