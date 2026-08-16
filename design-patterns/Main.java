import creational.Employee;
import creational.Singleton;

public class Main {
    public static void main(String[] args) {
        Employee e = Employee.builder().age(5).name("YUgam").build();
        System.out.println(e);
    }
}
