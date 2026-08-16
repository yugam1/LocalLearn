import java.io.*;
import java.util.*;

interface IOrder {
    void setName(String name);

    String getName();

    void setPrice(int price);

    int getPrice();
}

interface IOrderSystem {
    void addToCart(IOrder order);

    void removeFromCart(IOrder order);

    int calculateTotalAmount();

    Map<String, Integer> categoryDiscounts();

    Map<String, Integer> cartItems();
}

class Order implements IOrder {
    String name;
    int price;

    Order() {
        this.name = "";
        this.price = 0;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getName() {
        return this.name;
    }

    public void setPrice(int price) {
        this.price = price;
    }

    public int getPrice() {
        return this.price;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o)
            return true;
        if (o == null || getClass() != o.getClass())
            return false;
        Order order = (Order) o;
        return getPrice() == order.getPrice() && Objects.equals(getName(), order.getName());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getName(), getPrice());
    }
}

enum DiscountCategory {
    CHEAP, MODERATE, EXPENSIVE
}

class DiscountManager {
    Map<String, Integer> discountRules;

    DiscountManager() {
        discountRules = new HashMap<>();
        discountRules.put(DiscountCategory.CHEAP.toString(), 10);
        discountRules.put(DiscountCategory.MODERATE.toString(), 20);
        discountRules.put(DiscountCategory.EXPENSIVE.toString(), 30);
    }

    int getDiscountAmount(int price) {
        DiscountCategory category = getDiscountCategory(price);
        return price - price * discountRules.getOrDefault(category.toString(), 0) / 100;
    }

    DiscountCategory getDiscountCategory(int price) {
        if (price <= 10) {
            return DiscountCategory.CHEAP;
        } else if (price <= 20) {
            return DiscountCategory.MODERATE;
        } else {
            return DiscountCategory.EXPENSIVE;
        }
    }

    Map<String, Integer> getAllDiscountRules() {
        return discountRules;
    }

}

class OrderSystem implements IOrderSystem {
    Map<IOrder, Integer> orderItemCount;
    DiscountManager dm;
    Map<String, Integer> categoryDiscount;

    OrderSystem() {
        orderItemCount = new HashMap<>();
        dm = new DiscountManager();

    }

    public void addToCart(IOrder order) {
        orderItemCount.put(order, 1 + orderItemCount.getOrDefault(order, 0));
    }

    public void removeFromCart(IOrder order) {
        if (orderItemCount.getOrDefault(order, 0) == 0) {
            // throw no items to remove;
            return;
        }
        orderItemCount.put(order, orderItemCount.getOrDefault(order, 0) - 1);
        if (orderItemCount.get(order) == 0) {
            orderItemCount.remove(order);
        }
    }

    public int calculateTotalAmount() {
        this.categoryDiscount = new HashMap<>();
        int total = 0;
        for (Map.Entry<IOrder, Integer> item : orderItemCount.entrySet()) {
            IOrder order = item.getKey();
            int quantity = item.getValue();
            if (quantity <= 0)
                continue;
            int discountAmount = dm.getDiscountAmount(order.getPrice());
            int val = discountAmount * quantity;
            String key = dm.getDiscountCategory(order.getPrice()).toString();
            categoryDiscount.put(key,
                    (order.getPrice() - discountAmount) * quantity
                            + categoryDiscount.getOrDefault(key, 0));
            total += val;
        }
        return total;
    }

    public Map<String, Integer> categoryDiscounts() {
        calculateTotalAmount();
        return this.categoryDiscount;
    }

    public Map<String, Integer> cartItems() {
        Map<String, Integer> umap = new HashMap<>();
        for (Map.Entry<IOrder, Integer> row : orderItemCount.entrySet()) {
            umap.put(row.getKey().getName(), row.getValue());
        }
        return umap;
    }
}

public class Solution {

