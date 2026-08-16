class OldPaymentProcessor {
    void makePayment(double amount) {
        System.out.println("Processing payment via Old Gateway: " + amount);
    }
}

// New unified interface
interface PaymentGateway {
    void pay(double amount);
}

// Adapter
class PaymentAdapter implements PaymentGateway {
    private OldPaymentProcessor oldProcessor;

    public PaymentAdapter(OldPaymentProcessor oldProcessor) {
        this.oldProcessor = oldProcessor;
    }

    // TODO: adapt this call to the old processor's makePayment() method
    public void pay(double amount) {
        throw new UnsupportedOperationException("TODO: implement pay()");
    }
}

// Usage
public class AdapterPattern {
    public static void main(String[] args) {
        PaymentGateway gateway = new PaymentAdapter(new OldPaymentProcessor());
        gateway.pay(250.0);
    }
}

/*
 * SOLUTIONS (reference only - try to implement it yourself first)
 *
 * public void pay(double amount) {
 *     oldProcessor.makePayment(amount);
 * }
 */