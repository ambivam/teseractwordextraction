package com.tesseract.wordextractor;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * FileGroupingManager - Analyzes text files from output folder and groups files with similar content
 * into organized folder structures based on common data patterns.
 */
public class FileGroupingManager {
    private static final Logger LOGGER = Logger.getLogger(FileGroupingManager.class.getName());
    
    // Configuration constants
    private static final String OUTPUT_DIR = "output";
    private static final String GROUPING_DIR = "grouping";
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.7;
    private static final int MIN_COMMON_KEYWORDS = 3;
    
    // File analysis patterns
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{1,2}[/\\-]\\d{1,2}[/\\-]\\d{2,4}\\b|\\b\\d{4}[/\\-]\\d{1,2}[/\\-]\\d{1,2}\\b");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("\\b\\d+[.,]\\d{2}\\b|R\\$\\s*\\d+[.,]\\d{2}");
    private static final Pattern ID_PATTERN = Pattern.compile("\\b\\d{8,}\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b\\(?\\d{2,3}\\)?[\\s\\-]?\\d{4,5}[\\s\\-]?\\d{4}\\b");
    
    // Document type keywords
    private static final Map<String, Set<String>> DOCUMENT_TYPE_KEYWORDS = new HashMap<>();
    static {
        DOCUMENT_TYPE_KEYWORDS.put("PEDIDOS", Set.of("pedido", "pedidos", "compra", "compras", "produto", "quantidade", "preço", "total"));
        DOCUMENT_TYPE_KEYWORDS.put("PAGAMENTOS", Set.of("pagamento", "cartão", "crédito", "débito", "pix", "transferência", "cobrança"));
        DOCUMENT_TYPE_KEYWORDS.put("USUARIOS", Set.of("usuário", "conta", "perfil", "endereço", "informações", "dados", "cadastro"));
        DOCUMENT_TYPE_KEYWORDS.put("COMERCIANTE", Set.of("comerciante", "loja", "vendedor", "merchant", "natura", "empresa"));
        DOCUMENT_TYPE_KEYWORDS.put("TRANSACOES", Set.of("transação", "operação", "histórico", "movimentação", "extrato"));
        DOCUMENT_TYPE_KEYWORDS.put("CUPONS", Set.of("cupom", "desconto", "promoção", "oferta", "voucher"));
    }
    
    /**
     * Represents a file with its content analysis
     */
    public static class FileAnalysis {
        private final String fileName;
        private final String filePath;
        private final String content;
        private final Set<String> keywords;
        private final Set<String> emails;
        private final Set<String> dates;
        private final Set<String> currencies;
        private final Set<String> ids;
        private final String documentType;
        private final double contentLength;
        
        public FileAnalysis(String fileName, String filePath, String content) {
            this.fileName = fileName;
            this.filePath = filePath;
            this.content = content.toLowerCase();
            this.keywords = extractKeywords(this.content);
            this.emails = extractPatterns(EMAIL_PATTERN, content);
            this.dates = extractPatterns(DATE_PATTERN, content);
            this.currencies = extractPatterns(CURRENCY_PATTERN, content);
            this.ids = extractPatterns(ID_PATTERN, content);
            this.documentType = determineDocumentType(this.content, this.keywords);
            this.contentLength = content.length();
        }
        
        private Set<String> extractKeywords(String content) {
            return Arrays.stream(content.split("\\W+"))
                    .filter(word -> word.length() > 3)
                    .filter(word -> !isCommonWord(word))
                    .collect(Collectors.toSet());
        }
        
        private Set<String> extractPatterns(Pattern pattern, String content) {
            Set<String> matches = new HashSet<>();
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                matches.add(matcher.group().trim());
            }
            return matches;
        }
        
