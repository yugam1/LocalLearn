import java.util.*;

// ============= PROBLEM DESCRIPTION =============
/*
CATCH ME IF YOU CAN - FRAUD DETECTION (Stripe HackerRank OA 2025)

Platform: HackerRank (Stripe Online Assessment)
Difficulty: Medium-Hard
Link: https://www.linkjob.ai/interview-questions/stripe-hackerrank-online-assessment/

=============================================================================
PART 1: COUNT-BASED FRAUD DETECTION
=============================================================================

Background:
Stripe processes billions of dollars. Build a fraud detection model that marks 
merchants as fraudulent if too many transactions are suspicious.

Problem:
Each merchant has an MCC (Merchant Category Code) representing their industry.
Each MCC has a fraud threshold (integer > 1) - max allowed fraudulent transactions.

Input:
1. nonFraudulentCodes: Comma-separated list (e.g., "approved,invalid_pin")
2. fraudulentCodes: Comma-separated list (e.g., "do_not_honor,stolen_card")
3. operations[]: Array of operations (one per line):
   - "MCC,mcc_code,threshold"           (threshold is integer)
   - "MERCHANT,account_id,mcc_code"
   - "CHARGE,charge_id,account_id,amount,code"
4. minTransactions: Minimum transactions before evaluation

Rules:
- Count fraudulent transactions per merchant
- If fraud_count >= threshold AND total_count >= minTransactions → FRAUDULENT
- Once marked fraudulent, stays fraudulent

Output:
Lexicographically sorted, comma-separated list of fraudulent merchant account_ids.

Example:
nonFraudulentCodes = "approved,invalid_pin"
fraudulentCodes = "do_not_honor,stolen_card"
minTransactions = 2
operations = [
  "MCC,5814,3",                    // MCC 5814, threshold = 3 frauds
  "MERCHANT,m1,5814",
  "MERCHANT,m2,5814",
  "CHARGE,c1,m1,100,approved",     // m1: fraud=0, total=1
  "CHARGE,c2,m1,200,do_not_honor", // m1: fraud=1, total=2
  "CHARGE,c3,m1,150,stolen_card",  // m1: fraud=2, total=3
  "CHARGE,c4,m1,300,stolen_card",  // m1: fraud=3, total=4 → FRAUDULENT
  "CHARGE,c5,m2,100,approved"      // m2: fraud=0, total=1
]
Output: "m1"

=============================================================================
PART 2: PERCENTAGE-BASED FRAUD DETECTION
=============================================================================

Problem:
Count-based thresholds unfairly mark high-volume merchants. Use percentage instead.

Changes from Part 1:
- MCC threshold is now a fraction between 0 and 1 (e.g., 0.25 = 25%)
- If (fraud_count / total_count) >= threshold → FRAUDULENT
- Merchants stay fraudulent even if percentage decreases later
- Only evaluate after minTransactions

Input:
Same as Part 1, except:
  - "MCC,mcc_code,threshold" where threshold is float (e.g., "MCC,5814,0.25")

Rules:
- Calculate fraud_percentage = fraud_count / total_count
- If fraud_percentage >= threshold AND total_count >= minTransactions → FRAUDULENT
- Once marked, stays marked

Example:
operations = [
  "MCC,5814,0.5",                  // 50% fraud threshold
  "MERCHANT,m1,5814",
  "CHARGE,c1,m1,100,approved",     // m1: 0/1 = 0%
  "CHARGE,c2,m1,200,do_not_honor", // m1: 1/2 = 50% → FRAUDULENT (at threshold)
]
Output: "m1"

=============================================================================
PART 3: DISPUTE RESOLUTION
=============================================================================

Problem:
Transactions can be incorrectly marked fraudulent. Support disputes to reverse status.

Changes from Part 2:
- New operation: "DISPUTE,charge_id"
- When dispute occurs, that transaction is NOT fraudulent for calculations
- If merchant was fraudulent only due to disputed transactions, may return to 
  non-fraudulent status until threshold crossed again

Input:
Same as Part 2, with additional operation type:
  - "DISPUTE,charge_id" - reverses fraudulent status of charge_id

Rules:
- When DISPUTE processed:
  * If charge was fraudulent, decrement fraud_count
  * Recalculate fraud status
- If merchant drops below threshold after dispute, can be marked fraudulent again later
- Handle edge cases: out-of-order disputes, multiple disputes on same charge

Example:
operations = [
  "MCC,5814,3",
  "MERCHANT,m1,5814",
  "CHARGE,c1,m1,100,do_not_honor", // m1: fraud=1
  "CHARGE,c2,m1,200,stolen_card",  // m1: fraud=2
  "CHARGE,c3,m1,150,stolen_card",  // m1: fraud=3 → FRAUDULENT
  "DISPUTE,c1",                    // m1: fraud=2 (still fraudulent)
  "DISPUTE,c2",                    // m1: fraud=1 (still fraudulent, once marked stays)
]
Output: "m1"

Note: Implementation may vary - some versions allow merchants to return to non-fraudulent
status after disputes, others keep them marked once they cross threshold initially.

Constraints:
- 1 <= operations.length <= 10^5
- 1 <= minTransactions <= 10^6
- 0 < threshold (integer for Part 1, 0 < threshold <= 1 for Parts 2-3)
- Valid MCC codes, account IDs, charge IDs
*/