    // ANSI COLORS
    private static final String GREEN = "\u001B[32m";
    private static final String RED = "\u001B[31m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RESET = "\u001B[0m";

    private static int passCount = 0;
    private static int failCount = 0;
    private static int totalTests = 0;

    // ---------- Correct Implementation (used only for expected behavior)
    // ----------
    private static class CorrectOrderSystem implements IOrderSystem {

        Map<IOrder, Integer> cart = new HashMap<>();

        @Override
        public void addToCart(IOrder order) {
            cart.put(order, cart.getOrDefault(order, 0) + 1);
        }

        @Override
        public void removeFromCart(IOrder order) {
            if (!cart.containsKey(order))
                return;
            int q = cart.get(order);
            if (q == 1)
                cart.remove(order);
            else
                cart.put(order, q - 1);
        }

        private int rate(int p) {
            if (p <= 10)
                return 10;
            if (p <= 20)
                return 20;
            return 30;
        }

        @Override
        public int calculateTotalAmount() {
            int total = 0;
            for (var e : cart.entrySet()) {
                int p = e.getKey().getPrice();
                int r = rate(p);
                int discounted = p - (p * r / 100);
                total += discounted * e.getValue();
            }
            return total;
        }

        @Override
        public Map<String, Integer> categoryDiscounts() {
            Map<String, Integer> out = new HashMap<>();
            for (var e : cart.entrySet()) {
                int p = e.getKey().getPrice();
                int q = e.getValue();
                int r = rate(p);
                int saved = (p * r / 100) * q;

                String cat = (p <= 10) ? "CHEAP" : (p <= 20 ? "MODERATE" : "EXPENSIVE");
                out.put(cat, out.getOrDefault(cat, 0) + saved);
            }
            return out;
        }

        @Override
        public Map<String, Integer> cartItems() {
            Map<String, Integer> m = new HashMap<>();
            for (var e : cart.entrySet())
                m.put(e.getKey().getName(), e.getValue());
            return m;
        }
    }

    // ---------- Helpers ----------
    private static IOrder make(String name, int price) {
        IOrder o = new Order();
        o.setName(name);
        o.setPrice(price);
        return o;
    }

    private static void runTest(String name, Runnable r) {
        totalTests++;
        try {
            r.run();
            passCount++;
            System.out.println(GREEN + "PASS" + RESET + " - " + name);
        } catch (AssertionError e) {
            failCount++;
            System.out.println(RED + "FAIL" + RESET + " - " + name);
            System.out.println(YELLOW + "Reason: " + e.getMessage() + RESET);
        }
    }

    private static void assertEq(Object expected, Object actual, String msg) {
        if (!expected.equals(actual)) {
            throw new AssertionError(msg + " | expected=" + expected + ", actual=" + actual);
        }
    }

    private static void assertEqInt(int expected, int actual, String msg) {
        if (expected != actual)
            throw new AssertionError(msg + " | expected=" + expected + ", actual=" + actual);
    }

    // ------------------------------ TEST CASES -----------------------------------

    private static void test_singleCheap() {
        IOrderSystem exp = new CorrectOrderSystem();
        IOrderSystem act = new OrderSystem(); // <-- your buggy code

        IOrder a = make("Pen", 10);
        exp.addToCart(a);
        act.addToCart(a);

        assertEqInt(exp.calculateTotalAmount(), act.calculateTotalAmount(), "total mismatch");
        assertEq(exp.categoryDiscounts(), act.categoryDiscounts(), "category discount mismatch");
    }

    private static void test_singleModerate() {
        IOrderSystem exp = new CorrectOrderSystem();
        IOrderSystem act = new OrderSystem();

        IOrder a = make("Mug", 20);
        exp.addToCart(a);
        act.addToCart(a);

        assertEqInt(exp.calculateTotalAmount(), act.calculateTotalAmount(), "total mismatch");
        assertEq(exp.categoryDiscounts(), act.categoryDiscounts(), "category mismatch");
    }

    private static void test_singleExpensive() {
        IOrderSystem exp = new CorrectOrderSystem();
        IOrderSystem act = new OrderSystem();

        IOrder a = make("Phone", 30);
        exp.addToCart(a);
        act.addToCart(a);

        assertEqInt(exp.calculateTotalAmount(), act.calculateTotalAmount(), "total mismatch");
        assertEq(exp.categoryDiscounts(), act.categoryDiscounts(), "category mismatch");
    }

    private static void test_multipleQuantities() {
        IOrderSystem exp = new CorrectOrderSystem();
        IOrderSystem act = new OrderSystem();

        IOrder apple = make("Apple", 8);

        exp.addToCart(apple);
        exp.addToCart(apple);
        exp.addToCart(apple);
        act.addToCart(apple);
        act.addToCart(apple);
        act.addToCart(apple);

        assertEqInt(exp.calculateTotalAmount(), act.calculateTotalAmount(), "total mismatch");
        assertEq(exp.categoryDiscounts(), act.categoryDiscounts(), "category mismatch");
    }

    private static void test_remove() {
        IOrderSystem exp = new CorrectOrderSystem();
        IOrderSystem act = new OrderSystem();

        IOrder a = make("Book", 12);

        exp.addToCart(a);
        exp.addToCart(a);
        act.addToCart(a);
        act.addToCart(a);

        exp.removeFromCart(a);
        act.removeFromCart(a);

        assertEq(exp.cartItems(), act.cartItems(), "cartItems mismatch");
    }

    private static void test_mixed() {
        IOrderSystem exp = new CorrectOrderSystem();
        IOrderSystem act = new OrderSystem();

        exp.addToCart(make("Candy", 10));
        exp.addToCart(make("Bottle", 20));
        exp.addToCart(make("Keyboard", 30));

        act.addToCart(make("Candy", 10));
        act.addToCart(make("Bottle", 20));
        act.addToCart(make("Keyboard", 30));

        assertEqInt(exp.calculateTotalAmount(), act.calculateTotalAmount(), "total mismatch");
        assertEq(exp.categoryDiscounts(), act.categoryDiscounts(), "category mismatch");
    }

    // Add more based on your 15 tests...

    // ------------------------------------------------------------------------------

    public static void main(String[] args) {

        System.out.println("\n===== Running Comparison Tests =====\n");

        runTest("Single Cheap Item", Solution::test_singleCheap);
        runTest("Single Moderate Item", Solution::test_singleModerate);
        runTest("Single Expensive Item", Solution::test_singleExpensive);
        runTest("Multiple Quantities", Solution::test_multipleQuantities);
        runTest("Remove From Cart", Solution::test_remove);
        runTest("Mixed Categories", Solution::test_mixed);

        // Continue adding tests...

        System.out.println("\n===== SUMMARY =====");
        System.out.println(GREEN + "Passed : " + passCount + RESET);
        System.out.println(RED + "Failed : " + failCount + RESET);
        System.out.println("Total  : " + totalTests);
        System.out.println("===================\n");
    }
}
