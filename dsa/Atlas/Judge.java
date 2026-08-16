import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
ATLAS COMPANY NAME CHECK (Stripe)

Platform: Stripe Interview / HackerRank
Difficulty: MEDIUM

Background:
Stripe Atlas enables founders to remotely incorporate a US-based company from 
anywhere. Founders provide a company name, and Stripe validates that the name 
isn't being used by another company. Names don't have to be exactly identical 
to be considered the same - the government disallows names that are too similar.

=============================================================================
NORMALIZATION RULES
=============================================================================
1. Capitalization is ignored (case-insensitive)
   "Llama, Inc." = "LLAMA, Inc."

2. Ampersands (&) and commas (,) are treated as spaces
   "Llama & Friends" → "Llama   Friends"

3. Multiple spaces in a row are treated as a single space
   "Llama    Inc" → "Llama Inc"

4. Company name suffixes are ignored (case-insensitive):
   - "Inc.", "Corp.", "LLC", "L.L.C.", "LLC."
   "Llama, Inc." = "Llama, LLC"

5. Leading "The", "An", and "A" are ignored
   "Llama, Inc." = "The Llama, Inc."

6. "And" is ignored, UNLESS it is at the beginning of the company name
   "Llama Friend, Inc." = "Llama And Friend, Inc."
   BUT "And Llama Friend, Inc." is DIFFERENT from "Llama Friend, Inc."

7. After all transformations, if the name is empty or only contains spaces,
   it should be considered "Name Not Available"

=============================================================================
PART 1: BASIC NAME AVAILABILITY CHECK
=============================================================================

Task:
Implement function `check_availability` which takes a list of account IDs 
and their corresponding name requests.

For each requested name:
- Determine "Name Available" or "Name Not Available" based on name's availability
- If name is currently available, mark it as no longer available (register it)
- If name is not available, report that it is unavailable

Input:
Each line: "account_id|proposed_name"
Example: "acct_12345|Llama Industries, Inc."

Output:
For each request: "account_id|Name Available" or "account_id|Name Not Available"
Example: "acct_12345|Name Available"

Example 1:
Input:
  acct_12345|Llama Industries, Inc.

Output:
  acct_12345|Name Available

Explanation: First request, name not taken, so available and then registered.

Example 2:
Input:
  acct_12345|Llama Industries, Inc.
  acct_67890|Llama Industries, LLC

Output:
  acct_12345|Name Available
  acct_67890|Name Not Available

Explanation: Second request normalizes to same name as first (suffix ignored),
so it's not available.

=============================================================================
PART 2: PERSISTENT REGISTRATION TRACKING
=============================================================================

Extend Part 1 to maintain a permanent record of all registered names.

If a merchant re-submits a name (after normalization) that they or another 
merchant previously registered, it should be marked as unavailable.

The system must retain the state of registered names across all requests.

Track which account registered which name.

=============================================================================
PART 3: NAME RECLAMATION REQUESTS
=============================================================================

Companies may dissolve, freeing up their names for reuse. Support reclamation 
requests to remove a previously registered name from the unavailable list.

New Input Format:
- Regular request: "account_id|proposed_name"
- Reclamation: "RECLAIM,account_id,original_proposed_name"

Rules:
- When processing reclamation, the normalized version of the original name 
  is removed from unavailable list
- Only the account that originally registered the name can reclaim it
- If wrong account tries to reclaim, ignore the request (no output)
- If name wasn't registered, ignore the request (no output)

Output:
- Regular requests: "account_id|Name Available" or "account_id|Name Not Available"
- Reclamation requests: No output (silent operation)

Example:
Input:
  acct_12345|Llama Industries, Inc.
  RECLAIM,acct_12345,Llama Industries, Inc.
  acct_67890|Llama Industries, LLC

Output:
  acct_12345|Name Available
  acct_67890|Name Available

Explanation: First name registered, then reclaimed by same account, 
then available again for different account.

Constraints:
- 1 <= number of requests <= 10000
- Account IDs are alphanumeric strings
- Company names are ASCII strings with length 1-100
- All suffixes are one of: ["Inc.", "Corp.", "LLC", "L.L.C.", "LLC."]
*/
// Helper class

class NameProcessor {
    String processedString;

    NameProcessor() {
        processedString = "";
    }

    void init(String name) {
        processedString = name;
    }

    void ignoreCaps() {
        processedString = processedString.toLowerCase();
    }

    void removeSpecialChars() {
        processedString = processedString.replaceAll("&", " ");
        processedString = processedString.replaceAll(",", " ");
    }

    void removeMultiSpace() {
        processedString = processedString.replaceAll("\\s+", " ").trim();
    }

