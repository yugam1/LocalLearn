import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

// ============= PROBLEM DESCRIPTION =============
/*
CATCH ME IF YOU CAN - FRAUD DETECTION (Stripe HackerRank OA)
Platform: HackerRank (Stripe Online Assessment)
Link: https://www.linkjob.ai/interview-questions/stripe-hackerrank-online-assessment/

═══════════════════════════════════════════════════════════════════════════════
PART 1: COUNT-BASED FRAUD DETECTION
═══════════════════════════════════════════════════════════════════════════════

Background:
Build a fraud detection model that marks merchants as fraudulent if too many 
of their transactions are suspicious using COUNT-BASED thresholds.

Concepts:
- Each merchant has a Merchant Category Code (MCC) representing their industry
- Each MCC has a fraud threshold (integer > 1) = max allowed fraudulent transactions
- Track transactions and mark merchants exceeding threshold

Input Format:
1. nonFraudulentCodes: "approved,invalid_pin,expired_card" (comma-separated)
2. fraudulentCodes: "do_not_honor,stolen_card,lost_card" (comma-separated)
3. mccData[]: "MCC,threshold" (one per line)
   Example: "5411,10" means MCC 5411 has threshold of 10 frauds
4. merchantData[]: "account_id,MCC" (one per line)
   Example: "acct_123,5411"
5. minTransactions: integer >= 0 (min transactions before evaluation)
6. charges[]: "CHARGE,charge_id,account_id,amount,code"
   Example: "CHARGE,ch_1,acct_123,100.50,approved"

Rules:
- Only evaluate merchants after minTransactions reached
- Mark merchant as fraudulent if fraud_count >= threshold
- Once marked fraudulent, merchant STAYS fraudulent

Output:
Lexicographically sorted, comma-separated list of fraudulent merchants.
Example: "acct_001,acct_042,acct_123"

═══════════════════════════════════════════════════════════════════════════════
PART 2: PERCENTAGE-BASED FRAUD DETECTION
═══════════════════════════════════════════════════════════════════════════════

Problem:
Count-based thresholds unfairly mark high-volume merchants. Use PERCENTAGE instead.

Changes from Part 1:
- MCC threshold is now a FRACTION (0.0 to 1.0) instead of integer
- If fraud_percentage >= threshold, mark as fraudulent
- fraud_percentage = fraud_count / total_count
- Merchants stay fraudulent even if percentage decreases later
- Still only evaluate after minTransactions

Input Format: Same as Part 1, except:
3. mccData[]: "MCC,threshold" where threshold is float
   Example: "5411,0.25" means 25% fraud threshold

Rules:
- Calculate: fraud_percentage = fraud_count / total_count
- Mark fraudulent if fraud_percentage >= threshold
- Once marked, STAYS fraudulent
- Only evaluate after minTransactions

Output: Same as Part 1

═══════════════════════════════════════════════════════════════════════════════
PART 3: DISPUTE RESOLUTION
═══════════════════════════════════════════════════════════════════════════════

Problem:
Transactions can be incorrectly marked fraudulent. Support disputes to overturn them.

New Input:
7. disputes can appear in charges array: "DISPUTE,charge_id"

Rules:
- When DISPUTE appears, that charge is NO LONGER considered fraudulent
- Recalculate merchant's fraud count/percentage
- If merchant was ONLY fraudulent due to disputed transactions:
  * They may return to NON-FRAUDULENT status
  * Until they cross threshold again with NEW transactions
- If merchant has other frauds beyond disputed ones, they stay fraudulent

Edge Cases:
- DISPUTE can appear BEFORE the corresponding CHARGE (out-of-order)
- Multiple DISPUTEs for same charge_id (should be idempotent)
- Disputed transaction that was the "tipping point" for fraud

Output: Same as Part 1 & 2

Example:
MCC: "5000,3" (count-based, threshold=3)
Merchant: "acct_1,5000"
minTransactions: 2

Charges:
  CHARGE,ch_1,acct_1,100,fraud    -> fraud=1, total=1
  CHARGE,ch_2,acct_1,50,fraud     -> fraud=2, total=2 (evaluated, OK)
  CHARGE,ch_3,acct_1,75,fraud     -> fraud=3, total=3 -> FRAUDULENT!
  DISPUTE,ch_2                    -> fraud=2, total=3 (drops below threshold!)
  
If using percentage-based (0.5 = 50%):
  After dispute: 2/3 = 66.7% >= 50% -> STILL FRAUDULENT
*/

