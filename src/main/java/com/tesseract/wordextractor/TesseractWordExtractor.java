package com.tesseract.wordextractor;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.apache.pdfbox.pdmodel.interactive.form.PDCheckBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDRadioButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDComboBox;
import org.apache.pdfbox.pdmodel.interactive.form.PDListBox;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.stream.Stream;

public class TesseractWordExtractor {
    private static final Logger LOGGER = Logger.getLogger(TesseractWordExtractor.class.getName());
    private static final String OUTPUT_DIR = "output";
    private static final String DATA_DIR = "data";
    
    // Supported file extensions
    private static final List<String> SUPPORTED_IMAGE_EXTENSIONS = Arrays.asList(
        ".jpg", ".jpeg", ".png", ".tiff", ".tif", ".bmp", ".gif", ".webp"
    );
    private static final List<String> SUPPORTED_PDF_EXTENSIONS = Arrays.asList(".pdf");
    
    // Performance modes - users can modify this for their needs
    public enum ProcessingMode {
        FAST(300),      // Fast processing, good accuracy
        BALANCED(400),  // Balanced speed and accuracy (default)
        HIGH_QUALITY(600); // Maximum accuracy, slower processing
        
        private final int dpi;
        ProcessingMode(int dpi) { this.dpi = dpi; }
        public int getDpi() { return dpi; }
    }
    
    private static final ProcessingMode PROCESSING_MODE = ProcessingMode.HIGH_QUALITY;
    
    private final Tesseract tesseract;
    private final ObjectMapper objectMapper;
    private String tesseractDataPath;
    
    public TesseractWordExtractor() {
        this.tesseract = new Tesseract();
        this.objectMapper = new ObjectMapper();
        
        // Configure Tesseract
        try {
            // Set the tessdata path - try common installation paths
            String[] possiblePaths = {
                "C:\\Program Files\\Tesseract-OCR\\tessdata",
                "C:\\Program Files (x86)\\Tesseract-OCR\\tessdata",
                "C:\\Users\\Rajani Kanth\\AppData\\Local\\Programs\\Tesseract-OCR\\tessdata",
                System.getenv("TESSDATA_PREFIX"),
                "./tessdata"
            };
            
            boolean tessdataFound = false;
            for (String path : possiblePaths) {
                if (path != null && new File(path).exists()) {
                    tesseract.setDatapath(path);
                    this.tesseractDataPath = path;
                    tessdataFound = true;
                    LOGGER.info("Using tessdata path: " + path);
                    break;
                }
            }
            
            if (!tessdataFound) {
                LOGGER.warning("Tessdata path not found. Please set TESSDATA_PREFIX environment variable or install Tesseract OCR properly.");
                System.err.println("ERROR: Tesseract language data not found!");
                System.err.println("Please:");
                System.err.println("1. Install Tesseract OCR from: https://github.com/UB-Mannheim/tesseract/wiki");
                System.err.println("2. Or set TESSDATA_PREFIX environment variable to point to tessdata directory");
                System.err.println("3. Or ensure Tesseract is installed in: C:\\Program Files\\Tesseract-OCR\\");
                throw new RuntimeException("Tesseract language data not found");
            }
            
            // Use English and Spanish language models for better special character recognition
            tesseract.setLanguage("eng+spa");
            
            // Set OCR Engine Mode to LSTM only for better accuracy
            tesseract.setOcrEngineMode(1);
            
            // Set Page Segmentation Mode to auto (most stable)
            tesseract.setPageSegMode(3); // Auto page segmentation
            
            // Configure for better UTF-8 and special character handling
            tesseract.setVariable("preserve_interword_spaces", "1");
            tesseract.setVariable("tessedit_char_whitelist", "");
            tesseract.setVariable("tessedit_char_blacklist", "");
            
            // Enhanced configuration for high-resolution processing (stable settings)
            tesseract.setVariable("textord_min_linesize", "2.0");
            tesseract.setVariable("textord_noise_sizelimit", "0.7");
            tesseract.setVariable("classify_enable_learning", "0");
            tesseract.setVariable("classify_enable_adaptive_matcher", "0");
            tesseract.setVariable("wordrec_enable_assoc", "0");
            
            // Better handling of forms and checkboxes
            tesseract.setVariable("textord_tabfind_find_tables", "0"); // Disable table finding to avoid issues
            tesseract.setVariable("segment_penalty_dict_frequent_word", "0");
            tesseract.setVariable("allow_blob_division", "0");
            
            LOGGER.info("Tesseract initialized successfully");
            LOGGER.info("Processing mode: " + PROCESSING_MODE + " (" + PROCESSING_MODE.getDpi() + " DPI)");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize Tesseract", e);
            throw new RuntimeException("Tesseract initialization failed", e);
        }
    }
    
    public static void main(String[] args) {
        TesseractWordExtractor extractor = new TesseractWordExtractor();
        
        try {
            extractor.processDataFolder();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing data folder", e);
            System.err.println("Error processing data folder: " + e.getMessage());
        }
    }
    
