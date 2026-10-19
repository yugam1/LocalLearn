/*
 * ============================================================================
 *  IP to CIDR
 *  Source: LeetCode 751 (Premium)   |   Difficulty: Medium
 * ============================================================================
 *
 *  PROBLEM
 *  -------
 *  You are given a start IPv4 address `ip` and a count `n`. Return the
 *  shortest list of CIDR blocks that covers EXACTLY the n consecutive
 *  addresses ip, ip+1, ..., ip+n-1 — no address outside that range may be
 *  covered. Blocks are listed in ascending address order.
 *
 *  KEY DEFINITIONS
 *  ---------------
 *  IPv4 address: four 8-bit numbers "a.b.c.d", read as one 32-bit integer
 *  (a<<24 | b<<16 | c<<8 | d).
 *
 *  CIDR block "a.b.c.d/p": the 2^(32-p) addresses whose first p bits match
 *  a.b.c.d. A block of size 2^k must START at a multiple of 2^k.
 *
 *    ✓ "10.0.0.8/29"   -> 8 addresses 10.0.0.8 .. 10.0.0.15 (8 is a multiple of 8)
 *    ✓ "10.0.0.7/32"   -> exactly 1 address, 10.0.0.7
 *    ✓ "10.0.0.0/24"   -> 256 addresses 10.0.0.0 .. 10.0.0.255
 *    ✗ "10.0.0.4/29"   -> 4 is not a multiple of 8; not an aligned block start
 *    ✗ "10.0.0.6/30"   -> 6 is not a multiple of 4; not an aligned block start
 *
 *  SAMPLE
 *  ------
 *  Input:  ip = "255.0.0.7", n = 10
 *  Range:  255.0.0.7 .. 255.0.0.16
 *  Analysis:
 *    - 7 is odd, so only a size-1 block can start there   -> 255.0.0.7/32
 *    - 8 is aligned to 8, and 8 addresses still fit (9 left) -> 255.0.0.8/29
 *    - 1 address left at 16                                -> 255.0.0.16/32
 *  Output: ["255.0.0.7/32", "255.0.0.8/29", "255.0.0.16/32"]
 *
 *  CONSTRAINTS
 *  -----------
 *  - 7 <= ip.length <= 15
 *  - ip is a valid IPv4 address "a.b.c.d", each part in [0, 255]
 *  - 1 <= n <= 1000
 *  - Every address ip + x for 0 <= x < n is a valid IPv4 address
 *
 *  RUN
 *  ---
 *  javac IpToCidr.java && java Judge
 * ============================================================================
 */

import java.util.*;
import java.util.function.Supplier;

class Solution {

    /**
     * Covers the n consecutive IPv4 addresses starting at ip with the fewest
     * CIDR blocks.
     *
     * @param ip start address in dotted-decimal form, e.g. "255.0.0.7"
     * @param n  number of consecutive addresses to cover (1..1000)
     * @return CIDR blocks in ascending address order, e.g. ["255.0.0.7/32", ...]
     */
    public List<String> ipToCIDR(String ip, int n) {
        // TODO: Implement your solution here
        return new ArrayList<>();
    }

    // HINT 1: Think of the address as one 32-bit number; dotted form is just
    //         a way of printing it. Watch out for Java's signed int when the
    //         first octet is 128 or more.
    // HINT 2: A block of size 2^k can only start at a multiple of 2^k. The
    //         lowest set bit of the current address tells you the biggest
    //         size allowed there.
    // HINT 3: The biggest aligned block may still be too large for how many
    //         addresses you have left. Shrink it until it fits.
    // HINT 4: Address 0 has no set bit, so it is aligned to every size —
    //         handle it so your size doesn't come out as 0.
    // HINT 5: Taking the largest valid block at each step is optimal; you
    //         never need to look ahead.
}

class Judge {

    private static final boolean CHECK_FULL = false;
    private static final int[] SELECTED_TESTS = {};  // run only these test numbers when non-empty

    private static int passed = 0;
    private static int total = 0;

    public static void main(String[] args) {
        System.out.println("============================================================");
        System.out.println("  IP to CIDR  (LeetCode 751)");
        System.out.println("  Difficulty : Medium");
        System.out.println("  Mode       : " + (CHECK_FULL ? "FULL (basic + edge cases)" : "BASIC only"));
        System.out.println("  Selected   : " + (SELECTED_TESTS.length == 0 ? "all" : Arrays.toString(SELECTED_TESTS)));
        System.out.println("============================================================");
        System.out.println();

        runTests();
        printSummary();
    }