// Entities

class Merchant {
        public String mcc;
        public int threshold;
        public String thresholdType;
        List<Account> accounts;

        Merchant(String code, String thres) {
                this.mcc = code;
                Float num = Float.parseFloat(thres);
                if (num < 1) {
                        this.thresholdType = "PERCENT";
                        num = num * 100;

                } else {
                        this.thresholdType = "ABSOLUTE";
                }
                this.threshold = num.intValue();
                accounts = new ArrayList<>();
        }

        boolean isCutoffMet(int total, int curr) {
                if (this.thresholdType.equals("ABSOLUTE")) {
                        return curr >= threshold;
                } else {
                        return ((float) (curr) * 100 / (float) total) >= threshold;
                }
        }
}

class Account {
        public String accountId;
        public String merchantId;
        public List<Transaction> allTransactions;

        Account(String id, String mid) {
                this.accountId = id;
                this.merchantId = mid;
                allTransactions = new ArrayList<>();
        }

}

/**
 * 
 * "CHARGE,ch_4,acct_1,100,bad",
 * "CHARGE,ch_5,acct_2,100,ok"
 */
class Transaction {
        public String chargeId;
        public Double amount;
        public String accountId;
        public boolean isMarkedFraudulent;
        public boolean isDisputed;

        Transaction(String id, Double am, String accountId, boolean isMarkedFraudulent) {
                this.chargeId = id;
                this.amount = am;
                this.accountId = accountId;
                this.isMarkedFraudulent = isMarkedFraudulent;
                isDisputed = false;
        }

        Transaction(String id) {
                this.chargeId = id;
                this.amount = null;
                this.accountId = null;
                this.isMarkedFraudulent = false;
                this.isDisputed = false;

        }

        void setAmountFraudCodeAndIsMarkedFraudulent(Double am, String accountId, boolean isMarkedFraudulent) {
                this.amount = am;
                this.accountId = accountId;
                this.isMarkedFraudulent = isMarkedFraudulent;
        }
}

// ============= SOLUTION CLASS =============
class Solution {

        Set<String> fraudulentCodesSet;
        Set<String> nonFraudulentCodesSet;
        Map<String, Merchant> merchantEntityRepo;
        Map<String, Account> accountEntityRepo;
        Map<String, Transaction> transactionEntityRepo;

        // ========== PART 1: COUNT-BASED ==========
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