// Helper classes

class MerchantCategory {
        public String mcc;
        public Double threshold;
        String type;

        MerchantCategory(String code, Double thres) {
                this.mcc = code;
                this.threshold = thres;
                if (thres < 1) {
                        type = "PERCENT";
                } else {
                        type = "ABSOLUTE";
                }
        }

        boolean checkThreshold(int fCount, int tCount) {
                if (this.type.equals("PERCENT")) {
                        return (double) fCount / (double) tCount >= threshold;
                } else {
                        return fCount >= threshold;
                }
        }
}

class Merchant implements Comparable<Merchant> {
        String accountId;
        MerchantCategory category;
        Integer fraudCount;
        Integer totalTransaction;
        public List<Charge> charges;

        Merchant(String acc, MerchantCategory cat) {
                this.accountId = acc;
                this.category = cat;
                fraudCount = 0;
                totalTransaction = 0;
                charges = new ArrayList<>();
        }

        void processCharges() {
                totalTransaction = charges.size();
                this.fraudCount = 0;
                for (Charge ch : charges) {
                        if (ch.isFraudulent) {
                                this.fraudCount += 1;
                        }
                }
        }

        boolean isFraudulentMerchant(int proccessCount) {
                this.processCharges();
                return category.checkThreshold(fraudCount, totalTransaction) && proccessCount <= totalTransaction;
        }

        public int compareTo(Merchant m2) {
                return this.accountId.compareTo(m2.accountId);
        }
}

// "CHARGE,c3,m1,150,stolen_card", // m1: fraud=3 → FRAUDULENT
class Charge {
        String code;
        String accountId;
        Double amount;
        String codeType;
        boolean isFraudulent;
        boolean isDisputed;

        Charge(String code, String accountId, Double amt, String codeType, boolean isFraudulent) {
                this.code = code;
                this.accountId = accountId;
                this.amount = amt;
                this.codeType = codeType;
                this.isFraudulent = isFraudulent;
        }
}

// ============= SOLUTION CLASS =============
class Solution {

        HashSet<String> fraudulentCodeSet;
        Map<String, MerchantCategory> mccDb;
        Map<String, Merchant> merchantDb;
        Map<String, Charge> chargesDb;

