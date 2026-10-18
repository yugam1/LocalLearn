/*
 * ============================================================================
 *  LONGEST PREFIX MATCH — PBR Next-Hop Lookup
 *  Source: Interview question (networking / routing round)
 *  Difficulty: Medium
 * ============================================================================
 *
 *  PROBLEM
 *  -------
 *  A router holds a table of Policy Based Routing (PBR) entries. Each entry is
 *  a subnet written in CIDR notation plus the next-hop IP that traffic for
 *  that subnet should be sent to.
 *
 *  For every destination IP you are given, find the table entry whose subnet
 *  CONTAINS that IP. If several subnets contain it, pick the one with the
 *  LONGEST prefix length (the most specific one). Return a map of
 *  destination IP -> chosen next-hop.
 *
 *  The default route 0.0.0.0/0 contains every IP, so it is automatically the
 *  fallback when nothing more specific matches. If the table has no entry at
 *  all that contains an IP (no default route configured), map that IP to null.
 *
 *  KEY DEFINITIONS
 *  ---------------
 *  IPv4 address: 4 numbers 0..255 joined by dots = one 32-bit number.
 *      168.19.100.34 = 10101000.00010011.01100100.00100010
 *
 *  CIDR "A.B.C.D/L": the first L bits are the NETWORK part (fixed), the
 *  remaining 32-L bits are the HOST part (anything goes).
 *      168.19.100.0/24 -> first 24 bits must equal 168.19.100, last 8 free
 *                         -> covers 168.19.100.0 .. 168.19.100.255
 *      168.19.100.0/25 -> covers 168.19.100.0 .. 168.19.100.127
 *      168.19.100.34/32 -> covers exactly 168.19.100.34
 *      0.0.0.0/0        -> 0 fixed bits -> covers every IP
 *
 *  "IP is inside subnet/L"  <=>  the first L bits of IP == first L bits of subnet
 *
 *      Subnet 168.19.100.0/25
 *      ✓ 168.19.100.0     (first address)
 *      ✓ 168.19.100.127   (last address: host bits all 1)
 *      ✗ 168.19.100.128   (25th bit is 1, subnet's 25th bit is 0)
 *      ✗ 168.19.101.5     (third octet differs inside the first 24 bits)
 *
 *      Subnet 1.1.1.0/24  — compare BITS, not text
 *      ✓ 1.1.1.10
 *      ✗ 11.1.1.1         (the string starts with "1" but the first octet is 11)
 *      ✗ 1.1.10.5         (the string starts with "1.1.1" but the third octet is 10)
 *
 *  If the subnet is written with host bits set (e.g. 192.168.1.77/24), only
 *  its first L bits count: it means the same thing as 192.168.1.0/24.
 *  If the exact same subnet appears more than once, the EARLIER entry wins.
 *
 *  SAMPLE
 *  ------
 *  Prefix entries (each row is {subnet, nextHop}):
 *      168.19.100.34/32 -> 1.1.1.1
 *      168.19.100.0/25  -> 1.1.1.2
 *      168.19.100.0/24  -> 1.1.1.3
 *      168.0.0.0/8      -> 1.1.1.4
 *      0.0.0.0/0        -> 1.1.1.5
 *  IP addresses: 168.19.100.34, 168.19.100.198, 169.0.0.0
 *
 *  Analysis:
 *      168.19.100.34  is in /32, /25, /24, /8, /0  -> longest is /32 -> 1.1.1.1
 *      168.19.100.198 is in /24, /8, /0 (198 > 127, so not in the /25)
 *                                                  -> longest is /24 -> 1.1.1.3
 *      169.0.0.0      is only in /0 (169 != 168)   -> default        -> 1.1.1.5
 *
 *  Output:
 *      168.19.100.34  -> 1.1.1.1
 *      168.19.100.198 -> 1.1.1.3
 *      169.0.0.0      -> 1.1.1.5
 *
 *  CONSTRAINTS
 *  -----------
 *  0 <= prefixEntries.size() <= 10^4
 *  0 <= ipAddresses.size()   <= 10^4
 *  Every IP / subnet is a well-formed dotted quad, each octet 0..255
 *  0 <= prefix length <= 32
 *  ipAddresses may contain duplicates (the map simply has one key for them)
 *
 *  RUN
 *  ---
 *  javac LongestPrefixMatch.java && java Judge
 * ============================================================================
 */

