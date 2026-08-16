package Behavioral;

abstract class Support {
    Support next;

    void setNext(Support s) {
        next = s;
    }

    abstract void handleRequest(int lvl);

}

class PointOfContact extends Support {
    @Override
    public void handleRequest(int lvl) {
        if (lvl <= 1) {
            System.out.println("Handling requst at poc level");
        } else {
            next.handleRequest(lvl);
        }
    }
}

class Superviser extends Support {
    @Override
    void handleRequest(int lvl) {
        if (lvl < 4) {
            System.out.println("Handling requst at superviser level");
        } else {
            next.handleRequest(lvl);
        }
    }
}

class Manager extends Support {
    @Override
    void handleRequest(int lvl) {
        if (lvl < 10) {
            System.out.println("Handling requst at manager level");
        } else {
            System.out.println("Request needs to be excalated on mail");
        }
    }
}

public class ChainOfResponsibilty {
    public static void main(String[] args) {
        // Create handlers
        Support basic = new PointOfContact();
        Support supervisor = new Superviser();
        Support manager = new Manager();

        // Chain them
        basic.setNext(supervisor);
        supervisor.setNext(manager);

        // Test the chain
        System.out.println("=== Customer Request Level 1 ===");
        basic.handleRequest(1);

        System.out.println("\n=== Customer Request Level 2 ===");
        basic.handleRequest(3);

        System.out.println("\n=== Customer Request Level 3 ===");
        basic.handleRequest(5);

        System.out.println("\n=== Customer Request Level 4 ===");
        basic.handleRequest(14);
    }
}
