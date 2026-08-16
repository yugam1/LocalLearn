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

    public void pay(double amount) {
        oldProcessor.makePayment(amount); // adapts method
    }
}

// Usage
public class AdapterPattern {
    public static void main(String[] args) {
        PaymentGateway gateway = new PaymentAdapter(new OldPaymentProcessor());
        gateway.pay(250.0);
    }
}