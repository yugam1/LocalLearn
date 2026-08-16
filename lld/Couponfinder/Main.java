import java.util.*;

// Data structures for Coupon and Category
class Coupon {
    String categoryName;
    String couponName;

    public Coupon(String categoryName, String couponName) {
        this.categoryName = categoryName;
        this.couponName = couponName;
    }
}

class Category {
    String categoryName;
    String parentCategoryName;

    public Category(String categoryName, String parentCategoryName) {
        this.categoryName = categoryName;
        this.parentCategoryName = parentCategoryName;
    }
}

// ---------------------------
// CLASS TO COMPLETE
// ---------------------------
class CouponFinder {

    private Map<String, String> couponMap = new HashMap<>();
    private Map<String, String> parentMap = new HashMap<>();
    private Map<String, String> allCouponMap = new HashMap<>();

    public CouponFinder(List<Coupon> coupons, List<Category> categories) {
        for (Coupon c : coupons) {
            couponMap.put(c.categoryName, c.couponName);
        }
        for (Category cat : categories) {
            parentMap.put(cat.categoryName, cat.parentCategoryName);
        }

        // for 2nd enhancement;
        for (var item : parentMap.entrySet()) {
            allCouponMap.put(item.getKey(), findCouponRecursive(item.getKey()));
        }
    }

    public String findCouponRecursive(String categoryName) {
        // TODO: implement logic
        // 1. If category has its own coupon, return it
        // 2. Else move up hierarchy to find parent’s coupon
        // 3. Return null if no coupon found in hierarchy

        // Solution for 1
        if (categoryName == null)
            return null;

        if (couponMap.containsKey(categoryName)) {
            return couponMap.get(categoryName);
        }
        return findCouponRecursive(parentMap.get(categoryName));
    }

    public String findCoupon(String categoryName) {
        // TODO: implement logic
        // 1. If category has its own coupon, return it
        // 2. Else move up hierarchy to find parent’s coupon
        // 3. Return null if no coupon found in hierarchy

        return allCouponMap.get(categoryName);
    }
}

// ---------------------------
// MAIN CLASS WITH TEST CASES
// ---------------------------
public class Main {

    // ANSI colors
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    static class TestCase {
        String input;
        String expected;
        String actual;
        boolean passed;

        TestCase(String input, String expected, String actual) {
            this.input = input;
            this.expected = expected;
            this.actual = actual;
            if (expected == null && actual == null)
                passed = true;
            else if (expected != null && expected.equals(actual))
                passed = true;
            else
                passed = false;
        }
    }

    public static void main(String[] args) {
        List<Coupon> coupons = Arrays.asList(
                new Coupon("Comforter Sets", "Comforters Sale"),
                new Coupon("Bedding", "Savings on Bedding"),
                new Coupon("Bed & Bath", "Low price for Bed & Bath"));

        List<Category> categories = Arrays.asList(
                new Category("Comforter Sets", "Bedding"),
                new Category("Bedding", "Bed & Bath"),
                new Category("Bed & Bath", null),
                new Category("Soap Dispensers", "Bathroom Accessories"),
                new Category("Bathroom Accessories", "Bed & Bath"),
                new Category("Toy Organizers", "Baby And Kids"),
                new Category("Baby And Kids", null));

        CouponFinder finder = new CouponFinder(coupons, categories);

        List<TestCase> tests = Arrays.asList(
                new TestCase("Comforter Sets", "Comforters Sale", finder.findCoupon("Comforter Sets")),
                new TestCase("Bedding", "Savings on Bedding", finder.findCoupon("Bedding")),
                new TestCase("Bathroom Accessories", "Low price for Bed & Bath",
                        finder.findCoupon("Bathroom Accessories")),
                new TestCase("Soap Dispensers", "Low price for Bed & Bath", finder.findCoupon("Soap Dispensers")),
                new TestCase("Toy Organizers", null, finder.findCoupon("Toy Organizers")));

        printResults(tests);
    }

    private static void printResults(List<TestCase> results) {
        int passed = 0;
        for (TestCase t : results) {
            if (t.passed) {
                System.out.println(GREEN + "✅ " + t.input + " => " + t.actual + RESET);
                passed++;
            } else {
                System.out.println(RED + "❌ " + t.input + " => Expected: " + t.expected + ", Got: " + t.actual + RESET);
            }
        }

        System.out.println("\n-----------------------------");
        if (passed == results.size()) {
            System.out.println(GREEN + "ALL " + passed + " TEST CASES PASSED ✅" + RESET);
        } else if (passed > 0) {
            System.out.println(YELLOW + passed + "/" + results.size() + " TEST CASES PASSED ⚠️" + RESET);
        } else {
            System.out.println(RED + "0/" + results.size() + " TEST CASES PASSED ❌" + RESET);
        }
        System.out.println("-----------------------------");
    }
}
