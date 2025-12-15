import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

public class debug_file_count {
    public static void main(String[] args) throws IOException {
        Path outputPath = Paths.get("output");
        List<String> allFiles = new ArrayList<>();
        
        System.out.println("Scanning directory: " + outputPath.toAbsolutePath());
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputPath, "*.txt")) {
            for (Path filePath : stream) {
                String fileName = filePath.getFileName().toString();
                allFiles.add(fileName);
                System.out.println("Found: " + fileName);
            }
        }
        
        System.out.println("\nTotal files found: " + allFiles.size());
    }
}
