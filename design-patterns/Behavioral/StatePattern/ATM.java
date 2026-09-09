import lombok.Data;

/**
 * InnerATM
 */
interface ATMState {

    boolean insertCard();
    boolean enterPassword();
    boolean dispense();
}

class IdleState implements ATMState{
    public boolean insertCard(){
        System.out.println("Card inserted in the slot");
        return true;
    }

    public boolean enterPassword(){
        System.err.println("Enter card first");
        return false;
    }
    public boolean dispense(){
        System.err.println("Enter card first");
        return false;
    }
}

class ReadyState implements ATMState{

    public boolean insertCard(){
        System.err.println("Card locked cannot exit");
        return false;
    }

    public boolean enterPassword(){
        System.out.println("Card Password Entered");
        return true;
    }
    public boolean dispense(){
        System.err.println("Enter card password first");
        return false;
    }
}

class DispensingState implements ATMState {
        public boolean insertCard(){
        System.err.println("Card locked cannot exit");
        return false;
    }

    public boolean enterPassword(){
        System.err.println("Card Password cannot be entered in this state");
        return false;
    }
    public boolean dispense(){
        System.out.println("Dispensing cash");
        return true;
    }
}

@Data
class ATMMachine implements ATMState{

    ATMState state;

    ATMMachine(){
        state = new IdleState();
    }
    public boolean insertCard(){
        if(this.state.insertCard()){
            setState(new ReadyState());
            return true;
        }
        return false;
    }

    public boolean enterPassword(){
        if(this.state.enterPassword()){
            setState(new DispensingState());
            return true;
        }
        return false;
    }
    public boolean dispense(){
        if(this.state.dispense()){
            setState(new IdleState());
            return true;
        }
        return false;
    }
}

public class ATM {
    public static void main(String[] args){
        ATMMachine machine = new ATMMachine();
        machine.dispense();
        machine.insertCard();
        machine.enterPassword();
        machine.insertCard();
        machine.dispense();
    }
}
