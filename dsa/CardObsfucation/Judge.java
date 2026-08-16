package CardObsfucation;

import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
CARD RANGE OBFUSCATION (Stripe)

Platform: Stripe Interview / HackerRank
Difficulty: MEDIUM-HARD

Background:
Payment card numbers consist of 8-19 digits, with the first 6 digits referred 
to as the Bank Identification Number (BIN).

For a given BIN, all 16-digit card numbers starting with that BIN are considered 
to be in the BIN range.

Example: BIN 424242 corresponds to card numbers from:
- 4242420000000000 (inclusive) 
- through 4242429999999999 (inclusive)

Problem:
Stripe's card metadata API may return partial coverage of this BIN range, by 
providing a list of intervals mapping to card brands (e.g., VISA, MASTERCARD). 
However, these intervals may have gaps (at the beginning, middle, or end of the 
BIN range), which can be exploited by fraudsters to probe for valid cards.

Task:
Fill in missing intervals and apply various merging rules based on the part.

=============================================================================
PART 1: BASIC GAP FILLING
=============================================================================
Input: BIN + intervals with 10-digit offsets
Fill gaps at beginning, middle, and end to cover entire BIN range.

Input Format:
- Line 1: 6-digit BIN
- Line 2: Number of intervals
- Next lines: start,end,brand (10-digit offsets)

Output: List of "start,end,brand" with 16-digit card numbers

=============================================================================
PART 2: FILLING GAPS FOR SAME BRAND
=============================================================================
**INPUT FORMAT CHANGES:** Intervals now use 16-digit card numbers (not offsets)

Rules:
- Fill gaps at beginning/middle/end (like Part 1)
- If gap exists between two intervals of SAME brand, extend one interval
- Choose extension that minimizes distance (extend lower or upper bound)

Example:
Input:
  424242
  2
  4242420000000000,4242425000000000,VISA
  4242427000000000,4242429999999999,VISA

Gap: 4242425000000001 to 4242426999999999 (between two VISA intervals)
Solution: Extend to cover gap

Output:
  4242420000000000,4242429999999999,VISA

=============================================================================
PART 3: EXTENDING TO COVER DIFFERENT BRANDS (NESTED INTERVALS)
=============================================================================
Rules from Part 2, PLUS:
- If an interval for one brand is entirely NESTED within another brand's interval
- Keep only the outer (covering) interval
- Suppress the nested interval(s)

Example:
Input:
  424242
  3
  4242420000000000,4242429999999999,CB
  4242420000000000,4242425000000000,VISA
  4242425000000001,4242429999999999,VISA

Nested: Both VISA intervals are inside CB interval
Solution: Keep CB, suppress VISA

Output:
  4242420000000000,4242429999999999,CB

=============================================================================
PART 4: MERGE ADJACENT INTERVALS
=============================================================================
Rules from Parts 2-3, PLUS:
- Adjacent intervals of same brand with no gaps should be MERGED

Example:
Input:
  424242
  2
  4242420000000000,4242424999999999,VISA
  4242425000000000,4242429999999999,VISA

Adjacent: Second interval starts exactly where first ends + 1
Solution: Merge into single interval

Output:
  4242420000000000,4242429999999999,VISA

=============================================================================
FINAL OUTPUT REQUIREMENTS (ALL PARTS)
=============================================================================
- Intervals must be minimal (merged/extended when allowed)
- Sorted by start
- No two intervals for same brand may overlap
- Nested intervals for different brands handled correctly
- Full coverage from BIN0000000000 to BIN9999999999
*/

// ============= SOLUTION CLASS =============
class Solution {
    /**
     * PART 1: Fill gaps in BIN range intervals (input: 10-digit offsets)
     * 
     * @param bin       6-digit BIN string
     * @param intervals List of [start, end, brand] where start/end are 10-digit
     *                  offsets
     * @return List of "start,end,brand" strings with 16-digit card numbers, sorted
     */
    public List<String> fillBinGaps(String bin, List<String[]> intervals) {
        List<String> res = new ArrayList<>();
        if (intervals.size() == 0)
            return null;

        Collections.sort(intervals, (a, b) -> a[0].compareTo(b[0]));
        Long start = 0L;
        Long end = 9999999999L;
        Long cStart = 0L;
        String prevBrand = intervals.get(0)[2], cBrand;
        for (int i = 0; i < intervals.size(); i++) {
            cBrand = intervals.get(i)[2];
            if (!cBrand.equals(prevBrand)) {
                // start,end,brand
                cStart = Long.parseLong(intervals.get(i)[0]);
                res.add(String.format("%s%010d,%s%010d,%s", bin, start, bin, cStart - 1, prevBrand));
                start = cStart;
                prevBrand = cBrand;
            }
        }
        res.add(String.format("%s%010d,%s%010d,%s", bin, start, bin, end, prevBrand));

        return res;
    }

