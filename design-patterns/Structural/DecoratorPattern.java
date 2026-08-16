package Structural;

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

    public double cost() {
        return coffee.cost() + 2;
    }

    public String description() {
        return coffee.description() + ", Milk";
    }
}

class SugarDecorator implements Coffee {
    private Coffee coffee;

    public SugarDecorator(Coffee coffee) {
        this.coffee = coffee;
    }

    public double cost() {
        return coffee.cost() + 1;
    }

    public String description() {
        return coffee.description() + ", Sugar";
    }
}