        /**
         * Detect fraudulent merchants based on transaction patterns.
         * 
         * @param nonFraudulentCodes Comma-separated list of non-fraudulent codes
         * @param fraudulentCodes    Comma-separated list of fraudulent codes
         * @param operations         Array of operations (MCC, MERCHANT, CHARGE,
         *                           DISPUTE)
         * @param minTransactions    Minimum transactions before evaluation
         * @return Comma-separated, lexicographically sorted list of fraudulent merchant
         *         IDs
         */
        public String detectFraud(
                        String nonFraudulentCodes,
                        String fraudulentCodes,
                        String[] operations,
                        int minTransactions) {

                fraudulentCodeSet = new HashSet<>(List.of(fraudulentCodes.split(",")));

                mccDb = new HashMap<>();
                merchantDb = new TreeMap<>();
                chargesDb = new HashMap<>();

                /**
                 * "MCC,5814,3",
                 * "MERCHANT,m1,5814",
                 * "CHARGE,c1,m1,100,do_not_honor", // m1: fraud=1
                 * "CHARGE,c2,m1,200,stolen_card", // m1: fraud=2
                 * "CHARGE,c3,m1,150,stolen_card", // m1: fraud=3 → FRAUDULENT
                 * "DISPUTE,c1", // m1: fraud=2 (still fraudulent)
                 * "DISPUTE,c2", // m1: fraud=1 (still fraudulent, once marked stays)
                 */
                HashMap<String, List<String[]>> operationType = new HashMap<>();
                for (int i = 0; i < operations.length; i++) {
                        String[] row = operations[i].split(",");
                        if (!operationType.containsKey(row[0])) {
                                operationType.put(row[0], new ArrayList<>());
                        }
                        operationType.get(row[0]).add(Arrays.copyOfRange(row, 1, row.length));
                }

                // process mcc
                for (String[] row : operationType.get("MCC")) {
                        mccDb.put(row[0], new MerchantCategory(row[0], Double.parseDouble(row[1])));
                }

                // process merchant
                for (String[] row : operationType.get("MERCHANT")) {
                        MerchantCategory cat = mccDb.get(row[1]);
                        merchantDb.put(row[0], new Merchant(row[0], cat));
                }

                // process charges
                for (String[] row : operationType.get("CHARGE")) {
                        Charge ch = new Charge(row[0], row[1], Double.parseDouble(row[2]), row[3],
                                        fraudulentCodeSet.contains(row[3]));
                        chargesDb.put(row[0], ch);
                        merchantDb.get(row[1]).charges.add(ch);
                }

                // process dispute
                for (String[] row : operationType.get("DISPUTE")) {
                        Charge ch = chargesDb.get(row[0]);
                        ch.isFraudulent = false;
                        ch.isDisputed = true;
                }

                StringBuilder res = new StringBuilder();

                for (Merchant m : merchantDb.values()) {
                        if (m.isFraudulentMerchant(minTransactions)) {
                                if (res.length() != 0) {
                                        res.append(",");
                                }
                                res.append(m.accountId);
                        }
                }

                return res.toString();
        }
}

// ============= JUDGE CLASS =============
class Judge {
        // ========== CONFIGURATION FLAGS ==========
        private static final int PART = 3; // Which part to test: 1, 2, or 3
        private static final boolean CHECK_FULL = true; // true = all tests, false = basic only
        private static final int[] SELECTED_TESTS = { 3 }; // Empty = all, or specify: {1, 3, 5}

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
                System.out.println("CATCH ME IF YOU CAN - FRAUD DETECTION (Stripe OA)");
                System.out.println("Difficulty: MEDIUM-HARD");
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
                System.out.println("\n=== PART 1: COUNT-BASED FRAUD DETECTION ===\n");

                System.out.println("--- Basic Tests ---");

                // Test 1: Single merchant crosses threshold
                test(
                                () -> solution.detectFraud(
                                                "approved,invalid_pin",
                                                "do_not_honor,stolen_card",
                                                new String[] {
                                                                "MCC,5814,3",
                                                                "MERCHANT,m1,5814",
                                                                "CHARGE,c1,m1,100,approved",
                                                                "CHARGE,c2,m1,200,do_not_honor",
                                                                "CHARGE,c3,m1,150,stolen_card",
                                                                "CHARGE,c4,m1,300,stolen_card"
                                                },
                                                2),
                                "m1",
                                "Part 1: Single merchant, threshold=3");