    /**
     * PART 2: Fill gaps + merge same-brand gaps (input: 16-digit card numbers)
     * 
     * @param bin       6-digit BIN string
     * @param intervals List of [start, end, brand] where start/end are 16-digit
     *                  card numbers
     * @return List of "start,end,brand" strings with 16-digit card numbers, sorted
     */
    public List<String> fillAndMergeSameBrand(String bin, List<String[]> intervals) {
        // TODO: Implement Part 2
        // Rules from Part 1, PLUS:
        // - If gap between two same-brand intervals, extend to fill
        // - Choose minimal extension (extend closer boundary)
        List<String[]> latest = new ArrayList<>();
        for (int i = 0; i < intervals.size(); i++) {
            latest.add(new String[] { intervals.get(i)[0].substring(bin.length()),
                    intervals.get(i)[1].substring(bin.length()), intervals.get(i)[2] });
        }
        return fillBinGaps(bin, latest);
    }

    /**
     * PART 3: Handle nested intervals (input: 16-digit card numbers)
     * 
     * @param bin       6-digit BIN string
     * @param intervals List of [start, end, brand] where start/end are 16-digit
     *                  card numbers
     * @return List of "start,end,brand" strings with 16-digit card numbers, sorted
     */
    public List<String> handleNestedIntervals(String bin, List<String[]> intervals) {
        List<String[]> latest = new ArrayList<>();
        for (int i = 0; i < intervals.size(); i++) {
            latest.add(new String[] { intervals.get(i)[0].substring(bin.length()),
                    intervals.get(i)[1].substring(bin.length()), intervals.get(i)[2] });
        }
        intervals = latest;
        List<String> res = new ArrayList<>();
        if (intervals.size() == 0)
            return null;

        Collections.sort(intervals, (a, b) -> {
            if (a[0].equals(b[0])) {
                return b[1].compareTo(a[1]);
            }
            return a[0].compareTo(b[0]);
        });
        Long start = 0L;
        Long end = Long.parseLong(intervals.get(0)[1]);
        Long cStart = 0L, cEnd = Long.parseLong(intervals.get(0)[1]);
        String prevBrand = intervals.get(0)[2], cBrand;
        for (int i = 1; i < intervals.size(); i++) {
            cBrand = intervals.get(i)[2];
            cStart = Long.parseLong(intervals.get(i)[0]);
            cEnd = Long.parseLong(intervals.get(i)[1]);

            if (cEnd <= end && cStart >= start)
                continue;

            if (!cBrand.equals(prevBrand)) {
                // start,end,brand
                res.add(String.format("%s%010d,%s%010d,%s", bin, start, bin, end, prevBrand));
                start = cStart;
                prevBrand = cBrand;
                end = cEnd;
            }
        }
        res.add(String.format("%s%010d,%s%010d,%s", bin, start, bin, Math.max(9999999999L, end), prevBrand));

        return res;
    }

    /**
     * PART 4: Merge adjacent same-brand intervals (input: 16-digit card numbers)
     * 
     * @param bin       6-digit BIN string
     * @param intervals List of [start, end, brand] where start/end are 16-digit
     *                  card numbers
     * @return List of "start,end,brand" strings with 16-digit card numbers, sorted
     */
    public List<String> mergeAdjacentIntervals(String bin, List<String[]> intervals) {
        List<String[]> latest = new ArrayList<>();
        for (int i = 0; i < intervals.size(); i++) {
            latest.add(new String[] { intervals.get(i)[0].substring(bin.length()),
                    intervals.get(i)[1].substring(bin.length()), intervals.get(i)[2] });
        }
        intervals = latest;
        List<String> res = new ArrayList<>();
        if (intervals.size() == 0)
            return null;

        Collections.sort(intervals, (a, b) -> {
            if (a[0].equals(b[0])) {
                return b[1].compareTo(a[1]);
            }
            return a[0].compareTo(b[0]);
        });
        Long start = 0L;
        Long end = Long.parseLong(intervals.get(0)[1]);
        Long cStart = 0L, cEnd = Long.parseLong(intervals.get(0)[1]);
        String prevBrand = intervals.get(0)[2], cBrand;
        for (int i = 1; i < intervals.size(); i++) {
            cBrand = intervals.get(i)[2];
            cStart = Long.parseLong(intervals.get(i)[0]);
            cEnd = Long.parseLong(intervals.get(i)[1]);
            if (cBrand.equals(prevBrand) && cStart == end + 1) {
                cStart = start;
                end = Math.max(end, cEnd);
                continue;
            }
            if (cEnd <= end && cStart >= start)
                continue;

            // start,end,brand
            res.add(String.format("%s%010d,%s%010d,%s", bin, start, bin, end, prevBrand));
            start = cStart;
            prevBrand = cBrand;
            end = cEnd;

        }
        res.add(String.format("%s%010d,%s%010d,%s", bin, start, bin, Math.max(9999999999L, end), prevBrand));

        return res;
    }
}

