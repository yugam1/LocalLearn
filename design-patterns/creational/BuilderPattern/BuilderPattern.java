class Employee {
    private final String name;
    private final int age;
    private final String department;

    private Employee(EmployeeBuilder builder) {
        this.name = builder.name;
        this.age = builder.age;
        this.department = builder.department;
    }

    public String toString() {
        return "Employee{name=" + name + ", age=" + age + ", department=" + department + "}";
    }

    public static EmployeeBuilder builder() {
        return new EmployeeBuilder();
    }

    static class EmployeeBuilder {
        private String name;
        private int age;
        private String department;

        public EmployeeBuilder name(String name) {
            this.name = name;
            return this;
        }

        public EmployeeBuilder age(int age) {
            this.age = age;
            return this;
        }

        public EmployeeBuilder department(String department) {
            this.department = department;
            return this;
        }

        public Employee build() {
            return new Employee(this);
        }
    }
}

// Usage
public class BuilderPattern {
    public static void main(String[] args) {
        Employee employee = Employee.builder()
                .name("Yugam")
                .age(5)
                .department("Engineering")
                .build();

        System.out.println(employee);
    }
}