                // Test 2: Multiple merchants, only one fraudulent
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "stolen_card",
                                                new String[] {
                                                                "MCC,5814,2",
                                                                "MERCHANT,m1,5814",
                                                                "MERCHANT,m2,5814",
                                                                "CHARGE,c1,m1,100,stolen_card",
                                                                "CHARGE,c2,m1,200,stolen_card",
                                                                "CHARGE,c3,m2,100,approved"
                                                },
                                                1),
                                "m1",
                                "Part 1: Multiple merchants, one fraudulent");

                // Test 3: Minimum transactions not met
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "stolen_card",
                                                new String[] {
                                                                "MCC,5814,2",
                                                                "MERCHANT,m1,5814",
                                                                "CHARGE,c1,m1,100,stolen_card",
                                                                "CHARGE,c2,m1,200,stolen_card"
                                                },
                                                5 // Need 5 transactions, only have 2
                                ),
                                "",
                                "Part 1: Below minimum transactions");

                // Test 4: Lexicographic sorting
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "fraud",
                                                new String[] {
                                                                "MCC,5814,1",
                                                                "MERCHANT,zebra,5814",
                                                                "MERCHANT,alpha,5814",
                                                                "MERCHANT,mike,5814",
                                                                "CHARGE,c1,zebra,100,fraud",
                                                                "CHARGE,c2,alpha,100,fraud",
                                                                "CHARGE,c3,mike,100,fraud"
                                                },
                                                1),
                                "alpha,mike,zebra",
                                "Part 1: Lexicographic sorting");

                if (CHECK_FULL) {
                        System.out.println("\n--- Edge Cases ---");

                        // Test 5: No fraudulent merchants
                        test(
                                        () -> solution.detectFraud(
                                                        "approved",
                                                        "fraud",
                                                        new String[] {
                                                                        "MCC,5814,10",
                                                                        "MERCHANT,m1,5814",
                                                                        "CHARGE,c1,m1,100,fraud",
                                                                        "CHARGE,c2,m1,100,approved"
                                                        },
                                                        1),
                                        "",
                                        "Edge: No merchants reach threshold");

                        // Test 6: Multiple MCCs with different thresholds
                        test(
                                        () -> solution.detectFraud(
                                                        "approved",
                                                        "fraud",
                                                        new String[] {
                                                                        "MCC,1000,2",
                                                                        "MCC,2000,5",
                                                                        "MERCHANT,m1,1000",
                                                                        "MERCHANT,m2,2000",
                                                                        "CHARGE,c1,m1,100,fraud",
                                                                        "CHARGE,c2,m1,100,fraud",
                                                                        "CHARGE,c3,m2,100,fraud",
                                                                        "CHARGE,c4,m2,100,fraud"
                                                        },
                                                        1),
                                        "m1",
                                        "Edge: Multiple MCCs, different thresholds");

                        // Test 7: Exactly at threshold
                        test(
                                        () -> solution.detectFraud(
                                                        "approved",
                                                        "fraud",
                                                        new String[] {
                                                                        "MCC,5814,3",
                                                                        "MERCHANT,m1,5814",
                                                                        "CHARGE,c1,m1,100,fraud",
                                                                        "CHARGE,c2,m1,100,fraud",
                                                                        "CHARGE,c3,m1,100,fraud"
                                                        },
                                                        1),
                                        "m1",
                                        "Edge: Exactly at threshold (>=)");
                }
        }

        // ============= PART 2 TESTS =============
        private static void runPart2Tests(Solution solution) {
                System.out.println("\n=== PART 2: PERCENTAGE-BASED FRAUD DETECTION ===\n");

                System.out.println("--- Basic Tests ---");

                // Test 1: 50% threshold
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "fraud",
                                                new String[] {
                                                                "MCC,5814,0.5",
                                                                "MERCHANT,m1,5814",
                                                                "CHARGE,c1,m1,100,fraud",
                                                                "CHARGE,c2,m1,100,approved"
                                                },
                                                2),
                                "m1",
                                "Part 2: 50% threshold (1/2 = 50%)");

                // Test 2: Below threshold
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "fraud",
                                                new String[] {
                                                                "MCC,5814,0.5",
                                                                "MERCHANT,m1,5814",
                                                                "CHARGE,c1,m1,100,fraud",
                                                                "CHARGE,c2,m1,100,approved",
                                                                "CHARGE,c3,m1,100,approved"
                                                },
                                                2),
                                "",
                                "Part 2: Below threshold (1/3 = 33.3% < 50%)");

                // Test 3: Stays fraudulent even when percentage drops
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "fraud",
                                                new String[] {
                                                                "MCC,5814,0.4",
                                                                "MERCHANT,m1,5814",
                                                                "CHARGE,c1,m1,100,fraud",
                                                                "CHARGE,c2,m1,100,fraud", // 2/2 = 100% → FRAUDULENT
                                                                "CHARGE,c3,m1,100,approved", // 2/3 = 66.7%
                                                                "CHARGE,c4,m1,100,approved", // 2/4 = 50%
                                                                "CHARGE,c5,m1,100,approved" // 2/5 = 40% (still marked)
                                                },
                                                1),
                                "m1",
                                "Part 2: Stays fraudulent after percentage drops");

                // Test 4: 25% threshold
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "fraud",
                                                new String[] {
                                                                "MCC,5814,0.25",
                                                                "MERCHANT,m1,5814",
                                                                "MERCHANT,m2,5814",
                                                                "CHARGE,c1,m1,100,fraud",
                                                                "CHARGE,c2,m1,100,approved",
                                                                "CHARGE,c3,m1,100,approved",
                                                                "CHARGE,c4,m1,100,approved", // m1: 1/4 = 25% →
                                                                                             // FRAUDULENT
                                                                "CHARGE,c5,m2,100,fraud",
                                                                "CHARGE,c6,m2,100,approved",
                                                                "CHARGE,c7,m2,100,approved",
                                                                "CHARGE,c8,m2,100,approved",
                                                                "CHARGE,c9,m2,100,approved" // m2: 1/5 = 20% < 25%
                                                },
                                                4),
                                "m1",
                                "Part 2: 25% threshold, multiple merchants");

                if (CHECK_FULL) {
                        System.out.println("\n--- Edge Cases ---");

                        // Test 5: Exact boundary (>=)
                        test(
                                        () -> solution.detectFraud(
                                                        "approved",
                                                        "fraud",
                                                        new String[] {
                                                                        "MCC,5814,0.333333",
                                                                        "MERCHANT,m1,5814",
                                                                        "CHARGE,c1,m1,100,fraud",
                                                                        "CHARGE,c2,m1,100,approved",
                                                                        "CHARGE,c3,m1,100,approved"
                                                        },
                                                        1),
                                        "m1",
                                        "Edge: Exact boundary 1/3 = 0.3333... >= 0.333333");

                        // Test 6: High threshold (90%)
                        test(
                                        () -> solution.detectFraud(
                                                        "approved",
                                                        "fraud",
                                                        new String[] {
                                                                        "MCC,5814,0.9",
                                                                        "MERCHANT,m1,5814",
                                                                        "CHARGE,c1,m1,100,fraud",
                                                                        "CHARGE,c2,m1,100,fraud",
                                                                        "CHARGE,c3,m1,100,fraud",
                                                                        "CHARGE,c4,m1,100,fraud",
                                                                        "CHARGE,c5,m1,100,fraud",
                                                                        "CHARGE,c6,m1,100,fraud",
                                                                        "CHARGE,c7,m1,100,fraud",
                                                                        "CHARGE,c8,m1,100,fraud",
                                                                        "CHARGE,c9,m1,100,fraud",
                                                                        "CHARGE,c10,m1,100,approved"
                                                        },
                                                        1),
                                        "m1",
                                        "Edge: High threshold 90% (9/10)");
                }
        }

        // ============= PART 3 TESTS =============
        private static void runPart3Tests(Solution solution) {
                System.out.println("\n=== PART 3: DISPUTE RESOLUTION ===\n");

                System.out.println("--- Basic Tests ---");

                // Test 1: Dispute before crossing threshold
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "fraud",
                                                new String[] {
                                                                "MCC,5814,3",
                                                                "MERCHANT,m1,5814",
                                                                "CHARGE,c1,m1,100,fraud",
                                                                "CHARGE,c2,m1,100,fraud",
                                                                "DISPUTE,c1", // Now only 1 fraud
                                                                "CHARGE,c3,m1,100,fraud", // 2 frauds
                                                                "CHARGE,c4,m1,100,fraud" // 3 frauds → FRAUDULENT
                                                },
                                                1),
                                "m1",
                                "Part 3: Dispute before threshold");

                // Test 2: Dispute after marking (stays fraudulent)
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "fraud",
                                                new String[] {
                                                                "MCC,5814,2",
                                                                "MERCHANT,m1,5814",
                                                                "CHARGE,c1,m1,100,fraud",
                                                                "CHARGE,c2,m1,100,fraud", // m1 → FRAUDULENT
                                                                "DISPUTE,c1", // Still fraudulent (once marked)
                                                                "DISPUTE,c2"
                                                },
                                                1),
                                "m1",
                                "Part 3: Dispute after marking (stays fraudulent)");

                // Test 3: Percentage with dispute
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "fraud",
                                                new String[] {
                                                                "MCC,5814,0.5",
                                                                "MERCHANT,m1,5814",
                                                                "CHARGE,c1,m1,100,fraud",
                                                                "CHARGE,c2,m1,100,fraud", // 2/2 = 100% → FRAUDULENT
                                                                "DISPUTE,c1", // 1/2 = 50% (still fraudulent)
                                                                "CHARGE,c3,m1,100,approved" // 1/3 = 33.3%
                                                },
                                                2),
                                "m1",
                                "Part 3: Percentage threshold with dispute");

                // Test 4: Multiple disputes
                test(
                                () -> solution.detectFraud(
                                                "approved",
                                                "fraud",
                                                new String[] {
                                                                "MCC,5814,3",
                                                                "MERCHANT,m1,5814",
                                                                "CHARGE,c1,m1,100,fraud",
                                                                "CHARGE,c2,m1,100,fraud",
                                                                "CHARGE,c3,m1,100,fraud",
                                                                "DISPUTE,c1",
                                                                "DISPUTE,c2", // Now only 1 fraud
                                                                "CHARGE,c4,m1,100,fraud",
                                                                "CHARGE,c5,m1,100,fraud" // 3 frauds → FRAUDULENT
                                                },
                                                1),
                                "m1",
                                "Part 3: Multiple disputes");

                if (CHECK_FULL) {
                        System.out.println("\n--- Edge Cases ---");

                        // Test 5: Out-of-order dispute (dispute before charge)
                        test(
                                        () -> solution.detectFraud(
                                                        "approved",
                                                        "fraud",
                                                        new String[] {
                                                                        "MCC,5814,2",
                                                                        "MERCHANT,m1,5814",
                                                                        "DISPUTE,c1", // Dispute before charge appears
                                                                        "CHARGE,c1,m1,100,fraud", // Should be treated
                                                                                                  // as disputed
                                                                        "CHARGE,c2,m1,100,fraud",
                                                                        "CHARGE,c3,m1,100,fraud"
                                                        },
                                                        1),
                                        "m1",
                                        "Edge: Out-of-order dispute");

                        // Test 6: Duplicate disputes (idempotent)
                        test(
                                        () -> solution.detectFraud(
                                                        "approved",
                                                        "fraud",
                                                        new String[] {
                                                                        "MCC,5814,3",
                                                                        "MERCHANT,m1,5814",
                                                                        "CHARGE,c1,m1,100,fraud",
                                                                        "CHARGE,c2,m1,100,fraud",
                                                                        "CHARGE,c3,m1,100,fraud",
                                                                        "DISPUTE,c1",
                                                                        "DISPUTE,c1", // Duplicate
                                                                        "CHARGE,c4,m1,100,fraud"
                                                        },
                                                        1),
                                        "m1",
                                        "Edge: Duplicate disputes (idempotent)");

                        // Test 7: Dispute non-fraudulent charge (no effect)
                        test(
                                        () -> solution.detectFraud(
                                                        "approved",
                                                        "fraud",
                                                        new String[] {
                                                                        "MCC,5814,2",
                                                                        "MERCHANT,m1,5814",
                                                                        "CHARGE,c1,m1,100,approved",
                                                                        "CHARGE,c2,m1,100,fraud",
                                                                        "CHARGE,c3,m1,100,fraud",
                                                                        "DISPUTE,c1" // Disputing non-fraud (no effect)
                                                        },
                                                        1),
                                        "m1",
                                        "Edge: Dispute non-fraudulent charge");
                }
        }

        // ============= HELPER METHODS =============
        private static <T> void test(TestSupplier<T> supplier, T expected, String description) {
                currentTestNumber++;

                // Skip if this test is not in the selected set
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
                                System.out.println("  Expected: " + formatOutput(expected));
                                System.out.println("  Got:      " + formatOutput(result));
                        }
                } catch (Exception e) {
                        System.out.printf("✗ ERROR [Test #%d]: %s%n", currentTestNumber, description);
                        System.out.println("  Exception: " + e.getMessage());
                        e.printStackTrace();
                }
        }

        private static String formatOutput(Object obj) {
                if (obj instanceof int[]) {
                        return Arrays.toString((int[]) obj);
                } else if (obj instanceof int[][]) {
                        return Arrays.deepToString((int[][]) obj);
                } else if (obj instanceof String[]) {
                        return Arrays.toString((String[]) obj);
                } else if (obj instanceof List) {
                        return obj.toString();
                }
                return String.valueOf(obj);
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
 * DATA STRUCTURES:
 * ----------------
 * class MerchantStats {
 * int totalCharges;
 * int fraudCharges;
 * boolean isMarkedFraudulent;
 * }
 * 
 * Map<String, Object> mccThresholds; // MCC -> Integer/Double threshold
 * Map<String, String> merchantToMCC; // merchant_id -> mcc_code
 * Map<String, MerchantStats> stats; // merchant_id -> stats
 * Map<String, ChargeInfo> charges; // charge_id -> ChargeInfo
 * Set<String> fraudCodes; // Set of fraudulent codes
 * Set<String> fraudulentMerchants; // Result set (auto-sorted if TreeSet)
 * 
 * PART 1 - COUNT BASED:
 * ----------------------
 * 1. Parse fraudulent codes into Set
 * 2. Parse MCC definitions: threshold as Integer
 * 3. Process CHARGE operations:
 * - Update merchantStats (total++, fraud++ if fraudulent)
 * - Check: fraud >= threshold AND total >= minTransactions
 * - If yes: add to fraudulentMerchants
 * 4. Return sorted, comma-joined list
 * 
 * PART 2 - PERCENTAGE BASED:
 * ---------------------------
 * 1. Same as Part 1, but threshold is Double
 * 2. Check: (fraud/total) >= threshold AND total >= minTransactions
 * 3. Once marked fraudulent, stays fraudulent even if % drops
 * 
 * PART 3 - WITH DISPUTES:
 * ------------------------
 * 1. Same as Part 2, plus handle DISPUTE
 * 2. When DISPUTE processed:
 * - Find charge in charges map
 * - If was fraudulent and not already disputed:
 * fraudCharges--
 * Mark charge as disputed
 * - For out-of-order: store pending disputes
 * 3. Re-evaluation after dispute:
 * - Some implementations: merchant stays fraudulent once marked
 * - Others: can return to non-fraudulent if drops below threshold
 * 
 * EDGE CASES:
 * -----------
 * - Out-of-order disputes (DISPUTE before CHARGE)
 * - Duplicate disputes (idempotent)
 * - Disputing non-fraudulent charges
 * - Exactly at threshold (use >=)
 * - Minimum transactions requirement
 * - Lexicographic sorting
 * - Multiple MCCs
 * 
 * TIME COMPLEXITY: O(N + M log M) where N = operations, M = fraudulent
 * merchants
 * SPACE COMPLEXITY: O(N + M) for storing charges and merchant stats
 * 
 * EXAMPLE (PART 1):
 * -----------------
 * Input:
 * nonFraudulentCodes = "approved"
 * fraudulentCodes = "fraud"
 * minTransactions = 2
 * operations = [
 * "MCC,5814,3",
 * "MERCHANT,m1,5814",
 * "CHARGE,c1,m1,100,approved", // m1: fraud=0, total=1
 * "CHARGE,c2,m1,200,fraud", // m1: fraud=1, total=2 (check: 1 < 3)
 * "CHARGE,c3,m1,150,fraud", // m1: fraud=2, total=3 (check: 2 < 3)
 * "CHARGE,c4,m1,300,fraud" // m1: fraud=3, total=4 (check: 3 >= 3) ✓
 * ]
 * 
 * Output: "m1"
 */