// ============= JUDGE CLASS =============
class Judge {
    // ========== CONFIGURATION FLAGS ==========
    private static final int PART = 1; // Which part to test: 1, 2, or 3
    private static final boolean CHECK_FULL = true; // true = all tests, false = basic only
    private static final int[] SELECTED_TESTS = {}; // Empty = all, or specify: {1, 3, 5}

    private static int passedTests = 0;
    private static int totalTests = 0;
    private static int currentTestNumber = 0;
    private static Set<Integer> selectedTestSet = new HashSet<>();

    public static void main(String[] args) {
        Solution solution = new Solution();

        // Initialize selected tests
        for (int testNum : SELECTED_TESTS) {
            selectedTestSet.add(testNum);
        }

        System.out.println("=".repeat(70));
        System.out.println("CARD RANGE OBFUSCATION (Stripe)");
        System.out.println("Difficulty: MEDIUM");
        System.out.println("=".repeat(70));
        System.out.println("Testing: PART " + PART);
        System.out.println("Mode: " + (CHECK_FULL ? "FULL TEST (All Cases)" : "BASIC TEST"));

        if (SELECTED_TESTS.length > 0) {
            System.out.println("Selected Tests: " + Arrays.toString(SELECTED_TESTS));
        } else {
            System.out.println("Running: ALL TESTS");
        }
        System.out.println("=".repeat(70));

        // Run tests based on selected part
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
            case 4:
                runPart4Tests(solution);
                break;
            default:
                System.out.println("❌ Invalid PART selected. Choose 1, 2, 3, or 4.");
                return;
        }

