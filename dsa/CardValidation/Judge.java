package CardValidation;

import java.util.*;
import java.util.stream.Collector;
import java.util.stream.Collectors;

// ============= PROBLEM DESCRIPTION =============
/*
STRIPE PAYMENT CARD VALIDATION SYSTEM

Platform: Stripe Interview / HackerRank
Difficulty: MEDIUM-HARD

Background:
Stripe processes billions through payment cards. Validate card numbers using:
1. Network detection (VISA, MASTERCARD, AMEX)
2. Luhn algorithm validation
3. Handle redacted (*) and corrupted (?) inputs

Card Networks:
- VISA: 16 digits, starts with 4
- MASTERCARD: 16 digits, starts with 51-55
- AMEX: 15 digits, starts with 34 or 37

Luhn Algorithm:
1. From rightmost digit, double every 2nd digit going left
2. If doubled digit > 9, subtract 9
3. Sum all digits
4. Valid if sum % 10 == 0

Example: 4532015112830366
Digits:     4 5 3 2 0 1 5 1 1 2 8 3 0 3 6 6
Double 2nd: 4 10 3 4 0 2 5 2 1 4 8 6 0 6 6 12
Reduce >9:  4 1 3 4 0 2 5 2 1 4 8 6 0 6 6 3
Sum = 4+1+3+4+0+2+5+2+1+4+8+6+0+6+6+3 = 55... wait let me recalculate

Actually: 4532015112830366
Starting from right, double every other:
6: 6
6: 6*2=12 -> 1+2=3
3: 3
0: 0*2=0
3: 3
8: 8*2=16 -> 1+6=7
2: 2
1: 1*2=2
1: 1
5: 5*2=10 -> 1+0=1
1: 1
0: 0*2=0
2: 2
3: 3*2=6
5: 5
4: 4*2=8

Sum = 6+3+3+0+3+7+2+2+1+1+1+0+2+6+5+8 = 50
50 % 10 = 0 → VALID

=============================================================================
PART 1: BASIC VISA VALIDATION
=============================================================================
Input: 16-digit number starting with 4
Output:
- "VISA" if Luhn checksum passes
- "INVALID_CHECKSUM" if checksum fails

Examples:
Input: "4532015112830366" → Output: "VISA"
Input: "4242424242424243" → Output: "INVALID_CHECKSUM"

=============================================================================
PART 2: MULTI-NETWORK VALIDATION
=============================================================================
Input: 15 or 16 digit card number
Output:
- Network name (VISA / MASTERCARD / AMEX) if valid
- "INVALID_CHECKSUM" if checksum fails but network recognized
- "UNKNOWN_NETWORK" if length/prefix doesn't match any network

Examples:
Input: "5482334509943" → Output: "UNKNOWN_NETWORK" (13 digits)
Input: "4425233430109994" → Output: "VISA"
Input: "562523343010901" → Output: "UNKNOWN_NETWORK" (prefix 56, 15 digits)
Input: "5425233430109993" → Output: "MASTERCARD"
Input: "378282246310005" → Output: "AMEX"

=============================================================================
PART 3: REDACTED CARDS
=============================================================================
Input: Card with * (1-5 digits redacted)
Output: Count of valid cards per network, sorted alphabetically by network

Format: "NETWORK,count" (one per line)

Examples:
Input: "4242424242424*42"
- Try all 10 digits for *
- Count how many result in valid VISA cards
Output: "VISA,1"

Input: "3*8282246310005"
- Position 1 can be 0-9, check which make valid AMEX
Output: "AMEX,2" (34 and 37 both work)

Input: "**2424242424242"
- Two wildcards = 100 possibilities (00-99)
- Count valid for each network
Output:
"MASTERCARD,5
VISA,10"

=============================================================================
PART 4: CORRUPTED CARDS
=============================================================================
Input: Card ending with ? - exactly one error occurred:
- One digit changed, OR
- Two adjacent digits swapped

Output: All valid original cards in ascending numeric order
Format: "card_number,NETWORK" (one per line)

Example:
Input: "4344555566660004?"
Possibilities:
1. Last digit is 0-9: 43445555666600040 - 43445555666600049
2. Swap last two known digits (4?): 4344555566660040, 4344555566660044
3. One digit changed in first 15 digits
4. Adjacent swap in first 15 digits

Output (partial):
"4342555566660004,VISA"
"4344555566660004,VISA"
"4344555566660014,VISA"
...

Constraints:
- 1 <= card length <= 16
- Redacted: 1-5 asterisks
- Output must match format exactly
- Part 4: Sort numerically, then by network if same number
- Part 3: Sort alphabetically by network
*/