    private static void runTests() {
        Solution s = new Solution();

        // ---------------- Basic tests ----------------
        test(1, "Sample: 255.0.0.7 with n=10 needs 3 blocks",
                List.of("255.0.0.7/32", "255.0.0.8/29", "255.0.0.16/32"),
                () -> s.ipToCIDR("255.0.0.7", 10));

        test(2, "Even start 117.145.102.62 with n=8 splits /31, /30, /31",
                List.of("117.145.102.62/31", "117.145.102.64/30", "117.145.102.68/31"),
                () -> s.ipToCIDR("117.145.102.62", 8));

        test(3, "Single address gives one /32",
                List.of("1.2.3.4/32"),
                () -> s.ipToCIDR("1.2.3.4", 1));

        test(4, "Aligned start, n=256 is exactly one /24",
                List.of("10.0.0.0/24"),
                () -> s.ipToCIDR("10.0.0.0", 256));

        test(5, "Aligned start, n=4 is exactly one /30",
                List.of("10.0.0.0/30"),
                () -> s.ipToCIDR("10.0.0.0", 4));

        test(6, "Odd start, n=3 gives /32 then /31",
                List.of("192.168.1.1/32", "192.168.1.2/31"),
                () -> s.ipToCIDR("192.168.1.1", 3));

        test(7, "Aligned to 8 but n=7 forces shrinking: /30, /31, /32",
                List.of("10.0.0.8/30", "10.0.0.12/31", "10.0.0.14/32"),
                () -> s.ipToCIDR("10.0.0.8", 7));

        // ---------------- Edge-case tests ----------------
        if (CHECK_FULL) {
            test(8, "Minimum: address 0.0.0.0 with n=1 (zero has no set bit)",
                    List.of("0.0.0.0/32"),
                    () -> s.ipToCIDR("0.0.0.0", 1));

            test(9, "0.0.0.0 with max n=1000 decomposes into 6 blocks",
                    List.of("0.0.0.0/23", "0.0.2.0/24", "0.0.3.0/25",
                            "0.0.3.128/26", "0.0.3.192/27", "0.0.3.224/29"),
                    () -> s.ipToCIDR("0.0.0.0", 1000));

            test(10, "Upper boundary 255.255.255.255 with n=1",
                    List.of("255.255.255.255/32"),
                    () -> s.ipToCIDR("255.255.255.255", 1));

            test(11, "Last /24 of the address space, 255.255.255.0 with n=256",
                    List.of("255.255.255.0/24"),
                    () -> s.ipToCIDR("255.255.255.0", 256));

            test(12, "Crossing an octet boundary 10.0.0.255 -> 10.0.1.0",
                    List.of("10.0.0.255/32", "10.0.1.0/32"),
                    () -> s.ipToCIDR("10.0.0.255", 2));

            test(13, "High bit set: 128.0.0.0 with n=2 is one /31",
                    List.of("128.0.0.0/31"),
                    () -> s.ipToCIDR("128.0.0.0", 2));

            test(14, "Repeated octets 1.1.1.1 with n=2 gives two /32 blocks",
                    List.of("1.1.1.1/32", "1.1.1.2/32"),
                    () -> s.ipToCIDR("1.1.1.1", 2));

            test(15, "Start aligned to 2 with n=2 is one /31",
                    List.of("172.16.0.6/31"),
                    () -> s.ipToCIDR("172.16.0.6", 2));

            test(16, "Two equal-size /29 blocks from 8.8.8.8 with n=16",
                    List.of("8.8.8.8/29", "8.8.8.16/29"),
                    () -> s.ipToCIDR("8.8.8.8", 16));

            test(17, "Large input: 10.0.0.1 with n=1000 needs 15 blocks",
                    List.of("10.0.0.1/32", "10.0.0.2/31", "10.0.0.4/30", "10.0.0.8/29",
                            "10.0.0.16/28", "10.0.0.32/27", "10.0.0.64/26", "10.0.0.128/25",
                            "10.0.1.0/24", "10.0.2.0/24", "10.0.3.0/25", "10.0.3.128/26",
                            "10.0.3.192/27", "10.0.3.224/29", "10.0.3.232/32"),
                    () -> s.ipToCIDR("10.0.0.1", 1000));
        }
    }

    private static boolean isSelected(int num) {
        if (SELECTED_TESTS.length == 0) return true;
        for (int t : SELECTED_TESTS) if (t == num) return true;
        return false;
    }