import java.util.*;
import java.util.function.Supplier;

class RTrie {
        RTrie one;
        RTrie zero;
        String hop;

        void insert(String s, int dist, String hop) {
                RTrie node = this;
                for (int i = 0; i < dist; i++) {
                        if (s.charAt(i) == '1') {
                                if (node.one == null) {
                                        node.one = new RTrie();
                                }
                                node = node.one;
                        } else {
                                if (node.zero == null) {
                                        node.zero = new RTrie();
                                }
                                node = node.zero;
                        }
                }
                node.hop = hop;
        }

        String getHop(String s) {
                String res = null;
                RTrie node = this;
                for (int i = 0; i < 32 && node != null; i++) {
                        if (node.hop != null) {
                                res = node.hop;
                        }

                        if (s.charAt(i) == '1') {
                                node = node.one;
                        } else {
                                node = node.zero;
                        }
                }

                // Include a route stored at the final node.
                if (node != null && node.hop != null) {
                        res = node.hop;
                }
                return res;
        }

}

class Solution {
        /**
         * Resolves the next-hop for each destination IP using longest prefix match.
         *
         * @param prefixEntries routing table rows; each row is a 2-element array:
         *                      row[0] = CIDR subnet, e.g. "168.19.100.0/24"
         *                      row[1] = next-hop, e.g. "1.1.1.3"
         * @param ipAddresses   destination IPv4 addresses to look up
         * @return map of each IP to the next-hop of its most specific matching
         *         subnet, or to null if no subnet matches
         */
        public static Map<String, String> findNextHops(String[][] prefixEntries,
                        String[] ipAddresses) {
                // TODO: Implement your solution here
                RTrie trie = new RTrie();
                for (String[] entry : prefixEntries) {
                        String[] subnet = entry[0].split("/");
                        String[] parts = subnet[0].split("\\.");
                        String b = "";
                        for (String part : parts) {
                                // 1. Parse string as base-2 (binary) integer
                                int num = Integer.parseInt(part);

                                // 2. Convert back to binary and pad with zeros (Fixed parenthesis closing)
                                b += String.format("%8s", Integer.toBinaryString(num)).replace(' ', '0');
                        }
                        int prefix = Integer.parseInt(subnet[1]);
                        trie.insert(b, prefix, entry[1]);
                }

                Map<String, String> res = new HashMap<>();
                for (String ip : ipAddresses) {
                        String[] parts = ip.split("\\.");
                        String b = "";
                        for (String part : parts) {
                                // 1. Parse string as base-2 (binary) integer
                                int num = Integer.parseInt(part);

                                // 2. Convert back to binary and pad with zeros (Fixed parenthesis closing)
                                b += String.format("%8s", Integer.toBinaryString(num)).replace(' ', '0');
                        }

                        res.put(ip, trie.getHop(b));

                }
                return res;
        }

        // HINT 1: Stop thinking of an IP as text. Four octets of 8 bits each are
        // just one 32-bit number, and "is inside the subnet" is a question
        // about its leading bits.
        // HINT 2: A prefix length L describes a mask: L ones followed by 32-L zeros.
        // Applying that mask keeps the network part and erases the host part.
        // HINT 3: An IP belongs to a subnet exactly when both look identical after
        // the subnet's mask is applied to each.
        // HINT 4: There are only 33 possible prefix lengths. Grouping the table by
        // length lets you try the most specific length first and stop at
        // the first hit.
        // HINT 5: The /0 mask and the sign bit of a Java int are both traps; check
        // what your shift does when the shift amount is 32.
}

/**
 * Entry point matching the file name, so `java LongestPrefixMatch` (Code
 * Runner) works too.
 */
public class LongestPrefixMatch {
        public static void main(String[] args) {
                Judge.main(args);
        }
}

class Judge {
        private static final boolean CHECK_FULL = true;
        private static final int[] SELECTED_TESTS = {  }; // run only these test numbers when non-empty

        private static int passed = 0;
        private static int total = 0;

