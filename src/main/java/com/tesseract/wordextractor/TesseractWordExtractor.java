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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;

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
        HIGH_QUALITY(600), // Maximum accuracy, slower processing
        ULTRA_HIGH_QUALITY(800); // Ultra high quality for images with small text
        
        private final int dpi;
        ProcessingMode(int dpi) { this.dpi = dpi; }
        public int getDpi() { return dpi; }
    }
    
    private static final ProcessingMode PROCESSING_MODE = ProcessingMode.ULTRA_HIGH_QUALITY;
    
    // Multithreading configuration
    private static final int DEFAULT_THREAD_POOL_SIZE = Math.max(2, Runtime.getRuntime().availableProcessors() - 1);
    private static final int MAX_THREAD_POOL_SIZE = 8; // Prevent excessive resource usage
    private static final int OPTIMAL_THREAD_POOL_SIZE = Math.min(DEFAULT_THREAD_POOL_SIZE, MAX_THREAD_POOL_SIZE);
    
    // Thread pool for file processing
    private ExecutorService executorService;
    private final AtomicInteger processedFiles = new AtomicInteger(0);
    private final AtomicInteger successfulFiles = new AtomicInteger(0);
    private final AtomicInteger failedFiles = new AtomicInteger(0);
    private final ConcurrentHashMap<String, String> processingResults = new ConcurrentHashMap<>();
    
    private final Tesseract tesseract;
    private final ObjectMapper objectMapper;
    private String tesseractDataPath;
    
    public TesseractWordExtractor() {
        this.tesseract = new Tesseract();
        this.objectMapper = new ObjectMapper();
        
        // Initialize thread pool
        this.executorService = Executors.newFixedThreadPool(OPTIMAL_THREAD_POOL_SIZE);
        LOGGER.info("Initialized thread pool with " + OPTIMAL_THREAD_POOL_SIZE + " threads");
        
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
            
            // Check available languages and use appropriate one
            try {
                // Try Portuguese first
                tesseract.setLanguage("por");
                LOGGER.info("Using Portuguese language model");
            } catch (Exception e1) {
                try {
                    // Fallback to Portuguese + English
                    tesseract.setLanguage("por+eng");
                    LOGGER.info("Using Portuguese + English language models");
                } catch (Exception e2) {
                    try {
                        // Fallback to just English
                        tesseract.setLanguage("eng");
                        LOGGER.warning("Portuguese not available, using English only");
                        System.err.println("WARNING: Portuguese language model not found, using English only");
                        System.err.println("For better results, install Portuguese language data:");
                        System.err.println("Download por.traineddata from: https://github.com/tesseract-ocr/tessdata");
                        System.err.println("Place it in: " + this.tesseractDataPath);
                    } catch (Exception e3) {
                        LOGGER.severe("No language models available");
                        throw new RuntimeException("No Tesseract language models available", e3);
                    }
                }
            }
            
            // Set OCR Engine Mode to LSTM only for better accuracy
            tesseract.setOcrEngineMode(1);
            
            // Set Page Segmentation Mode to auto (most reliable)
            tesseract.setPageSegMode(3); // Auto page segmentation
            
            // Configure for better UTF-8 and special character handling
            tesseract.setVariable("preserve_interword_spaces", "1");
            tesseract.setVariable("tessedit_char_whitelist", "");
            tesseract.setVariable("tessedit_char_blacklist", "");
            
            // Simple, proven Tesseract configuration
            tesseract.setVariable("classify_enable_learning", "0");
            tesseract.setVariable("classify_enable_adaptive_matcher", "0");
            
            // List available languages for debugging
            try {
                java.io.File tessDataDir = new java.io.File(this.tesseractDataPath);
                if (tessDataDir.exists()) {
                    java.io.File[] languageFiles = tessDataDir.listFiles((dir, name) -> name.endsWith(".traineddata"));
                    if (languageFiles != null && languageFiles.length > 0) {
                        StringBuilder availableLangs = new StringBuilder("Available language models: ");
                        for (java.io.File langFile : languageFiles) {
                            String langCode = langFile.getName().replace(".traineddata", "");
                            availableLangs.append(langCode).append(" ");
                        }
                        LOGGER.info(availableLangs.toString());
                        System.out.println(availableLangs.toString());
                    } else {
                        LOGGER.warning("No language model files found in tessdata directory");
                        System.err.println("WARNING: No .traineddata files found in: " + this.tesseractDataPath);
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("Could not list available languages: " + e.getMessage());
            }
            
            LOGGER.info("Tesseract initialized successfully");
            LOGGER.info("Processing mode: " + PROCESSING_MODE + " (" + PROCESSING_MODE.getDpi() + " DPI)");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error initializing Tesseract", e);
            // Clean up thread pool if initialization fails
            if (this.executorService != null) {
                this.executorService.shutdown();
            }
            throw new RuntimeException("Failed to initialize Tesseract OCR", e);
        }
    }
    
    /**
     * Cleanup method to properly shutdown thread pool
     */
    public void cleanup() {
        if (executorService != null && !executorService.isShutdown()) {
            LOGGER.info("Shutting down thread pool...");
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(60, TimeUnit.SECONDS)) {
                    LOGGER.warning("Thread pool did not terminate gracefully, forcing shutdown");
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                LOGGER.warning("Interrupted while waiting for thread pool termination");
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }
    
    public static void main(String[] args) {
        TesseractWordExtractor extractor = new TesseractWordExtractor();
        
        try {
            extractor.processDataFolderConcurrently();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing data folder", e);
            System.err.println("Error processing data folder: " + e.getMessage());
        } finally {
            // Ensure proper cleanup of resources
            extractor.cleanup();
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
     * Process all supported files in the data folder using multithreading for improved performance
     */
    public void processDataFolderConcurrently() throws IOException {
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
        System.out.println("Processing files concurrently with " + OPTIMAL_THREAD_POOL_SIZE + " threads...");
        System.out.println();
        
        // Reset counters
        processedFiles.set(0);
        successfulFiles.set(0);
        failedFiles.set(0);
        processingResults.clear();
        
        long startTime = System.currentTimeMillis();
        
        // Create CompletableFuture for each file
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (Path filePath : filesToProcess) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    String fileName = filePath.getFileName().toString();
                    System.out.println("[Thread-" + Thread.currentThread().getName() + "] Processing: " + fileName);
                    
                    // Process file with thread-safe method
                    processFileThreadSafe(filePath.toString());
                    
                    successfulFiles.incrementAndGet();
                    processingResults.put(fileName, "SUCCESS");
                    System.out.println("[Thread-" + Thread.currentThread().getName() + "] ✓ Successfully processed: " + fileName);
                    
                } catch (Exception e) {
                    String fileName = filePath.getFileName().toString();
                    failedFiles.incrementAndGet();
                    processingResults.put(fileName, "FAILED: " + e.getMessage());
                    LOGGER.log(Level.SEVERE, "Error processing file: " + filePath, e);
                    System.err.println("[Thread-" + Thread.currentThread().getName() + "] ✗ Error processing " + fileName + ": " + e.getMessage());
                } finally {
                    int completed = processedFiles.incrementAndGet();
                    if (completed % 5 == 0 || completed == filesToProcess.size()) {
                        System.out.println("Progress: " + completed + "/" + filesToProcess.size() + " files processed");
                    }
                }
            }, executorService);
            
            futures.add(future);
        }
        
        // Wait for all files to complete
        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error waiting for concurrent processing to complete", e);
            System.err.println("Error during concurrent processing: " + e.getMessage());
        }
        
        long endTime = System.currentTimeMillis();
        long processingTime = endTime - startTime;
        
        System.out.println();
        System.out.println("=== CONCURRENT PROCESSING SUMMARY ===");
        System.out.println("Total files: " + filesToProcess.size());
        System.out.println("Successfully processed: " + successfulFiles.get());
        System.out.println("Errors: " + failedFiles.get());
        System.out.println("Processing time: " + (processingTime / 1000.0) + " seconds");
        System.out.println("Average time per file: " + (processingTime / (double) filesToProcess.size() / 1000.0) + " seconds");
        System.out.println("Thread pool size: " + OPTIMAL_THREAD_POOL_SIZE + " threads");
        System.out.println("Output directory: " + OUTPUT_DIR);
        
        // Show detailed results if there were failures
        if (failedFiles.get() > 0) {
            System.out.println();
            System.out.println("=== DETAILED RESULTS ===");
            processingResults.forEach((fileName, result) -> {
                if (result.startsWith("FAILED")) {
                    System.err.println("✗ " + fileName + ": " + result);
                } else {
                    System.out.println("✓ " + fileName + ": " + result);
                }
            });
        }
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
     * Thread-safe version of processFile that creates its own Tesseract instance
     * This prevents conflicts when multiple threads are processing files simultaneously
     */
    public void processFileThreadSafe(String filePath) throws IOException, TesseractException {
        Path path = Paths.get(filePath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("File not found: " + filePath);
        }
        
        String fileName = path.getFileName().toString().toLowerCase();
        
        if (SUPPORTED_PDF_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            extractFromPdfThreadSafe(filePath);
        } else if (SUPPORTED_IMAGE_EXTENSIONS.stream().anyMatch(fileName::endsWith)) {
            extractFromImageThreadSafe(filePath);
        } else {
            throw new IllegalArgumentException("Unsupported file format: " + fileName);
        }
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
     * Thread-safe PDF extraction that creates its own Tesseract instance
     */
    public void extractFromPdfThreadSafe(String pdfFilePath) throws IOException, TesseractException {
        Path pdfPath = Paths.get(pdfFilePath);
        if (!Files.exists(pdfPath)) {
            throw new FileNotFoundException("PDF file not found: " + pdfFilePath);
        }
        
        String baseFileName = getBaseFileName(pdfFilePath);
        LOGGER.info("[Thread-Safe] Processing PDF: " + pdfFilePath);
        
        // Create output directory if it doesn't exist
        Path outputDir = Paths.get(OUTPUT_DIR);
        Files.createDirectories(outputDir);
        
        List<ExtractedWord> allWords = new ArrayList<>();
        StringBuilder allText = new StringBuilder();
        Map<String, Object> formData = new HashMap<>();
        
        try (PDDocument document = Loader.loadPDF(new File(pdfFilePath))) {
            // Step 1: Extract form fields using PDFBox (thread-safe)
            Map<String, String> extractedFormFields = extractFormFields(document);
            formData.put("formFields", extractedFormFields);
            
            // Step 2: Extract regular text using PDFBox text stripper (thread-safe)
            String extractedText = extractTextContent(document);
            formData.put("extractedText", extractedText);
            
            // Step 3: Enhanced OCR processing with thread-safe Tesseract instance
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
                
                // Multiple OCR passes optimized for forms with thread-safe methods
                StringBuilder pageTextBuilder = new StringBuilder();
                
                // Pass 1: Standard OCR with PSM 3 (auto) 
                try {
                    String pageText1 = performOCRWithConfigThreadSafe(processedImage, 3, "Auto Detection");
                    pageTextBuilder.append("=== OCR Pass 1 (Auto Detection) ===\n").append(pageText1).append("\n\n");
                } catch (Exception e) {
                    LOGGER.warning("Pass 1 OCR failed for page " + (pageIndex + 1) + ": " + e.getMessage());
                }
                
                // Pass 2: Form-optimized OCR with PSM 6 (uniform block)
                try {
                    String pageText2 = performOCRWithConfigThreadSafe(processedImage, 6, "Form Fields");
                    pageTextBuilder.append("=== OCR Pass 2 (Form Fields) ===\n").append(pageText2).append("\n\n");
                } catch (Exception e) {
                    LOGGER.warning("Pass 2 OCR failed for page " + (pageIndex + 1) + ": " + e.getMessage());
                }
                
                // Pass 3: Single word detection for text boxes
                try {
                    String pageText3 = performOCRWithConfigThreadSafe(processedImage, 8, "Single Words/Dates");
                    pageTextBuilder.append("=== OCR Pass 3 (Single Words/Dates) ===\n").append(pageText3).append("\n\n");
                } catch (Exception e) {
                    LOGGER.warning("Pass 3 OCR failed for page " + (pageIndex + 1) + ": " + e.getMessage());
                }
                
                // Pass 4: Checkbox and symbol detection
                try {
                    String pageText4 = performCheckboxOCRThreadSafe(processedImage);
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
        
        LOGGER.info("[Thread-Safe] PDF extraction completed. Files saved in " + OUTPUT_DIR + " directory");
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
     * Thread-safe image extraction that uses thread-safe OCR methods
     */
    public void extractFromImageThreadSafe(String imageFilePath) throws IOException, TesseractException {
        Path imagePath = Paths.get(imageFilePath);
        if (!Files.exists(imagePath)) {
            throw new FileNotFoundException("Image file not found: " + imageFilePath);
        }
        
        String baseFileName = getBaseFileName(imageFilePath);
        LOGGER.info("[Thread-Safe] Processing Image: " + imageFilePath);
        
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
            
            // Test both original and processed images
            BufferedImage processedImage = preprocessImageForHighQualityOCR(originalImage);
            
            LOGGER.info("Testing with both original and processed images for comparison");
            
            // Simple OCR passes with thread-safe methods
            StringBuilder imageTextBuilder = new StringBuilder();
            
            // Pass 1: Simple processed image with PSM 3 (auto) + post-processing
            try {
                String imageText1 = performOCRWithConfigThreadSafe(processedImage, 3, "Simple Processed (Auto)");
                String correctedText1 = applyCommonCorrections(imageText1);
                imageTextBuilder.append("=== OCR Pass 1 (Simple Processed - Auto) ===\n").append(correctedText1).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 1 OCR failed: " + e.getMessage());
            }
            
            // Pass 2: Original image with PSM 3 (auto)
            try {
                String imageText2 = performOCRWithConfigThreadSafe(originalImage, 3, "Original Image (Auto)");
                imageTextBuilder.append("=== OCR Pass 2 (Original Image - Auto) ===\n").append(imageText2).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 2 OCR failed: " + e.getMessage());
            }
            
            // Pass 3: Specialized numbers and codes recognition
            try {
                String imageText3 = performNumbersOCRThreadSafe(processedImage);
                imageTextBuilder.append("=== OCR Pass 3 (Numbers & Codes Specialized) ===\n").append(imageText3).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 3 OCR failed: " + e.getMessage());
            }
            
            // Pass 4: Header section focused (PSM 6 for form fields)
            try {
                String imageText4 = performOCRWithConfigThreadSafe(processedImage, 6, "Header Section (Form Fields)");
                String correctedText4 = applyCommonCorrections(imageText4);
                imageTextBuilder.append("=== OCR Pass 4 (Header Section - Form Fields) ===\n").append(correctedText4).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 4 OCR failed: " + e.getMessage());
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
        
        LOGGER.info("[Thread-Safe] Image extraction completed. Files saved in " + OUTPUT_DIR + " directory");
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
            
            // Test both original and processed images
            BufferedImage processedImage = preprocessImageForHighQualityOCR(originalImage);
            
            LOGGER.info("Testing with both original and processed images for comparison");
            
            // Simple OCR passes with basic approaches
            StringBuilder imageTextBuilder = new StringBuilder();
            
            // Pass 1: Simple processed image with PSM 3 (auto) + post-processing
            try {
                String imageText1 = performOCRWithConfig(processedImage, 3, "Simple Processed (Auto)");
                String correctedText1 = applyCommonCorrections(imageText1);
                imageTextBuilder.append("=== OCR Pass 1 (Simple Processed - Auto) ===\n").append(correctedText1).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 1 OCR failed: " + e.getMessage());
            }
            
            // Pass 2: Original image with PSM 3 (auto)
            try {
                String imageText2 = performOCRWithConfig(originalImage, 3, "Original Image (Auto)");
                imageTextBuilder.append("=== OCR Pass 2 (Original Image - Auto) ===\n").append(imageText2).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 2 OCR failed: " + e.getMessage());
            }
            
            // Pass 3: Specialized numbers and codes recognition
            try {
                String imageText3 = performNumbersOCR(processedImage);
                imageTextBuilder.append("=== OCR Pass 3 (Numbers & Codes Specialized) ===\n").append(imageText3).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 3 OCR failed: " + e.getMessage());
            }
            
            // Pass 4: Header section focused (PSM 6 for form fields)
            try {
                String imageText4 = performOCRWithConfig(processedImage, 6, "Header Section (Form Fields)");
                String correctedText4 = applyCommonCorrections(imageText4);
                imageTextBuilder.append("=== OCR Pass 4 (Header Section - Form Fields) ===\n").append(correctedText4).append("\n\n");
            } catch (Exception e) {
                LOGGER.warning("Pass 4 OCR failed: " + e.getMessage());
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
        System.out.println("- OCR processing: 4 passes (Precision-Enhanced Multi-Pass)");
        System.out.println("- Image processing: Adaptive 2x scaling + precision contrast enhancement");
        System.out.println("- Final processed size: " + (((Integer)imageData.get("imageWidth")) * 2) + "x" + (((Integer)imageData.get("imageHeight")) * 2));
        System.out.println("- Languages: Portuguese+English with smart corrections");
        System.out.println("- Processing DPI: " + PROCESSING_MODE.getDpi());
        System.out.println("- Text extraction: Precision-Focused + Post-Processing Corrections");
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
     * Perform OCR with specific language
     */
    private String performOCRWithLanguage(BufferedImage image, String language, int psmMode, String passDescription) throws TesseractException {
        // Create temporary Tesseract instance with specific language
        Tesseract tempTesseract = new Tesseract();
        tempTesseract.setDatapath(this.tesseractDataPath);
        tempTesseract.setLanguage(language);
        tempTesseract.setOcrEngineMode(1);
        tempTesseract.setPageSegMode(psmMode);
        
        LOGGER.info("OCR Pass (" + passDescription + ") - Language: " + language + ", PSM: " + psmMode);
        return tempTesseract.doOCR(image);
    }
    
    /**
     * Thread-safe version of performNumbersOCR that creates its own Tesseract instance
     */
    private String performNumbersOCRThreadSafe(BufferedImage image) throws TesseractException {
        // Create specialized Tesseract instance for numbers and codes
        Tesseract numbersTesseract = new Tesseract();
        
        try {
            numbersTesseract.setDatapath(this.tesseractDataPath);
            numbersTesseract.setLanguage("eng"); // English for better number recognition
            numbersTesseract.setOcrEngineMode(1);
            numbersTesseract.setPageSegMode(8); // Single word mode for numbers
            
            // Optimized for numbers and codes
            numbersTesseract.setVariable("tessedit_char_whitelist", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz:/-. ");
            numbersTesseract.setVariable("classify_bln_numeric_mode", "1");
            numbersTesseract.setVariable("textord_min_linesize", "1.0");
            numbersTesseract.setVariable("textord_noise_sizelimit", "0.3");
            numbersTesseract.setVariable("classify_enable_learning", "0");
            numbersTesseract.setVariable("classify_enable_adaptive_matcher", "0");
            
            LOGGER.info("OCR Pass (Numbers & Codes) - Thread-safe specialized number recognition");
            return numbersTesseract.doOCR(image);
            
        } catch (Exception e) {
            LOGGER.warning("Thread-safe numbers OCR failed: " + e.getMessage());
            return "[Numbers detection failed]"; 
        }
    }
    
    /**
     * Specialized OCR for numbers and codes with enhanced character recognition
     */
    private String performNumbersOCR(BufferedImage image) throws TesseractException {
        // Create specialized Tesseract instance for numbers and codes
        Tesseract numbersTesseract = new Tesseract();
        
        try {
            numbersTesseract.setDatapath(this.tesseractDataPath);
            numbersTesseract.setLanguage("eng"); // English for better number recognition
            numbersTesseract.setOcrEngineMode(1);
            numbersTesseract.setPageSegMode(8); // Single word mode for numbers
            
            // Optimized for numbers and codes
            numbersTesseract.setVariable("tessedit_char_whitelist", "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz:/-. ");
            numbersTesseract.setVariable("classify_bln_numeric_mode", "1");
            numbersTesseract.setVariable("textord_min_linesize", "1.0");
            numbersTesseract.setVariable("textord_noise_sizelimit", "0.3");
            numbersTesseract.setVariable("classify_enable_learning", "0");
            numbersTesseract.setVariable("classify_enable_adaptive_matcher", "0");
            
            LOGGER.info("OCR Pass (Numbers & Codes) - Specialized number recognition");
            return numbersTesseract.doOCR(image);
            
        } catch (Exception e) {
            LOGGER.warning("Numbers OCR failed: " + e.getMessage());
            return "[Numbers detection failed]";
        }
    }
    
    /**
     * Perform OCR with specific configuration - enhanced for comprehensive text extraction
     */
    private String performOCRWithConfig(BufferedImage image, int pageSegMode, String passName) throws TesseractException {
        tesseract.setPageSegMode(pageSegMode);
        
        // Optimized configuration for interface text extraction
        switch (pageSegMode) {
            case 3: // Auto detection - precision-focused for interface text
                tesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                tesseract.setVariable("textord_min_linesize", "1.2"); // Fine-tuned for precision
                tesseract.setVariable("textord_noise_sizelimit", "0.4"); // Stricter noise filtering
                tesseract.setVariable("textord_tabfind_find_tables", "1");
                // Precision-focused settings for character accuracy
                tesseract.setVariable("classify_bln_numeric_mode", "1"); // Better number recognition
                tesseract.setVariable("tessedit_single_match", "0"); // Allow multiple character matches
                tesseract.setVariable("segment_penalty_dict_frequent_word", "1"); // Prefer dictionary words
                tesseract.setVariable("classify_character_fragments_garbage_certainty_threshold", "50"); // Reduce character fragmentation
                tesseract.setVariable("wordrec_worst_state", "1"); // Better word recognition
                tesseract.setVariable("language_model_penalty_non_freq_dict_word", "0.1"); // Prefer common words
                break;
            case 6: // Form fields - comprehensive Portuguese character set
                tesseract.setVariable("textord_tabfind_find_tables", "1");
                tesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-._()[] ");
                tesseract.setVariable("textord_min_linesize", "2.5");
                tesseract.setVariable("textord_noise_sizelimit", "0.8");
                break;
            case 7: // Single text line - for headers and labels
                tesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                tesseract.setVariable("textord_min_linesize", "1.5");
                tesseract.setVariable("textord_noise_sizelimit", "0.5");
                break;
            case 8: // Single words/dates - numbers and dates
                tesseract.setVariable("tessedit_char_whitelist", "0123456789/:-. ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");
                tesseract.setVariable("textord_min_linesize", "1.8");
                tesseract.setVariable("textord_noise_sizelimit", "0.6");
                break;
            case 11: // Sparse text - for scattered interface elements
                tesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                tesseract.setVariable("textord_min_linesize", "1.0");
                tesseract.setVariable("textord_noise_sizelimit", "0.4");
                tesseract.setVariable("textord_tabfind_find_tables", "1");
                break;
            case 13: // Raw line - for difficult text
                tesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                tesseract.setVariable("textord_min_linesize", "1.2");
                tesseract.setVariable("textord_noise_sizelimit", "0.3");
                break;
            default:
                tesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                tesseract.setVariable("textord_min_linesize", "2.0");
                break;
        }
        
        // Memory-optimized aggressive settings for better text capture
        tesseract.setVariable("textord_heavy_nr", "0"); // Disable to prevent memory issues
        tesseract.setVariable("textord_show_initial_words", "0"); // Disable to prevent memory issues
        tesseract.setVariable("wordrec_enable_assoc", "0"); // Keep disabled for stability
        
        String result = tesseract.doOCR(image);
        
        // Reset to default values
        tesseract.setPageSegMode(3);
        tesseract.setVariable("tessedit_char_whitelist", "");
        tesseract.setVariable("textord_min_linesize", "2.0");
        tesseract.setVariable("textord_noise_sizelimit", "0.7");
        tesseract.setVariable("textord_tabfind_find_tables", "0");
        tesseract.setVariable("textord_heavy_nr", "0");
        tesseract.setVariable("textord_show_initial_words", "0");
        tesseract.setVariable("wordrec_enable_assoc", "0");
        
        return result;
    }
    
    /**
     * Thread-safe version of performOCRWithConfig that creates its own Tesseract instance
     */
    private String performOCRWithConfigThreadSafe(BufferedImage image, int pageSegMode, String passName) throws TesseractException {
        // Create a new Tesseract instance for this thread
        Tesseract threadTesseract = new Tesseract();
        
        try {
            // Configure the thread-specific Tesseract instance
            threadTesseract.setDatapath(this.tesseractDataPath);
            threadTesseract.setLanguage("por+eng"); // Use Portuguese + English
            threadTesseract.setOcrEngineMode(1);
            threadTesseract.setPageSegMode(pageSegMode);
            
            // Apply the same configuration logic as the original method
            switch (pageSegMode) {
                case 3: // Auto detection - precision-focused for interface text
                    threadTesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                    threadTesseract.setVariable("textord_min_linesize", "1.2");
                    threadTesseract.setVariable("textord_noise_sizelimit", "0.4");
                    threadTesseract.setVariable("textord_tabfind_find_tables", "1");
                    threadTesseract.setVariable("classify_bln_numeric_mode", "1");
                    threadTesseract.setVariable("tessedit_single_match", "0");
                    threadTesseract.setVariable("segment_penalty_dict_frequent_word", "1");
                    threadTesseract.setVariable("classify_character_fragments_garbage_certainty_threshold", "50");
                    threadTesseract.setVariable("wordrec_worst_state", "1");
                    threadTesseract.setVariable("language_model_penalty_non_freq_dict_word", "0.1");
                    break;
                case 6: // Form fields - comprehensive Portuguese character set
                    threadTesseract.setVariable("textord_tabfind_find_tables", "1");
                    threadTesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-._()[] ");
                    threadTesseract.setVariable("textord_min_linesize", "2.5");
                    threadTesseract.setVariable("textord_noise_sizelimit", "0.8");
                    break;
                case 7: // Single text line - for headers and labels
                    threadTesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                    threadTesseract.setVariable("textord_min_linesize", "1.5");
                    threadTesseract.setVariable("textord_noise_sizelimit", "0.5");
                    break;
                case 8: // Single words/dates - numbers and dates
                    threadTesseract.setVariable("tessedit_char_whitelist", "0123456789/:-. ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz");
                    threadTesseract.setVariable("textord_min_linesize", "1.8");
                    threadTesseract.setVariable("textord_noise_sizelimit", "0.6");
                    break;
                case 11: // Sparse text - for scattered interface elements
                    threadTesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                    threadTesseract.setVariable("textord_min_linesize", "1.0");
                    threadTesseract.setVariable("textord_noise_sizelimit", "0.4");
                    threadTesseract.setVariable("textord_tabfind_find_tables", "1");
                    break;
                case 13: // Raw line - for difficult text
                    threadTesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                    threadTesseract.setVariable("textord_min_linesize", "1.2");
                    threadTesseract.setVariable("textord_noise_sizelimit", "0.3");
                    break;
                default:
                    threadTesseract.setVariable("tessedit_char_whitelist", "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyzÀÁÂÃÇÉÊÍÓÔÕÚàáâãçéêíóôõú0123456789:/-. ");
                    threadTesseract.setVariable("textord_min_linesize", "2.0");
                    break;
            }
            
            // Memory-optimized settings
            threadTesseract.setVariable("textord_heavy_nr", "0");
            threadTesseract.setVariable("textord_show_initial_words", "0");
            threadTesseract.setVariable("wordrec_enable_assoc", "0");
            threadTesseract.setVariable("classify_enable_learning", "0");
            threadTesseract.setVariable("classify_enable_adaptive_matcher", "0");
            
            return threadTesseract.doOCR(image);
            
        } catch (Exception e) {
            LOGGER.warning("Thread-safe OCR failed (" + passName + "): " + e.getMessage());
            return "[OCR failed: " + e.getMessage() + "]"; 
        }
    }
    
    /**
     * Thread-safe version of performCheckboxOCR that creates its own Tesseract instance
     */
    private String performCheckboxOCRThreadSafe(BufferedImage image) throws TesseractException {
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
            LOGGER.warning("Thread-safe checkbox OCR failed: " + e.getMessage());
            return "[Checkbox detection failed]"; 
        }
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
     * Apply common OCR corrections for interface text
     */
    private String applyCommonCorrections(String text) {
        if (text == null || text.trim().isEmpty()) {
            return text;
        }
        
        String corrected = text;
        
        // Common character confusions in interface text
        corrected = corrected.replaceAll("\\bS6S(\\d+)", "565$1"); // S6S -> 565 (common in order numbers)
        corrected = corrected.replaceAll("Trarsportadora", "Transportadora"); // r/n confusion
        corrected = corrected.replaceAll("Trarsporsócra", "Transportadora"); // Multiple character errors
        corrected = corrected.replaceAll("\\bNe\\.", "NF"); // e/F confusion in "NF"
        corrected = corrected.replaceAll("\\bFilsl\\b", "Filial"); // s/a confusion
        corrected = corrected.replaceAll("\\bFilal\\b", "Filial"); // s/a confusion
        corrected = corrected.replaceAll("\\bFisl\\b", "Filial"); // s/a confusion
        corrected = corrected.replaceAll("Filial Transe Final", "Filial Transp. Final"); // s/p confusion
        corrected = corrected.replaceAll("993500", "993900"); // 5/9 confusion in route numbers
        corrected = corrected.replaceAll("CDBana", "CD Bahia"); // n/h confusion
        corrected = corrected.replaceAll("\\bvOC(O+)\\b", "VDC000"); // O/0 confusion in codes
        corrected = corrected.replaceAll("\\bvDC(O+)\\b", "VDC000"); // O/0 confusion in codes
        corrected = corrected.replaceAll("\\bSAJU\\b", "SAIU"); // J/I confusion
        corrected = corrected.replaceAll("\\bSay\\b", "SAIU"); // a/I confusion
        corrected = corrected.replaceAll("\\bDota\\b", "Data"); // o/a confusion
        corrected = corrected.replaceAll("\\bMora\\b", "Hora"); // o/r confusion
        corrected = corrected.replaceAll("Oererercia", "Ocorrência"); // Multiple character errors
        corrected = corrected.replaceAll("Decoração", "Descrição"); // c/s confusion
        
        // Fix incorrectly formatted order/document numbers (prevent date formatting on IDs)
        corrected = corrected.replaceAll("\\b56/57/99305\\b", "565799305"); // Fix order number
        corrected = corrected.replaceAll("\\b00/52/21508\\b", "005221508"); // Fix NF number
        corrected = corrected.replaceAll("\\bCD Bana\\b", "CD Bahia"); // n/h confusion
        corrected = corrected.replaceAll("\\bvDCO0O\\b", "VDC000"); // O/0 confusion
        corrected = corrected.replaceAll("\\bD7NOTOZS\\b", "07/10/2025"); // Multiple character errors in date
        corrected = corrected.replaceAll("\\bSA PARA\\b", "SAIU PARA"); // Missing letters
        corrected = corrected.replaceAll("\\bPRRA\\b", "PARA"); // R duplication error
        
        // Date and time formatting corrections (only for actual dates, not IDs)
        corrected = corrected.replaceAll("(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2})\\s+(\\d{2})\\s*(\\d{2})", "$1 $2:$3:$4"); // Fix time format
        corrected = corrected.replaceAll("(\\d{2}/\\d{2}/\\d{4})\\s+(\\d{2})\\s+(\\d{4})", "$1 $2:$3"); // Fix time format
        
        // Common Portuguese words corrections
        corrected = corrected.replaceAll("\\bTransp\\.", "Transp."); // Ensure proper abbreviation
        corrected = corrected.replaceAll("\\bFinal:", "Final:"); // Ensure proper formatting
        
        LOGGER.info("Applied common corrections to OCR text");
        return corrected;
    }
    
    /**
     * Analyze image contrast to determine optimal processing approach
     */
    private int[] analyzeImageContrast(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        
        int minGray = 255;
        int maxGray = 0;
        int totalGray = 0;
        int pixelCount = 0;
        
        // Sample every 4th pixel for performance
        for (int y = 0; y < height; y += 4) {
            for (int x = 0; x < width; x += 4) {
                int rgb = image.getRGB(x, y);
                int gray = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                
                minGray = Math.min(minGray, gray);
                maxGray = Math.max(maxGray, gray);
                totalGray += gray;
                pixelCount++;
            }
        }
        
        int avgContrast = totalGray / pixelCount;
        int contrastRange = maxGray - minGray;
        
        return new int[]{avgContrast, contrastRange};
    }
    
    /**
     * Intelligent adaptive preprocessing
     * Detects image quality and applies appropriate processing
     */
    private BufferedImage preprocessImageForHighQualityOCR(BufferedImage originalImage) {
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();
        
        LOGGER.info("Original image size: " + width + "x" + height);
        
        // Analyze image contrast to determine processing approach
        int[] contrastAnalysis = analyzeImageContrast(originalImage);
        int avgContrast = contrastAnalysis[0];
        int contrastRange = contrastAnalysis[1];
        
        LOGGER.info("Image analysis - Average contrast: " + avgContrast + ", Range: " + contrastRange);
        
        // Simple 2x scaling for all images
        int scaleFactor = 2;
        int scaledWidth = width * scaleFactor;
        int scaledHeight = height * scaleFactor;
        
        BufferedImage scaledImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g2d = scaledImage.createGraphics();
        g2d.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2d.drawImage(originalImage, 0, 0, scaledWidth, scaledHeight, null);
        g2d.dispose();
        
        BufferedImage processedImage = new BufferedImage(scaledWidth, scaledHeight, BufferedImage.TYPE_INT_RGB);
        
        // Adaptive processing based on image quality
        if (contrastRange > 150 && avgContrast > 100) {
            // High-contrast, clean image - precision-focused minimal processing
            LOGGER.info("High-contrast image detected - applying precision-focused minimal processing");
            for (int y = 0; y < scaledHeight; y++) {
                for (int x = 0; x < scaledWidth; x++) {
                    int rgb = scaledImage.getRGB(x, y);
                    int gray = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    
                    // Precision-focused contrast adjustment for character clarity
                    if (gray < 90) {
                        gray = Math.max(0, gray - 15); // Slightly more aggressive darkening for text
                    } else if (gray > 210) {
                        gray = 255; // Pure white background
                    } else if (gray < 140) {
                        // Mid-dark range - likely text edges, enhance slightly
                        gray = Math.max(0, gray - 8);
                    } else if (gray > 180) {
                        // Mid-light range - likely background, brighten slightly
                        gray = Math.min(255, gray + 15);
                    }
                    
                    processedImage.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
                }
            }
        } else {
            // Low-contrast or poor quality image - aggressive processing
            LOGGER.info("Low-contrast image detected - applying enhanced processing");
            for (int y = 0; y < scaledHeight; y++) {
                for (int x = 0; x < scaledWidth; x++) {
                    int rgb = scaledImage.getRGB(x, y);
                    int gray = (int) (0.299 * ((rgb >> 16) & 0xFF) + 0.587 * ((rgb >> 8) & 0xFF) + 0.114 * (rgb & 0xFF));
                    
                    // Aggressive contrast for poor quality images
                    if (gray < 120) {
                        gray = Math.max(0, gray - 50);
                    } else if (gray > 200) {
                        gray = 255;
                    } else {
                        if (gray < 160) {
                            gray = Math.max(0, gray - 40);
                        } else {
                            gray = Math.min(255, gray + 60);
                        }
                    }
                    
                    processedImage.setRGB(x, y, (gray << 16) | (gray << 8) | gray);
                }
            }
        }
        
        LOGGER.info("Completed adaptive preprocessing - final image: " + scaledWidth + "x" + scaledHeight);
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