    private static void test(int num, String desc, List<String> expected, Supplier<List<String>> run) {
        if (!isSelected(num)) return;
        total++;
        Object got;
        boolean ok;
        try {
            List<String> result = run.get();
            got = result;
            ok = expected.equals(result);  // order matters: blocks are in ascending address order
        } catch (Exception e) {
            got = "Exception: " + e;
            ok = false;
        }
        if (ok) {
            passed++;
            System.out.println("✓ PASS [#" + num + "]: " + desc);
        } else {
            System.out.println("✗ FAIL [#" + num + "]: " + desc);
            System.out.println("    Expected: " + expected);
            System.out.println("    Got:      " + got);
        }
    }

    private static void printSummary() {
        System.out.println();
        System.out.println("------------------------------------------------------------");
        System.out.println("  Result: " + passed + "/" + total + " passed");
        if (!CHECK_FULL) {
            System.out.println("  Tip: set CHECK_FULL = true to run the edge-case tests.");
        }
        System.out.println("  Tip: fill SELECTED_TESTS (e.g. {2, 9}) to run specific tests only.");
        System.out.println("------------------------------------------------------------");
    }
}

/*
 * ============================================================================
 *  SPOILER BELOW
 * ============================================================================
 *
 *  OPTIMAL APPROACH (greedy, largest aligned block that fits)
 *  ----------------------------------------------------------
 *
 *  // public List<String> ipToCIDR(String ip, int n) {
 *  //     long x = 0;
 *  //     for (String part : ip.split("\\.")) {
 *  //         x = x * 256 + Integer.parseInt(part);
 *  //     }
 *  //     List<String> result = new ArrayList<>();
 *  //     while (n > 0) {
 *  //         long size = x & -x;              // largest block aligned at x
 *  //         if (size == 0) size = 1L << 32;  // x == 0 is aligned to everything
 *  //         while (size > n) size >>= 1;     // must not overshoot the range
 *  //         int prefix = 32 - Long.numberOfTrailingZeros(size);
 *  //         result.add(toIp(x) + "/" + prefix);
 *  //         x += size;
 *  //         n -= (int) size;
 *  //     }
 *  //     return result;
 *  // }
 *  //
 *  // private String toIp(long x) {
 *  //     return ((x >> 24) & 255) + "." + ((x >> 16) & 255) + "."
 *  //          + ((x >> 8) & 255) + "." + (x & 255);
 *  // }
 *
 *  KEY INSIGHTS
 *  ------------
 *  - A /p block holds 2^(32-p) addresses and must start at a multiple of
 *    that size; the lowest set bit of x (x & -x) is the largest size allowed.
 *  - The block also has to fit in the remaining count, so halve the size
 *    until size <= n.
 *  - Taking the largest valid block each step is optimal: any cover must
 *    start a block at x, and no valid block at x is bigger than this one.
 *  - Use a long for the address: an int goes negative once the first octet
 *    is >= 128, and 0 needs a size beyond int range.
 *  - prefix = 32 - log2(size), and log2 of a power of two is its count of
 *    trailing zeros.
 *
 *  WALKTHROUGH: ip = "255.0.0.7", n = 10 (last octet shown)
 *  --------------------------------------------------------
 *  x ends 7  (..0111): size = 1;              1 <= 10 -> "255.0.0.7/32",  n = 9
 *  x ends 8  (..1000): size = 8;              8 <= 9  -> "255.0.0.8/29",  n = 1
 *  x ends 16 (.10000): size = 16 -> 8,4,2,1;  1 <= 1  -> "255.0.0.16/32", n = 0
 *  Result: ["255.0.0.7/32", "255.0.0.8/29", "255.0.0.16/32"]
 *
 *  COMPLEXITY
 *  ----------
 *  Time:  O(log n * 32) ~ O(1) — each block at least halves what is left of
 *         the misalignment or the count, so there are O(log n) blocks, each
 *         needing at most 32 halvings.
 *  Space: O(1) extra beyond the output list.
 *
 *  COMMON MISTAKES
 *  ---------------
 *  - Storing the address in an int: 128.x.x.x and above become negative and
 *    x & -x or the shifts go wrong.
 *  - Forgetting x == 0: x & -x is 0 and the loop never advances.
 *  - Picking only by alignment and covering addresses past ip + n - 1.
 *  - Picking only by remaining count (largest power of two <= n) and
 *    producing a misaligned block like "10.0.0.4/29".
 *  - Printing octets with signed shifts or without masking with & 255.
 * ============================================================================
 */