        public static void main(String[] args) {
                System.out.println("==============================================");
                System.out.println(" Longest Prefix Match — PBR Next-Hop Lookup");
                System.out.println(" Difficulty: Medium");
                System.out.println(" Mode: " + (CHECK_FULL ? "FULL (basic + edge cases)" : "BASIC only"));
                System.out.println(" Selected tests: "
                                + (SELECTED_TESTS.length == 0 ? "ALL" : Arrays.toString(SELECTED_TESTS)));
                System.out.println("==============================================\n");
                runTests();
                printSummary();
        }

        private static void runTests() {
                String[][] sample = entries(
                                "168.19.100.34/32", "1.1.1.1",
                                "168.19.100.0/25", "1.1.1.2",
                                "168.19.100.0/24", "1.1.1.3",
                                "168.0.0.0/8", "1.1.1.4",
                                "0.0.0.0/0", "1.1.1.5");

                // ---------------- BASIC ----------------
                test(1, "Problem sample: /32, /24 and default route each win once",
                                () -> Solution.findNextHops(sample,
                                                ips("168.19.100.34", "168.19.100.198", "169.0.0.0")),
                                map("168.19.100.34", "1.1.1.1",
                                                "168.19.100.198", "1.1.1.3",
                                                "169.0.0.0", "1.1.1.5"));

                test(2, "IP in lower half of 168.19.100.0/24 picks the /25",
                                () -> Solution.findNextHops(sample, ips("168.19.100.100")),
                                map("168.19.100.100", "1.1.1.2"));

                test(3, "IP outside 168.19.100.x but inside 168.x.x.x picks the /8",
                                () -> Solution.findNextHops(sample, ips("168.200.1.1")),
                                map("168.200.1.1", "1.1.1.4"));

                test(4, "Table with only the default route sends everything to it",
                                () -> Solution.findNextHops(entries("0.0.0.0/0", "9.9.9.9"),
                                                ips("8.8.8.8", "192.168.0.1")),
                                map("8.8.8.8", "9.9.9.9", "192.168.0.1", "9.9.9.9"));

                String[][] nested = entries(
                                "10.0.0.0/8", "A",
                                "10.1.0.0/16", "B",
                                "10.1.2.0/24", "C");
                test(5, "Nested /8, /16, /24: each IP gets its deepest containing subnet",
                                () -> Solution.findNextHops(nested, ips("10.1.2.3", "10.1.3.3", "10.2.0.0")),
                                map("10.1.2.3", "C", "10.1.3.3", "B", "10.2.0.0", "A"));

                test(6, "Table order does not matter (nested table reversed)",
                                () -> {
                                        String[][] rev = nested.clone();
                                        Collections.reverse(Arrays.asList(rev));
                                        return Solution.findNextHops(rev, ips("10.1.2.3", "10.1.3.3", "10.2.0.0"));
                                },
                                map("10.1.2.3", "C", "10.1.3.3", "B", "10.2.0.0", "A"));

                test(7, "Non-octet-aligned /20 covers 172.16.0.0-172.16.15.255 only",
                                () -> Solution.findNextHops(
                                                entries("172.16.0.0/20", "X", "0.0.0.0/0", "D"),
                                                ips("172.16.15.255", "172.16.16.0")),
                                map("172.16.15.255", "X", "172.16.16.0", "D"));

                test(8, "Edge of the /25: .127 stays in it, .128 falls to the /24",
                                () -> Solution.findNextHops(sample, ips("168.19.100.127", "168.19.100.128")),
                                map("168.19.100.127", "1.1.1.2", "168.19.100.128", "1.1.1.3"));

                if (CHECK_FULL) {
                        // ---------------- EDGE CASES ----------------
                        test(9, "Empty IP list returns an empty map",
                                        () -> Solution.findNextHops(sample, ips()),
                                        map());

                        test(10, "Empty prefix table maps every IP to null",
                                        () -> Solution.findNextHops(entries(), ips("1.2.3.4", "5.6.7.8")),
                                        map("1.2.3.4", null, "5.6.7.8", null));

                        test(11, "No match and no default route maps the IP to null",
                                        () -> Solution.findNextHops(entries("10.0.0.0/8", "A"),
                                                        ips("11.0.0.1", "10.255.255.255")),
                                        map("11.0.0.1", null, "10.255.255.255", "A"));

                        test(12, "Boundary IPs 0.0.0.0 and 255.255.255.255",
                                        () -> Solution.findNextHops(
                                                        entries("0.0.0.0/0", "D", "255.255.255.255/32", "H"),
                                                        ips("0.0.0.0", "255.255.255.255", "255.255.255.254")),
                                        map("0.0.0.0", "D", "255.255.255.255", "H", "255.255.255.254", "D"));

                        test(13, "/1 halves split at 128.0.0.0 (sign-bit trap)",
                                        () -> Solution.findNextHops(
                                                        entries("0.0.0.0/1", "L", "128.0.0.0/1", "R"),
                                                        ips("127.255.255.255", "128.0.0.0", "255.0.0.1")),
                                        map("127.255.255.255", "L", "128.0.0.0", "R", "255.0.0.1", "R"));

                        test(14, "/31 covers exactly two addresses",
                                        () -> Solution.findNextHops(
                                                        entries("192.168.1.4/31", "P", "0.0.0.0/0", "D"),
                                                        ips("192.168.1.3", "192.168.1.4", "192.168.1.5",
                                                                        "192.168.1.6")),
                                        map("192.168.1.3", "D", "192.168.1.4", "P",
                                                        "192.168.1.5", "P", "192.168.1.6", "D"));

                        test(15, "Subnet written with host bits set (192.168.1.77/24) still matches .200",
                                        () -> Solution.findNextHops(entries("192.168.1.77/24", "Q"),
                                                        ips("192.168.1.200", "192.168.2.77")),
                                        map("192.168.1.200", "Q", "192.168.2.77", null));

                        test(16, "Duplicate IPs in input produce a single key",
                                        () -> Solution.findNextHops(sample, ips("10.0.0.1", "10.0.0.1")),
                                        map("10.0.0.1", "1.1.1.5"));

                        test(17, "Duplicate subnet: the earlier entry wins",
                                        () -> Solution.findNextHops(
                                                        entries("10.0.0.0/8", "A", "10.0.0.0/8", "B"),
                                                        ips("10.9.9.9")),
                                        map("10.9.9.9", "A"));

                        test(18, "Same network address, different lengths: /24 beats /8 only inside the /24",
                                        () -> Solution.findNextHops(
                                                        entries("10.0.0.0/8", "A", "10.0.0.0/24", "B"),
                                                        ips("10.0.0.255", "10.0.1.0")),
                                        map("10.0.0.255", "B", "10.0.1.0", "A"));

                        test(19, "Bitwise, not string, compare: 1.1.1.0/24 matches 1.1.1.10, not 11.1.1.1 or 1.1.10.5",
                                        () -> Solution.findNextHops(
                                                        entries("1.1.1.0/24", "S", "0.0.0.0/0", "D"),
                                                        ips("1.1.1.10", "11.1.1.1", "1.1.10.5")),
                                        map("1.1.1.10", "S", "11.1.1.1", "D", "1.1.10.5", "D"));

                        test(20, "Large: 256 /24s + /16 + default, 10,000 lookups",
                                        () -> {
                                                String[][] big = new String[258][];
                                                big[0] = new String[] { "0.0.0.0/0", "def" };
                                                big[1] = new String[] { "10.0.0.0/16", "agg" };
                                                for (int k = 0; k < 256; k++) {
                                                        big[k + 2] = new String[] { "10.0." + k + ".0/24", "nh" + k };
                                                }
                                                return Solution.findNextHops(big, largeIps());
                                        },
                                        largeExpected());
                }
        }