        // ========== PART 2: PERCENTAGE-BASED ==========
        public String detectFraudPart2(
                        String nonFraudulentCodes,
                        String fraudulentCodes,
                        String[] mccData, // Now contains floats: "MCC,0.25"
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
                                if (total >= minTransactions && m.isCutoffMet(total, count)) {
                                        fmerchant.add(acc.accountId);
                                }
                        }

                }
                Collections.sort(fmerchant);
                String res = String.join(",", fmerchant);

                return res;
        }

        // ========== PART 3: WITH DISPUTES ==========

        public String detectFraudPart3(
                        String nonFraudulentCodes,
                        String fraudulentCodes,
                        String[] mccData, // Can be integer OR float
                        String[] merchantData,
                        int minTransactions,
                        String[] operations // Now includes "DISPUTE,charge_id"
        ) {
                List<String> fmerchant = new ArrayList<>();
                // TODO: Implement Part
                merchantEntityRepo = new HashMap<>();
                accountEntityRepo = new HashMap<>();
                transactionEntityRepo = new HashMap<>();
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
                for (int i = 0; i < operations.length; i++) {
                        String[] data = operations[i].split(",");
                        Transaction t;
                        if (!transactionEntityRepo.containsKey(data[1])) {
                                transactionEntityRepo.put(data[1], new Transaction(data[1]));
                        }
                        t = transactionEntityRepo.get(data[1]);
                        if (data[0].equals("CHARGE")) {
                                t.setAmountFraudCodeAndIsMarkedFraudulent(Double.parseDouble(data[3]), data[2],
                                                fraudulentCodesSet.contains(data[4]));
                                Account acc = accountEntityRepo.get(data[2]);
                                acc.allTransactions.add(t);
                        } else {
                                t.isDisputed = true;
                        }
                }

                for (var row : merchantEntityRepo.entrySet()) {
                        Merchant m = row.getValue();

                        for (Account acc : m.accounts) {
                                int count = 0;
                                int total = 0;
                                for (Transaction t : acc.allTransactions) {
                                        if (t.isMarkedFraudulent && !t.isDisputed) {
                                                count++;
                                        }
                                        total++;
                                }
                                if (total >= minTransactions && m.isCutoffMet(total, count)) {
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
class Judge {
        // ========== CONFIGURATION ==========
        private static final int CURRENT_PART = 2; // Set to 1, 2, or 3
        private static final boolean CHECK_FULL = true; // Full test suite
        private static final int[] SELECTED_TESTS = { 5 }; // Specific tests

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
                System.out.println("CATCH ME IF YOU CAN - FRAUD DETECTION");
                System.out.println("Platform: Stripe HackerRank OA");
                System.out.println("=".repeat(70));
                System.out.println("CURRENT PART: " + CURRENT_PART);
                System.out.println("Mode: " + (CHECK_FULL ? "FULL TEST" : "BASIC TEST"));
                if (SELECTED_TESTS.length > 0) {
                        System.out.println("Selected Tests: " + Arrays.toString(SELECTED_TESTS));
                }
                System.out.println("=".repeat(70));

                switch (CURRENT_PART) {
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
                                System.out.println("Invalid CURRENT_PART. Set to 1, 2, or 3.");
                                return;
                }

                printSummary();
        }

        // ╔═══════════════════════════════════════════════════════════════════════╗
        // ║ PART 1 TESTS ║
        // ╚═══════════════════════════════════════════════════════════════════════╝

        private static void runPart1Tests(Solution solution) {
                System.out.println("\n" + "═".repeat(70));
                System.out.println("PART 1: COUNT-BASED FRAUD DETECTION");
                System.out.println("═".repeat(70));

                runPart1BasicTests(solution);

                if (CHECK_FULL) {
                        runPart1EdgeTests(solution);
                        runPart1PerformanceTests(solution);
                }
        }

        private static void runPart1BasicTests(Solution solution) {
                System.out.println("\n--- Part 1: Basic Tests ---");

                // Test 1: Simple threshold crossing
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

                // Test 3: Lexicographic sorting
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

                // Test 4: Minimum transaction not met
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

                // Test 5: No fraudulent merchants
                test(
                                () -> solution.detectFraudPart1(
                                                "approved,valid",
                                                "fraud",
                                                new String[] { "5000,10" },
                                                new String[] { "acct_1,5000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,approved",
                                                                "CHARGE,ch_2,acct_1,100,valid"
                                                }),
                                "",
                                "No fraudulent merchants");
        }

        private static void runPart1EdgeTests(Solution solution) {
                System.out.println("\n--- Part 1: Edge Cases ---");

                // Test 6: Different MCCs with different thresholds
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
                                                                "CHARGE,ch_4,acct_2,100,bad"
                                                }),
                                "acct_1",
                                "Different MCCs with different thresholds");

                // Test 7: Exactly at threshold
                test(
                                () -> solution.detectFraudPart1(
                                                "ok",
                                                "bad",
                                                new String[] { "3000,5" },
                                                new String[] { "acct_1,3000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,bad",
                                                                "CHARGE,ch_2,acct_1,100,bad",
                                                                "CHARGE,ch_3,acct_1,100,bad",
                                                                "CHARGE,ch_4,acct_1,100,bad",
                                                                "CHARGE,ch_5,acct_1,100,bad"
                                                }),
                                "acct_1",
                                "Exactly at threshold (5 >= 5)");
        }

        private static void runPart1PerformanceTests(Solution solution) {
                System.out.println("\n--- Part 1: Performance Tests ---");

                String[] mccData = { "9999,10" };
                List<String> merchants = new ArrayList<>();
                List<String> charges = new ArrayList<>();

                for (int i = 0; i < 1000; i++) {
                        merchants.add("acct_" + i + ",9999");
                        for (int j = 0; j < 15; j++) {
                                charges.add("CHARGE,ch_" + i + "_" + j + ",acct_" + i + ",100,fraud");
                        }
                }

                long start = System.nanoTime();
                String result = solution.detectFraudPart1(
                                "ok", "fraud", mccData,
                                merchants.toArray(new String[0]), 1,
                                charges.toArray(new String[0]));
                long duration = (System.nanoTime() - start) / 1_000_000;

                System.out.printf("✓ Large scale (1000 merchants, 15k charges): %d ms%n", duration);
                if (duration > 1000)
                        System.out.println("  ⚠ Performance could be optimized");
                else
                        System.out.println("  ✓ Performance excellent");
        }

        // ╔═══════════════════════════════════════════════════════════════════════╗
        // ║ PART 2 TESTS ║
        // ╚═══════════════════════════════════════════════════════════════════════╝

        private static void runPart2Tests(Solution solution) {
                System.out.println("\n" + "═".repeat(70));
                System.out.println("PART 2: PERCENTAGE-BASED FRAUD DETECTION");
                System.out.println("═".repeat(70));

                runPart2BasicTests(solution);

                if (CHECK_FULL) {
                        runPart2EdgeTests(solution);
                        runPart2PerformanceTests(solution);
                }
        }

        private static void runPart2BasicTests(Solution solution) {
                System.out.println("\n--- Part 2: Basic Tests ---");

                // Test 1: 50% threshold - exactly at boundary
                test(
                                () -> solution.detectFraudPart2(
                                                "ok",
                                                "fraud",
                                                new String[] { "5000,0.5" }, // 50% threshold
                                                new String[] { "acct_1,5000" },
                                                2,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // 1/1 = 100%
                                                                "CHARGE,ch_2,acct_1,50,ok" // 1/2 = 50% -> FRAUD!
                                                }),
                                "acct_1",
                                "50% threshold, exactly at boundary (1 fraud in 2 tx)");

                // Test 2: 25% threshold
                test(
                                () -> solution.detectFraudPart2(
                                                "ok",
                                                "fraud",
                                                new String[] { "6000,0.25" }, // 25% threshold
                                                new String[] { "acct_1,6000" },
                                                3,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,ok", // 0/1 = 0%
                                                                "CHARGE,ch_2,acct_1,50,ok", // 0/2 = 0%
                                                                "CHARGE,ch_3,acct_1,75,fraud", // 1/3 = 33% -> FRAUD!
                                                                "CHARGE,ch_4,acct_1,80,ok" // 1/4 = 25% (stays
                                                                                           // fraudulent)
                                                }),
                                "acct_1",
                                "25% threshold, crosses at 33%");

                // Test 3: High-volume merchant stays clean
                test(
                                () -> solution.detectFraudPart2(
                                                "ok",
                                                "fraud",
                                                new String[] { "7000,0.1" }, // 10% threshold
                                                new String[] { "acct_1,7000" },
                                                5,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,ok",
                                                                "CHARGE,ch_2,acct_1,50,ok",
                                                                "CHARGE,ch_3,acct_1,75,ok",
                                                                "CHARGE,ch_4,acct_1,80,ok",
                                                                "CHARGE,ch_5,acct_1,90,ok", // 0/5 = 0%
                                                                "CHARGE,ch_6,acct_1,100,fraud" // 1/6 = 16.7% -> FRAUD!
                                                }),
                                "acct_1",
                                "10% threshold, 1 fraud in 6 transactions");

                // Test 4: Below threshold stays clean
                test(
                                () -> solution.detectFraudPart2(
                                                "ok",
                                                "fraud",
                                                new String[] { "8000,0.5" },
                                                new String[] { "acct_1,8000" },
                                                4,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // 1/1 = 100%
                                                                "CHARGE,ch_2,acct_1,50,ok", // 1/2 = 50%
                                                                "CHARGE,ch_3,acct_1,75,ok", // 1/3 = 33%
                                                                "CHARGE,ch_4,acct_1,80,ok" // 1/4 = 25% < 50%
                                                }),
                                "",
                                "Below 50% threshold (1 fraud in 4 tx = 25%)");
        }

        private static void runPart2EdgeTests(Solution solution) {
                System.out.println("\n--- Part 2: Edge Cases ---");

                // Test 5: Merchant marked then percentage drops (stays fraudulent)
                test(
                                () -> solution.detectFraudPart2(
                                                "ok",
                                                "fraud",
                                                new String[] { "9000,0.5" },
                                                new String[] { "acct_1,9000" },
                                                2,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // 1/1 = 100%
                                                                "CHARGE,ch_2,acct_1,50,fraud", // 2/2 = 100% -> FRAUD!
                                                                "CHARGE,ch_3,acct_1,75,ok", // 2/3 = 66%
                                                                "CHARGE,ch_4,acct_1,80,ok", // 2/4 = 50%
                                                                "CHARGE,ch_5,acct_1,90,ok" // 2/5 = 40% (still
                                                                                           // fraudulent)
                                                }),
                                "",
                                "Percentage drops below threshold (stays fraudulent)");

                // Test 6: 100% fraud rate
                test(
                                () -> solution.detectFraudPart2(
                                                "ok",
                                                "fraud",
                                                new String[] { "1111,0.8" }, // 80% threshold
                                                new String[] { "acct_1,1111" },
                                                2,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud",
                                                                "CHARGE,ch_2,acct_1,50,fraud",
                                                                "CHARGE,ch_3,acct_1,75,fraud" // 3/3 = 100% >= 80%
                                                }),
                                "acct_1",
                                "100% fraud rate exceeds 80% threshold");
        }

        private static void runPart2PerformanceTests(Solution solution) {
                System.out.println("\n--- Part 2: Performance Tests ---");

                List<String> charges = new ArrayList<>();
                for (int i = 0; i < 10000; i++) {
                        String code = (i % 4 == 0) ? "fraud" : "ok"; // 25% fraud
                        charges.add("CHARGE,ch_" + i + ",acct_test,100," + code);
                }

                long start = System.nanoTime();
                solution.detectFraudPart2(
                                "ok", "fraud",
                                new String[] { "8888,0.2" }, // 20% threshold
                                new String[] { "acct_test,8888" },
                                10,
                                charges.toArray(new String[0]));
                long duration = (System.nanoTime() - start) / 1_000_000;

                System.out.printf("✓ High volume (10k charges, percentage calc): %d ms%n", duration);
                if (duration > 1000)
                        System.out.println("  ⚠ Performance could be optimized");
                else
                        System.out.println("  ✓ Performance excellent");
        }

        // ╔═══════════════════════════════════════════════════════════════════════╗
        // ║ PART 3 TESTS ║
        // ╚═══════════════════════════════════════════════════════════════════════╝

        private static void runPart3Tests(Solution solution) {
                System.out.println("\n" + "═".repeat(70));
                System.out.println("PART 3: DISPUTE RESOLUTION");
                System.out.println("═".repeat(70));

                runPart3BasicTests(solution);

                if (CHECK_FULL) {
                        runPart3EdgeTests(solution);
                        runPart3PerformanceTests(solution);
                }
        }

        private static void runPart3BasicTests(Solution solution) {
                System.out.println("\n--- Part 3: Basic Tests ---");

                // Test 1: Dispute before crossing threshold (count-based)
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "1000,3" }, // Count threshold
                                                new String[] { "acct_1,1000" },
                                                2,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // fraud=1
                                                                "CHARGE,ch_2,acct_1,50,fraud", // fraud=2
                                                                "DISPUTE,ch_1", // fraud=1 (disputed)
                                                                "CHARGE,ch_3,acct_1,75,fraud", // fraud=2
                                                                "CHARGE,ch_4,acct_1,80,fraud" // fraud=3 -> FRAUD!
                                                }),
                                "acct_1",
                                "Dispute reduces count, still crosses threshold");

                // Test 2: Dispute drops below threshold -> unmarked (count-based)
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "2000,3" },
                                                new String[] { "acct_1,2000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // fraud=1
                                                                "CHARGE,ch_2,acct_1,50,fraud", // fraud=2
                                                                "CHARGE,ch_3,acct_1,75,fraud", // fraud=3 -> FRAUDULENT!
                                                                "DISPUTE,ch_1", // fraud=2 (< 3, UNMARKED!)
                                                                "CHARGE,ch_4,acct_1,80,ok" // fraud=2 (stays clean)
                                                }),
                                "",
                                "Dispute drops below threshold -> merchant unmarked");

                // Test 3: Dispute stays at threshold -> stays marked (count-based)
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "3000,2" }, // threshold = 2
                                                new String[] { "acct_1,3000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // fraud=1
                                                                "CHARGE,ch_2,acct_1,50,fraud", // fraud=2 -> FRAUDULENT!
                                                                "CHARGE,ch_3,acct_1,75,fraud", // fraud=3
                                                                "DISPUTE,ch_1", // fraud=2 (= 2, stays fraudulent)
                                                                "CHARGE,ch_4,acct_1,80,ok"
                                                }),
                                "acct_1",
                                "Dispute stays at threshold -> merchant stays marked");

                // Test 4: Dispute with percentage drops below threshold -> unmarked
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "4000,0.5" }, // 50% threshold
                                                new String[] { "acct_1,4000" },
                                                2,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // 1/1 = 100%
                                                                "CHARGE,ch_2,acct_1,50,fraud", // 2/2 = 100% -> FRAUD!
                                                                "DISPUTE,ch_1", // 1/2 = 50% (at threshold, stays
                                                                                // marked)
                                                                "CHARGE,ch_3,acct_1,75,ok" // 1/3 = 33% (< 50%,
                                                                                           // UNMARKED!)
                                                }),
                                "",
                                "Dispute with percentage drops below 50% -> unmarked");

                // Test 5: Dispute prevents marking (count-based)
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "5000,3" },
                                                new String[] { "acct_1,5000" },
                                                2,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // fraud=1
                                                                "CHARGE,ch_2,acct_1,50,fraud", // fraud=2
                                                                "DISPUTE,ch_1", // fraud=1
                                                                "CHARGE,ch_3,acct_1,75,fraud" // fraud=2 < 3 (never
                                                                                              // marked)
                                                }),
                                "",
                                "Dispute prevents reaching threshold");
        }

        private static void runPart3EdgeTests(Solution solution) {
                System.out.println("\n--- Part 3: Edge Cases ---");

                // Test 6: Out-of-order dispute (dispute before charge)
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "6000,2" },
                                                new String[] { "acct_1,6000" },
                                                1,
                                                new String[] {
                                                                "DISPUTE,ch_2", // Dispute before charge exists!
                                                                "CHARGE,ch_1,acct_1,100,fraud", // fraud=1
                                                                "CHARGE,ch_2,acct_1,50,fraud", // Disputed -> fraud=1
                                                                                               // still
                                                                "CHARGE,ch_3,acct_1,75,fraud" // fraud=2 -> FRAUD!
                                                }),
                                "acct_1",
                                "Out-of-order dispute (dispute before charge)");

                // Test 7: Multiple disputes same charge (idempotent)
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "7000,2" },
                                                new String[] { "acct_1,7000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // fraud=1
                                                                "DISPUTE,ch_1", // fraud=0
                                                                "DISPUTE,ch_1", // fraud=0 (idempotent, no effect)
                                                                "CHARGE,ch_2,acct_1,50,fraud", // fraud=1
                                                                "CHARGE,ch_3,acct_1,75,fraud" // fraud=2 -> FRAUD!
                                                }),
                                "acct_1",
                                "Multiple disputes same charge (idempotent)");

                // Test 8: Dispute non-fraud transaction (no-op)
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "8000,2" },
                                                new String[] { "acct_1,8000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,ok", // Not fraud, fraud=0
                                                                "DISPUTE,ch_1", // No effect, fraud=0
                                                                "CHARGE,ch_2,acct_1,50,fraud", // fraud=1
                                                                "CHARGE,ch_3,acct_1,75,fraud" // fraud=2 -> FRAUD!
                                                }),
                                "acct_1",
                                "Dispute non-fraudulent transaction (no effect)");

                // Test 9: Merchant re-crosses threshold after being unmarked
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "9000,2" },
                                                new String[] { "acct_1,9000" },
                                                1,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // fraud=1
                                                                "CHARGE,ch_2,acct_1,50,fraud", // fraud=2 -> FRAUDULENT!
                                                                "DISPUTE,ch_1", // fraud=1 (< 2, UNMARKED)
                                                                "DISPUTE,ch_2", // fraud=0 (still unmarked)
                                                                "CHARGE,ch_3,acct_1,75,fraud", // fraud=1
                                                                "CHARGE,ch_4,acct_1,80,fraud" // fraud=2 -> FRAUDULENT
                                                                                              // AGAIN!
                                                }),
                                "acct_1",
                                "Merchant unmarked then re-crosses threshold");

                // Test 10: Percentage threshold - dispute keeps at exact boundary
                test(
                                () -> solution.detectFraudPart3(
                                                "ok",
                                                "fraud",
                                                new String[] { "10000,0.5" }, // 50%
                                                new String[] { "acct_1,10000" },
                                                2,
                                                new String[] {
                                                                "CHARGE,ch_1,acct_1,100,fraud", // 1/1 = 100%
                                                                "CHARGE,ch_2,acct_1,50,fraud", // 2/2 = 100% -> FRAUD!
                                                                "CHARGE,ch_3,acct_1,75,ok", // 2/3 = 66.7%
                                                                "DISPUTE,ch_1", // 1/3 = 33% (< 50%, UNMARKED)
                                                                "CHARGE,ch_4,acct_1,80,ok" // 1/4 = 25% (stays clean)
                                                }),
                                "",
                                "Percentage dispute unmarking below threshold");
        }

        private static void runPart3PerformanceTests(Solution solution) {
                System.out.println("\n--- Part 3: Performance Tests ---");

                List<String> ops = new ArrayList<>();
                for (int i = 0; i < 5000; i++) {
                        ops.add("CHARGE,ch_" + i + ",acct_test,100,fraud");
                        if (i % 2 == 0) {
                                ops.add("DISPUTE,ch_" + i); // Dispute half
                        }
                }

                long start = System.nanoTime();
                solution.detectFraudPart3(
                                "ok", "fraud",
                                new String[] { "8888,1000" },
                                new String[] { "acct_test,8888" },
                                10,
                                ops.toArray(new String[0]));
                long duration = (System.nanoTime() - start) / 1_000_000;

                System.out.printf("✓ Many disputes (5k charges, 2.5k disputes): %d ms%n", duration);
                if (duration > 1000)
                        System.out.println("  ⚠ Performance could be optimized");
                else
                        System.out.println("  ✓ Performance excellent");
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
                                System.out.printf("✓ PASS [#%d]: %s%n", currentTestNumber, description);
                        } else {
                                System.out.printf("✗ FAIL [#%d]: %s%n", currentTestNumber, description);
                                System.out.println("  Expected: " + expected);
                                System.out.println("  Got:      " + result);
                        }
                } catch (Exception e) {
                        System.out.printf("✗ ERROR [#%d]: %s%n", currentTestNumber, description);
                        System.out.println("  Exception: " + e.getMessage());
                        e.printStackTrace();
                }
        }

        private static void printSummary() {
                System.out.println("\n" + "═".repeat(70));
                System.out.println("TEST SUMMARY - PART " + CURRENT_PART);
                System.out.println("═".repeat(70));

                if (SELECTED_TESTS.length > 0) {
                        System.out.printf("Selected: %d/%d tests%n", totalTests, currentTestNumber);
                }

                System.out.printf("Passed: %d/%d tests%n", passedTests, totalTests);

                if (passedTests == totalTests) {
                        System.out.println("✓ All tests passed! 🎉");
                        if (CURRENT_PART < 3) {
                                System.out.println("\n📌 Next: Set CURRENT_PART = " + (CURRENT_PART + 1)
                                                + " to test Part " + (CURRENT_PART + 1));
                        } else {
                                System.out.println("\n🎊 All parts complete!");
                        }
                } else {
                        System.out.printf("✗ %d test(s) failed%n", totalTests - passedTests);
                }

                System.out.println("\n" + "─".repeat(70));
                System.out.println("Configuration:");
                System.out.println("  CURRENT_PART = " + CURRENT_PART + " (Set to 1, 2, or 3)");
                System.out.println("  CHECK_FULL = " + CHECK_FULL + " (Set true for edge cases)");
                System.out.println("  SELECTED_TESTS = " + Arrays.toString(SELECTED_TESTS)
                                + " (Empty = all)");
                System.out.println("─".repeat(70));
        }

        @FunctionalInterface
        interface TestSupplier<T> {
                T get() throws Exception;
        }
}

