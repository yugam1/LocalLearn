import java.util.*;

// ---------------------------
// DO NOT MODIFY THIS CLASS
// ---------------------------
class Order {
    private float amount;

    public Order(float amount) {
        this.amount = amount;
    }

    public float getAmount() {
        return amount;
    }
}

// ---------------------------
// CLASS TO COMPLETE
// ---------------------------
class OrderBill {
    private List<Order> orders = new ArrayList<>();

    public void addOrder(Order order) {
        orders.add(order);
    }

    private float calculateTotal() {
        float total = 0;
        for (Order o : orders) {
            total += o.getAmount();
        }
        return total;
    }

    private float applyDiscount(float total) {
        DiscountManager discount = new DiscountManager();

        return total - discount.getDiscount(total); // placeholder
    }

    public float generateBill() {
        float total = calculateTotal();
        float finalAmount = applyDiscount(total);
        return finalAmount;
    }
}

/**
 * Total Amount Discount Rule
 * < 40 Subtract $20
 * 40 < amount <= 100 20% discount
 * > 100 15% discount
 */
abstract class OrderDiscount {
    OrderDiscount next = null;

    void setNext(OrderDiscount n) {
        next = n;
    }

    abstract float getDiscount(float amount);

}

class FirstDiscount extends OrderDiscount {
    float getDiscount(float amount) {
        if (amount <= 40) {
            return 20;
        } else {
            return next.getDiscount(amount);
        }
    }
}

class SecondDiscount extends OrderDiscount {
    float getDiscount(float amount) {
        if (amount > 40 && amount <= 100) {
            return (float) (0.2 * amount);
        } else {
            return next.getDiscount(amount);
        }
    }
}

class ThirdDiscount extends OrderDiscount {
    float getDiscount(float amount) {
        return (float) (0.15 * amount);
    }
}

class DiscountManager {
    OrderDiscount discount;

    DiscountManager() {
        OrderDiscount third = new ThirdDiscount();
        OrderDiscount second = new SecondDiscount();
        second.setNext(third);
        OrderDiscount first = new FirstDiscount();
        first.setNext(second);
        discount = first;
    }

    float getDiscount(float amount) {
        return discount.getDiscount(amount);
    }
}

// ---------------------------
// MAIN CLASS WITH TEST CASES
// ---------------------------
public class Main {

    // ANSI Colors for output
    private static final String GREEN = "\u001B[32m";
    private static final String YELLOW = "\u001B[33m";
    private static final String RED = "\u001B[31m";
    private static final String RESET = "\u001B[0m";

    static class TestCase {
        String name;
        float expected;
        float actual;
        boolean isPassed;

        TestCase(String name, float expected, float actual) {
            this.name = name;
            this.expected = expected;
            this.actual = actual;
            this.isPassed = Math.abs(expected - actual) < 0.01;
        }
    }

    public static void main(String[] args) {
        List<TestCase> results = new ArrayList<>();

        // ---------- Test Case 1 ----------
        OrderBill bill1 = new OrderBill();
        bill1.addOrder(new Order(30));
        bill1.addOrder(new Order(5)); // total = 35 → minus 20
        results.add(new TestCase("Test 1: total < 40", 15.0f, bill1.generateBill()));

        // ---------- Test Case 2 ----------
        OrderBill bill2 = new OrderBill();
        bill2.addOrder(new Order(50));
        bill2.addOrder(new Order(25)); // total = 75 → 20% off = 60
        results.add(new TestCase("Test 2: 40 < total <= 100", 60.0f, bill2.generateBill()));

        // ---------- Test Case 3 ----------
        OrderBill bill3 = new OrderBill();
        bill3.addOrder(new Order(120));
        bill3.addOrder(new Order(40)); // total = 160 → 15% off = 136
        results.add(new TestCase("Test 3: total > 100", 136.0f, bill3.generateBill()));

        // ---------- Test Case 4 ----------
        OrderBill bill4 = new OrderBill();
        bill4.addOrder(new Order(40)); // total = 40 → minus 20
        results.add(new TestCase("Test 4: total = 40", 20.0f, bill4.generateBill()));

        // ---------- Test Case 5 ----------
        OrderBill bill5 = new OrderBill();
        bill5.addOrder(new Order(100)); // total = 100 → 20% off = 80
        results.add(new TestCase("Test 5: total = 100", 80.0f, bill5.generateBill()));

        printResults(results);
    }

    private static void printResults(List<TestCase> results) {
        int passed = 0;
        for (TestCase t : results) {
            if (t.isPassed) {
                System.out.println(
                        GREEN + "✅ " + t.name + " | Expected: $" + t.expected + " | Got: $" + t.actual + RESET);
                passed++;
            } else {
                System.out
                        .println(RED + "❌ " + t.name + " | Expected: $" + t.expected + " | Got: $" + t.actual + RESET);
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