        // 10.0.k.y (k < 50, y < 100) -> nhk ; 10.1.k.y (k < 25, y < 100) -> def ;
        // 11.x.x.1 -> def
        private static String[] largeIps() {
                List<String> list = new ArrayList<>();
                for (int k = 0; k < 50; k++)
                        for (int y = 0; y < 100; y++)
                                list.add("10.0." + k + "." + y); // 5,000
                for (int k = 0; k < 25; k++)
                        for (int y = 0; y < 100; y++)
                                list.add("10.1." + k + "." + y); // 2,500
                for (int y = 0; y < 2500; y++)
                        list.add("11." + (y / 256) + "." + (y % 256) + ".1"); // 2,500
                return list.toArray(new String[0]);
        }

        private static Map<String, String> largeExpected() {
                Map<String, String> m = new HashMap<>();
                for (int k = 0; k < 50; k++)
                        for (int y = 0; y < 100; y++)
                                m.put("10.0." + k + "." + y, "nh" + k);
                for (int k = 0; k < 25; k++)
                        for (int y = 0; y < 100; y++)
                                m.put("10.1." + k + "." + y, "def");
                for (int y = 0; y < 2500; y++)
                        m.put("11." + (y / 256) + "." + (y % 256) + ".1", "def");
                return m;
        }