// ============= ALGORITHM HINTS =============
/*
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║ PART 1 HINTS ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 * 
 * DATA STRUCTURES:
 * - Set<String> fraudCodes (O(1) lookup)
 * - Map<String, Integer> mccThresholds (MCC -> count threshold)
 * - Map<String, String> accountToMcc
 * - Map<String, MerchantStats> stats
 * 
 * MerchantStats: {totalCharges, fraudCharges, isMarkedFraudulent}
 * 
 * ALGORITHM:
 * 1. Parse fraud codes into Set
 * 2. Parse MCC thresholds
 * 3. Parse merchant->MCC mappings
 * 4. For each CHARGE:
 * - Update merchant stats
 * - After minTransactions: if fraudCharges >= threshold -> mark fraudulent
 * 5. Sort and join fraudulent merchants
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║ PART 2 HINTS ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 * 
 * CHANGES FROM PART 1:
 * - MCC threshold is now DOUBLE (0.0 to 1.0)
 * - Check: (fraudCharges / totalCharges) >= threshold
 * - Still only evaluate after minTransactions
 * - Once marked, stays marked even if percentage drops
 * 
 * TIP: Use Double.parseDouble() for threshold parsing
 * 
 * ╔═══════════════════════════════════════════════════════════════════════════╗
 * ║ PART 3 HINTS ║
 * ╚═══════════════════════════════════════════════════════════════════════════╝
 * 
 * NEW COMPLEXITY:
 * - Operations now include "DISPUTE,charge_id"
 * - Need to track individual charges: Map<charge_id, ChargeInfo>
 * - ChargeInfo: {merchant, isFraud, isDisputed}
 * 
 * DISPUTE HANDLING:
 * 1. If charge exists and was fraud and not already disputed:
 * - Mark as disputed
 * - Decrement merchant's fraud count
 * - Recalculate if still fraudulent
 * 2. If dispute comes BEFORE charge (out-of-order):
 * - Store in Set<charge_id> pendingDisputes
 * - When charge arrives, check if disputed
 * 3. Multiple disputes same charge: idempotent (use isDisputed flag)
 * 
 * EDGE CASE: Merchant marked fraudulent, then dispute drops below threshold
 * - For COUNT: If frauds drop below threshold, merchant could become clean
 * - For PERCENTAGE: Recalculate ratio after dispute
 * - Implementation choice: Keep marked or allow reversal
 * 
 * TIME: O(N) for N operations
 * SPACE: O(M + C) for M merchants, C charges
 */