    /**
     * Process all supported files in the data folder
     */
    public void processDataFolder() throws IOException {
        Path dataPath = Paths.get(DATA_DIR);
        
        // Create data directory if it doesn't exist
        if (!Files.exists(dataPath)) {
            Files.createDirectories(dataPath);
            System.out.println("Created data directory: " + DATA_DIR);
            System.out.println("Please place your PDF and image files in the data folder and run again.");
            return;
        }
        
        // Create output directory if it doesn't exist
        Path outputDir = Paths.get(OUTPUT_DIR);
        Files.createDirectories(outputDir);
        
        List<Path> filesToProcess = new ArrayList<>();
        
        // Find all supported files in data directory
        try (Stream<Path> paths = Files.walk(dataPath)) {
            paths.filter(Files::isRegularFile)
                 .filter(this::isSupportedFile)
                 .forEach(filesToProcess::add);
        }
        
        if (filesToProcess.isEmpty()) {
            System.out.println("No supported files found in data directory.");
            System.out.println("Supported formats: PDF, JPG, JPEG, PNG, TIFF, TIF, BMP, GIF, WEBP");
            return;
        }
        
        System.out.println("Found " + filesToProcess.size() + " file(s) to process:");
        for (Path file : filesToProcess) {
            System.out.println("- " + file.getFileName());
        }
        System.out.println();
        
        int successCount = 0;
        int errorCount = 0;
        
        for (Path filePath : filesToProcess) {
            try {
                System.out.println("Processing: " + filePath.getFileName());
                processFile(filePath.toString());
                successCount++;
                System.out.println("✓ Successfully processed: " + filePath.getFileName());
            } catch (Exception e) {
                errorCount++;
                LOGGER.log(Level.SEVERE, "Error processing file: " + filePath, e);
                System.err.println("✗ Error processing " + filePath.getFileName() + ": " + e.getMessage());
            }
            System.out.println();
        }
        
        System.out.println("=== PROCESSING SUMMARY ===");
        System.out.println("Total files: " + filesToProcess.size());
        System.out.println("Successfully processed: " + successCount);
        System.out.println("Errors: " + errorCount);
        System.out.println("Output directory: " + OUTPUT_DIR);
    }
    
    /**
     * Check if file has supported extension
     */
    private boolean isSupportedFile(Path filePath) {
        String fileName = filePath.getFileName().toString().toLowerCase();
        return SUPPORTED_IMAGE_EXTENSIONS.stream().anyMatch(fileName::endsWith) ||
               SUPPORTED_PDF_EXTENSIONS.stream().anyMatch(fileName::endsWith);
    }
    
