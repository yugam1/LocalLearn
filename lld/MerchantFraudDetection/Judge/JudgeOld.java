import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

// ============= PROBLEM DESCRIPTION =============
/*
CATCH ME IF YOU CAN - FRAUD DETECTION (Stripe HackerRank OA)
Platform: HackerRank (Stripe Online Assessment)
Link: https://www.linkjob.ai/interview-questions/stripe-hackerrank-online-assessment/

PART 1: COUNT-BASED FRAUD DETECTION
====================================

Background:
Stripe processes billions of transactions daily. Build a simplified fraud detection model 
that marks merchants as fraudulent if too many of their transactions are suspicious.

Concepts:
- Each merchant has a Merchant Category Code (MCC) representing their industry
- Each MCC has a fraud threshold (integer > 1) = max allowed fraudulent transactions
- Track transactions and mark merchants exceeding threshold

Input Format:
1. Non-fraudulent codes: "approved,invalid_pin,expired_card" (comma-separated)
2. Fraudulent codes: "do_not_honor,stolen_card,lost_card" (comma-separated)
3. MCC table: "MCC,threshold" (one per line)
   Example: "5411,10" means MCC 5411 has threshold of 10 frauds
4. Merchant table: "account_id,MCC" (one per line)
   Example: "acct_123,5411" means account acct_123 is in MCC 5411
5. Minimum transactions: integer >= 0 (min transactions before evaluation)
6. Charge transactions: "CHARGE,charge_id,account_id,amount,code"
   Example: "CHARGE,ch_1,acct_123,100.50,approved"

Rules:
- Only evaluate merchants after minimum transaction count is reached
- Mark merchant as fraudulent if fraud_count >= threshold
- Once marked fraudulent, merchant stays fraudulent

Output:
Return a lexicographically sorted, comma-separated list of fraudulent merchants by account_id.
Example: "acct_001,acct_042,acct_123"

Example:
Non-fraudulent: "approved,insufficient_funds"
Fraudulent: "stolen_card,do_not_honor"
MCC table:
  5411,3
Merchant table:
  acct_1,5411
  acct_2,5411
Min transactions: 2
Charges:
  CHARGE,ch_1,acct_1,100,approved           -> acct_1: fraud=0, total=1
  CHARGE,ch_2,acct_1,50,stolen_card         -> acct_1: fraud=1, total=2 (evaluated now)
  CHARGE,ch_3,acct_1,75,do_not_honor        -> acct_1: fraud=2, total=3
  CHARGE,ch_4,acct_1,80,stolen_card         -> acct_1: fraud=3, total=4 -> FRAUDULENT!
  CHARGE,ch_5,acct_2,200,approved           -> acct_2: fraud=0, total=1
  CHARGE,ch_6,acct_2,150,approved           -> acct_2: fraud=0, total=2 (evaluated, clean)

Output: "acct_1"
*/

// ============= SOLUTION CLASS - PART 1 =============

class SolutionPart1 {
        /**
         * Detect fraudulent merchants based on count threshold.
         * 
         * @param nonFraudulentCodes Comma-separated non-fraudulent codes
         * @param fraudulentCodes    Comma-separated fraudulent codes
         * @param mccData            Array of "MCC,threshold" strings
         * @param merchantData       Array of "account_id,MCC" strings
         * @param minTransactions    Minimum transactions before evaluation
         * @param charges            Array of "CHARGE,charge_id,account_id,amount,code"
         *                           strings
         * @return Comma-separated, lexicographically sorted fraudulent merchants
         */

        Set<String> fraudulentCodesSet;
        Set<String> nonFraudulentCodesSet;
        Map<String, Merchant> merchantEntityRepo;
        Map<String, Account> accountEntityRepo;