        // ---------------- helpers ----------------
        private static String[][] entries(String... kv) {
                String[][] rows = new String[kv.length / 2][];
                for (int i = 0; i < kv.length; i += 2)
                        rows[i / 2] = new String[] { kv[i], kv[i + 1] };
                return rows;
        }

        private static String[] ips(String... ips) {
                return ips;
        }

        /** Builds a map from key/value pairs; values may be null. */
        private static Map<String, String> map(String... kv) {
                Map<String, String> m = new LinkedHashMap<>();
                for (int i = 0; i < kv.length; i += 2)
                        m.put(kv[i], kv[i + 1]);
                return m;
        }

        private static boolean selected(int n) {
                if (SELECTED_TESTS.length == 0)
                        return true;
                for (int t : SELECTED_TESTS)
                        if (t == n)
                                return true;
                return false;
        }

        private static void test(int n, String desc, Supplier<Map<String, String>> actual,
                        Map<String, String> expected) {
                if (!selected(n))
                        return;
                total++;
                Map<String, String> got;
                try {
                        got = actual.get();
                } catch (Exception e) {
                        System.out.println("✗ FAIL [#" + n + "]: " + desc);
                        System.out.println("    Expected: " + preview(expected));
                        System.out.println("    Got:      exception " + e);
                        return;
                }
                // Map.equals is order-insensitive and handles null values correctly.
                if (expected.equals(got)) {
                        passed++;
                        System.out.println("✓ PASS [#" + n + "]: " + desc);
                } else {
                        System.out.println("✗ FAIL [#" + n + "]: " + desc);
                        System.out.println("    Expected: " + preview(expected));
                        System.out.println("    Got:      " + preview(got));
                }
        }

        private static String preview(Map<String, String> m) {
                if (m == null)
                        return "null";
                if (m.size() <= 6)
                        return m.toString();
                Iterator<Map.Entry<String, String>> it = m.entrySet().iterator();
                StringBuilder sb = new StringBuilder("{");
                for (int i = 0; i < 3; i++)
                        sb.append(it.next()).append(i < 2 ? ", " : "");
                return sb.append(", ... (").append(m.size()).append(" entries)}").toString();
        }

        private static void printSummary() {
                System.out.println("\n==============================================");
                System.out.println(" Result: " + passed + "/" + total + " passed");
                if (!CHECK_FULL)
                        System.out.println(" Tip: set CHECK_FULL = true to run the edge-case tests.");
                if (SELECTED_TESTS.length > 0)
                        System.out.println(" Note: only SELECTED_TESTS ran. Empty the array to run everything.");
                else
                        System.out.println(" Tip: put test numbers in SELECTED_TESTS to run just those.");
                System.out.println("==============================================");
        }
}