        printSummary();
    }

    // ============= PART 1 TESTS =============
    private static void runPart1Tests(Solution solution) {
        System.out.println("\n=== PART 1: BASIC GAP FILLING ===\n");

        System.out.println("--- Basic Tests ---");

        // Test 1: Example from document - Gap at beginning and end
        List<String[]> intervals1 = new ArrayList<>();
        intervals1.add(new String[] { "1000000000", "3999999999", "VISA" });
        intervals1.add(new String[] { "4000000000", "5999999999", "MASTERCARD" });

        test(
                () -> solution.fillBinGaps("777777", intervals1),
                Arrays.asList(
                        "7777770000000000,7777773999999999,VISA",
                        "7777774000000000,7777779999999999,MASTERCARD"),
                "Example 1: Gap at beginning and end");

        // Test 2: Already complete coverage
        List<String[]> intervals2 = new ArrayList<>();
        intervals2.add(new String[] { "0000000000", "4999999999", "VISA" });
        intervals2.add(new String[] { "5000000000", "9999999999", "MASTERCARD" });

        test(
                () -> solution.fillBinGaps("555555", intervals2),
                Arrays.asList(
                        "5555550000000000,5555554999999999,VISA",
                        "5555555000000000,5555559999999999,MASTERCARD"),
                "Example 2: Complete coverage (no gaps)");

        // Test 3: Gap in middle
        List<String[]> intervals3 = new ArrayList<>();
        intervals3.add(new String[] { "0000000000", "2999999999", "VISA" });
        intervals3.add(new String[] { "5000000000", "7999999999", "MASTERCARD" });
        intervals3.add(new String[] { "8000000000", "9999999999", "AMEX" });

        test(
                () -> solution.fillBinGaps("424242", intervals3),
                Arrays.asList(
                        "4242420000000000,4242424999999999,VISA",
                        "4242425000000000,4242427999999999,MASTERCARD",
                        "4242428000000000,4242429999999999,AMEX"),
                "Example 3: Gap in middle (extend VISA)");

        // Test 4: Single interval with gaps at both ends
        List<String[]> intervals4 = new ArrayList<>();
        intervals4.add(new String[] { "3000000000", "6999999999", "VISA" });

        test(
                () -> solution.fillBinGaps("123456", intervals4),
                Arrays.asList(
                        "1234560000000000,1234569999999999,VISA"),
                "Example 4: Single interval (extend both directions)");

        // Test 5: Multiple gaps
        List<String[]> intervals5 = new ArrayList<>();
        intervals5.add(new String[] { "1000000000", "1999999999", "VISA" });
        intervals5.add(new String[] { "3000000000", "3999999999", "MASTERCARD" });
        intervals5.add(new String[] { "5000000000", "5999999999", "AMEX" });

        test(
                () -> solution.fillBinGaps("999999", intervals5),
                Arrays.asList(
                        "9999990000000000,9999992999999999,VISA",
                        "9999993000000000,9999994999999999,MASTERCARD",
                        "9999995000000000,9999999999999999,AMEX"),
                "Example 5: Multiple gaps (beginning, middle, end)");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 6: Unsorted intervals
            List<String[]> intervals6 = new ArrayList<>();
            intervals6.add(new String[] { "5000000000", "7999999999", "MASTERCARD" });
            intervals6.add(new String[] { "0000000000", "2999999999", "VISA" });

            test(
                    () -> solution.fillBinGaps("111111", intervals6),
                    Arrays.asList(
                            "1111110000000000,1111114999999999,VISA",
                            "1111115000000000,1111119999999999,MASTERCARD"),
                    "Edge: Unsorted input intervals");

            // Test 7: Adjacent intervals (no gap between)
            List<String[]> intervals7 = new ArrayList<>();
            intervals7.add(new String[] { "2000000000", "4999999999", "VISA" });
            intervals7.add(new String[] { "5000000000", "7999999999", "MASTERCARD" });

            test(
                    () -> solution.fillBinGaps("222222", intervals7),
                    Arrays.asList(
                            "2222220000000000,2222224999999999,VISA",
                            "2222225000000000,2222229999999999,MASTERCARD"),
                    "Edge: Adjacent intervals (fill end gaps only)");

            // Test 8: Tiny interval at start
            List<String[]> intervals8 = new ArrayList<>();
            intervals8.add(new String[] { "0000000000", "0000000000", "VISA" });

            test(
                    () -> solution.fillBinGaps("333333", intervals8),
                    Arrays.asList(
                            "3333330000000000,3333339999999999,VISA"),
                    "Edge: Single offset interval");

            // Test 9: Overlapping intervals (same brand)
            List<String[]> intervals9 = new ArrayList<>();
            intervals9.add(new String[] { "0000000000", "5999999999", "VISA" });
            intervals9.add(new String[] { "3000000000", "8999999999", "VISA" });

            test(
                    () -> solution.fillBinGaps("444444", intervals9),
                    Arrays.asList(
                            "4444440000000000,4444449999999999,VISA"),
                    "Edge: Overlapping intervals (merge same brand)");

            // Test 10: Full range single interval
            List<String[]> intervals10 = new ArrayList<>();
            intervals10.add(new String[] { "0000000000", "9999999999", "VISA" });

            test(
                    () -> solution.fillBinGaps("888888", intervals10),
                    Arrays.asList(
                            "8888880000000000,8888889999999999,VISA"),
                    "Edge: Complete range single interval");

            // Test 11: Three intervals with two gaps
            List<String[]> intervals11 = new ArrayList<>();
            intervals11.add(new String[] { "1000000000", "2999999999", "VISA" });
            intervals11.add(new String[] { "5000000000", "6999999999", "MASTERCARD" });
            intervals11.add(new String[] { "8000000000", "8999999999", "AMEX" });

            test(
                    () -> solution.fillBinGaps("666666", intervals11),
                    Arrays.asList(
                            "6666660000000000,6666664999999999,VISA",
                            "6666665000000000,6666667999999999,MASTERCARD",
                            "6666668000000000,6666669999999999,AMEX"),
                    "Edge: Three intervals, multiple gaps");
        }
    }

    // ============= PART 2 TESTS =============
    private static void runPart2Tests(Solution solution) {
        System.out.println("\n=== PART 2: FILLING GAPS FOR SAME BRAND ===");
        System.out.println("CRITICAL: Input now uses 16-digit card numbers (not offsets)!\n");

        System.out.println("--- Basic Tests ---");

        // Test 1: Fill gap between same-brand intervals
        List<String[]> intervals1 = new ArrayList<>();
        intervals1.add(new String[] { "4242420000000000", "4242425000000000", "VISA" });
        intervals1.add(new String[] { "4242427000000000", "4242429999999999", "VISA" });

        test(
                () -> solution.fillAndMergeSameBrand("424242", intervals1),
                Arrays.asList(
                        "4242420000000000,4242429999999999,VISA"),
                "Part 2: Fill gap between same VISA intervals");

        // Test 2: Multiple brands with gaps
        List<String[]> intervals2 = new ArrayList<>();
        intervals2.add(new String[] { "7777770000000000", "7777772999999999", "VISA" });
        intervals2.add(new String[] { "7777775000000000", "7777777999999999", "VISA" });
        intervals2.add(new String[] { "7777778000000000", "7777779999999999", "MASTERCARD" });

        test(
                () -> solution.fillAndMergeSameBrand("777777", intervals2),
                Arrays.asList(
                        "7777770000000000,7777777999999999,VISA",
                        "7777778000000000,7777779999999999,MASTERCARD"),
                "Part 2: Fill gap in VISA, keep MASTERCARD separate");

        // Test 3: No gaps between same brand
        List<String[]> intervals3 = new ArrayList<>();
        intervals3.add(new String[] { "5555550000000000", "5555554999999999", "VISA" });
        intervals3.add(new String[] { "5555555000000000", "5555559999999999", "MASTERCARD" });

        test(
                () -> solution.fillAndMergeSameBrand("555555", intervals3),
                Arrays.asList(
                        "5555550000000000,5555554999999999,VISA",
                        "5555555000000000,5555559999999999,MASTERCARD"),
                "Part 2: No same-brand gaps to fill");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 4: Three same-brand intervals with gaps
            List<String[]> intervals4 = new ArrayList<>();
            intervals4.add(new String[] { "1111110000000000", "1111111999999999", "VISA" });
            intervals4.add(new String[] { "1111113000000000", "1111114999999999", "VISA" });
            intervals4.add(new String[] { "1111116000000000", "1111119999999999", "VISA" });

            test(
                    () -> solution.fillAndMergeSameBrand("111111", intervals4),
                    Arrays.asList(
                            "1111110000000000,1111119999999999,VISA"),
                    "Edge: Three VISA intervals with gaps -> merge all");
        }
    }

    // ============= PART 3 TESTS =============
    private static void runPart3Tests(Solution solution) {
        System.out.println("\n=== PART 3: EXTENDING TO COVER DIFFERENT BRANDS ===");
        System.out.println("(Nested intervals suppressed)\n");

        System.out.println("--- Basic Tests ---");

        // Test 1: VISA nested inside CB
        List<String[]> intervals1 = new ArrayList<>();
        intervals1.add(new String[] { "4242420000000000", "4242429999999999", "CB" });
        intervals1.add(new String[] { "4242420000000000", "4242425000000000", "VISA" });
        intervals1.add(new String[] { "4242425000000001", "4242429999999999", "VISA" });

        test(
                () -> solution.handleNestedIntervals("424242", intervals1),
                Arrays.asList(
                        "4242420000000000,4242429999999999,CB"),
                "Part 3: VISA nested in CB -> suppress VISA");

        // Test 2: Partial overlap (not fully nested)
        List<String[]> intervals2 = new ArrayList<>();
        intervals2.add(new String[] { "7777770000000000", "7777775999999999", "VISA" });
        intervals2.add(new String[] { "7777773000000000", "7777778999999999", "MASTERCARD" });

        test(
                () -> solution.handleNestedIntervals("777777", intervals2),
                Arrays.asList(
                        "7777770000000000,7777775999999999,VISA",
                        "7777773000000000,7777779999999999,MASTERCARD"),
                "Part 3: Partial overlap (not nested) -> keep both, fill end gap");

        // Test 3: Multiple nested intervals
        List<String[]> intervals3 = new ArrayList<>();
        intervals3.add(new String[] { "5555550000000000", "5555559999999999", "CB" });
        intervals3.add(new String[] { "5555551000000000", "5555553999999999", "VISA" });
        intervals3.add(new String[] { "5555556000000000", "5555558999999999", "MASTERCARD" });

        test(
                () -> solution.handleNestedIntervals("555555", intervals3),
                Arrays.asList(
                        "5555550000000000,5555559999999999,CB"),
                "Part 3: Multiple intervals nested in CB -> suppress all");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 4: No nesting
            List<String[]> intervals4 = new ArrayList<>();
            intervals4.add(new String[] { "1111110000000000", "1111114999999999", "VISA" });
            intervals4.add(new String[] { "1111115000000000", "1111119999999999", "MASTERCARD" });

            test(
                    () -> solution.handleNestedIntervals("111111", intervals4),
                    Arrays.asList(
                            "1111110000000000,1111114999999999,VISA",
                            "1111115000000000,1111119999999999,MASTERCARD"),
                    "Edge: No nesting -> keep both");
        }
    }

    // ============= PART 4 TESTS =============
    private static void runPart4Tests(Solution solution) {
        System.out.println("\n=== PART 4: MERGE ADJACENT INTERVALS ===");
        System.out.println("(Adjacent same-brand intervals merged)\n");

        System.out.println("--- Basic Tests ---");

        // Test 1: Adjacent VISA intervals
        List<String[]> intervals1 = new ArrayList<>();
        intervals1.add(new String[] { "4242420000000000", "4242424999999999", "VISA" });
        intervals1.add(new String[] { "4242425000000000", "4242429999999999", "VISA" });

        test(
                () -> solution.mergeAdjacentIntervals("424242", intervals1),
                Arrays.asList(
                        "4242420000000000,4242429999999999,VISA"),
                "Part 4: Adjacent VISA intervals -> merge");

        // Test 2: Adjacent different brands (don't merge)
        List<String[]> intervals2 = new ArrayList<>();
        intervals2.add(new String[] { "7777770000000000", "7777774999999999", "VISA" });
        intervals2.add(new String[] { "7777775000000000", "7777779999999999", "MASTERCARD" });

        test(
                () -> solution.mergeAdjacentIntervals("777777", intervals2),
                Arrays.asList(
                        "7777770000000000,7777774999999999,VISA",
                        "7777775000000000,7777779999999999,MASTERCARD"),
                "Part 4: Adjacent different brands -> don't merge");

        // Test 3: Three adjacent VISA intervals
        List<String[]> intervals3 = new ArrayList<>();
        intervals3.add(new String[] { "5555550000000000", "5555552999999999", "VISA" });
        intervals3.add(new String[] { "5555553000000000", "5555555999999999", "VISA" });
        intervals3.add(new String[] { "5555556000000000", "5555559999999999", "VISA" });

        test(
                () -> solution.mergeAdjacentIntervals("555555", intervals3),
                Arrays.asList(
                        "5555550000000000,5555559999999999,VISA"),
                "Part 4: Three adjacent VISA -> merge all");

        // Test 4: Mix of adjacent and non-adjacent
        List<String[]> intervals4 = new ArrayList<>();
        intervals4.add(new String[] { "1111110000000000", "1111112999999999", "VISA" });
        intervals4.add(new String[] { "1111113000000000", "1111115999999999", "VISA" });
        intervals4.add(new String[] { "1111117000000000", "1111119999999999", "VISA" });

        test(
                () -> solution.mergeAdjacentIntervals("111111", intervals4),
                Arrays.asList(
                        "1111110000000000,1111115999999999,VISA",
                        "1111117000000000,1111119999999999,VISA"),
                "Part 4: Merge adjacent, keep gap");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 5: Complex scenario with nesting and adjacency
            List<String[]> intervals5 = new ArrayList<>();
            intervals5.add(new String[] { "4242420000000000", "4242429999999999", "CB" });
            intervals5.add(new String[] { "4242420000000000", "4242424999999999", "VISA" });
            intervals5.add(new String[] { "4242425000000000", "4242429999999999", "VISA" });

            test(
                    () -> solution.mergeAdjacentIntervals("424242", intervals5),
                    Arrays.asList(
                            "4242420000000000,4242429999999999,CB"),
                    "Edge: Nested + adjacent VISA inside CB -> suppress VISA");
        }
    }

    // ============= HELPER METHODS =============
    private static void test(TestSupplier<List<String>> supplier, List<String> expected, String description) {
        currentTestNumber++;

        // Skip if this test is not in the selected set
        if (SELECTED_TESTS.length > 0 && !selectedTestSet.contains(currentTestNumber)) {
            return;
        }

        totalTests++;
        try {
            List<String> result = supplier.get();
            boolean passed = Objects.equals(result, expected);

            if (passed) {
                passedTests++;
                System.out.printf("✓ PASS [Test #%d]: %s%n", currentTestNumber, description);
            } else {
                System.out.printf("✗ FAIL [Test #%d]: %s%n", currentTestNumber, description);
                System.out.println("  Expected:");
                printList(expected);
                System.out.println("  Got:");
                printList(result);
            }
        } catch (Exception e) {
            System.out.printf("✗ ERROR [Test #%d]: %s%n", currentTestNumber, description);
            System.out.println("  Exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printList(List<String> list) {
        if (list == null || list.isEmpty()) {
            System.out.println("    (empty)");
            return;
        }
        for (String item : list) {
            System.out.println("    " + item);
        }
    }

    private static void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("TEST SUMMARY - PART " + PART);
        System.out.println("=".repeat(70));

        if (SELECTED_TESTS.length > 0) {
            System.out.printf("Selected: %d test(s) from %d total%n",
                    totalTests, currentTestNumber);
        }

        System.out.printf("Passed: %d/%d tests%n", passedTests, totalTests);

        if (passedTests == totalTests) {
            System.out.println("✓ All tests passed! 🎉");
        } else {
            System.out.printf("✗ %d test(s) failed%n", totalTests - passedTests);
        }

        System.out.println("\n" + "ℹ".repeat(70));
        if (!CHECK_FULL) {
            System.out.println("ℹ Set CHECK_FULL = true for edge cases");
        }
        if (SELECTED_TESTS.length == 0) {
            System.out.println("ℹ Set SELECTED_TESTS = new int[]{1,2,3} to run specific tests");
        }
        if (PART < 4) {
            System.out.println("ℹ Set PART = " + (PART + 1) + " to test next part");
        }
    }

    @FunctionalInterface
    interface TestSupplier<T> {
        T get() throws Exception;
    }
}

