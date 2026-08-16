public class SingletonDemo {

    public static void main(String[] args) {
        Singleton first = Singleton.getInstance();
        first.setVal(5);

        Singleton second = Singleton.getInstance();

        System.out.println("Same instance: " + (first == second));
        System.out.println("Value seen by second reference: " + second.getVal());
    }

}
