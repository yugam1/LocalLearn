import java.util.*;

interface FileSystemComponent {
    void showDetails();
}

class File implements FileSystemComponent {
    private String name;

    public File(String name) {
        this.name = name;
    }

    public void showDetails() {
        System.out.println("File: " + name);
    }
}

class Folder implements FileSystemComponent {
    private String name;
    private List<FileSystemComponent> components = new ArrayList<>();

    public Folder(String name) {
        this.name = name;
    }

    public void add(FileSystemComponent component) {
        components.add(component);
    }

    public void showDetails() {
        System.out.println("Folder: " + name);
        for (FileSystemComponent c : components) {
            c.showDetails();
        }
    }
}

// Usage
public class CompositePattern {
    public static void main(String[] args) {
        FileSystemComponent file1 = new File("resume.pdf");
        FileSystemComponent file2 = new File("photo.jpg");
        Folder folder = new Folder("Documents");
        folder.add(file1);
        folder.add(file2);
        folder.showDetails();
    }
}