// Helper classes
// Input: "5482334509943" → Output: "UNKNOWN_NETWORK" (13 digits)
// Input: "4425233430109994" → Output: "VISA"
// Input: "562523343010901" → Output: "UNKNOWN_NETWORK" (prefix 56, 15 digits)
// Input: "5425233430109993" → Output: "MASTERCARD"
// Input: "378282246310005" → Output: "AMEX"

abstract class Validator {
    String message;
    Validator next;

    abstract boolean isValid(String cardNumber);

    String getMessage() {
        return message;
    }

}

class VendorValidatorComposite extends Validator {
    List<Validator> vendors;

    VendorValidatorComposite() {
        message = "UNKNOWN_NETWORK";
        vendors = new ArrayList<>();
        vendors.add(new AmexValidator());
        vendors.add(new MasterCardValidator());
        vendors.add(new VisaValidator());
    }

    boolean isValid(String cardNumber) {
        Validator basic = new BasicValidator();
        if (!basic.isValid(cardNumber)) {
            message = basic.message;
            return false;
        }
        for (Validator v : vendors) {
            if (v.isValid(cardNumber)) {
                message = v.message;
            }
        }
        return true;
    }
}

// - VISA: 16 digits, starts with 4
// - MASTERCARD: 16 digits, starts with 51-55
// - AMEX: 15 digits, starts with 34 or 37
class VisaValidator extends Validator {
    VisaValidator() {
        message = "VISA";
    }

    boolean isValid(String cardNumber) {
        int n = cardNumber.length();
        return n == 16 && (cardNumber.charAt(0) == '4');
    }
}

class MasterCardValidator extends Validator {
    MasterCardValidator() {
        message = "MASTERCARD";
    }

    boolean isValid(String cardNumber) {
        int n = cardNumber.length();
        String substr = cardNumber.substring(0, 2);
        int sub = Integer.parseInt(substr);
        return n == 16 && (sub >= 51 && sub <= 55);
    }
}

class AmexValidator extends Validator {
    AmexValidator() {
        message = "AMEX";
    }

    boolean isValid(String cardNumber) {
        int n = cardNumber.length();
        int sub = Integer.parseInt(cardNumber.substring(0, 2));
        return n == 15 && (sub == 34 || sub == 37);
    }
}

class BasicValidator extends Validator {
    BasicValidator() {
        message = "INVALID_CHECKSUM";
    }

    boolean isValid(String cardNumber) {
        int n = cardNumber.length();
        int sum = 0;
        int count = 0;
        for (int i = n - 1; i >= 0; i--) {
            int num = cardNumber.charAt(i) - '0';
            if (count % 2 == 1) {
                num = 2 * num;
                num = num > 9 ? num - 9 : num;
            }
            sum += num;
            count++;
        }

        if (sum % 10 == 0) {
            return true;
        }
        return false;

    }
}

// ============= SOLUTION CLASS =============

class Solution {
    List<String> arr;

    void permute(String cardNumber, StringBuilder sb, int start, char delimiter) {
        if (start >= cardNumber.length()) {
            arr.add(sb.toString());
            return;
        }
        if (cardNumber.charAt(start) == delimiter) {
            for (int i = 0; i < 10; i++) {
                sb.append(i);
                permute(cardNumber, sb, start + 1, delimiter);
                sb.deleteCharAt(sb.length() - 1);
            }
        } else {
            sb.append(cardNumber.charAt(start));
            permute(cardNumber, sb, start + 1, delimiter);
            sb.deleteCharAt(sb.length() - 1);
        }
    }

    /**
     * Validate payment card based on problem part requirements.
     * 
     * @param cardNumber Card number string (may contain * or end with ?)
     * @return Validation result (format varies by part)
     */
    // public String validateCard(String cardNumber) {
    // arr = new ArrayList<>();
    // Validator validator = new VendorValidatorComposite();
    // validator.isValid(cardNumber);
    // return validator.getMessage();

