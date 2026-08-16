interface Image {
    void display();
}

class RealImage implements Image {
    private String fileName;

    public RealImage(String fileName) {
        this.fileName = fileName;
        loadFromDisk();
    }

    private void loadFromDisk() {
        System.out.println("Loading image: " + fileName);
    }

    public void display() {
        System.out.println("Displaying " + fileName);
    }
}

class ProxyImage implements Image {
    private String fileName;
    private RealImage realImage;

    public ProxyImage(String fileName) {
        this.fileName = fileName;
    }

    // TODO: lazily create the RealImage on first call, then delegate display() to it
    public void display() {
        throw new UnsupportedOperationException("TODO: implement display()");
    }
}

// Usage
public class ProxyPattern {
    public static void main(String[] args) {
        Image image = new ProxyImage("profile.png");
        image.display(); // Loads from disk
        image.display(); // Uses cached instance
    }
}

/*
 * SOLUTIONS (reference only - try to implement it yourself first)
 *
 * public void display() {
 *     if (realImage == null)
 *         realImage = new RealImage(fileName);
 *     realImage.display();
 * }
 */