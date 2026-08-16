public class DecoratorPattern {
    public static void main(String[] args) {
        Coffee coffee = new SugarDecorator(new MilkDecorator(new BasicCoffee()));
        System.out.println(coffee.description() + " => $" + coffee.cost());
    }
}

interface Coffee {
    double cost();

    String description();
}

class BasicCoffee implements Coffee {
    public double cost() {
        return 5;
    }

    public String description() {
        return "Basic Coffee";
    }
}

// Decorators
class MilkDecorator implements Coffee {
    private Coffee coffee;

    public MilkDecorator(Coffee coffee) {
        this.coffee = coffee;
    }

    // TODO: return the wrapped coffee's cost/description plus this decorator's own contribution
    public double cost() {
        throw new UnsupportedOperationException("TODO: implement cost() for MilkDecorator");
    }

    public String description() {
        throw new UnsupportedOperationException("TODO: implement description() for MilkDecorator");
    }
}

class SugarDecorator implements Coffee {
    private Coffee coffee;

    public SugarDecorator(Coffee coffee) {
        this.coffee = coffee;
    }

    // TODO: return the wrapped coffee's cost/description plus this decorator's own contribution
    public double cost() {
        throw new UnsupportedOperationException("TODO: implement cost() for SugarDecorator");
    }

    public String description() {
        throw new UnsupportedOperationException("TODO: implement description() for SugarDecorator");
    }
}

/*
 * SOLUTIONS (reference only - try to implement it yourself first)
 *
 * // MilkDecorator
 * public double cost() {
 *     return coffee.cost() + 2;
 * }
 *
 * public String description() {
 *     return coffee.description() + ", Milk";
 * }
 *
 * // SugarDecorator
 * public double cost() {
 *     return coffee.cost() + 1;
 * }
 *
 * public String description() {
 *     return coffee.description() + ", Sugar";
 * }
 */
