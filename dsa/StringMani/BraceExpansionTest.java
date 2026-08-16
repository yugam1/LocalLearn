import java.util.*;

public class BraceExpansionTest {

        // Toggle to run extended test suite
        static final boolean RUN_FULL_TEST_SUITE = true;

        public static void main(String[] args) {
                Solution sol = new Solution();

                // Basic example tests
                test(sol.expand("/2022/{jan,feb,march}/report"),
                                List.of("/2022/jan/report", "/2022/feb/report", "/2022/march/report"),
                                "Example 1");

                test(sol.expand("over{crowd,eager,bold,fond}ness"),
                                List.of("overcrowdness", "overeagerness", "overboldness", "overfondness"),
                                "Example 2");

                test(sol.expand("read.txt{,.bak}"),
                                List.of("read.txt", "read.txt.bak"),
                                "Example 3");

                // Full test suite
                if (RUN_FULL_TEST_SUITE) {
                        runFullTestSuite(sol);
                }
        }

        // -------------------------------------------------------------------------
        // Core Solution class
        // -------------------------------------------------------------------------
        static class Solution {

                public List<String> expand(String input) {
                        System.out.println("Yugam " + input);
                        List<String> res = new ArrayList<>();

                        int start = input.indexOf('{');
                        int end = input.indexOf('}');
                        if (start == -1 || end == -1 || end < start) {
                                res.add(input);
                                return res;
                        }
                        int sCount = input.indexOf('{', start + 1);
                        int cCount = input.indexOf('}', end + 1);
                        if (sCount != -1 || cCount != -1) {
                                res.add(input);
                                return res;
                        }
                        String prefix = input.substring(0, start);
                        String suffix = input.substring(end + 1, input.length());
                        String areaOfInterest = input.substring(start + 1, end);
                        String[] arr = areaOfInterest.split(",");

                        if (arr.length < 2) {
                                res.add(input);
                                return res;
                        }
                        for (String s : arr) {
                                res.add(prefix + s + suffix);
                        }
                        return res;
                }
        }

        // -------------------------------------------------------------------------
        // Test Utility
        // -------------------------------------------------------------------------
        static void test(List<String> got, List<String> expected, String label) {
                boolean pass = got.equals(expected);

                System.out.println("\n[" + label + "]");
                System.out.println("Expected: " + expected);
                System.out.println("Got     : " + got);
                System.out.println(pass ? "=> PASS" : "=> FAIL");
        }

        // -------------------------------------------------------------------------
        // Full Edge-Case Test Suite
        // -------------------------------------------------------------------------
        static void runFullTestSuite(Solution sol) {
                System.out.println("\n========== RUNNING FULL TEST SUITE ==========\n");

                // Case: No braces at all
                test(sol.expand("hello_world"),
                                List.of("hello_world"), "No braces");

                // Case: Only opening brace
                test(sol.expand("abc{def"),
                                List.of("abc{def"), "Only opening brace");

                // Case: Only closing brace
                test(sol.expand("abc}def"),
                                List.of("abc}def"), "Only closing brace");

                // Case: Reversed braces
                test(sol.expand("abc}xyz{def"),
                                List.of("abc}xyz{def"), "Reversed braces");

                // Case: empty braces "{}"
                test(sol.expand("abc{}def"),
                                List.of("abc{}def"), "Empty braces");

                // Case: single token only → invalid
                test(sol.expand("abc{one}def"),
                                List.of("abc{one}def"), "Single token inside braces");

                // Case: Valid with empty token
                test(sol.expand("x{,a,b}y"),
                                List.of("xy", "xay", "xby"), "Token list including empty");

                // Case: multi-character tokens
                test(sol.expand("{aa,bb,ccc}end"),
                                List.of("aaend", "bbend", "cccend"), "Multi-char tokens");

                // Prefix only
                test(sol.expand("file_{1,2,3}"),
                                List.of("file_1", "file_2", "file_3"), "Prefix only");

                // Suffix only
                test(sol.expand("{u,v,w}.txt"),
                                List.of("u.txt", "v.txt", "w.txt"), "Suffix only");

                // Nested braces (not supported — treated as invalid)
                test(sol.expand("a{b{c,d},e}f"),
                                List.of("a{b{c,d},e}f"), "Nested braces → invalid");

                System.out.println("\n========== FULL SUITE COMPLETE ==========");
        }
}