// ============= ALGORITHM HINTS =============
/*
 * HOW TO USE THE JUDGE:
 * ====================
 * 1. PART = 1, 2, 3, or 4 → Select which part to test
 * 2. CHECK_FULL = false/true → Basic or comprehensive tests
 * 3. SELECTED_TESTS = {} → Run all, or specify: {1, 3, 5}
 * 
 * CRITICAL: MULTI-PART PATTERN
 * =============================
 * ✅ EACH PART HAS ITS OWN METHOD (different input formats!)
 * ✅ Part 1 tests never break when implementing Part 4
 * ✅ Can test parts independently
 * 
 * Methods:
 * - fillBinGaps() → Part 1 (10-digit offsets input)
 * - fillAndMergeSameBrand() → Part 2 (16-digit cards input)
 * - handleNestedIntervals() → Part 3 (16-digit cards input)
 * - mergeAdjacentIntervals() → Part 4 (16-digit cards input)
 * 
 * IMPLEMENTATION APPROACH:
 * ========================
 * 
 * PART 1 - BASIC GAP FILLING:
 * ----------------------------
 * Input: 10-digit offsets
 * Output: 16-digit card numbers
 * 
 * 1. Parse intervals and sort by start offset
 * 2. Fill gap at beginning (extend first brand backward to 0)
 * 3. Fill gaps between intervals (extend previous brand forward)
 * 4. Fill gap at end (extend last brand forward to 9999999999)
 * 5. Convert 10-digit offsets to 16-digit card numbers (BIN + offset)
 * 
 * PART 2 - FILL SAME-BRAND GAPS:
 * -------------------------------
 * Input: 16-digit card numbers (NOT offsets!)
 * Output: 16-digit card numbers
 * 
 * Rules from Part 1, PLUS:
 * - If gap exists between two intervals of SAME brand:
 * Extend one interval to fill the gap
 * Choose minimal extension (closer boundary)
 * Merge resulting intervals
 * 
 * Algorithm:
 * 1. Parse 16-digit card numbers (extract offset by removing BIN)
 * 2. Apply Part 1 rules (fill outer gaps)
 * 3. Identify same-brand gaps:
 * - Group consecutive intervals by brand
 * - If gap < threshold, extend to merge
 * 4. Merge overlapping/adjacent same-brand intervals
 * 
 * PART 3 - HANDLE NESTED INTERVALS:
 * ----------------------------------
 * Input: 16-digit card numbers
 * Output: 16-digit card numbers
 * 
 * Rules from Part 2, PLUS:
 * - If interval A is entirely within interval B (different brands):
 * Keep B (covering interval)
 * Suppress A (nested interval)
 * 
 * Algorithm:
 * 1. Apply Part 2 rules
 * 2. Detect nesting:
 * - For each pair of intervals (A, B):
 * If A.start >= B.start AND A.end <= B.end AND A.brand != B.brand
 * Mark A as nested (suppress)
 * 3. Filter out nested intervals
 * 4. Merge remaining intervals
 * 
 * PART 4 - MERGE ADJACENT INTERVALS:
 * -----------------------------------
 * Input: 16-digit card numbers
 * Output: 16-digit card numbers
 * 
 * Rules from Part 3, PLUS:
 * - Adjacent same-brand intervals (end + 1 = next start) should be merged
 * 
 * Algorithm:
 * 1. Apply Part 3 rules
 * 2. Merge adjacent:
 * - Sort intervals by start
 * - For each consecutive pair:
 * If interval[i].end + 1 == interval[i+1].start AND same brand
 * Merge: interval[i].end = interval[i+1].end
 * Remove interval[i+1]
 * 3. Return minimal representation
 * 
 * HELPER FUNCTIONS:
 * -----------------
 * String formatCardNumber(String bin, long offset) {
 * String paddedOffset = String.format("%010d", offset);
 * return bin + paddedOffset;
 * }
 * 
 * long extractOffset(String bin, String cardNumber) {
 * String offsetStr = cardNumber.substring(bin.length());
 * return Long.parseLong(offsetStr);
 * }
 * 
 * boolean isNested(Interval a, Interval b) {
 * return a.start >= b.start && a.end <= b.end && !a.brand.equals(b.brand);
 * }
 * 
 * boolean isAdjacent(Interval a, Interval b) {
 * return a.end + 1 == b.start && a.brand.equals(b.brand);
 * }
 * 
 * EXAMPLE WALKTHROUGHS:
 * =====================
 * 
 * PART 2 EXAMPLE:
 * ---------------
 * Input:
 * BIN: 424242
 * Intervals:
 * 4242420000000000,4242425000000000,VISA
 * 4242427000000000,4242429999999999,VISA
 * 
 * Step 1: Detect gap between VISA intervals
 * - First VISA ends: 4242425000000000
 * - Second VISA starts: 4242427000000000
 * - Gap: 4242425000000001 to 4242426999999999
 * 
 * Step 2: Extend to fill gap
 * - Option 1: Extend first to 4242426999999999
 * - Option 2: Extend second to 4242425000000000
 * - Both result in merged interval
 * 
 * Output: 4242420000000000,4242429999999999,VISA
 * 
 * PART 3 EXAMPLE:
 * ---------------
 * Input:
 * BIN: 424242
 * Intervals:
 * 4242420000000000,4242429999999999,CB
 * 4242420000000000,4242425000000000,VISA (1)
 * 4242425000000001,4242429999999999,VISA (2)
 * 
 * Step 1: Check for nesting
 * - VISA (1): start=0, end=5000000000
 * - VISA (2): start=5000000001, end=9999999999
 * - CB: start=0, end=9999999999
 * - Both VISA intervals are within CB range
 * 
 * Step 2: Suppress nested intervals
 * - Remove VISA (1) and VISA (2)
 * - Keep CB
 * 
 * Output: 4242420000000000,4242429999999999,CB
 * 
 * PART 4 EXAMPLE:
 * ---------------
 * Input:
 * BIN: 424242
 * Intervals:
 * 4242420000000000,4242424999999999,VISA
 * 4242425000000000,4242429999999999,VISA
 * 
 * Step 1: Check adjacency
 * - First ends: 4242424999999999
 * - Second starts: 4242425000000000
 * - Adjacent: 4999999999 + 1 = 5000000000 ✓
 * - Same brand: VISA ✓
 * 
 * Step 2: Merge
 * - Merged: 4242420000000000,4242429999999999,VISA
 * 
 * Output: 4242420000000000,4242429999999999,VISA
 * 
 * EDGE CASES:
 * -----------
 * Part 1:
 * - Unsorted intervals
 * - Overlapping intervals (same brand)
 * - Single interval
 * - Complete coverage
 * 
 * Part 2:
 * - Multiple same-brand gaps
 * - Three or more same-brand intervals
 * 
 * Part 3:
 * - Multiple nested intervals
 * - Partial overlap (not nested)
 * - No nesting
 * 
 * Part 4:
 * - Three or more adjacent intervals
 * - Mix of adjacent and non-adjacent
 * - Adjacent different brands (don't merge)
 * - Nested + adjacent combination
 * 
 * TIME COMPLEXITY:
 * ----------------
 * Part 1: O(n log n) for sorting
 * Part 2: O(n log n) + O(n) for gap filling
 * Part 3: O(n log n) + O(n²) for nesting check
 * Part 4: O(n log n) + O(n) for merging
 * 
 * SPACE COMPLEXITY: O(n) for storing intervals
 * 
 * CRITICAL NOTES:
 * ---------------
 * - Part 1: Input uses 10-digit offsets
 * - Parts 2-4: Input uses 16-digit card numbers
 * - Always maintain sorted order by start
 * - Full BIN range: BIN0000000000 to BIN9999999999
 * - Watch inclusive endpoints (end + 1 for adjacency)
 * - Each part builds on previous parts' rules
 */