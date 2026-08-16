package Behavioral;

import lombok.Data;

interface VMState {
    boolean insertCoin();

    boolean selectProduct();

    boolean dispense();
}

class IdleState implements VMState {
    @Override
    public boolean insertCoin() {
        System.out.println("Money added Select product");
        return true;
    }

    @Override
    public boolean selectProduct() {
        System.out.println("Insert money first");
        return false;
    }

    @Override
    public boolean dispense() {
        System.out.println("insert money first");
        return false;
    }
}

class ReadyState implements VMState {
    @Override
    public boolean insertCoin() {
        System.out.println("Money already added");
        return false;
    }

    @Override
    public boolean selectProduct() {
        System.out.println("product selected");
        return true;
    }

    @Override
    public boolean dispense() {
        System.out.println("select product first");
        return false;
    }
}

class DispensingState implements VMState {

    @Override
    public boolean insertCoin() {
        System.out.println("Money already added");
        return false;
    }

    @Override
    public boolean selectProduct() {
        System.out.println("different product selected");
        return true;
    }

    @Override
    public boolean dispense() {
        System.out.println("product dispensing");
        return true;
    }
}

@Data
class VendingMachine implements VMState {
    VMState state;

    public VendingMachine() {
        state = new IdleState();
    }

    @Override
    public boolean insertCoin() {
        if (state.insertCoin()) {
            setState(new ReadyState());
            return true;
        }
        return false;
    }

    @Override
    public boolean selectProduct() {
        if (state.selectProduct()) {
            setState(new DispensingState());
            return true;
        }
        return false;
    }

    @Override
    public boolean dispense() {
        if (state.dispense()) {
            setState(new IdleState());
            return true;
        }
        return false;
    }
}

public class StatePattern {
    public static void main(String[] args) {
        VendingMachine vm = new VendingMachine();
        vm.insertCoin();
        vm.selectProduct();
        vm.dispense();
        vm.selectProduct();
        vm.dispense();

    }
}