    /**
     * Process a single file (PDF or image)
     */
    public void processFile(String filePath) throws IOException, TesseractException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("File not found: " + filePath);
        }
        
        String fileName = path.getFileName().toString().toLowerCase();
        
        if (SUPPORTED_PDF_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            extractFromPdf(filePath);
        } else if (SUPPORTED_IMAGE_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            extractFromImage(filePath);
        } else {
            throw new IllegalArgumentException("Unsupported file format: " + fileName);
        }
    }
    
    /**
     * Extract text from PDF file
     */
    public void extractFromPdf(String pdfFilePath) throws IOException, TesseractException {
        Path pdfPath = Paths.get(pdfFilePath);
        if (!Files.exists(pdfPath)) {
            throw new FileNotFoundException("PDF file not found: " + pdfFilePath);
        }
        
        String baseFileName = getBaseFileName(pdfFilePath);
        LOGGER.info("Processing PDF: " + pdfFilePath);
        
        // Create output directory if it doesn't exist
        Path outputDir = Paths.get(OUTPUT_DIR);
        Files.createDirectories(outputDir);
        
        List<ExtractedWord> allWords = new ArrayList<>();
        StringBuilder allText = new StringBuilder();
        Map<String, Object> formData = new HashMap<>();
        
        try (PDDocument document = Loader.loadPDF(new File(pdfFilePath))) {
            // Step 1: Extract form fields using PDFBox
            Map<String, String> extractedFormFields = extractFormFields(document);
            formData.put("formFields", extractedFormFields);
            
            // Step 2: Extract regular text using PDFBox text stripper
            String extractedText = extractTextContent(document);
            formData.put("extractedText", extractedText);
            
            // Step 3: Enhanced OCR processing
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int numberOfPages = document.getNumberOfPages();
            
            LOGGER.info("PDF has " + numberOfPages + " pages");
            LOGGER.info("Found " + extractedFormFields.size() + " form fields");
            
            for (int pageIndex = 0; pageIndex < numberOfPages; pageIndex++) {
                LOGGER.info("Processing page " + (pageIndex + 1) + " of " + numberOfPages);
                
                // Convert PDF page to image using selected processing mode
                int currentDpi = PROCESSING_MODE.getDpi();
                BufferedImage image = pdfRenderer.renderImageWithDPI(pageIndex, currentDpi, ImageType.RGB);
                
                // Enhanced preprocessing for form fields
                BufferedImage processedImage = preprocessImageForFormOCR(image);
                
                // Multiple OCR passes optimized for forms
                StringBuilder pageTextBuilder = new StringBuilder();
                
                // Pass 1: Standard OCR with PSM 3 (auto) 
                try {
                    String pageText1 = performOCRWithConfig(processedImage, 3, "Auto Detection");
                    pageTextBuilder.append("=== OCR Pass 1 (Auto Detection) ===\n").append(pageText1).append("\n\n");
                } catch (Exception e) {
                    LOGGER.warning("Pass 1 OCR failed for page " + (pageIndex + 1) + ": " + e.getMessage());
                }
                
                // Pass 2: Form-optimized OCR with PSM 6 (uniform block)
                try {
                    String pageText2 = performOCRWithConfig(processedImage, 6, "Form Fields");
                    pageTextBuilder.append("=== OCR Pass 2 (Form Fields) ===\n").append(pageText2).append("\n\n");
                } catch (Exception e) {
                    LOGGER.warning("Pass 2 OCR failed for page " + (pageIndex + 1) + ": " + e.getMessage());
                }
                
                // Pass 3: Single word detection for text boxes
                try {
                    String pageText3 = performOCRWithConfig(processedImage, 8, "Single Words/Dates");
                    pageTextBuilder.append("=== OCR Pass 3 (Single Words/Dates) ===\n").append(pageText3).append("\n\n");
                } catch (Exception e) {
                    LOGGER.warning("Pass 3 OCR failed for page " + (pageIndex + 1) + ": " + e.getMessage());
                }
                
                // Pass 4: Checkbox and symbol detection
                try {
                    String pageText4 = performCheckboxOCR(processedImage);
                    pageTextBuilder.append("=== OCR Pass 4 (Checkboxes & Symbols) ===\n").append(pageText4).append("\n\n");
                } catch (Exception e) {
                    LOGGER.warning("Pass 4 OCR failed for page " + (pageIndex + 1) + ": " + e.getMessage());
                }
                
                allText.append(pageTextBuilder.toString()).append("\n\n");
            }
        }
        
        // Generate comprehensive outputs
        generateEnhancedTextOutput(baseFileName, allText.toString(), formData, "PDF");
        generateEnhancedJsonOutput(baseFileName, allWords, formData, "PDF");
        
        LOGGER.info("PDF extraction completed. Files saved in " + OUTPUT_DIR + " directory");
        System.out.println("Output files:");
        System.out.println("- " + OUTPUT_DIR + "/" + baseFileName + "_comprehensive.txt");
        System.out.println("- " + OUTPUT_DIR + "/" + baseFileName + "_comprehensive.json");
        System.out.println("\nExtraction Summary:");
        System.out.println("- Form fields found: " + ((Map<?, ?>) formData.get("formFields")).size());
        System.out.println("- OCR processing: 4 passes completed");
        System.out.println("- Text extraction: PDFBox + Enhanced OCR");
    }
    
    /**
     * Extract text from image file
     */
    public void extractFromImage(String imageFilePath) throws IOException, TesseractException {
        Path imagePath = Paths.get(imageFilePath);
        if (!Files.exists(imagePath)) {
            throw new FileNotFoundException("Image file not found: " + imageFilePath);
        }
        
        String baseFileName = getBaseFileName(imageFilePath);
        LOGGER.info("Processing Image: " + imageFilePath);
        
        // Create output directory if it doesn't exist
        Path outputDir = Paths.get(OUTPUT_DIR);
        Files.createDirectories(outputDir);
        
        List<ExtractedWord> allWords = new ArrayList<>();
        StringBuilder allText = new StringBuilder();
        Map<String, Object> imageData = new HashMap<>();
        
        try {
            // Load the image
            BufferedImage originalImage = ImageIO.read(new File(imageFilePath));
            if (originalImage == null) {
                throw new IOException("Unable to read image file: " + imageFilePath);
            }
            
            LOGGER.info("Image dimensions: " + originalImage.getWidth() + "x" + originalImage.getHeight());
            
            // Enhanced preprocessing for high-quality OCR
            BufferedImage processedImage = preprocessImageForHighQualityOCR(originalImage);
            
            // Multiple OCR passes for comprehensive text extraction
            StringBuilder imageTextBuilder = new StringBuilder();
            
            // Pass 1: Standard OCR with PSM 3 (auto detection)
            try {
                String imageText1 = performOCRWithConfig(processedImage, 3, "Auto Detection");
                imageTextBuilder.append("=== OCR Pass 1 (Auto Detection) ===\n").append(imageText1).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 1 OCR failed: " + e.getMessage());
            }
            
            // Pass 2: Form-optimized OCR with PSM 6 (uniform block)
            try {
                String imageText2 = performOCRWithConfig(processedImage, 6, "Form Fields");
                imageTextBuilder.append("=== OCR Pass 2 (Form Fields) ===\n").append(imageText2).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 2 OCR failed: " + e.getMessage());
            }
            
            // Pass 3: Single word detection
            try {
                String imageText3 = performOCRWithConfig(processedImage, 8, "Single Words/Dates");
                imageTextBuilder.append("=== OCR Pass 3 (Single Words/Dates) ===\n").append(imageText3).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 3 OCR failed: " + e.getMessage());
            }
            
            // Pass 4: Checkbox and symbol detection
            try {
                String imageText4 = performCheckboxOCR(processedImage);
                imageTextBuilder.append("=== OCR Pass 4 (Checkboxes & Symbols) ===\n").append(imageText4).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 4 OCR failed: " + e.getMessage());
            }
            
            // Pass 5: High-resolution small text detection
            try {
                String imageText5 = performSmallTextOCR(processedImage);
                imageTextBuilder.append("=== OCR Pass 5 (Small Text Detection) ===\n").append(imageText5).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 5 OCR failed: " + e.getMessage());
            }
            
            allText.append(imageTextBuilder.toString());
            
            // Store image metadata
            imageData.put("imageWidth", originalImage.getWidth());
            imageData.put("imageHeight", originalImage.getHeight());
            imageData.put("imageFormat", getImageFormat(imageFilePath));
            
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing image: " + imageFilePath, e);
            throw e;
        }
        
        // Generate comprehensive outputs
        generateEnhancedTextOutput(baseFileName, allText.toString(), imageData, "IMAGE");
        generateEnhancedJsonOutput(baseFileName, allWords, imageData, "IMAGE");
        
        LOGGER.info("Image extraction completed. Files saved in " + OUTPUT_DIR + " directory");
        System.out.println("Output files:");
        System.out.println("- " + OUTPUT_DIR + "/" + baseFileName + "_comprehensive.txt");
        System.out.println("- " + OUTPUT_DIR + "/" + baseFileName + "_comprehensive.json");
        System.out.println("\nExtraction Summary:");
        System.out.println("- Image dimensions: " + imageData.get("imageWidth") + "x" + imageData.get("imageHeight"));
        System.out.println("- OCR processing: 5 passes completed");
        System.out.println("- Text extraction: Enhanced Multi-Pass OCR");
    }
    
    
    private String getBaseFileName(String filePath) {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex > 0 ? fileName.substring(0, lastDotIndex) : fileName;
    }
    
    /**
     * Extract form fields from PDF using PDFBox
     */
    private Map<String, String> extractFormFields(PDDocument document) {
        Map<String, String> formFields = new HashMap<>();
        
        try {
            PDAcroForm acroForm = document.getDocumentCatalog().getAcroForm();
            if (acroForm != null) {
                LOGGER.info("PDF contains interactive form fields");
                
                for (PDField field : acroForm.getFields()) {
                    String fieldName = field.getFullyQualifiedName();
                    String fieldValue = "";
                    
                    try {
                        if (field instanceof PDTextField) {
                            fieldValue = ((PDTextField) field).getValue();
                        } else if (field instanceof PDCheckBox) {
                            fieldValue = ((PDCheckBox) field).isChecked() ? "☑" : "☐";
                        } else if (field instanceof PDRadioButton) {
                            fieldValue = ((PDRadioButton) field).getValue();
                        } else if (field instanceof PDComboBox) {
                            List<String> values = ((PDComboBox) field).getValue();
                            fieldValue = values != null && !values.isEmpty() ? values.get(0) : "";
                        } else if (field instanceof PDListBox) {
                            List<String> selectedValues = ((PDListBox) field).getValue();
                            fieldValue = selectedValues != null ? String.join(", ", selectedValues) : "";
                        } else {
                            fieldValue = field.getValueAsString();
                        }
                        
                        if (fieldValue == null) {
                            fieldValue = "";
                        }
                        
                        formFields.put(fieldName, fieldValue);
                        LOGGER.info("Form field: " + fieldName + " = " + fieldValue);
                        
                    } catch (Exception e) {
                        LOGGER.warning("Error reading form field " + fieldName + ": " + e.getMessage());
                        formFields.put(fieldName, "[Error reading field]");
                    }
                }
            } else {
                LOGGER.info("PDF does not contain interactive form fields");
            }
        } catch (Exception e) {
            LOGGER.warning("Error extracting form fields: " + e.getMessage());
        }
        
        return formFields;
    }
    
    /**
     * Extract text content using PDFBox text stripper
     */
    private String extractTextContent(PDDocument document) {
        StringBuilder extractedText = new StringBuilder();
        
        try {
            PDFTextStripper textStripper = new PDFTextStripper();
            textStripper.setSortByPosition(true);
            textStripper.setLineSeparator("\n");
            
            String text = textStripper.getText(document);
            extractedText.append(text);
            
            LOGGER.info("Extracted " + text.length() + " characters using PDFBox text stripper");
            
        } catch (Exception e) {
            LOGGER.warning("Error extracting text content: " + e.getMessage());
            extractedText.append("[Error extracting text content]");
        }
        
        return extractedText.toString();
    }
    
    /**
     * Perform OCR with specific configuration
     */
    private String performOCRWithConfig(BufferedImage image, int pageSegMode, String passName) throws TesseractException {
        tesseract.setPageSegMode(pageSegMode);
        
        // Enhanced configuration for specific pass
        switch (pageSegMode) {
            case 6: // Form fields
                tesseract.setVariable("textord_tabfind_find_tables", "1");
                tesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789áéíóúñÁÉÍÓÚÑüÜ¿¡.,;:()[]{}/-_@#$%&*+=<>?!\"' ");
                break;
            case 8: // Single words/dates
                tesseract.setVariable("tessedit_char_whitelist", "0123456789/.-ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");
                break;
            default:
                tesseract.setVariable("tessedit_char_whitelist", "");
                break;
        }
        
        String result = tesseract.doOCR(image);
        
        // Reset to default
        tesseract.setPageSegMode(3);
        tesseract.setVariable("tessedit_char_whitelist", "");
        
        return result;
    }
    
    /**
     * Specialized OCR for checkbox and symbol detection
     */
    private String performCheckboxOCR(BufferedImage image) throws TesseractException {
        // Create a specialized Tesseract instance for checkbox detection
        Tesseract checkboxTesseract = new Tesseract();
        
        try {
            // Use same datapath as main instance
            checkboxTesseract.setDatapath(this.tesseractDataPath);
            checkboxTesseract.setLanguage("eng");
            checkboxTesseract.setOcrEngineMode(1);
            checkboxTesseract.setPageSegMode(6);
            
            // Checkbox-specific character whitelist
            checkboxTesseract.setVariable("tessedit_char_whitelist", "XxχΧ✓✗☐☑☒□■▢▣⬜⬛◯○●◉◎⚪⚫🔲🔳▫▪");
            checkboxTesseract.setVariable("classify_enable_learning", "0");
            checkboxTesseract.setVariable("classify_enable_adaptive_matcher", "0");
            
            return checkboxTesseract.doOCR(image);
            
        } catch (Exception e) {
            LOGGER.warning("Checkbox OCR failed: " + e.getMessage());
            return "[Checkbox detection failed]";
        }
    }
    
    /**
     * Enhanced preprocessing specifically optimized for form field detection
     */
    private BufferedImage preprocessImageForFormOCR(BufferedImage originalImage) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();
        
        // Enhanced preprocessing for forms and checkboxes with better contrast
        BufferedImage processedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        
        // First pass: Convert to grayscale with optimized weights for forms
        int[][] grayValues = new int[height][width];
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = originalImage.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                
                // Enhanced grayscale conversion for form elements
                int gray = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
                grayValues[y][x] = gray;
            }
        }
        
        // Second pass: Advanced adaptive processing for form fields
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = grayValues[y][x];
                
                // Calculate adaptive local threshold for form field detection
                int localSum = 0;
                int localCount = 0;
                int windowSize = 20; // Larger window for form fields
                
                for (int wy = Math.max(0, y - windowSize); wy < Math.min(height, y + windowSize); wy++) {
                    for (int wx = Math.max(0, x - windowSize); wx < Math.min(width, x + windowSize); wx++) {
                        localSum += grayValues[wy][wx];
                        localCount++;
                    }
                }
                
                int localAvg = localSum / localCount;
                
                // Enhanced contrast for form elements and text boxes
                if (gray < localAvg - 20) {
                    // Dark areas (text/form borders) - enhance significantly
                    gray = Math.max(0, gray - 50);
                } else if (gray > localAvg + 20) {
                    // Light areas (background/form fields) - brighten
                    gray = Math.min(255, gray + 40);
                } else {
                    // Apply stronger contrast enhancement for form elements
                    gray = Math.min(255, Math.max(0, (int) (2.0 * (gray - 128) + 128)));
                }
                
                // Multi-directional edge enhancement for better form field boundaries
                if (x > 2 && x < width - 3 && y > 2 && y < height - 3) {
                    int edgeStrength = 0;
                    
                    // Calculate edge strength in multiple directions for form detection
                    edgeStrength += Math.abs(grayValues[y][x-2] - grayValues[y][x+2]); // Horizontal
                    edgeStrength += Math.abs(grayValues[y-2][x] - grayValues[y+2][x]); // Vertical
                    edgeStrength += Math.abs(grayValues[y-2][x-2] - grayValues[y+2][x+2]); // Diagonal
                    edgeStrength += Math.abs(grayValues[y-2][x+2] - grayValues[y+2][x-2]); // Anti-diagonal
                    
                    if (edgeStrength > 80) {
                        // Strong edge - likely form boundary or text
                        if (gray < localAvg) {
                            gray = Math.max(0, gray - 40); // Make dark edges much darker
                        } else {
                            gray = Math.min(255, gray + 40); // Make light edges lighter
                        }
                    }
                }
                
                processedImage.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
            }
        }
        
        return processedImage;
    }
    
    /**
     * Enhanced preprocessing specifically for high-quality image OCR
     */
    private BufferedImage preprocessImageForHighQualityOCR(BufferedImage originalImage) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();
        
        // Enhanced preprocessing for high-quality OCR with small text detection
        BufferedImage processedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        
        // First pass: Convert to grayscale with optimized weights
        int[][] grayValues = new int[height][width];
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = originalImage.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                
                // Enhanced grayscale conversion for small text
                int gray = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
                grayValues[y][x] = gray;
            }
        }
        
        // Second pass: Advanced adaptive processing for small text
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = grayValues[y][x];
                
                // Calculate adaptive local threshold for small text detection
                int localSum = 0;
                int localCount = 0;
                int windowSize = 10; // Smaller window for fine details
                
                for (int wy = Math.max(0, y - windowSize); wy < Math.min(height, y + windowSize); wy++) {
                    for (int wx = Math.max(0, x - windowSize); wx < Math.min(width, x + windowSize); wx++) {
                        localSum += grayValues[wy][wx];
                        localCount++;
                    }
                }
                
                int localAvg = localSum / localCount;
                
                // Enhanced contrast for small text and fine details
                if (gray < localAvg - 25) {
                    // Dark areas (text) - enhance significantly for small text
                    gray = Math.max(0, gray - 60);
                } else if (gray > localAvg + 25) {
                    // Light areas (background) - brighten more
                    gray = Math.min(255, gray + 50);
                } else {
                    // Apply stronger contrast enhancement for fine details
                    gray = Math.min(255, Math.max(0, (int) (2.5 * (gray - 128) + 128)));
                }
                
                // Multi-directional edge enhancement for small text
                if (x > 3 && x < width - 4 && y > 3 && y < height - 4) {
                    int edgeStrength = 0;
                    
                    // Calculate edge strength in multiple directions for fine details
                    edgeStrength += Math.abs(grayValues[y][x-3] - grayValues[y][x+3]); // Horizontal
                    edgeStrength += Math.abs(grayValues[y-3][x] - grayValues[y+3][x]); // Vertical
                    edgeStrength += Math.abs(grayValues[y-2][x-2] - grayValues[y+2][x+2]); // Diagonal
                    edgeStrength += Math.abs(grayValues[y-2][x+2] - grayValues[y+2][x-2]); // Anti-diagonal
                    
                    if (edgeStrength > 100) {
                        // Strong edge - likely small text or fine details
                        if (gray < localAvg) {
                            gray = Math.max(0, gray - 50); // Make dark edges much darker
                        } else {
                            gray = Math.min(255, gray + 50); // Make light edges lighter
                        }
                    }
                }
                
                processedImage.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
            }
        }
        
        return processedImage;
    }
    
    /**
     * Specialized OCR for small text detection
     */
    private String performSmallTextOCR(BufferedImage image) throws TesseractException {
        // Create a specialized Tesseract instance for small text detection
        Tesseract smallTextTesseract = new Tesseract();
        
        try {
            // Use same datapath as main instance
            smallTextTesseract.setDatapath(this.tesseractDataPath);
            smallTextTesseract.setLanguage("eng+spa");
            smallTextTesseract.setOcrEngineMode(1);
            smallTextTesseract.setPageSegMode(13); // Raw line. Treat the image as a single text line
            
            // Small text specific configuration
            smallTextTesseract.setVariable("tessedit_char_whitelist", "");
            smallTextTesseract.setVariable("classify_enable_learning", "0");
            smallTextTesseract.setVariable("classify_enable_adaptive_matcher", "0");
            smallTextTesseract.setVariable("textord_min_linesize", "1.0"); // Smaller minimum line size
            smallTextTesseract.setVariable("textord_noise_sizelimit", "0.5"); // More sensitive to small features
            
            return smallTextTesseract.doOCR(image);
            
        } catch (Exception e) {
            LOGGER.warning("Small text OCR failed: " + e.getMessage());
            return "[Small text detection failed]";
        }
    }
    
    /**
     * Get image format from file path
     */
    private String getImageFormat(String filePath) {
        String fileName = filePath.toLowerCase();
        if (fileName.endsWith(".jpg") || fileName.endsWith(".jpeg")) {
            return "JPEG";
        } else if (fileName.endsWith(".png")) {
            return "PNG";
        } else if (fileName.endsWith(".tiff") || fileName.endsWith(".tif")) {
            return "TIFF";
        } else if (fileName.endsWith(".bmp")) {
            return "BMP";
        } else if (fileName.endsWith(".gif")) {
            return "GIF";
        } else if (fileName.endsWith(".webp")) {
            return "WEBP";
        } else {
            return "UNKNOWN";
        }
    }
    
    
    
    /**
     * Preprocesses the image to improve OCR accuracy for forms and checkboxes
     * @param originalImage The original BufferedImage from PDF
     * @return Processed BufferedImage optimized for OCR
     */
    private BufferedImage preprocessImageForOCR(BufferedImage originalImage) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();
        
        // Enhanced preprocessing for forms and checkboxes
        BufferedImage processedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        
        // First pass: Convert to grayscale
        int[][] grayValues = new int[height][width];
        
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = originalImage.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                
                // Convert to grayscale with optimized weights for text
                int gray = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
                grayValues[y][x] = gray;
            }
        }
        
        // Second pass: Enhanced processing with adaptive thresholding
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int gray = grayValues[y][x];
                
                // Calculate local threshold for better checkbox detection
                int localSum = 0;
                int localCount = 0;
                int windowSize = 15;
                
                for (int wy = Math.max(0, y - windowSize); wy < Math.min(height, y + windowSize); wy++) {
                    for (int wx = Math.max(0, x - windowSize); wx < Math.min(width, x + windowSize); wx++) {
                        localSum += grayValues[wy][wx];
                        localCount++;
                    }
                }
                
                int localAvg = localSum / localCount;
                
                // Enhanced adaptive contrast for forms and checkboxes
                if (gray < localAvg - 15) {
                    // Dark areas (text/checkboxes) - make much darker for better recognition
                    gray = Math.max(0, gray - 40);
                } else if (gray > localAvg + 15) {
                    // Light areas (background) - make lighter
                    gray = Math.min(255, gray + 30);
                } else {
                    // Apply stronger contrast enhancement for form elements
                    gray = Math.min(255, Math.max(0, (int) (1.5 * (gray - 128) + 128)));
                }
                
                // Edge enhancement for better character definition
                if (x > 1 && x < width - 2 && y > 1 && y < height - 2) {
                    int edgeStrength = 0;
                    
                    // Calculate edge strength in multiple directions
                    edgeStrength += Math.abs(grayValues[y][x-1] - grayValues[y][x+1]); // Horizontal
                    edgeStrength += Math.abs(grayValues[y-1][x] - grayValues[y+1][x]); // Vertical
                    edgeStrength += Math.abs(grayValues[y-1][x-1] - grayValues[y+1][x+1]); // Diagonal
                    edgeStrength += Math.abs(grayValues[y-1][x+1] - grayValues[y+1][x-1]); // Anti-diagonal
                    
                    if (edgeStrength > 60) {
                        // Strong edge - enhance contrast
                        if (gray < localAvg) {
                            gray = Math.max(0, gray - 30); // Make dark edges darker
                        } else {
                            gray = Math.min(255, gray + 30); // Make light edges lighter
                        }
                    }
                }
                
                processedImage.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
            }
        }
        
        return processedImage;
    }
    
    /**
     * Generate enhanced text output with form fields and OCR results
     */
    private void generateEnhancedTextOutput(String baseFileName, String ocrText, Map<String, Object> data, String fileType) throws IOException {
        Path textFile = Paths.get(OUTPUT_DIR, baseFileName + "_comprehensive.txt");
        
        try (BufferedWriter writer = Files.newBufferedWriter(textFile, StandardCharsets.UTF_8)) {
            writer.write("=== COMPREHENSIVE " + fileType + " EXTRACTION RESULTS ===\n");
            writer.write("Generated: " + new java.util.Date() + "\n");
            writer.write("Processing Mode: " + PROCESSING_MODE + " (" + PROCESSING_MODE.getDpi() + " DPI)\n");
            writer.write("File Type: " + fileType + "\n\n");
            
            if ("PDF".equals(fileType)) {
                // Form fields section (PDF only)
                @SuppressWarnings("unchecked")
                Map<String, String> formFields = (Map<String, String>) data.get("formFields");
                writer.write("=== FORM FIELDS EXTRACTED (PDFBox) ===\n");
                if (formFields != null && !formFields.isEmpty()) {
                    for (Map.Entry<String, String> entry : formFields.entrySet()) {
                        writer.write("Field:\t" + entry.getKey() + "\n");
                        writer.write("Value:\t" + entry.getValue() + "\n");
                        writer.write("---\n");
                    }
                } else {
                    writer.write("No interactive form fields found.\n");
                }
                writer.write("\n");
                
                // PDFBox text extraction
                String extractedText = (String) data.get("extractedText");
                writer.write("=== TEXT CONTENT EXTRACTED (PDFBox Text Stripper) ===\n");
                if (extractedText != null && !extractedText.trim().isEmpty()) {
                    writer.write(extractedText);
                } else {
                    writer.write("No text content extracted by PDFBox.\n");
                }
                writer.write("\n\n");
            } else if ("IMAGE".equals(fileType)) {
                // Image metadata section
                writer.write("=== IMAGE METADATA ===\n");
                writer.write("Width:\t" + data.get("imageWidth") + " pixels\n");
                writer.write("Height:\t" + data.get("imageHeight") + " pixels\n");
                writer.write("Format:\t" + data.get("imageFormat") + "\n\n");
            }
            
            // OCR results
            writer.write("=== ENHANCED OCR RESULTS (Tesseract Multi-Pass) ===\n");
            writer.write(ocrText);
        }
        
        LOGGER.info("Enhanced text output saved to: " + textFile);
    }
    
    /**
     * Generate enhanced JSON output with all extraction data
     */
    private void generateEnhancedJsonOutput(String baseFileName, List<ExtractedWord> words, Map<String, Object> data, String fileType) throws IOException {
        Path jsonFile = Paths.get(OUTPUT_DIR, baseFileName + "_comprehensive.json");
        
        ObjectNode rootNode = objectMapper.createObjectNode();
        rootNode.put("extractionTimestamp", System.currentTimeMillis());
        rootNode.put("processingMode", PROCESSING_MODE.toString());
        rootNode.put("processingDPI", PROCESSING_MODE.getDpi());
        rootNode.put("fileType", fileType);
        
        if ("PDF".equals(fileType)) {
            // Form fields (PDF only)
            @SuppressWarnings("unchecked")
            Map<String, String> formFields = (Map<String, String>) data.get("formFields");
            ObjectNode formFieldsNode = objectMapper.createObjectNode();
            if (formFields != null) {
                for (Map.Entry<String, String> entry : formFields.entrySet()) {
                    formFieldsNode.put(entry.getKey(), entry.getValue());
                }
            }
            rootNode.set("formFields", formFieldsNode);
            
            // PDFBox extracted text
            String extractedText = (String) data.get("extractedText");
            rootNode.put("pdfBoxText", extractedText != null ? extractedText : "");
        } else if ("IMAGE".equals(fileType)) {
            // Image metadata
            ObjectNode imageMetadata = objectMapper.createObjectNode();
            imageMetadata.put("width", (Integer) data.get("imageWidth"));
            imageMetadata.put("height", (Integer) data.get("imageHeight"));
            imageMetadata.put("format", (String) data.get("imageFormat"));
            rootNode.set("imageMetadata", imageMetadata);
        }
        
        // OCR results (if any words were extracted)
        rootNode.put("totalWords", words.size());
        ArrayNode wordsArray = objectMapper.createArrayNode();
        
        for (ExtractedWord word : words) {
            ObjectNode wordNode = objectMapper.createObjectNode();
            wordNode.put("text", word.getText());
            wordNode.put("page", word.getPage());
            wordNode.put("x", word.getX());
            wordNode.put("y", word.getY());
            wordNode.put("width", word.getWidth());
            wordNode.put("height", word.getHeight());
            wordNode.put("confidence", word.getConfidence());
            
            wordsArray.add(wordNode);
        }
        
        rootNode.set("words", wordsArray);
        
        // Summary
        ObjectNode summaryNode = objectMapper.createObjectNode();
        if ("PDF".equals(fileType)) {
            @SuppressWarnings("unchecked")
            Map<String, String> formFields = (Map<String, String>) data.get("formFields");
            String extractedText = (String) data.get("extractedText");
            summaryNode.put("formFieldsFound", formFields != null ? formFields.size() : 0);
            summaryNode.put("textLengthPDFBox", extractedText != null ? extractedText.length() : 0);
        } else if ("IMAGE".equals(fileType)) {
            summaryNode.put("imageWidth", (Integer) data.get("imageWidth"));
            summaryNode.put("imageHeight", (Integer) data.get("imageHeight"));
            summaryNode.put("imageFormat", (String) data.get("imageFormat"));
        }
        summaryNode.put("wordsExtracted", words.size());
        rootNode.set("summary", summaryNode);
        
        try (BufferedWriter writer = Files.newBufferedWriter(jsonFile, StandardCharsets.UTF_8)) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(writer, rootNode);
        }
        
        LOGGER.info("Enhanced JSON output saved to: " + jsonFile);
    }
    
    // Inner class to represent an extracted word
    public static class ExtractedWord {
        private final String text;
        private final int page;
        private final int x;
        private final int y;
        private final int width;
        private final int height;
        private final float confidence;
        
        public ExtractedWord(String text, int page, int x, int y, int width, int height, float confidence) {
            this.text = text;
            this.page = page;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.confidence = confidence;
        }
        
        // Getters
        public String getText() { return text; }
        public int getPage() { return page; }
        public int getX() { return x; }
        public int getY() { return y; }
        public int getWidth() { return width; }
        public int getHeight() { return height; }
        public float getConfidence() { return confidence; }
    }
}