    void removeCompanySuffix() {
        String[] suffixMaster = new String[] { "inc.", "llc.", "corp.", "llc", "l.l.c." };
        for (String s : suffixMaster) {
            processedString = processedString.replaceAll(s, "");
        }
    }

    void removePrefix() {
        String[] suffixMaster = new String[] { "the ", "an ", "a " };
        for (String s : suffixMaster) {
            while (processedString.indexOf(s) == 0) {
                processedString = processedString.replaceAll(s, "");
            }
        }
    }

    void removeStringInMiddle() {
        String[] suffixMaster = new String[] { "and" };
        for (String s : suffixMaster) {
            int idx = processedString.indexOf(s);
            while (idx != 0 && idx != -1) {
                processedString = processedString.replaceAll(s, "");
                idx = processedString.indexOf(s);
            }
        }
    }

    void trim() {
        processedString = processedString.trim();
    }

    void startProcessingChain() {
        this.ignoreCaps();
        this.removeSpecialChars();
        this.trim();
        this.removePrefix();
        this.removeCompanySuffix();
        this.trim();
        this.removeStringInMiddle();
        this.removeMultiSpace();
        this.trim();
    }

    String getProcessedName() {
        return processedString;
    }
}

// ============= SOLUTION CLASS =============
class Solution {
    Map<String, String> notAvailable;

    /**
     * Process name availability requests and reclamation requests.
     * 
     * @param requests Array of request strings (regular or RECLAIM)
     * @return List of output strings for regular requests only
     */
    public List<String> checkAvailability(String[] requests) {
        notAvailable = new HashMap<>();
        List<String> res = new ArrayList<>();
        NameProcessor processor = new NameProcessor();
        for (String comp : requests) {
            System.out.println(comp);
            String[] arr = comp.split("\\|");
            String companyName = "";
            String accountId = "";
            String requestType = "NORMAL";
            if (arr[0].equals("RECLAIM")) {
                requestType = arr[0];
                companyName = arr[2];
                accountId = arr[1];
            } else {
                companyName = arr[1];
                accountId = arr[0];
            }
            processor.init(companyName);
            processor.startProcessingChain();
            String name = processor.getProcessedName();
            if (requestType.equals("RECLAIM")) {
                if (notAvailable.containsKey(name) && notAvailable.get(name).equals(accountId)) {
                    notAvailable.remove(name);
                }
            } else if (notAvailable.containsKey(name) || name.length() == 0) {
                res.add(accountId + "|Name Not Available");
            } else {
                notAvailable.put(name, accountId);
                res.add(accountId + "|Name Available");
            }
        }
        System.out.println();
        return res;
    }

    /**
     * Normalize a company name according to the rules.
     * 
     * @param name Original company name
     * @return Normalized name
     */
    private String normalizeName(String name) {
        // TODO: Implement normalization
        // 1. Convert to lowercase
        // 2. Replace & and , with spaces
        // 3. Collapse multiple spaces
        // 4. Remove suffixes
        // 5. Remove leading The/An/A
        // 6. Remove And (except at start)
        // 7. Trim and check if empty

        return "";
    }
}

