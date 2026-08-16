package Coupontest3;

import java.util.*;
import java.time.*;

// ---------------------------
// DATA STRUCTURES
// ---------------------------
class Coupon implements Comparable<Coupon> {
    String categoryName;
    String couponName;
    LocalDate dateModified;

    public Coupon(String categoryName, String couponName, String dateModified) {
        this.categoryName = categoryName;
        this.couponName = couponName;
        this.dateModified = LocalDate.parse(dateModified);
    }

    public int compareTo(Coupon other) {
        return -dateModified.compareTo(other.dateModified);
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
class CouponFinderV3 {
    private Map<String, Set<Coupon>> couponsByCategory = new HashMap<>();
    private Map<String, String> parentMap = new HashMap<>();

    public CouponFinderV3(List<Coupon> coupons, List<Category> categories) {
        for (Coupon c : coupons) {
            couponsByCategory.computeIfAbsent(c.categoryName, k -> new TreeSet<>()).add(c);
        }
        for (Category cat : categories) {
            parentMap.put(cat.categoryName, cat.parentCategoryName);
        }
    }

    public String findCoupon(String categoryName) {
        // TODO:
        // 1. Find valid coupon for this category (most recent past/present date)
        // 2. If none, move up parent hierarchy
        // 3. Return null if no coupon found
        if (categoryName == null)
            return null;
        Set<Coupon> curr = couponsByCategory.get(categoryName);
        Coupon c = getLatestValidCoupon(curr);
        if (c != null) {
            return c.couponName;
        }
        return findCoupon(parentMap.get(categoryName));
    }

    // Optional helper: find latest coupon in list
    private Coupon getLatestValidCoupon(Set<Coupon> coupons) {
        // TODO: choose coupon with max dateModified that <= today
        if (coupons == null || coupons.isEmpty())
            return null;
        Coupon res = null;
        for (Coupon c : coupons) {
            if ((c.dateModified).isAfter(LocalDate.now())) {
                continue;
            }
            return c;
        }
        return res;
    }
}

// ---------------------------
// MAIN CLASS WITH TEST CASES
// ---------------------------
public class MainCoupon {
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    static class TestCase {
        String input;
        String expected1;
        String expected2; // some have two acceptable answers
        String actual;
        boolean passed;

        TestCase(String input, String expected1, String expected2, String actual) {
            this.input = input;
            this.expected1 = expected1;
            this.expected2 = expected2;
            this.actual = actual;
            this.passed = (Objects.equals(expected1, actual) || Objects.equals(expected2, actual));
        }
    }

    public static void main(String[] args) {
        List<Coupon> coupons = Arrays.asList(
                new Coupon("Comforter Sets", "Comforters Sale", "2020-01-01"),
                new Coupon("Comforter Sets", "Cozy Comforter Coupon", "2020-01-01"),
                new Coupon("Bedding", "Best Bedding Bargains", "2019-01-01"),
                new Coupon("Bedding", "Savings on Bedding", "2019-01-01"),
                new Coupon("Bed & Bath", "Low price for Bed & Bath", "2018-01-01"),
                new Coupon("Bed & Bath", "Bed & Bath extravaganza", "2019-01-01"),
                new Coupon("Bed & Bath", "Big Savings for Bed & Bath", "2030-01-01") // future, ignore
        );

        List<Category> categories = Arrays.asList(
                new Category("Comforter Sets", "Bedding"),
                new Category("Bedding", "Bed & Bath"),
                new Category("Bed & Bath", null),
                new Category("Soap Dispensers", "Bathroom Accessories"),
                new Category("Bathroom Accessories", "Bed & Bath"),
                new Category("Toy Organizers", "Baby And Kids"),
                new Category("Baby And Kids", null));

        CouponFinderV3 finder = new CouponFinderV3(coupons, categories);

        List<TestCase> tests = Arrays.asList(
                new TestCase("Bed & Bath", "Bed & Bath extravaganza", null, finder.findCoupon("Bed & Bath")),
                new TestCase("Bedding", "Savings on Bedding", "Best Bedding Bargains", finder.findCoupon("Bedding")),
                new TestCase("Bathroom Accessories", "Bed & Bath extravaganza", null,
                        finder.findCoupon("Bathroom Accessories")),
                new TestCase("Comforter Sets", "Comforters Sale", "Cozy Comforter Coupon",
                        finder.findCoupon("Comforter Sets")),
                new TestCase("Toy Organizers", null, null, finder.findCoupon("Toy Organizers")));

        printResults(tests);
    }

    private static void printResults(List<TestCase> results) {
        int passed = 0;
        for (TestCase t : results) {
            if (t.passed) {
                System.out.println(GREEN + "✅ " + t.input + " => " + t.actual + RESET);
                passed++;
            } else {
                System.out.println(RED + "❌ " + t.input + " => Expected: " + t.expected1 +
                        (t.expected2 != null ? " or " + t.expected2 : "") +
                        ", Got: " + t.actual + RESET);
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