        public String detectFraudPart1(
                        String nonFraudulentCodes,
                        String fraudulentCodes,
                        String[] mccData,
                        String[] merchantData,
                        int minTransactions,
                        String[] charges) {

                List<String> fmerchant = new ArrayList<>();
                // TODO: Implement Part
                merchantEntityRepo = new HashMap<>();
                accountEntityRepo = new HashMap<>();
                fraudulentCodesSet = new HashSet<String>(List.of(fraudulentCodes.split(",")));
                for (int i = 0; i < mccData.length; i++) {
                        String[] data = mccData[i].split(",");
                        merchantEntityRepo.put(data[0], new Merchant(data[0], data[1]));
                }
                for (int i = 0; i < merchantData.length; i++) {
                        String[] data = merchantData[i].split(",");
                        Merchant m = merchantEntityRepo.get(data[1]);
                        Account a = new Account(data[0], data[1]);
                        m.accounts.add(a);
                        accountEntityRepo.put(data[0], a);
                }
                /**
                 * 
                 * "CHARGE,ch_4,acct_1,100,bad",
                 * "CHARGE,ch_5,acct_2,100,ok"
                 */
                for (int i = 0; i < charges.length; i++) {
                        String[] data = charges[i].split(",");
                        Transaction t = new Transaction(data[1], Double.parseDouble(data[3]), data[4],
                                        fraudulentCodesSet.contains(data[4]));
                        Account acc = accountEntityRepo.get(data[2]);
                        acc.allTransactions.add(t);
                }

                for (var row : merchantEntityRepo.entrySet()) {
                        Merchant m = row.getValue();

                        for (Account acc : m.accounts) {
                                int count = 0;
                                int total = 0;
                                for (Transaction t : acc.allTransactions) {
                                        if (t.isMarkedFraudulent) {
                                                count++;
                                        }
                                        total++;
                                }
                                if (total > minTransactions && count >= m.threshold) {
                                        fmerchant.add(acc.accountId);
                                }
                        }

                }
                Collections.sort(fmerchant);
                String res = String.join(",", fmerchant);

                return res;
        }
}

// ============= JUDGE CLASS =============
class JudgeOld {
        private static final boolean CHECK_FULL = true;
        private static final int[] SELECTED_TESTS = {};

        private static int passedTests = 0;
        private static int totalTests = 0;
        private static int currentTestNumber = 0;
        private static Set<Integer> selectedTestSet = new HashSet<>();

        static void main(String[] args) {
                SolutionPart1 solution = new SolutionPart1();

                for (int testNum : SELECTED_TESTS) {
                        selectedTestSet.add(testNum);
                }

                System.out.println("=".repeat(70));
                System.out.println("FRAUD DETECTION - PART 1: COUNT-BASED");
                System.out.println("Platform: Stripe HackerRank OA");
                System.out.println("Mode: " + (CHECK_FULL ? "FULL TEST" : "BASIC TEST"));
                if (SELECTED_TESTS.length > 0) {
                        System.out.println("Selected Tests: " + Arrays.toString(SELECTED_TESTS));
                }
                System.out.println("=".repeat(70));

                runBasicTests(solution);

                if (CHECK_FULL) {
                        System.out.println("\n" + "=".repeat(70));
                        System.out.println("RUNNING FULL TEST SUITE");
                        System.out.println("=".repeat(70));
                        runEdgeCaseTests(solution);
                        runPerformanceTests(solution);
                }

                printSummary();
        }

        // ============= BASIC TESTS =============
        private static void runBasicTests(SolutionPart1 solution) {
                System.out.println("\n--- Basic Tests ---");

                // Test 1: Simple case - one merchant crosses threshold
                test(
                                () -> solution.detectFraudPart1(
                                                "approved,insufficient_funds",
                                                "stolen_card,do_not_honor",
                                                new String[] { "5411,3" },
                                                new String[] { "acct_1,5411" },
                                                2,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,approved",
                                                                "CHARGE,ch_2,acct_1,50,stolen_card",
                                                                "CHARGE,ch_3,acct_1,75,do_not_honor",
                                                                "CHARGE,ch_4,acct_1,80,stolen_card"
                                                }),
                                "acct_1",
                                "One merchant crosses threshold (3 frauds)");

                // Test 2: Multiple merchants, only one fraudulent
                test(
                                () -> solution.detectFraudPart1(
                                                "approved",
                                                "fraud",
                                                new String[] { "1000,2" },
                                                new String[] { "acct_1,1000", "acct_2,1000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud",
                                                                "CHARGE,ch_2,acct_1,50,fraud",
                                                                "CHARGE,ch_3,acct_2,100,approved"
                                                }),
                                "acct_1",
                                "Multiple merchants, one fraudulent");