/*
 * ============================================================================
 * SPOILER BELOW
 * ============================================================================
 *
 * OPTIMAL APPROACH: bucket the table by prefix length, probe longest first
 * -----------------------------------------------------------------------
 *
 * // public static Map<String, String> findNextHops(String[][] prefixEntries,
 * // String[] ipAddresses) {
 * // // byLen[L] : masked network address -> next-hop, for prefixes of length L
 * // List<Map<Integer, String>> byLen = new ArrayList<>();
 * // for (int L = 0; L <= 32; L++) byLen.add(new HashMap<>());
 * //
 * // for (String[] row : prefixEntries) {
 * // String[] parts = row[0].split("/");
 * // String nextHop = row[1];
 * // int len = Integer.parseInt(parts[1]);
 * // int network = toInt(parts[0]) & mask(len);
 * // byLen.get(len).putIfAbsent(network, nextHop); // earlier entry wins
 * // }
 * //
 * // Map<String, String> result = new LinkedHashMap<>();
 * // for (String ip : ipAddresses) {
 * // int addr = toInt(ip);
 * // String hop = null;
 * // for (int L = 32; L >= 0 && hop == null; L--) {
 * // hop = byLen.get(L).get(addr & mask(L));
 * // }
 * // result.put(ip, hop);
 * // }
 * // return result;
 * // }
 * //
 * // private static int toInt(String ip) {
 * // String[] o = ip.split("\\.");
 * // return (Integer.parseInt(o[0]) << 24) | (Integer.parseInt(o[1]) << 16)
 * // | (Integer.parseInt(o[2]) << 8) | Integer.parseInt(o[3]);
 * // }
 * //
 * // // L leading ones. Java masks shift counts to 5 bits, so -1 << 32 == -1;
 * // // /0 must be special-cased to 0.
 * // private static int mask(int len) {
 * // return len == 0 ? 0 : -1 << (32 - len);
 * // }
 *
 * KEY INSIGHTS
 * ------------
 * - An IPv4 address is a 32-bit integer; "a.b.c.d" is just a way to print it.
 * - "IP in subnet/L" means (ip & mask(L)) == (subnet & mask(L)). Masking the
 * subnet once at load time also normalizes entries like 192.168.1.77/24.
 * - Longest prefix = most specific = try L = 32, 31, ..., 0 and stop at the
 * first bucket that contains the masked IP. At most 33 hash lookups per IP.
 * - 0.0.0.0/0 needs no special case in the lookup: mask(0) = 0, so every IP
 * masks to 0 and hits it last.
 * - Signed ints are fine here: we only compare bit patterns for equality,
 * never use < or >.
 *
 * WALKTHROUGH (sample)
 * --------------------
 * Buckets after loading:
 * L=32: {168.19.100.34 -> 1.1.1.1}
 * L=25: {168.19.100.0 -> 1.1.1.2}
 * L=24: {168.19.100.0 -> 1.1.1.3}
 * L=8 : {168.0.0.0 -> 1.1.1.4}
 * L=0 : {0.0.0.0 -> 1.1.1.5}
 *
 * 168.19.100.34:
 * L=32: masked 168.19.100.34 -> found 1.1.1.1. Done.
 *
 * 168.19.100.198 (last octet 198 = 1100 0110):
 * L=32: 168.19.100.198 not in bucket
 * L=31..26: buckets empty
 * L=25: masked keeps the top bit of 198 -> 168.19.100.128, bucket has .0 ->
 * miss
 * L=24: masked -> 168.19.100.0 -> found 1.1.1.3. Done.
 *
 * 169.0.0.0:
 * L=32, 25, 24: masked values start with 169 -> miss
 * L=8 : masked 169.0.0.0, bucket has 168.0.0.0 -> miss
 * L=0 : masked 0.0.0.0 -> found 1.1.1.5. Done.
 *
 * Result: {168.19.100.34=1.1.1.1, 168.19.100.198=1.1.1.3, 169.0.0.0=1.1.1.5}
 *
 * COMPLEXITY
 * ----------
 * Time: O(N + 33·M) = O(N + M) — each of N entries is hashed once, each of M
 * IPs does at most 33 constant-time hash lookups.
 * Space: O(N + M) — the buckets hold N networks; the result holds M keys.
 * (A binary trie on the 32 bits gives the same bounds and is what real
 * routers use; the bucket version is easier to write in an interview.)
 *
 * COMMON MISTAKES
 * ---------------
 * - Comparing strings with startsWith: "1.1.10.5".startsWith("1.1.1") is
 * true, yet 1.1.10.5 is not in 1.1.1.0/24, and text cannot express a /25
 * at all. Compare bits.
 * - Only handling /8, /16, /24 by comparing whole octets; /20, /25, /31 cut
 * through the middle of an octet.
 * - Writing the mask as -1 << (32 - len) without a /0 case: Java treats a
 * shift of 32 as a shift of 0, so /0 becomes "match only 0.0.0.0".
 * - Comparing ip & mask with the raw subnet instead of subnet & mask, which
 * breaks on subnets written with host bits set.
 * - Picking the first matching entry in table order instead of the longest
 * prefix, or sorting by prefix and forgetting that ties need a rule.
 * ============================================================================
 */