    // }

    // public String validateCard(String cardNumber) {
    // Map<String, Integer> hmap = new HashMap<>();
    // arr = new ArrayList<>();
    // Validator validator = new VendorValidatorComposite();
    // if (cardNumber.contains("*")) {
    // permute(cardNumber, new StringBuilder(), 0, '*');
    // } else if (cardNumber.contains("*")) {
    // permute(cardNumber, new StringBuilder(), 0, '?');
    // } else {
    // arr.add(cardNumber);
    // }
    // Set<String> valids = new HashSet<>();
    // for (String card : arr) {
    // validator.isValid(card);
    // String mess = validator.getMessage();
    // if (mess.equals("UNKNOWN_NETWORK") || mess.equals("INVALID_CHECKSUM"))
    // continue;
    // valids.add(card);
    // hmap.put(mess, 1 + hmap.getOrDefault(mess, 0));
    // }

    // return hmap.entrySet().stream().sorted(Map.Entry.comparingByKey())
    // .map(entry -> entry.getKey() + "," +
    // entry.getValue())
    // .collect(Collectors.joining("\n"));

    // }
    void findCorroptedSpot(String cardNumber) {
        StringBuilder sb = new StringBuilder(cardNumber);
        sb.setLength(sb.length() - 1);
        for (int i = 0; i < sb.length(); i++) {
            for (int v = 0; v < 10; v++) {
                char ch = (char) (v + '0');
                sb.setCharAt(i, ch);
                arr.add(sb.toString());

            }
            sb.setCharAt(i, cardNumber.charAt(i));
        }
        for (int i = 1; i < sb.length(); i++) {
            char ch = sb.charAt(i - 1);
            sb.setCharAt(i - 1, sb.charAt(i));
            sb.setCharAt(i, ch);
            arr.add(sb.toString());
        }
    }

    public String validateCard(String cardNumber) {
        System.out.println(cardNumber);
        Map<String, String> hmap = new HashMap<>();
        arr = new ArrayList<>();
        Validator validator = new VendorValidatorComposite();
        if (cardNumber.contains("*")) {
            permute(cardNumber, new StringBuilder(), 0, '*');
        } else if (cardNumber.contains("?")) {
            permute(cardNumber, new StringBuilder(), 0, '?');
            findCorroptedSpot(cardNumber);
        } else {
            arr.add(cardNumber);
        }
        // Set<String> valids = new HashSet<>();
        for (String card : arr) {
            validator.isValid(card);
            String mess = validator.getMessage();
            if (mess.equals("UNKNOWN_NETWORK") || mess.equals("INVALID_CHECKSUM"))
                continue;
            // valids.add(card);
            hmap.put(card, mess);
        }

        return hmap.entrySet().stream().sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "," +
                        entry.getValue())
                .collect(Collectors.joining("\n"));

    }
}

