package com.tesseract.wordextractor;

import net.sourceforge.tess4j.Tesseract;
import net.sourceforge.tess4j.TesseractException;
import net.sourceforge.tess4j.Word;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import java.util.logging.Level;

public class TesseractWordExtractor {
    private static final Logger LOGGER = Logger.getLogger(TesseractWordExtractor.class.getName());
    private static final String OUTPUT_DIR = "output";
    private static final int DPI = 300; // High DPI for better OCR accuracy
    
    private final Tesseract tesseract;
    private final ObjectMapper objectMapper;
    
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
            
            // Set Page Segmentation Mode to auto
            tesseract.setPageSegMode(3);
            
            // Configure for better UTF-8 and special character handling
            tesseract.setVariable("tessedit_char_whitelist", "");
            tesseract.setVariable("tessedit_char_blacklist", "");
            tesseract.setVariable("preserve_interword_spaces", "1");
            
            // Enable better handling of accented characters
            tesseract.setVariable("textord_really_old_xheight", "1");
            tesseract.setVariable("segment_penalty_dict_frequent_word", "1");
            tesseract.setVariable("allow_blob_division", "1");
            tesseract.setVariable("wordrec_enable_assoc", "1");
            
            LOGGER.info("Tesseract initialized successfully");
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to initialize Tesseract", e);
            throw new RuntimeException("Tesseract initialization failed", e);
        }
    }
    
    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Usage: java TesseractWordExtractor <pdf-file-path>");
            System.out.println("Example: java TesseractWordExtractor \"PDF for Automation Testing.pdf\"");
            return;
        }
        
        String pdfFilePath = args[0];
        TesseractWordExtractor extractor = new TesseractWordExtractor();
        
        try {
            extractor.extractWordsFromPdf(pdfFilePath);
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error processing PDF: " + pdfFilePath, e);
            System.err.println("Error processing PDF: " + e.getMessage());
        }
    }
    
    public void extractWordsFromPdf(String pdfFilePath) throws IOException, TesseractException {
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
        
        try (PDDocument document = Loader.loadPDF(new File(pdfFilePath))) {
            PDFRenderer pdfRenderer = new PDFRenderer(document);
            int numberOfPages = document.getNumberOfPages();
            
            LOGGER.info("PDF has " + numberOfPages + " pages");
            
            for (int pageIndex = 0; pageIndex < numberOfPages; pageIndex++) {
                LOGGER.info("Processing page " + (pageIndex + 1) + " of " + numberOfPages);
                
                // Convert PDF page to image with high quality for better OCR
                BufferedImage image = pdfRenderer.renderImageWithDPI(pageIndex, DPI, ImageType.RGB);
                
                // Preprocess image for better special character recognition
                BufferedImage processedImage = preprocessImageForOCR(image);
                
                // Extract text from the processed image
                String pageText = tesseract.doOCR(processedImage);
                allText.append(pageText).append("\n\n");
                
                // Extract words with coordinates using processed image
                List<Word> words = tesseract.getWords(processedImage, 3); // Page segmentation mode 3
                
                for (Word word : words) {
                    ExtractedWord extractedWord = new ExtractedWord(
                        word.getText().trim(),
                        pageIndex + 1,
                        word.getBoundingBox().x,
                        word.getBoundingBox().y,
                        word.getBoundingBox().width,
                        word.getBoundingBox().height,
                        word.getConfidence()
                    );
                    
                    // Only add non-empty words
                    if (!extractedWord.getText().isEmpty()) {
                        allWords.add(extractedWord);
                    }
                }
            }
        }
        
        // Generate outputs
        generateTextOutput(baseFileName, allText.toString());
        generateJsonOutput(baseFileName, allWords);
        
        LOGGER.info("Extraction completed. Files saved in " + OUTPUT_DIR + " directory");
        System.out.println("Extraction completed successfully!");
        System.out.println("Total words extracted: " + allWords.size());
        System.out.println("Output files:");
        System.out.println("- " + OUTPUT_DIR + "/" + baseFileName + ".txt");
        System.out.println("- " + OUTPUT_DIR + "/" + baseFileName + ".json");
    }
    
    private void generateTextOutput(String baseFileName, String text) throws IOException {
        Path textFile = Paths.get(OUTPUT_DIR, baseFileName + ".txt");
        
        try (BufferedWriter writer = Files.newBufferedWriter(textFile, StandardCharsets.UTF_8)) {
            writer.write(text);
        }
        
        LOGGER.info("Text output saved to: " + textFile);
    }
    
    private void generateJsonOutput(String baseFileName, List<ExtractedWord> words) throws IOException {
        Path jsonFile = Paths.get(OUTPUT_DIR, baseFileName + ".json");
        
        ObjectNode rootNode = objectMapper.createObjectNode();
        rootNode.put("totalWords", words.size());
        rootNode.put("extractionTimestamp", System.currentTimeMillis());
        
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
        
        try (BufferedWriter writer = Files.newBufferedWriter(jsonFile, StandardCharsets.UTF_8)) {
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(writer, rootNode);
        }
        
        LOGGER.info("JSON output saved to: " + jsonFile);
    }
    
    private String getBaseFileName(String filePath) {
        Path path = Paths.get(filePath);
        String fileName = path.getFileName().toString();
        int lastDotIndex = fileName.lastIndexOf('.');
        return lastDotIndex > 0 ? fileName.substring(0, lastDotIndex) : fileName;
    }
    
    /**
     * Preprocesses the image to improve OCR accuracy for special characters
     * @param originalImage The original BufferedImage from PDF
     * @return Processed BufferedImage optimized for OCR
     */
    private BufferedImage preprocessImageForOCR(BufferedImage originalImage) {
        // Create a new image with better contrast and sharpness
        int width = originalImage.getWidth();
        int height = originalImage.getHeight();
        
        // Create a new RGB image
        BufferedImage processedImage = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        
        // Apply contrast enhancement and noise reduction
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int rgb = originalImage.getRGB(x, y);
                
                // Extract RGB components
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                
                // Convert to grayscale for better text recognition
                int gray = (int) (0.299 * red + 0.587 * green + 0.114 * blue);
                
                // Apply contrast enhancement
                gray = Math.min(255, Math.max(0, (int) (1.2 * (gray - 128) + 128)));
                
                // Create enhanced RGB value
                int enhancedRgb = (gray << 16) | (gray << 8) | gray;
                processedImage.setRGB(x, y, enhancedRgb);
            }
        }
        
        return processedImage;
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
