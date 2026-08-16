import lombok.Data;

@Data
public class Singleton {
    static Singleton instance;

    private Singleton() {

    }

    public static Singleton getInstance() {
        // TODO: make this thread-safe and always return the same shared instance
        if(instance == null) {
            synchronized(Singleton.class){
                if(instance == null) {
                    instance = new Singleton();
                }
            }
        }
        return instance;
    }

    int val;

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