class Judge {
    private static final int PART = 4;
    private static final boolean CHECK_FULL = true;
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
        System.out.println("STRIPE PAYMENT CARD VALIDATION SYSTEM");
        System.out.println("Difficulty: MEDIUM-HARD");
        System.out.println("=".repeat(70));
        System.out.println("Testing: PART " + PART);
        System.out.println("Mode: " + (CHECK_FULL ? "FULL TEST" : "BASIC TEST"));
        System.out.println("=".repeat(70));

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
                System.out.println("❌ Invalid PART");
                return;
        }

        printSummary();
    }

    private static void runPart1Tests(Solution solution) {
        System.out.println("\n=== PART 1: BASIC VISA VALIDATION ===\n");

        test(() -> solution.validateCard("4532015112830366"), "VISA", "Valid VISA card");
        test(() -> solution.validateCard("4242424242424243"), "INVALID_CHECKSUM", "Invalid checksum");
        test(() -> solution.validateCard("4539148803436467"), "VISA", "Valid VISA #2");
        test(() -> solution.validateCard("4111111111111112"), "INVALID_CHECKSUM", "Invalid checksum #2");
        test(() -> solution.validateCard("4111111111111111"), "VISA", "Valid VISA #3");

        if (CHECK_FULL) {
            test(() -> solution.validateCard("4444444444444448"), "VISA", "All same digits");
        }
    }

    private static void runPart2Tests(Solution solution) {
        System.out.println("\n=== PART 2: MULTI-NETWORK VALIDATION ===\n");

        test(() -> solution.validateCard("6011111111111117"), "UNKNOWN_NETWORK", "Discover (unknown)");
        test(() -> solution.validateCard("4111111111111111"), "VISA", "Valid VISA");
        test(() -> solution.validateCard("30569309025904"), "UNKNOWN_NETWORK", "Diners (unknown)");
        test(() -> solution.validateCard("5425233430109903"), "MASTERCARD", "Valid MASTERCARD");
        test(() -> solution.validateCard("378282246310005"), "AMEX", "Valid AMEX");

        if (CHECK_FULL) {
            test(() -> solution.validateCard("378282246310006"), "INVALID_CHECKSUM", "AMEX invalid");
            test(() -> solution.validateCard("5105105105105100"), "MASTERCARD", "MASTERCARD 51");
            test(() -> solution.validateCard("5555555555554444"), "MASTERCARD", "MASTERCARD 55");
            test(() -> solution.validateCard("341111111111111"), "AMEX", "AMEX 34");
            test(() -> solution.validateCard("123456789012347"), "UNKNOWN_NETWORK", "15 digits valid Luhn");
            test(() -> solution.validateCard("4111111111111112"), "INVALID_CHECKSUM", "VISA invalid");
        }
    }

    private static void runPart3Tests(Solution solution) {
        System.out.println("\n=== PART 3: REDACTED CARDS ===\n");

        System.out.println("--- Basic Tests ---");

        // Test 1: Single * in VISA - VERIFIED ✓
        test(() -> solution.validateCard("4242424242424*42"),
                "VISA,1",
                "Single * in VISA");

        // Test 2: Single * at position 1 in AMEX - CORRECTED ✓
        test(() -> solution.validateCard("3*8282246310005"),
                "AMEX,1", // ✅ Changed from AMEX,2 to AMEX,1
                "Single * at pos 1 AMEX");

        // Test 3: Two ** at start - CORRECTED ✓
        test(() -> solution.validateCard("**24242424242424"), // ✅ 16 digits not 15
                "MASTERCARD,1\nVISA,1", // ✅ Changed from MC,5\nVISA,10
                "Two ** at start");

        // Test 4: Single * at end MASTERCARD - VERIFIED ✓
        test(() -> solution.validateCard("542523343010999*"),
                "MASTERCARD,1",
                "Single * at end MC");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 5: Three *** in VISA - CORRECTED ✓
            test(() -> solution.validateCard("4111111111111***"),
                    "VISA,100", // ✅ Changed from VISA,10 to VISA,100
                    "Three *** in VISA");

            // Test 6: No valid cards - VERIFIED ✓
            test(() -> solution.validateCard("4242424242424*43"),
                    "VISA,1",
                    "1 valid cards");

            // Test 7: Two * different positions - NEEDS VERIFICATION
            test(() -> solution.validateCard("42*2424242424*42"),
                    "VISA,10", // ⚠️ Still needs verification
                    "Two * different pos");
        }
    }

    private static void runPart4Tests(Solution solution) {
        System.out.println("\n=== PART 4: CORRUPTED CARDS ===\n");

        // Test 1: Simple case - ✓ CORRECT
        test(() -> solution.validateCard("411111111111111?"),
                "4111111111111111,VISA",
                "Corrupted last digit");

        // Test 2: Multiple corrections - ❌ CORRECTED
        test(() -> solution.validateCard("434455556666000?"),
                "4344555566660007,VISA", // ✅ Only 1 valid card, not 3
                "Single valid correction");

        // Test 3: AMEX - ✓ CORRECT
        test(() -> solution.validateCard("37828224631000?"),
                "378282246310005,AMEX",
                "Corrupted AMEX");

        // Test 4: MASTERCARD - ❌ CORRECTED
        test(() -> solution.validateCard("542523343010999?"),
                "5425233430109994,MASTERCARD", // ✅ Ends in 4, not 3
                "Corrupted MASTERCARD");

        if (CHECK_FULL) {
            // Test 5: Swap test - ❌ CORRECTED
            test(() -> solution.validateCard("411111111111112?"),
                    "4111111111111129,VISA", // ✅ Only 1 valid card
                    "Corrupted digit creates valid");

            // Test 6: End corruption - ✓ CORRECT
            test(() -> solution.validateCard("453914880343646?"),
                    "4539148803436467,VISA",
                    "Corruption at end");
        }
    }

    private static void test(TestSupplier<String> supplier, String expected, String desc) {
        currentTestNumber++;
        if (SELECTED_TESTS.length > 0 && !selectedTestSet.contains(currentTestNumber)) {
            return;
        }

        totalTests++;
        try {
            String result = supplier.get();
            boolean passed = Objects.equals(result, expected);

            if (passed) {
                passedTests++;
                System.out.printf("✓ PASS [Test #%d]: %s%n", currentTestNumber, desc);
            } else {
                System.out.printf("✗ FAIL [Test #%d]: %s%n", currentTestNumber, desc);
                System.out.println("  Expected: " + expected);
                System.out.println("  Got: " + result);
            }
        } catch (Exception e) {
            System.out.printf("✗ ERROR [Test #%d]: %s%n", currentTestNumber, desc);
            e.printStackTrace();
        }
    }

    private static void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("TEST SUMMARY - PART " + PART);
        System.out.println("=".repeat(70));
        System.out.printf("Passed: %d/%d tests%n", passedTests, totalTests);

        if (passedTests == totalTests) {
            System.out.println("✓ All tests passed! 🎉");
        } else {
            System.out.printf("✗ %d test(s) failed%n", totalTests - passedTests);
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
 * IMPLEMENTATION APPROACH:
 * ========================
 * 
 * LUHN ALGORITHM:
 * ---------------
 * boolean isValidLuhn(String card) {
 * int sum = 0;
 * boolean alternate = false;
 * 
 * // Process from right to left
 * for (int i = card.length() - 1; i >= 0; i--) {
 * int digit = card.charAt(i) - '0';
 * 
 * if (alternate) {
 * digit *= 2;
 * if (digit > 9) {
 * digit -= 9; // or digit = digit / 10 + digit % 10
 * }
 * }
 * 
 * sum += digit;
 * alternate = !alternate;
 * }
 * 
 * return sum % 10 == 0;
 * }
 * 
 * NETWORK DETECTION:
 * ------------------
 * String detectNetwork(String card) {
 * int length = card.length();
 * 
 * // VISA: 16 digits, starts with 4
 * if (length == 16 && card.startsWith("4")) {
 * return "VISA";
 * }
 * 
 * // MASTERCARD: 16 digits, starts with 51-55
 * if (length == 16 && card.length() >= 2) {
 * String prefix = card.substring(0, 2);
 * int prefixNum = Integer.parseInt(prefix);
 * if (prefixNum >= 51 && prefixNum <= 55) {
 * return "MASTERCARD";
 * }
 * }
 * 
 * // AMEX: 15 digits, starts with 34 or 37
 * if (length == 15) {
 * String prefix = card.substring(0, 2);
 * if (prefix.equals("34") || prefix.equals("37")) {
 * return "AMEX";
 * }
 * }
 * 
 * return "UNKNOWN_NETWORK";
 * }
 * 
 * PART 1 - BASIC VISA:
 * --------------------
 * 1. Check if 16 digits and starts with 4
 * 2. Validate with Luhn algorithm
 * 3. Return "VISA" or "INVALID_CHECKSUM"
 * 
 * PART 2 - MULTI-NETWORK:
 * ------------------------
 * 1. Detect network using prefix and length
 * 2. If "UNKNOWN_NETWORK", return immediately
 * 3. Validate with Luhn algorithm
 * 4. Return network name or "INVALID_CHECKSUM"
 * 
 * PART 3 - REDACTED CARDS:
 * -------------------------
 * 1. Find positions of all * characters
 * 2. Generate all combinations (recursively or iteratively)
 * - For 1 asterisk: 10 possibilities (0-9)
 * - For 2 asterisks: 100 possibilities (00-99)
 * - For n asterisks: 10^n possibilities
 * 3. For each combination:
 * - Create complete card number
 * - Detect network
 * - Validate with Luhn
 * - If valid, increment count for that network
 * 4. Sort networks alphabetically
 * 5. Format output: "NETWORK,count\n" (skip networks with 0 count)
 * 
 * Optimization:
 * - Use Map<String, Integer> to count per network
 * - TreeMap for automatic sorting
 * - Prune early if length doesn't match any network
 * 
 * PART 4 - CORRUPTED CARDS:
 * --------------------------
 * Input: Card ending with ? (one error in any position)
 * Two types of errors:
 * 1. One digit changed
 * 2. Two adjacent digits swapped
 * 
 * Algorithm:
 * 1. Remove the ? to get base card
 * 2. Generate all possibilities:
 * 
 * a) Last digit is wrong (10 options):
 * - Try digits 0-9 for last position
 * 
 * b) One of first 15 digits is wrong (15 positions × 10 digits = 150 options):
 * - For each position i in [0, 14]:
 * - Try replacing with digits 0-9 (except current digit)
 * 
 * c) Two adjacent digits swapped (15 pairs):
 * - For positions [0, 14]:
 * - Swap position i with i+1
 * - Note: Only if digits are different
 * 
 * 3. For each generated card:
 * - Detect network
 * - Validate with Luhn
 * - If valid, add to results with network
 * 4. Remove duplicates (use Set)
 * 5. Sort numerically (then by network if tied)
 * 6. Format: "card_number,NETWORK\n"
 * 
 * Example:
 * Input: "411111111111111?"
 * Base: "411111111111111"
 * 
 * Generate:
 * - Last digit: 4111111111111110, 4111111111111111, ..., 4111111111111119
 * - Change pos 0: 0111111111111111, 1111111111111111, 2111111111111111, ...
 * - Swap pos 0-1: 1411111111111111
 * - Swap pos 1-2: 4111111111111111 (no change, all 1s)
 * - ...
 * 
 * Validate each and keep valid ones.
 * 
 * OPTIMIZATION TIPS:
 * ------------------
 * Part 3:
 * - Early exit if length doesn't match any network after filling asterisks
 * - Cache Luhn validation results if needed
 * 
 * Part 4:
 * - Use Set<String> to avoid duplicates
 * - For swaps, skip if digits are same (no point swapping)
 * - Consider using TreeSet with custom comparator for sorting
 * 
 * TIME COMPLEXITY:
 * ----------------
 * Part 1: O(n) where n = card length (16)
 * Part 2: O(n) where n = card length
 * Part 3: O(10^k × n) where k = number of asterisks, n = card length
 * Part 4: O(m × n) where m = number of possibilities (~175), n = card length
 * 
 * EXAMPLE WALKTHROUGH (PART 3):
 * ------------------------------
 * Input: "4242424242424*42"
 * 
 * Step 1: Find asterisk at position 13 (0-indexed)
 * Step 2: Generate 10 cards:
 * - 42424242424200042
 * - 42424242424212 42
 * - 42424242424222 42
 * - ...
 * - 42424242424292 42
 * 
 * Step 3: For each:
 * - Network: All start with 4, length 16 → VISA
 * - Validate Luhn
 * 
 * Step 4: Suppose only 4242424242424242 is valid
 * - Count: VISA = 1
 * 
 * Output: "VISA,1"
 * 
 * EXAMPLE WALKTHROUGH (PART 4):
 * ------------------------------
 * Input: "411111111111111?"
 * Base: "411111111111111" (15 digits)
 * 
 * Possibilities:
 * 1. Last digit 0-9:
 * - 4111111111111110
 * - 4111111111111111 ✓ (valid VISA)
 * - 4111111111111112
 * - ...
 * 
 * 2. Change digit at position i (i=0 to 14):
 * - Position 0, digit 4 → try 0,1,2,3,5,6,7,8,9
 * - Position 1, digit 1 → try 0,2,3,4,5,6,7,8,9
 * - ...
 * 
 * 3. Swap adjacent digits (i=0 to 14):
 * - Swap 0-1: 1411111111111111
 * - Swap 1-2: 4111111111111111 (same, skip)
 * - ...
 * 
 * After generating all, validate and sort.
 * 
 * Output: "4111111111111111,VISA"
 */