interface PaymentStrategy {
    void pay(int amount);
}

class CreditCardPayment implements PaymentStrategy {
    public void pay(int amount) {
        System.out.println("Paid " + amount + " using Credit Card");
    }
}

class UpiPayment implements PaymentStrategy {
    public void pay(int amount) {
        System.out.println("Paid " + amount + " using UPI");
    }
}

class PaymentContext {
    private PaymentStrategy strategy;

    // TODO: implement setStrategy() and payAmount() so the context delegates to whichever strategy is set
    public void setStrategy(PaymentStrategy strategy) {
        throw new UnsupportedOperationException("TODO: implement setStrategy()");
    }

    public void payAmount(int amount) {
        throw new UnsupportedOperationException("TODO: implement payAmount()");
    }
}

// Usage
public class StrategyPattern {
    public static void main(String[] args) {
        PaymentContext context = new PaymentContext();
        context.setStrategy(new CreditCardPayment());
        context.payAmount(500);

        context.setStrategy(new UpiPayment());
        context.payAmount(300);
    }
}

/*
 * SOLUTIONS (reference only - try to implement it yourself first)
 *
 * public void setStrategy(PaymentStrategy strategy) {
 *     this.strategy = strategy;
 * }
 *
 * public void payAmount(int amount) {
 *     strategy.pay(amount);
 * }
 */
