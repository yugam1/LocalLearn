interface Shape {
    void draw();
}

class Circle implements Shape {
    public void draw() {
        System.out.println("Drawing a Circle");
    }
}

class Square implements Shape {
    public void draw() {
        System.out.println("Drawing a Square");
    }
}

class Rectangle implements Shape {
    public void draw() {
        System.out.println("Drawing a Rectangle");
    }
}

class ShapeFactory {
    public Shape getShape(String shapeType) {
        // TODO: return the right Shape instance for "CIRCLE"/"SQUARE"/"RECTANGLE" (or null otherwise)
        throw new UnsupportedOperationException("TODO: implement getShape()");
    }
}

// Usage
public class FactoryPattern {
    public static void main(String[] args) {
        ShapeFactory shapeFactory = new ShapeFactory();

        Shape circle = shapeFactory.getShape("CIRCLE");
        circle.draw();

        Shape square = shapeFactory.getShape("SQUARE");
        square.draw();

        Shape rectangle = shapeFactory.getShape("RECTANGLE");
        rectangle.draw();
    }
}

/*
 * SOLUTIONS (reference only - try to implement it yourself first)
 *
 * public Shape getShape(String shapeType) {
 *     if (shapeType == null) {
 *         return null;
 *     }
 *     switch (shapeType.toUpperCase()) {
 *         case "CIRCLE":
 *             return new Circle();
 *         case "SQUARE":
 *             return new Square();
 *         case "RECTANGLE":
 *             return new Rectangle();
 *         default:
 *             return null;
 *     }
 * }
 */
