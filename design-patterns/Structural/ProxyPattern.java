package Structural;

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

    public void display() {
        if (realImage == null)
            realImage = new RealImage(fileName); // Lazy loading
        realImage.display();
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