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

        // TODO: implement name/age/department as fluent setters that return this builder
        public EmployeeBuilder name(String name) {
            throw new UnsupportedOperationException("TODO: implement fluent setter for name");
        }

        public EmployeeBuilder age(int age) {
            throw new UnsupportedOperationException("TODO: implement fluent setter for age");
        }

        public EmployeeBuilder department(String department) {
            throw new UnsupportedOperationException("TODO: implement fluent setter for department");
        }

        // TODO: implement build() to construct an Employee from this builder's fields
        public Employee build() {
            throw new UnsupportedOperationException("TODO: implement build()");
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

/*
 * SOLUTIONS (reference only - try to implement it yourself first)
 *
 * public EmployeeBuilder name(String name) {
 *     this.name = name;
 *     return this;
 * }
 *
 * public EmployeeBuilder age(int age) {
 *     this.age = age;
 *     return this;
 * }
 *
 * public EmployeeBuilder department(String department) {
 *     this.department = department;
 *     return this;
 * }
 *
 * public Employee build() {
 *     return new Employee(this);
 * }
 */