        private String determineDocumentType(String content, Set<String> keywords) {
            Map<String, Integer> typeScores = new HashMap<>();
            
            for (Map.Entry<String, Set<String>> entry : DOCUMENT_TYPE_KEYWORDS.entrySet()) {
                String type = entry.getKey();
                Set<String> typeKeywords = entry.getValue();
                
                int score = 0;
                for (String keyword : typeKeywords) {
                    if (content.contains(keyword)) {
                        score += 2; // Higher weight for exact matches
                    }
                }
                
                // Check keywords intersection
                Set<String> intersection = new HashSet<>(keywords);
                intersection.retainAll(typeKeywords);
                score += intersection.size();
                
                if (score > 0) {
                    typeScores.put(type, score);
                }
            }
            
            return typeScores.entrySet().stream()
                    .max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey)
                    .orElse("OUTROS");
        }
        
        private boolean isCommonWord(String word) {
            Set<String> commonWords = Set.of("the", "and", "for", "are", "but", "not", "you", "all", "can", "had", "her", "was", "one", "our", "out", "day", "get", "has", "him", "his", "how", "man", "new", "now", "old", "see", "two", "way", "who", "boy", "did", "its", "let", "put", "say", "she", "too", "use",
                    "que", "para", "com", "uma", "por", "não", "dos", "das", "como", "mais", "seu", "sua", "tem", "foi", "são", "pelo", "pela", "nos", "nas", "aos", "às", "isso", "esta", "este", "essa", "esse", "aquela", "aquele", "muito", "bem", "onde", "quando", "porque", "então", "assim", "também", "ainda", "já", "só", "até", "depois", "antes", "sobre", "entre", "sem", "contra", "durante");
            return commonWords.contains(word.toLowerCase());
        }
        
        // Getters
        public String getFileName() { return fileName; }
        public String getFilePath() { return filePath; }
        public String getContent() { return content; }
        public Set<String> getKeywords() { return keywords; }
        public Set<String> getEmails() { return emails; }
        public Set<String> getDates() { return dates; }
        public Set<String> getCurrencies() { return currencies; }
        public Set<String> getIds() { return ids; }
        public String getDocumentType() { return documentType; }
        public double getContentLength() { return contentLength; }
    }
    
    /**
     * Represents a group of similar files
     */
    public static class FileGroup {
        private final String groupName;
        private final String groupType;
        private final List<FileAnalysis> files;
        private final Set<String> commonKeywords;
        private final Set<String> commonEmails;
        private final Set<String> commonIds;
        
        public FileGroup(String groupName, String groupType) {
            this.groupName = groupName;
            this.groupType = groupType;
            this.files = new ArrayList<>();
            this.commonKeywords = new HashSet<>();
            this.commonEmails = new HashSet<>();
            this.commonIds = new HashSet<>();
        }
        
        public void addFile(FileAnalysis file) {
            files.add(file);
            updateCommonElements();
        }
        
        private void updateCommonElements() {
            if (files.isEmpty()) return;
            
            // Find common keywords
            commonKeywords.clear();
            commonKeywords.addAll(files.get(0).getKeywords());
            for (int i = 1; i < files.size(); i++) {
                commonKeywords.retainAll(files.get(i).getKeywords());
            }
            
            // Find common emails
            commonEmails.clear();
            commonEmails.addAll(files.get(0).getEmails());
            for (int i = 1; i < files.size(); i++) {
                commonEmails.retainAll(files.get(i).getEmails());
            }
            
            // Find common IDs
            commonIds.clear();
            commonIds.addAll(files.get(0).getIds());
            for (int i = 1; i < files.size(); i++) {
                commonIds.retainAll(files.get(i).getIds());
            }
        }
        
        // Getters
        public String getGroupName() { return groupName; }
        public String getGroupType() { return groupType; }
        public List<FileAnalysis> getFiles() { return files; }
        public Set<String> getCommonKeywords() { return commonKeywords; }
        public Set<String> getCommonEmails() { return commonEmails; }
        public Set<String> getCommonIds() { return commonIds; }
        public int getFileCount() { return files.size(); }
    }
    
    private double similarityThreshold;
    
    public FileGroupingManager() {
        this.similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;
    }
    
    public FileGroupingManager(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }
    
    /**
     * Main method to analyze and group files
     */
    public void analyzeAndGroupFiles() throws IOException {
        LOGGER.info("Starting file analysis and grouping process...");
        
        // Step 1: Read and analyze all text files
        List<FileAnalysis> fileAnalyses = analyzeOutputFiles();
        
        if (fileAnalyses.isEmpty()) {
            LOGGER.warning("No text files found in output directory");
            return;
        }
        
        LOGGER.info("Analyzed " + fileAnalyses.size() + " files");
        
        // Step 2: Group files based on similarity
        List<FileGroup> groups = groupSimilarFiles(fileAnalyses);
        
        LOGGER.info("Created " + groups.size() + " file groups");
        
        // Step 3: Create folder structure and organize files
        createGroupingStructure(groups);
        
        // Step 4: Generate summary report
        generateGroupingSummary(groups);
        
        LOGGER.info("File grouping process completed successfully");
    }
    
    /**
     * Analyze all text files in the output directory
     */
    private List<FileAnalysis> analyzeOutputFiles() throws IOException {
        List<FileAnalysis> analyses = new ArrayList<>();
        Path outputPath = Paths.get(OUTPUT_DIR);
        
        if (!Files.exists(outputPath)) {
            LOGGER.warning("Output directory does not exist: " + OUTPUT_DIR);
            return analyses;
        }
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputPath, "*.txt")) {
            for (Path filePath : stream) {
                try {
                    String content = Files.readString(filePath);
                    String fileName = filePath.getFileName().toString();
                    
                    FileAnalysis analysis = new FileAnalysis(fileName, filePath.toString(), content);
                    analyses.add(analysis);
                    
                    LOGGER.info("Analyzed file: " + fileName + " (Type: " + analysis.getDocumentType() + ")");
                    
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error analyzing file: " + filePath, e);
                }
            }
        }
        
        return analyses;
    }
    
    /**
     * Group files based on similarity analysis
     */
    private List<FileGroup> groupSimilarFiles(List<FileAnalysis> fileAnalyses) {
        Map<String, FileGroup> groups = new HashMap<>();
        Map<String, List<FileAnalysis>> typeGroups = new HashMap<>();
        
        // First, group by document type
        for (FileAnalysis analysis : fileAnalyses) {
            typeGroups.computeIfAbsent(analysis.getDocumentType(), k -> new ArrayList<>()).add(analysis);
        }
        
        // Then, within each type, find similar files
        for (Map.Entry<String, List<FileAnalysis>> entry : typeGroups.entrySet()) {
            String docType = entry.getKey();
            List<FileAnalysis> typeFiles = entry.getValue();
            
            if (typeFiles.size() == 1) {
                // Single file group
                FileGroup group = new FileGroup(docType + "_SINGLE", docType);
                group.addFile(typeFiles.get(0));
                groups.put(group.getGroupName(), group);
            } else {
                // Multiple files - find similarity clusters
                List<FileGroup> similarityGroups = findSimilarityClusters(typeFiles, docType);
                for (FileGroup group : similarityGroups) {
                    groups.put(group.getGroupName(), group);
                }
            }
        }
        
        return new ArrayList<>(groups.values());
    }
    
    /**
     * Find similarity clusters within files of the same type
     */
    private List<FileGroup> findSimilarityClusters(List<FileAnalysis> files, String docType) {
        List<FileGroup> clusters = new ArrayList<>();
        List<FileAnalysis> unprocessed = new ArrayList<>(files);
        int groupCounter = 1;
        
        while (!unprocessed.isEmpty()) {
            FileAnalysis seed = unprocessed.remove(0);
            FileGroup cluster = new FileGroup(docType + "_GROUP_" + groupCounter++, docType);
            cluster.addFile(seed);
            
            // Find similar files to the seed
            Iterator<FileAnalysis> iterator = unprocessed.iterator();
            while (iterator.hasNext()) {
                FileAnalysis candidate = iterator.next();
                if (calculateSimilarity(seed, candidate) >= similarityThreshold) {
                    cluster.addFile(candidate);
                    iterator.remove();
                }
            }
            
            clusters.add(cluster);
        }
        
        return clusters;
    }
    
    /**
     * Calculate similarity between two file analyses
     */
    private double calculateSimilarity(FileAnalysis file1, FileAnalysis file2) {
        double keywordSimilarity = calculateJaccardSimilarity(file1.getKeywords(), file2.getKeywords());
        double emailSimilarity = calculateJaccardSimilarity(file1.getEmails(), file2.getEmails());
        double idSimilarity = calculateJaccardSimilarity(file1.getIds(), file2.getIds());
        
        // Weighted similarity calculation
        double similarity = (keywordSimilarity * 0.5) + (emailSimilarity * 0.3) + (idSimilarity * 0.2);
        
        // Bonus for same document type
        if (file1.getDocumentType().equals(file2.getDocumentType())) {
            similarity += 0.1;
        }
        
        return Math.min(1.0, similarity);
    }
    
    /**
     * Calculate Jaccard similarity between two sets
     */
    private double calculateJaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) {
            return 1.0;
        }
        
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    
    /**
     * Create the grouping folder structure and copy files
     */
    private void createGroupingStructure(List<FileGroup> groups) throws IOException {
        Path groupingPath = Paths.get(GROUPING_DIR);
        
        // Clean and create grouping directory
        if (Files.exists(groupingPath)) {
            deleteDirectoryRecursively(groupingPath);
        }
        Files.createDirectories(groupingPath);
        
        for (FileGroup group : groups) {
            // Create group directory
            Path groupDir = groupingPath.resolve(group.getGroupName());
            Files.createDirectories(groupDir);
            
            // Copy files to group directory
            for (FileAnalysis file : group.getFiles()) {
                Path sourcePath = Paths.get(file.getFilePath());
                Path targetPath = groupDir.resolve(file.getFileName());
                
                try {
                    Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                    
                    // Also copy corresponding JSON file if exists
                    String jsonFileName = file.getFileName().replace("_comprehensive.txt", "_comprehensive.json");
                    Path jsonSourcePath = sourcePath.getParent().resolve(jsonFileName);
                    if (Files.exists(jsonSourcePath)) {
                        Path jsonTargetPath = groupDir.resolve(jsonFileName);
                        Files.copy(jsonSourcePath, jsonTargetPath, StandardCopyOption.REPLACE_EXISTING);
                    }
                    
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Error copying file: " + file.getFileName(), e);
                }
            }
            
            // Create group summary file
            createGroupSummary(groupDir, group);
            
            LOGGER.info("Created group: " + group.getGroupName() + " with " + group.getFileCount() + " files");
        }
    }
    
    /**
     * Create a summary file for each group
     */
    private void createGroupSummary(Path groupDir, FileGroup group) throws IOException {
        Path summaryPath = groupDir.resolve("GROUP_SUMMARY.txt");
        
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(summaryPath))) {
            writer.println("=== FILE GROUP SUMMARY ===");
            writer.println("Group Name: " + group.getGroupName());
            writer.println("Group Type: " + group.getGroupType());
            writer.println("File Count: " + group.getFileCount());
            writer.println("Generated: " + new Date());
            writer.println();
            
            writer.println("=== FILES IN GROUP ===");
            for (FileAnalysis file : group.getFiles()) {
                writer.println("- " + file.getFileName());
            }
            writer.println();
            
            if (!group.getCommonKeywords().isEmpty()) {
                writer.println("=== COMMON KEYWORDS ===");
                group.getCommonKeywords().stream().sorted().forEach(keyword -> writer.println("- " + keyword));
                writer.println();
            }
            
            if (!group.getCommonEmails().isEmpty()) {
                writer.println("=== COMMON EMAILS ===");
                group.getCommonEmails().stream().sorted().forEach(email -> writer.println("- " + email));
                writer.println();
            }
            
            if (!group.getCommonIds().isEmpty()) {
                writer.println("=== COMMON IDs ===");
                group.getCommonIds().stream().sorted().forEach(id -> writer.println("- " + id));
                writer.println();
            }
        }
    }
    
    /**
     * Generate overall grouping summary
     */
    private void generateGroupingSummary(List<FileGroup> groups) throws IOException {
        Path summaryPath = Paths.get(GROUPING_DIR, "GROUPING_SUMMARY.txt");
        
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(summaryPath))) {
            writer.println("=== FILE GROUPING SUMMARY REPORT ===");
            writer.println("Generated: " + new Date());
            writer.println("Similarity Threshold: " + similarityThreshold);
            writer.println("Total Groups Created: " + groups.size());
            writer.println();
            
            // Group statistics
            Map<String, Integer> typeStats = new HashMap<>();
            int totalFiles = 0;
            
            for (FileGroup group : groups) {
                typeStats.merge(group.getGroupType(), group.getFileCount(), Integer::sum);
                totalFiles += group.getFileCount();
            }
            
            writer.println("=== STATISTICS BY DOCUMENT TYPE ===");
            for (Map.Entry<String, Integer> entry : typeStats.entrySet()) {
                writer.println(entry.getKey() + ": " + entry.getValue() + " files");
            }
            writer.println("Total Files Processed: " + totalFiles);
            writer.println();
            
            writer.println("=== GROUP DETAILS ===");
            for (FileGroup group : groups) {
                writer.println("Group: " + group.getGroupName());
                writer.println("  Type: " + group.getGroupType());
                writer.println("  Files: " + group.getFileCount());
                writer.println("  Common Keywords: " + group.getCommonKeywords().size());
                writer.println("  Common Emails: " + group.getCommonEmails().size());
                writer.println("  Common IDs: " + group.getCommonIds().size());
                writer.println();
            }
        }
        
        System.out.println("=== FILE GROUPING COMPLETED ===");
        System.out.println("Total groups created: " + groups.size());
        System.out.println("Total files processed: " + groups.stream().mapToInt(FileGroup::getFileCount).sum());
        System.out.println("Results saved in: " + GROUPING_DIR);
        System.out.println("Summary report: " + summaryPath);
    }
    
    /**
     * Utility method to delete directory recursively
     */
    private void deleteDirectoryRecursively(Path directory) throws IOException {
        if (Files.exists(directory)) {
            Files.walk(directory)
                    .sorted(Comparator.reverseOrder())
                    .map(Path::toFile)
                    .forEach(File::delete);
        }
    }
    
    /**
     * Main method for standalone execution
     */
    public static void main(String[] args) {
        FileGroupingManager manager = new FileGroupingManager();
        
        try {
            manager.analyzeAndGroupFiles();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during file grouping process", e);
            System.err.println("Error: " + e.getMessage());
        }
    }
}