                // Test 3: Multiple fraudulent merchants (lexicographic order)
                test(
                                () -> solution.detectFraudPart1(
                                                "ok",
                                                "bad",
                                                new String[] { "2000,2" },
                                                new String[] { "acct_3,2000", "acct_1,2000", "acct_2,2000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_3,100,bad",
                                                                "CHARGE,ch_2,acct_3,100,bad",
                                                                "CHARGE,ch_3,acct_1,100,bad",
                                                                "CHARGE,ch_4,acct_1,100,bad",
                                                                "CHARGE,ch_5,acct_2,100,ok"
                                                }),
                                "acct_1,acct_3",
                                "Multiple fraudulent (lexicographically sorted)");

                // Test 4: Minimum transaction requirement not met
                test(
                                () -> solution.detectFraudPart1(
                                                "approved",
                                                "fraud",
                                                new String[] { "3000,1" },
                                                new String[] { "acct_1,3000" },
                                                5,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud",
                                                                "CHARGE,ch_2,acct_1,100,fraud"
                                                }),
                                "",
                                "Min transactions not met (need 5, have 2)");

                // Test 5: Exactly at threshold
                test(
                                () -> solution.detectFraudPart1(
                                                "approved",
                                                "fraud",
                                                new String[] { "4000,5" },
                                                new String[] { "acct_1,4000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud",
                                                                "CHARGE,ch_2,acct_1,100,fraud",
                                                                "CHARGE,ch_3,acct_1,100,fraud",
                                                                "CHARGE,ch_4,acct_1,100,fraud",
                                                                "CHARGE,ch_5,acct_1,100,fraud"
                                                }),
                                "acct_1",
                                "Exactly at threshold (5 frauds, threshold=5)");

                // Test 6: No fraudulent merchants
                test(
                                () -> solution.detectFraudPart1(
                                                "approved,valid",
                                                "fraud",
                                                new String[] { "5000,10" },
                                                new String[] { "acct_1,5000", "acct_2,5000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,approved",
                                                                "CHARGE,ch_2,acct_1,100,valid",
                                                                "CHARGE,ch_3,acct_2,100,approved"
                                                }),
                                "",
                                "No fraudulent merchants");
        }

        // ============= EDGE CASE TESTS =============
        private static void runEdgeCaseTests(SolutionPart1 solution) {
                System.out.println("\n--- Edge Case Tests ---");

                // Test 7: Different MCCs with different thresholds
                test(
                                () -> solution.detectFraudPart1(
                                                "ok",
                                                "bad",
                                                new String[] { "1000,2", "2000,5" },
                                                new String[] { "acct_1,1000", "acct_2,2000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,bad",
                                                                "CHARGE,ch_2,acct_1,100,bad",
                                                                "CHARGE,ch_3,acct_2,100,bad",
                                                                "CHARGE,ch_4,acct_2,100,bad",
                                                                "CHARGE,ch_5,acct_2,100,bad"
                                                }),
                                "acct_1",
                                "Different MCCs with different thresholds");

                // Test 8: Merchant crosses threshold then continues
                test(
                                () -> solution.detectFraudPart1(
                                                "ok",
                                                "bad",
                                                new String[] { "3000,2" },
                                                new String[] { "acct_1,3000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,bad",
                                                                "CHARGE,ch_2,acct_1,100,bad", // Crosses threshold here
                                                                "CHARGE,ch_3,acct_1,100,ok", // More transactions after
                                                                "CHARGE,ch_4,acct_1,100,ok"
                                                }),
                                "acct_1",
                                "Merchant stays fraudulent after threshold");

                // Test 9: Zero minimum transactions
                test(
                                () -> solution.detectFraudPart1(
                                                "ok",
                                                "bad",
                                                new String[] { "4000,1" },
                                                new String[] { "acct_1,4000" },
                                                0,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,bad"
                                                }),
                                "acct_1",
                                "Zero minimum transactions (evaluate immediately)");

                // Test 10: Large threshold never reached
                test(
                                () -> solution.detectFraudPart1(
                                                "ok",
                                                "bad",
                                                new String[] { "5000,1000" },
                                                new String[] { "acct_1,5000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,bad",
                                                                "CHARGE,ch_2,acct_1,100,bad",
                                                                "CHARGE,ch_3,acct_1,100,bad"
                                                }),
                                "",
                                "High threshold never reached");

                // Test 11: Multiple fraud codes
                test(
                                () -> solution.detectFraudPart1(
                                                "approved,valid,success",
                                                "stolen,fraud,bad,evil",
                                                new String[] { "6000,3" },
                                                new String[] { "acct_1,6000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,stolen",
                                                                "CHARGE,ch_2,acct_1,100,fraud",
                                                                "CHARGE,ch_3,acct_1,100,bad"
                                                }),
                                "acct_1",
                                "Multiple different fraud codes");

                // Test 12: Mixed valid and fraudulent transactions
                test(
                                () -> solution.detectFraudPart1(
                                                "ok",
                                                "bad",
                                                new String[] { "7000,5" },
                                                new String[] { "acct_1,7000" },
                                                3,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,ok",
                                                                "CHARGE,ch_2,acct_1,100,bad",
                                                                "CHARGE,ch_3,acct_1,100,ok", // Min transactions met
                                                                "CHARGE,ch_4,acct_1,100,bad",
                                                                "CHARGE,ch_5,acct_1,100,bad",
                                                                "CHARGE,ch_6,acct_1,100,ok",
                                                                "CHARGE,ch_7,acct_1,100,bad",
                                                                "CHARGE,ch_8,acct_1,100,bad"
                                                }),
                                "acct_1",
                                "Mixed valid/fraudulent reaching threshold");
        }

        // ============= PERFORMANCE TESTS =============
        private static void runPerformanceTests(SolutionPart1 solution) {
                System.out.println("\n--- Performance Tests ---");

                // Test 1: Many merchants
                String[] mccData1 = { "9999,10" };
                List<String> merchants1 = new ArrayList<>();
                List<String> charges1 = new ArrayList<>();

                for (int i = 0; i < 1000; i++) {
                        merchants1.add("acct_" + i + ",9999");
                        for (int j = 0; j < 15; j++) {
                                String code = (j < 12) ? "fraud" : "ok";
                                charges1.add("CHARGE,ch_" + i + "_" + j + ",acct_" + i + ",100," + code);
                        }
                }

                long start = System.nanoTime();
                String result1 = solution.detectFraudPart1(
                                "ok",
                                "fraud",
                                mccData1,
                                merchants1.toArray(new String[0]),
                                1,
                                charges1.toArray(new String[0]));
                long duration1 = (System.nanoTime() - start) / 1_000_000;

                System.out.printf("✓ Many merchants (1000): %d ms (%d fraudulent)%n",
                                duration1, result1.isEmpty() ? 0 : result1.split(",").length);

                // Test 2: Many charges per merchant
                String[] mccData2 = { "8888,500" };
                String[] merchants2 = { "acct_test,8888" };
                List<String> charges2 = new ArrayList<>();

                for (int i = 0; i < 10000; i++) {
                        charges2.add("CHARGE,ch_" + i + ",acct_test,100,fraud");
                }

                start = System.nanoTime();
                String result2 = solution.detectFraudPart1(
                                "ok",
                                "fraud",
                                mccData2,
                                merchants2,
                                1,
                                charges2.toArray(new String[0]));
                long duration2 = (System.nanoTime() - start) / 1_000_000;

                System.out.printf("✓ Many charges (10,000): %d ms%n", duration2);

                long maxDuration = Math.max(duration1, duration2);
                if (maxDuration > 5000) {
                        System.out.println("  ⚠ WARNING: Solution too slow");
                } else if (maxDuration > 1000) {
                        System.out.println("  ⚠ Performance could be optimized");
                } else {
                        System.out.println("  ✓ Performance excellent");
                }
        }

        // ============= HELPER METHODS =============
        private static <T> void test(TestSupplier<T> supplier, T expected, String description) {
                currentTestNumber++;
                if (SELECTED_TESTS.length > 0 && !selectedTestSet.contains(currentTestNumber)) {
                        return;
                }

                totalTests++;
                try {
                        T result = supplier.get();
                        boolean passed = Objects.deepEquals(result, expected);

                        if (passed) {
                                passedTests++;
                                System.out.printf("✓ PASS [Test #%d]: %s%n", currentTestNumber, description);
                        } else {
                                System.out.printf("✗ FAIL [Test #%d]: %s%n", currentTestNumber, description);
                                System.out.println("  Expected: " + expected);
                                System.out.println("  Got:      " + result);
                        }
                } catch (Exception e) {
                        System.out.printf("✗ ERROR [Test #%d]: %s%n", currentTestNumber, description);
                        System.out.println("  Exception: " + e.getMessage());
                        e.printStackTrace();
                }
        }

        private static void printSummary() {
                System.out.println("\n" + "=".repeat(70));
                System.out.println("TEST SUMMARY - PART 1");
                System.out.println("=".repeat(70));

                if (SELECTED_TESTS.length > 0) {
                        System.out.printf("Selected: %d test(s) from %d total%n", totalTests, currentTestNumber);
                }

                System.out.printf("Passed: %d/%d tests%n", passedTests, totalTests);

                if (passedTests == totalTests) {
                        System.out.println("✓ All tests passed! 🎉");
                        System.out.println("\n📌 Next: Implement Part 2 (Percentage-Based Detection)");
                } else {
                        System.out.printf("✗ %d test(s) failed%n", totalTests - passedTests);
                }

                if (!CHECK_FULL) {
                        System.out.println("\nℹ Set CHECK_FULL = true for comprehensive testing");
                }
                if (SELECTED_TESTS.length == 0) {
                        System.out.println("ℹ Set SELECTED_TESTS = new int[]{1,2,3} to run specific tests");
                }
        }

        @FunctionalInterface
        interface TestSupplier<T> {
                T get() throws Exception;
        }
}