// ============= JUDGE CLASS =============
class Judge {
    // ========== CONFIGURATION FLAGS ==========
    private static final int PART = 2; // Which part to test: 1, 2, or 3
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
        System.out.println("ATLAS COMPANY NAME CHECK (Stripe)");
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
            default:
                System.out.println("❌ Invalid PART selected. Choose 1, 2, or 3.");
                return;
        }

        // Print summary
        printSummary();
    }

    // ============= PART 1 TESTS =============
    private static void runPart1Tests(Solution solution) {
        System.out.println("\n=== PART 1: BASIC NAME AVAILABILITY CHECK ===\n");

        System.out.println("--- Basic Tests ---");

        // Test 1: Single request, available
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_12345|Llama Industries, Inc."
                }),
                Arrays.asList(
                        "acct_12345|Name Available"),
                "Part 1: Single request - available");

        // Test 2: Duplicate with suffix difference
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_12345|Llama Industries, Inc.",
                        "acct_67890|Llama Industries, LLC"
                }),
                Arrays.asList(
                        "acct_12345|Name Available",
                        "acct_67890|Name Not Available"),
                "Part 1: Duplicate name (different suffix)");

        // Test 3: Case insensitive
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_001|LLAMA Inc.",
                        "acct_002|llama inc."
                }),
                Arrays.asList(
                        "acct_001|Name Available",
                        "acct_002|Name Not Available"),
                "Part 1: Case insensitive matching");

        // Test 4: Leading article ignored
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_001|Llama Company, Inc.",
                        "acct_002|The Llama Company, Inc."
                }),
                Arrays.asList(
                        "acct_001|Name Available",
                        "acct_002|Name Not Available"),
                "Part 1: Leading 'The' ignored");

        // Test 5: 'And' removed (not at start)
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_001|Llama Friend, Inc.",
                        "acct_002|Llama And Friend, Inc."
                }),
                Arrays.asList(
                        "acct_001|Name Available",
                        "acct_002|Name Not Available"),
                "Part 1: 'And' ignored (not at start)");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 6: And at beginning (preserved)
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|Llama Friend, Inc.",
                            "acct_002|And Llama Friend, Inc."
                    }),
                    Arrays.asList(
                            "acct_001|Name Available",
                            "acct_002|Name Available"),
                    "Edge: 'And' at beginning is different");

            // Test 7: Ampersand and comma as spaces
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|Smith & Jones Corp.",
                            "acct_002|Smith Jones Corp."
                    }),
                    Arrays.asList(
                            "acct_001|Name Available",
                            "acct_002|Name Not Available"),
                    "Edge: Ampersand treated as space");

            // Test 8: Multiple spaces collapsed
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|Llama    Industries    Inc.",
                            "acct_002|Llama Industries Inc."
                    }),
                    Arrays.asList(
                            "acct_001|Name Available",
                            "acct_002|Name Not Available"),
                    "Edge: Multiple spaces collapsed");

            // Test 9: Empty after normalization
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|Inc.",
                            "acct_002|Corp."
                    }),
                    Arrays.asList(
                            "acct_001|Name Not Available",
                            "acct_002|Name Not Available"),
                    "Edge: Empty after normalization");

            // Test 10: All suffixes recognized
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|TechCo Inc.",
                            "acct_002|TechCo Corp.",
                            "acct_003|TechCo LLC",
                            "acct_004|TechCo L.L.C.",
                            "acct_005|TechCo LLC."
                    }),
                    Arrays.asList(
                            "acct_001|Name Available",
                            "acct_002|Name Not Available",
                            "acct_003|Name Not Available",
                            "acct_004|Name Not Available",
                            "acct_005|Name Not Available"),
                    "Edge: All suffix types recognized");

            // Test 11: Leading articles (A, An, The)
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|Elephant LLC",
                            "acct_002|An Elephant LLC",
                            "acct_003|A Elephant LLC",
                            "acct_004|The Elephant LLC"
                    }),
                    Arrays.asList(
                            "acct_001|Name Available",
                            "acct_002|Name Not Available",
                            "acct_003|Name Not Available",
                            "acct_004|Name Not Available"),
                    "Edge: All leading articles (A, An, The)");
        }
    }

    // ============= PART 2 TESTS =============
    private static void runPart2Tests(Solution solution) {
        System.out.println("\n=== PART 2: PERSISTENT REGISTRATION TRACKING ===\n");

        System.out.println("--- Basic Tests ---");

        // Test 1: Track original registrant
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_001|TechCorp Inc.",
                        "acct_002|TechCorp LLC",
                        "acct_001|TechCorp Corp."
                }),
                Arrays.asList(
                        "acct_001|Name Available",
                        "acct_002|Name Not Available",
                        "acct_001|Name Not Available"),
                "Part 2: Same account re-requests own name");

        // Test 2: Multiple accounts, persistent state
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_001|Alpha Inc.",
                        "acct_002|Beta LLC",
                        "acct_003|Alpha Corp.",
                        "acct_004|Beta Inc."
                }),
                Arrays.asList(
                        "acct_001|Name Available",
                        "acct_002|Name Available",
                        "acct_003|Name Not Available",
                        "acct_004|Name Not Available"),
                "Part 2: Persistent tracking across accounts");

        // Test 3: Complex sequence
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_001|DataFlow Inc.",
                        "acct_002|CloudSync LLC",
                        "acct_003|DataFlow Corp.",
                        "acct_004|NewCo Inc.",
                        "acct_005|CloudSync Inc."
                }),
                Arrays.asList(
                        "acct_001|Name Available",
                        "acct_002|Name Available",
                        "acct_003|Name Not Available",
                        "acct_004|Name Available",
                        "acct_005|Name Not Available"),
                "Part 2: Complex registration sequence");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 4: Many requests for same name
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|Popular Inc.",
                            "acct_002|Popular LLC",
                            "acct_003|Popular Corp.",
                            "acct_004|Popular L.L.C."
                    }),
                    Arrays.asList(
                            "acct_001|Name Available",
                            "acct_002|Name Not Available",
                            "acct_003|Name Not Available",
                            "acct_004|Name Not Available"),
                    "Edge: Multiple attempts for same name");
        }
    }

    // ============= PART 3 TESTS =============
    private static void runPart3Tests(Solution solution) {
        System.out.println("\n=== PART 3: NAME RECLAMATION REQUESTS ===\n");

        System.out.println("--- Basic Tests ---");

        // Test 1: Simple reclaim by original owner
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_001|TechCorp Inc.",
                        "RECLAIM|acct_001|TechCorp Inc.",
                        "acct_002|TechCorp LLC"
                }),
                Arrays.asList(
                        "acct_001|Name Available",
                        "acct_002|Name Available"),
                "Part 3: Reclaim by original owner");

        // Test 2: Reclaim attempt by wrong account (ignored)
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_001|DataCo Inc.",
                        "RECLAIM|acct_002|DataCo Inc.",
                        "acct_002|DataCo LLC"
                }),
                Arrays.asList(
                        "acct_001|Name Available",
                        "acct_002|Name Not Available"),
                "Part 3: Reclaim by wrong account (ignored)");

        // Test 3: Reclaim non-existent name (ignored)
        test(
                () -> solution.checkAvailability(new String[] {
                        "RECLAIM|acct_001|NonExistent Inc.",
                        "acct_001|NewCo LLC"
                }),
                Arrays.asList(
                        "acct_001|Name Available"),
                "Part 3: Reclaim non-existent name (ignored)");

        // Test 4: Multiple reclaims
        test(
                () -> solution.checkAvailability(new String[] {
                        "acct_001|Alpha Inc.",
                        "acct_002|Beta LLC",
                        "RECLAIM|acct_001|Alpha Inc.",
                        "RECLAIM|acct_002|Beta LLC",
                        "acct_003|Alpha Corp.",
                        "acct_004|Beta Inc."
                }),
                Arrays.asList(
                        "acct_001|Name Available",
                        "acct_002|Name Available",
                        "acct_003|Name Available",
                        "acct_004|Name Available"),
                "Part 3: Multiple reclaims");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 5: Reclaim with different suffix
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|GammaCo Inc.",
                            "RECLAIM|acct_001|GammaCo LLC",
                            "acct_002|GammaCo Corp."
                    }),
                    Arrays.asList(
                            "acct_001|Name Available",
                            "acct_002|Name Available"),
                    "Edge: Reclaim with different suffix (normalized)");

            // Test 6: Reclaim and re-register by same account
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|DeltaCo Inc.",
                            "RECLAIM|acct_001|DeltaCo Inc.",
                            "acct_001|DeltaCo LLC"
                    }),
                    Arrays.asList(
                            "acct_001|Name Available",
                            "acct_001|Name Available"),
                    "Edge: Reclaim and re-register by same account");

            // Test 7: Complex sequence with reclaims
            test(
                    () -> solution.checkAvailability(new String[] {
                            "acct_001|EpsilonCo Inc.",
                            "acct_002|EpsilonCo LLC",
                            "RECLAIM|acct_001|EpsilonCo Inc.",
                            "acct_002|EpsilonCo Corp.",
                            "acct_003|ZetaCo Inc.",
                            "RECLAIM|acct_003|ZetaCo Inc.",
                            "acct_001|ZetaCo LLC"
                    }),
                    Arrays.asList(
                            "acct_001|Name Available",
                            "acct_002|Name Not Available",
                            "acct_002|Name Available",
                            "acct_003|Name Available",
                            "acct_001|Name Available"),
                    "Edge: Complex sequence with multiple reclaims");
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
        if (PART < 3) {
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
 * 1. PART = 1, 2, or 3 → Select which part to test
 * 2. CHECK_FULL = false/true → Basic or comprehensive tests
 * 3. SELECTED_TESTS = {} → Run all, or specify: {1, 3, 5}
 * 
 * IMPLEMENTATION APPROACH:
 * ========================
 * 
 * NORMALIZATION FUNCTION:
 * -----------------------
 * String normalizeName(String name) {
 * // Step 1: Convert to lowercase
 * name = name.toLowerCase();
 * 
 * // Step 2: Replace & and , with spaces
 * name = name.replace('&', ' ').replace(',', ' ');
 * 
 * // Step 3: Collapse multiple spaces into single space
 * name = name.replaceAll("\\s+", " ");
 * 
 * // Step 4: Remove suffixes (case-insensitive)
 * // Suffixes: "Inc.", "Corp.", "LLC", "L.L.C.", "LLC."
 * String[] suffixes = {"inc.", "corp.", "llc", "l.l.c.", "llc."};
 * for (String suffix : suffixes) {
 * if (name.endsWith(" " + suffix)) {
 * name = name.substring(0, name.length() - suffix.length() - 1);
 * }
 * }
 * 
 * // Step 5: Remove leading "The", "An", "A"
 * name = name.trim();
 * if (name.startsWith("the ")) {
 * name = name.substring(4);
 * } else if (name.startsWith("an ")) {
 * name = name.substring(3);
 * } else if (name.startsWith("a ")) {
 * name = name.substring(2);
 * }
 * 
 * // Step 6: Remove "And" unless at beginning
 * name = name.trim();
 * if (!name.startsWith("and ")) {
 * name = name.replaceAll(" and ", " ");
 * }
 * 
 * // Step 7: Collapse spaces again and trim
 * name = name.replaceAll("\\s+", " ").trim();
 * 
 * return name;
 * }
 * 
 * PART 1 - BASIC AVAILABILITY:
 * -----------------------------
 * Data Structure:
 * - Set<String> registeredNames; // Stores normalized names
 * 
 * Algorithm:
 * 1. Parse input: split by "|" to get account_id and proposed_name
 * 2. Normalize the proposed name
 * 3. Check if normalized name is empty or only spaces → "Name Not Available"
 * 4. Check if normalized name exists in registeredNames
 * - If exists → "Name Not Available"
 * - If not exists → "Name Available" and add to registeredNames
 * 5. Format output: "account_id|Name Available/Not Available"
 * 
 * PART 2 - PERSISTENT TRACKING:
 * ------------------------------
 * Data Structure:
 * - Map<String, String> nameToAccount; // normalized_name -> account_id
 * 
 * Algorithm:
 * Same as Part 1, but also track which account registered each name.
 * This enables checking if the same account is trying to re-register.
 * 
 * PART 3 - RECLAMATION:
 * ----------------------
 * Data Structure:
 * - Map<String, String> nameToAccount; // normalized_name -> account_id
 * - Map<String, String> accountNames; // account_id -> Set<normalized_names>
 * 
 * Algorithm:
 * 1. Check if request is RECLAIM:
 * - Parse: "RECLAIM,account_id,original_proposed_name"
 * - Normalize the original_proposed_name
 * - Check if this account owns this normalized name
 * - If yes, remove from nameToAccount
 * - No output for RECLAIM requests
 * 
 * 2. Regular requests:
 * - Same as Part 2
 * - Check availability in nameToAccount
 * - If available, register and track ownership
 * 
 * EDGE CASES:
 * -----------
 * 1. Empty after normalization (only suffixes/articles)
 * 2. Multiple spaces, tabs, special characters
 * 3. All possible suffixes (Inc., Corp., LLC, L.L.C., LLC.)
 * 4. Leading articles (The, An, A) with various capitalizations
 * 5. "And" at beginning vs middle
 * 6. Ampersands and commas mixed with spaces
 * 7. Same account trying to register same name twice
 * 8. Wrong account trying to reclaim
 * 9. Reclaiming non-existent name
 * 10. Reclaim with different suffix (should work due to normalization)
 * 
 * TIME COMPLEXITY:
 * ----------------
 * Part 1: O(N × M) where N = requests, M = average name length
 * Part 2: O(N × M) same as Part 1
 * Part 3: O(N × M) same, with O(1) lookups
 * 
 * SPACE COMPLEXITY:
 * -----------------
 * O(N × M) for storing all registered names
 * 
 * EXAMPLE WALKTHROUGH (PART 3):
 * ------------------------------
 * Input:
 * acct_001|TechCorp Inc.
 * RECLAIM,acct_001,TechCorp Inc.
 * acct_002|TechCorp LLC
 * 
 * Step 1: Process "acct_001|TechCorp Inc."
 * - Normalize: "techcorp inc." → "techcorp"
 * - Check: Not in registry
 * - Register: nameToAccount["techcorp"] = "acct_001"
 * - Output: "acct_001|Name Available"
 * 
 * Step 2: Process "RECLAIM,acct_001,TechCorp Inc."
 * - Normalize: "techcorp inc." → "techcorp"
 * - Check: nameToAccount["techcorp"] == "acct_001" ✓
 * - Remove: delete nameToAccount["techcorp"]
 * - No output (silent)
 * 
 * Step 3: Process "acct_002|TechCorp LLC"
 * - Normalize: "techcorp llc" → "techcorp"
 * - Check: Not in registry (was reclaimed)
 * - Register: nameToAccount["techcorp"] = "acct_002"
 * - Output: "acct_002|Name Available"
 * 
 * Final Output:
 * acct_001|Name Available
 * acct_002|Name Available
 */