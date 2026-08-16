import lombok.Data;

@Data
public class Singleton {
    static Singleton instance;

    private Singleton() {

    }

    public static Singleton getInstance() {
        // TODO: make this thread-safe and always return the same shared instance
        throw new UnsupportedOperationException("TODO: implement thread-safe getInstance()");
    }

    int val;

    public static void main(String[] args) {
        Singleton first = Singleton.getInstance();
        first.setVal(5);

        Singleton second = Singleton.getInstance();

        System.out.println("Same instance: " + (first == second));
        System.out.println("Value seen by second reference: " + second.getVal());
    }

}

/*
 * SOLUTIONS (reference only - try to implement it yourself first)
 *
 * public static Singleton getInstance() {
 *     if (instance == null) {
 *         synchronized (Singleton.class) {
 *             if (instance == null) {
 *                 instance = new Singleton();
 *             }
 *         }
 *     }
 *     return instance;
 * }
 */