// ============= ALGORITHM HINTS =============
/*
 * PART 1 IMPLEMENTATION GUIDE:
 * 
 * DATA STRUCTURES:
 * 1. Set<String> fraudulentCodesSet - O(1) fraud code lookup
 * 2. Map<String, Integer> mccThresholds - MCC -> fraud threshold
 * 3. Map<String, String> accountToMcc - account_id -> MCC
 * 4. Map<String, MerchantStats> merchantStats - track per-merchant stats
 * 
 * MerchantStats class:
 * - int totalCharges
 * - int fraudCharges
 * - boolean isMarkedFraudulent
 * 
 * ALGORITHM:
 * 1. Parse fraudulent codes: split by comma, add to Set
 * 2. Parse MCC data: split each line by comma, store in map
 * 3. Parse merchant data: split each line by comma, map account to MCC
 * 4. For each CHARGE:
 * a) Parse: "CHARGE,charge_id,account_id,amount,code"
 * b) Get or create MerchantStats for account
 * c) Increment totalCharges
 * d) If code in fraudulentCodesSet: increment fraudCharges
 * e) If totalCharges >= minTransactions AND not already marked:
 * - Get MCC for this account
 * - Get threshold for MCC
 * - If fraudCharges >= threshold: mark as fraudulent
 * 5. Collect all fraudulent accounts, sort lexicographically, join with comma
 * 
 * TIME COMPLEXITY: O(N + M log M) where N = charges, M = fraudulent merchants
 * SPACE COMPLEXITY: O(M + C) where M = merchants, C = MCCs
 * 
 * EXAMPLE WALKTHROUGH:
 * Input:
 * fraudulent: "stolen,bad"
 * MCC: "5000,3"
 * Merchant: "acct_1,5000"
 * minTx: 2
 * 
 * Charges:
 * CHARGE,ch_1,acct_1,100,ok -> total=1, fraud=0 (not evaluated yet)
 * CHARGE,ch_2,acct_1,50,stolen -> total=2, fraud=1 (evaluate: 1 < 3, OK)
 * CHARGE,ch_3,acct_1,75,bad -> total=3, fraud=2 (evaluate: 2 < 3, OK)
 * CHARGE,ch_4,acct_1,80,stolen -> total=4, fraud=3 (evaluate: 3 >= 3, FRAUD!)
 * 
 * Output: "acct_1"
 * 
 * TIPS:
 * - Use String.split(",") carefully - watch for edge cases
 * - Remember to sort fraudulent merchants lexicographically
 * - Once marked fraudulent, merchant stays fraudulent
 * - Don't evaluate until minTransactions reached
 */