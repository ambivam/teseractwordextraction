package com.tesseract.wordextractor;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Logger;
import java.util.logging.Level;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

/**
 * ImprovedFileGroupingManager - Enhanced version with better similarity detection
 * and more intelligent grouping based on content patterns and shared data elements.
 */
public class ImprovedFileGroupingManager {
    private static final Logger LOGGER = Logger.getLogger(ImprovedFileGroupingManager.class.getName());
    
    // Configuration constants
    private static final String OUTPUT_DIR = "output";
    private static final String GROUPING_DIR = "grouping";
    private static final double DEFAULT_SIMILARITY_THRESHOLD = 0.3; // Lowered for better grouping
    private static final int MIN_SHARED_ELEMENTS = 2;
    
    // Enhanced patterns for better data extraction with special character handling
    private static final Pattern EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Z|a-z]{2,}\\b");
    private static final Pattern DATE_PATTERN = Pattern.compile("\\b\\d{1,2}[/\\-\\.]\\d{1,2}[/\\-\\.]\\d{2,4}\\b|\\b\\d{4}[/\\-\\.]\\d{1,2}[/\\-\\.]\\d{1,2}\\b");
    private static final Pattern CURRENCY_PATTERN = Pattern.compile("R\\$[\\s]*\\d+[.,]\\d{2}|\\$[\\s]*\\d+[.,]\\d{2}|\\d+[.,]\\d{2}[\\s]*(?:reais?|real|BRL|USD)");
    private static final Pattern ID_PATTERN = Pattern.compile("\\b\\d{6,}(?:[\\s\\-\\._]\\d+)*\\b");
    private static final Pattern PHONE_PATTERN = Pattern.compile("\\b\\(?\\d{2,3}\\)?[\\s\\-\\.]?\\d{4,5}[\\s\\-\\.]?\\d{4}\\b");
    private static final Pattern CARD_PATTERN = Pattern.compile("\\b\\d{4}[\\s\\-\\*\\.]?\\d{4}[\\s\\-\\*\\.]?\\d{4}[\\s\\-\\*\\.]?\\d{4}\\b");
    private static final Pattern REFERENCE_PATTERN = Pattern.compile("\\b(?:ref|referência|reference|código|code|id)[\\s:]*([A-Za-z0-9\\-\\_\\.]+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern ACCOUNT_PATTERN = Pattern.compile("\\b(?:conta|account|acc)[\\s:]*([A-Za-z0-9\\-\\_\\.]+)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern MERCHANT_PATTERN = Pattern.compile("\\b(?:merchant|comerciante|loja)[\\s:]*([A-Za-z0-9\\s\\-\\_\\.]+)\\b", Pattern.CASE_INSENSITIVE);
    
    // Enhanced document type classification
    private static final Map<String, Set<String>> DOCUMENT_TYPE_KEYWORDS = new HashMap<>();
    static {
        DOCUMENT_TYPE_KEYWORDS.put("PEDIDOS", Set.of("pedido", "pedidos", "compra", "compras", "produto", "quantidade", "preço", "total", "venda", "vendedor"));
        DOCUMENT_TYPE_KEYWORDS.put("PAGAMENTOS", Set.of("pagamento", "cartão", "crédito", "débito", "pix", "transferência", "cobrança", "mastercard", "visa"));
        DOCUMENT_TYPE_KEYWORDS.put("USUARIOS", Set.of("usuário", "conta", "perfil", "endereço", "informações", "dados", "cadastro", "email", "telefone"));
        DOCUMENT_TYPE_KEYWORDS.put("COMERCIANTE", Set.of("comerciante", "loja", "vendedor", "merchant", "natura", "empresa", "estabelecimento"));
        DOCUMENT_TYPE_KEYWORDS.put("TRANSACOES", Set.of("transação", "operação", "histórico", "movimentação", "extrato", "saldo"));
        DOCUMENT_TYPE_KEYWORDS.put("CUPONS", Set.of("cupom", "desconto", "promoção", "oferta", "voucher"));
    }
    
    /**
     * Enhanced file analysis with better data extraction
     */
    public static class EnhancedFileAnalysis {
        private final String fileName;
        private final String filePath;
        private final String content;
        private final Set<String> keywords;
        private final Set<String> emails;
        private final Set<String> dates;
        private final Set<String> currencies;
        private final Set<String> ids;
        private final Set<String> phones;
        private final Set<String> cards;
        private final Set<String> references;
        private final Set<String> accounts;
        private final Set<String> merchants;
        private final String documentType;
        private final Map<String, Integer> keywordFrequency;
        private final double contentLength;
        private boolean grouped = false;
        
        public EnhancedFileAnalysis(String fileName, String filePath, String content) {
            this.fileName = fileName;
            this.filePath = filePath;
            this.content = content.toLowerCase();
            this.keywords = extractKeywords(this.content);
            this.emails = extractPatterns(EMAIL_PATTERN, content);
            this.dates = extractPatterns(DATE_PATTERN, content);
            this.currencies = extractPatterns(CURRENCY_PATTERN, content);
            this.ids = extractPatterns(ID_PATTERN, content);
            this.phones = extractPatterns(PHONE_PATTERN, content);
            this.cards = extractPatterns(CARD_PATTERN, content);
            this.references = extractGroupPatterns(REFERENCE_PATTERN, content, 1);
            this.accounts = extractGroupPatterns(ACCOUNT_PATTERN, content, 1);
            this.merchants = extractGroupPatterns(MERCHANT_PATTERN, content, 1);
            this.keywordFrequency = calculateKeywordFrequency(this.content);
            this.documentType = determineDocumentType(this.content, this.keywords);
            this.contentLength = content.length();
        }
        
        private Set<String> extractKeywords(String content) {
            return Arrays.stream(content.split("\\W+"))
                    .filter(word -> word.length() > 3)
                    .filter(word -> !isCommonWord(word))
                    .filter(word -> !word.matches("\\d+")) // Exclude pure numbers
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
        
        private Set<String> extractGroupPatterns(Pattern pattern, String content, int groupIndex) {
            Set<String> matches = new HashSet<>();
            Matcher matcher = pattern.matcher(content);
            while (matcher.find()) {
                if (matcher.groupCount() >= groupIndex) {
                    String match = matcher.group(groupIndex);
                    if (match != null && !match.trim().isEmpty()) {
                        matches.add(normalizeSpecialCharacters(match.trim()));
                    }
                }
            }
            return matches;
        }
        
        private String normalizeSpecialCharacters(String text) {
            // Normalize special characters that might appear in OCR text
            return text.replaceAll("[\\s\\-\\_\\.]+", " ")
                      .replaceAll("\\s+", " ")
                      .trim()
                      .toLowerCase();
        }
        
        private Map<String, Integer> calculateKeywordFrequency(String content) {
            Map<String, Integer> frequency = new HashMap<>();
            String[] words = content.split("\\W+");
            
            for (String word : words) {
                if (word.length() > 3 && !isCommonWord(word) && !word.matches("\\d+")) {
                    frequency.merge(word, 1, Integer::sum);
                }
            }
            
            return frequency;
        }
        
        private String determineDocumentType(String content, Set<String> keywords) {
            Map<String, Double> typeScores = new HashMap<>();
            
            for (Map.Entry<String, Set<String>> entry : DOCUMENT_TYPE_KEYWORDS.entrySet()) {
                String type = entry.getKey();
                Set<String> typeKeywords = entry.getValue();
                
                double score = 0.0;
                
                // Check for exact keyword matches with frequency weighting
                for (String keyword : typeKeywords) {
                    if (content.contains(keyword)) {
                        int frequency = keywordFrequency.getOrDefault(keyword, 0);
                        score += 3.0 + (frequency * 0.5); // Base score + frequency bonus
                    }
                }
                
                // Check keywords intersection
                Set<String> intersection = new HashSet<>(keywords);
                intersection.retainAll(typeKeywords);
                score += intersection.size() * 1.5;
                
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
                    "que", "para", "com", "uma", "por", "não", "dos", "das", "como", "mais", "seu", "sua", "tem", "foi", "são", "pelo", "pela", "nos", "nas", "aos", "às", "isso", "esta", "este", "essa", "esse", "aquela", "aquele", "muito", "bem", "onde", "quando", "porque", "então", "assim", "também", "ainda", "já", "só", "até", "depois", "antes", "sobre", "entre", "sem", "contra", "durante",
                    "comprehensive", "extraction", "results", "generated", "processing", "mode", "ultra_high_quality", "file", "type", "form", "fields", "extracted", "pdfbox", "text", "content", "stripper", "enhanced", "tesseract", "multi", "pass", "auto", "detection", "single", "words", "dates", "checkboxes", "symbols");
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
        public Set<String> getPhones() { return phones; }
        public Set<String> getCards() { return cards; }
        public Set<String> getReferences() { return references; }
        public Set<String> getAccounts() { return accounts; }
        public Set<String> getMerchants() { return merchants; }
        public String getDocumentType() { return documentType; }
        public Map<String, Integer> getKeywordFrequency() { return keywordFrequency; }
        public double getContentLength() { return contentLength; }
    }
    
    /**
     * Enhanced file group with better similarity analysis
     */
    public static class EnhancedFileGroup {
        private final String groupName;
        private final String groupType;
        private final List<EnhancedFileAnalysis> files;
        private final Set<String> sharedEmails;
        private final Set<String> sharedIds;
        private final Set<String> sharedKeywords;
        private final String groupingReason;
        
        public EnhancedFileGroup(String groupName, String groupType, String groupingReason) {
            this.groupName = groupName;
            this.groupType = groupType;
            this.groupingReason = groupingReason;
            this.files = new ArrayList<>();
            this.sharedEmails = new HashSet<>();
            this.sharedIds = new HashSet<>();
            this.sharedKeywords = new HashSet<>();
        }
        
        public void addFile(EnhancedFileAnalysis file) {
            files.add(file);
            updateSharedElements();
        }
        
        private void updateSharedElements() {
            if (files.isEmpty()) return;
            
            // Find shared emails across all files
            sharedEmails.clear();
            if (!files.get(0).getEmails().isEmpty()) {
                sharedEmails.addAll(files.get(0).getEmails());
                for (int i = 1; i < files.size(); i++) {
                    sharedEmails.retainAll(files.get(i).getEmails());
                }
            }
            
            // Find shared IDs across all files
            sharedIds.clear();
            if (!files.get(0).getIds().isEmpty()) {
                sharedIds.addAll(files.get(0).getIds());
                for (int i = 1; i < files.size(); i++) {
                    sharedIds.retainAll(files.get(i).getIds());
                }
            }
            
            // Find shared keywords with frequency consideration
            sharedKeywords.clear();
            Map<String, Integer> keywordCounts = new HashMap<>();
            
            for (EnhancedFileAnalysis file : files) {
                for (String keyword : file.getKeywords()) {
                    keywordCounts.merge(keyword, 1, Integer::sum);
                }
            }
            
            // Keywords that appear in at least half of the files
            int threshold = Math.max(1, files.size() / 2);
            for (Map.Entry<String, Integer> entry : keywordCounts.entrySet()) {
                if (entry.getValue() >= threshold) {
                    sharedKeywords.add(entry.getKey());
                }
            }
        }
        
        // Getters
        public String getGroupName() { return groupName; }
        public String getGroupType() { return groupType; }
        public String getGroupingReason() { return groupingReason; }
        public List<EnhancedFileAnalysis> getFiles() { return files; }
        public Set<String> getSharedEmails() { return sharedEmails; }
        public Set<String> getSharedIds() { return sharedIds; }
        public Set<String> getSharedKeywords() { return sharedKeywords; }
        public int getFileCount() { return files.size(); }
    }
    
    private double similarityThreshold;
    
    public ImprovedFileGroupingManager() {
        this.similarityThreshold = DEFAULT_SIMILARITY_THRESHOLD;
    }
    
    public ImprovedFileGroupingManager(double similarityThreshold) {
        this.similarityThreshold = similarityThreshold;
    }
    
    /**
     * Main method to analyze and group files with enhanced logic
     */
    public void analyzeAndGroupFiles() throws IOException {
        LOGGER.info("Starting enhanced file analysis and grouping process...");
        
        // Step 1: Read and analyze all text files
        List<EnhancedFileAnalysis> fileAnalyses = analyzeOutputFiles();
        
        if (fileAnalyses.isEmpty()) {
            LOGGER.warning("No text files found in output directory");
            return;
        }
        
        LOGGER.info("Analyzed " + fileAnalyses.size() + " files");
        
        // Step 2: Group files using enhanced similarity detection
        List<EnhancedFileGroup> groups = groupSimilarFilesEnhanced(fileAnalyses);
        
        LOGGER.info("Created " + groups.size() + " enhanced file groups");
        
        // Step 3: Create folder structure and organize files
        createEnhancedGroupingStructure(groups);
        
        // Step 3.5: Create unique folder for ungrouped files
        createUniqueFilesFolder(fileAnalyses, groups);
        
        // Step 4: Generate detailed summary report
        generateEnhancedGroupingSummary(groups);
        
        // Step 5: Display summary including ungrouped files
        displayGroupingSummary(fileAnalyses, groups);
        
        LOGGER.info("Enhanced file grouping process completed successfully");
    }
    
    /**
     * Analyze all text files with enhanced analysis
     */
    private List<EnhancedFileAnalysis> analyzeOutputFiles() throws IOException {
        List<EnhancedFileAnalysis> analyses = new ArrayList<>();
        Path outputPath = Paths.get(OUTPUT_DIR);
        
        if (!Files.exists(outputPath)) {
            LOGGER.warning("Output directory does not exist: " + OUTPUT_DIR);
            return analyses;
        }
        
        int totalFiles = 0;
        int successfulFiles = 0;
        int failedFiles = 0;
        
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputPath, "*.txt")) {
            for (Path filePath : stream) {
                totalFiles++;
                try {
                    String content = Files.readString(filePath);
                    String fileName = filePath.getFileName().toString();
                    
                    // Skip empty or very small files
                    if (content.trim().length() < 50) {
                        LOGGER.warning("Skipping file with insufficient content: " + fileName + " (length: " + content.length() + ")");
                        failedFiles++;
                        continue;
                    }
                    
                    EnhancedFileAnalysis analysis = new EnhancedFileAnalysis(fileName, filePath.toString(), content);
                    analyses.add(analysis);
                    successfulFiles++;
                    
                    LOGGER.info("Analyzed file: " + fileName + " (Type: " + analysis.getDocumentType() + 
                               ", Keywords: " + analysis.getKeywords().size() + 
                               ", IDs: " + analysis.getIds().size() + 
                               ", Emails: " + analysis.getEmails().size() + ")");
                    
                } catch (Exception e) {
                    failedFiles++;
                    String fileName = filePath.getFileName().toString();
                    LOGGER.log(Level.SEVERE, "FAILED to analyze file: " + fileName + " - " + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
                    System.err.println("ERROR: Failed to analyze " + fileName + " - " + e.getMessage());
                }
            }
        }
        
        LOGGER.info("File analysis summary - Total: " + totalFiles + ", Successful: " + successfulFiles + ", Failed/Skipped: " + failedFiles);
        
        return analyses;
    }
    
    /**
     * Enhanced grouping algorithm with multiple similarity criteria
     */
    private List<EnhancedFileGroup> groupSimilarFilesEnhanced(List<EnhancedFileAnalysis> fileAnalyses) {
        List<EnhancedFileGroup> groups = new ArrayList<>();
        
        // Step 1: Group by shared emails (highest priority)
        groups.addAll(groupBySharedEmails(fileAnalyses));
        
        // Step 2: Group by shared IDs
        groups.addAll(groupBySharedIds(fileAnalyses));
        
        // Step 2.5: Group by shared references, accounts, and merchants
        groups.addAll(groupBySharedReferences(fileAnalyses));
        groups.addAll(groupBySharedAccounts(fileAnalyses));
        groups.addAll(groupBySharedMerchants(fileAnalyses));
        
        // Step 3: Group by document type and content similarity
        groups.addAll(groupByContentSimilarity(fileAnalyses));
        
        // Step 4: Handle remaining ungrouped files
        groups.addAll(handleUngroupedFiles(fileAnalyses));
        
        return groups;
    }
    
    private List<EnhancedFileGroup> groupBySharedEmails(List<EnhancedFileAnalysis> files) {
        List<EnhancedFileGroup> groups = new ArrayList<>();
        Map<String, List<EnhancedFileAnalysis>> emailGroups = new HashMap<>();
        
        // Group files by shared emails
        for (EnhancedFileAnalysis file : files) {
            for (String email : file.getEmails()) {
                emailGroups.computeIfAbsent(email, k -> new ArrayList<>()).add(file);
            }
        }
        
        // Create groups for emails with multiple files
        int groupCounter = 1;
        for (Map.Entry<String, List<EnhancedFileAnalysis>> entry : emailGroups.entrySet()) {
            if (entry.getValue().size() > 1) {
                String email = entry.getKey();
                EnhancedFileGroup group = new EnhancedFileGroup(
                    "EMAIL_GROUP_" + groupCounter++, 
                    "SHARED_EMAIL", 
                    "Files sharing email: " + email
                );
                
                for (EnhancedFileAnalysis file : entry.getValue()) {
                    group.addFile(file);
                    file.grouped = true; // Mark as grouped
                }
                
                groups.add(group);
                LOGGER.info("Created email group with " + group.getFileCount() + " files sharing: " + email);
            }
        }
        
        return groups;
    }
    
    private List<EnhancedFileGroup> groupBySharedIds(List<EnhancedFileAnalysis> files) {
        List<EnhancedFileGroup> groups = new ArrayList<>();
        Map<String, List<EnhancedFileAnalysis>> idGroups = new HashMap<>();
        
        // Group files by shared IDs (only ungrouped files)
        for (EnhancedFileAnalysis file : files) {
            if (!file.grouped) {
                for (String id : file.getIds()) {
                    idGroups.computeIfAbsent(id, k -> new ArrayList<>()).add(file);
                }
            }
        }
        
        // Create groups for IDs with multiple files
        int groupCounter = 1;
        for (Map.Entry<String, List<EnhancedFileAnalysis>> entry : idGroups.entrySet()) {
            if (entry.getValue().size() > 1) {
                String id = entry.getKey();
                EnhancedFileGroup group = new EnhancedFileGroup(
                    "ID_GROUP_" + groupCounter++, 
                    "SHARED_ID", 
                    "Files sharing ID: " + id
                );
                
                for (EnhancedFileAnalysis file : entry.getValue()) {
                    if (!file.grouped) {
                        group.addFile(file);
                        file.grouped = true;
                    }
                }
                
                if (group.getFileCount() > 1) {
                    groups.add(group);
                    LOGGER.info("Created ID group with " + group.getFileCount() + " files sharing: " + id);
                }
            }
        }
        
        return groups;
    }
    
    private List<EnhancedFileGroup> groupBySharedReferences(List<EnhancedFileAnalysis> files) {
        List<EnhancedFileGroup> groups = new ArrayList<>();
        Map<String, List<EnhancedFileAnalysis>> refGroups = new HashMap<>();
        
        // Group files by shared references (only ungrouped files)
        for (EnhancedFileAnalysis file : files) {
            if (!file.grouped) {
                for (String ref : file.getReferences()) {
                    if (ref.length() >= 4) { // Only consider meaningful references
                        refGroups.computeIfAbsent(ref, k -> new ArrayList<>()).add(file);
                    }
                }
            }
        }
        
        // Create groups for references with multiple files
        int groupCounter = 1;
        for (Map.Entry<String, List<EnhancedFileAnalysis>> entry : refGroups.entrySet()) {
            if (entry.getValue().size() > 1) {
                String ref = entry.getKey();
                EnhancedFileGroup group = new EnhancedFileGroup(
                    "REF_GROUP_" + groupCounter++, 
                    "SHARED_REFERENCE", 
                    "Files sharing reference: " + ref
                );
                
                for (EnhancedFileAnalysis file : entry.getValue()) {
                    if (!file.grouped) {
                        group.addFile(file);
                        file.grouped = true;
                    }
                }
                
                if (group.getFileCount() > 1) {
                    groups.add(group);
                    LOGGER.info("Created reference group with " + group.getFileCount() + " files sharing: " + ref);
                }
            }
        }
        
        return groups;
    }
    
    private List<EnhancedFileGroup> groupBySharedAccounts(List<EnhancedFileAnalysis> files) {
        List<EnhancedFileGroup> groups = new ArrayList<>();
        Map<String, List<EnhancedFileAnalysis>> accGroups = new HashMap<>();
        
        // Group files by shared accounts (only ungrouped files)
        for (EnhancedFileAnalysis file : files) {
            if (!file.grouped) {
                for (String acc : file.getAccounts()) {
                    if (acc.length() >= 3) { // Only consider meaningful accounts
                        accGroups.computeIfAbsent(acc, k -> new ArrayList<>()).add(file);
                    }
                }
            }
        }
        
        // Create groups for accounts with multiple files
        int groupCounter = 1;
        for (Map.Entry<String, List<EnhancedFileAnalysis>> entry : accGroups.entrySet()) {
            if (entry.getValue().size() > 1) {
                String acc = entry.getKey();
                EnhancedFileGroup group = new EnhancedFileGroup(
                    "ACCOUNT_GROUP_" + groupCounter++, 
                    "SHARED_ACCOUNT", 
                    "Files sharing account: " + acc
                );
                
                for (EnhancedFileAnalysis file : entry.getValue()) {
                    if (!file.grouped) {
                        group.addFile(file);
                        file.grouped = true;
                    }
                }
                
                if (group.getFileCount() > 1) {
                    groups.add(group);
                    LOGGER.info("Created account group with " + group.getFileCount() + " files sharing: " + acc);
                }
            }
        }
        
        return groups;
    }
    
    private List<EnhancedFileGroup> groupBySharedMerchants(List<EnhancedFileAnalysis> files) {
        List<EnhancedFileGroup> groups = new ArrayList<>();
        Map<String, List<EnhancedFileAnalysis>> merchGroups = new HashMap<>();
        
        // Group files by shared merchants (only ungrouped files)
        for (EnhancedFileAnalysis file : files) {
            if (!file.grouped) {
                for (String merch : file.getMerchants()) {
                    if (merch.length() >= 3) { // Only consider meaningful merchant names
                        merchGroups.computeIfAbsent(merch, k -> new ArrayList<>()).add(file);
                    }
                }
            }
        }
        
        // Create groups for merchants with multiple files
        int groupCounter = 1;
        for (Map.Entry<String, List<EnhancedFileAnalysis>> entry : merchGroups.entrySet()) {
            if (entry.getValue().size() > 1) {
                String merch = entry.getKey();
                EnhancedFileGroup group = new EnhancedFileGroup(
                    "MERCHANT_GROUP_" + groupCounter++, 
                    "SHARED_MERCHANT", 
                    "Files sharing merchant: " + merch
                );
                
                for (EnhancedFileAnalysis file : entry.getValue()) {
                    if (!file.grouped) {
                        group.addFile(file);
                        file.grouped = true;
                    }
                }
                
                if (group.getFileCount() > 1) {
                    groups.add(group);
                    LOGGER.info("Created merchant group with " + group.getFileCount() + " files sharing: " + merch);
                }
            }
        }
        
        return groups;
    }
    
    private List<EnhancedFileGroup> groupByContentSimilarity(List<EnhancedFileAnalysis> files) {
        List<EnhancedFileGroup> groups = new ArrayList<>();
        Map<String, List<EnhancedFileAnalysis>> typeGroups = new HashMap<>();
        
        // Group ungrouped files by document type
        for (EnhancedFileAnalysis file : files) {
            if (!file.grouped) {
                typeGroups.computeIfAbsent(file.getDocumentType(), k -> new ArrayList<>()).add(file);
            }
        }
        
        // Within each type, find content similarity
        for (Map.Entry<String, List<EnhancedFileAnalysis>> entry : typeGroups.entrySet()) {
            String docType = entry.getKey();
            List<EnhancedFileAnalysis> typeFiles = entry.getValue();
            
            if (typeFiles.size() > 1) {
                groups.addAll(findContentSimilarityClusters(typeFiles, docType));
            }
        }
        
        return groups;
    }
    
    private List<EnhancedFileGroup> findContentSimilarityClusters(List<EnhancedFileAnalysis> files, String docType) {
        List<EnhancedFileGroup> clusters = new ArrayList<>();
        List<EnhancedFileAnalysis> unprocessed = new ArrayList<>(files);
        int groupCounter = 1;
        
        while (!unprocessed.isEmpty()) {
            EnhancedFileAnalysis seed = unprocessed.remove(0);
            EnhancedFileGroup cluster = new EnhancedFileGroup(
                docType + "_CONTENT_GROUP_" + groupCounter++, 
                docType, 
                "Files with similar content patterns"
            );
            cluster.addFile(seed);
            seed.grouped = true;
            
            // Find similar files to the seed
            Iterator<EnhancedFileAnalysis> iterator = unprocessed.iterator();
            while (iterator.hasNext()) {
                EnhancedFileAnalysis candidate = iterator.next();
                if (calculateEnhancedSimilarity(seed, candidate) >= similarityThreshold) {
                    cluster.addFile(candidate);
                    candidate.grouped = true;
                    iterator.remove();
                }
            }
            
            clusters.add(cluster);
        }
        
        return clusters;
    }
    
    private List<EnhancedFileGroup> handleUngroupedFiles(List<EnhancedFileAnalysis> files) {
        List<EnhancedFileGroup> groups = new ArrayList<>();
        
        for (EnhancedFileAnalysis file : files) {
            if (!file.grouped) {
                EnhancedFileGroup singleGroup = new EnhancedFileGroup(
                    file.getDocumentType() + "_SINGLE_" + file.getFileName().replace("_comprehensive.txt", ""), 
                    file.getDocumentType(), 
                    "Single file without similar matches"
                );
                singleGroup.addFile(file);
                groups.add(singleGroup);
            }
        }
        
        return groups;
    }
    
    /**
     * Enhanced similarity calculation with multiple criteria including special character handling
     */
    private double calculateEnhancedSimilarity(EnhancedFileAnalysis file1, EnhancedFileAnalysis file2) {
        // Keyword similarity with frequency weighting
        double keywordSim = calculateWeightedKeywordSimilarity(file1, file2);
        
        // Shared data elements similarity with enhanced patterns
        double emailSim = calculateJaccardSimilarity(file1.getEmails(), file2.getEmails());
        double idSim = calculateJaccardSimilarity(file1.getIds(), file2.getIds());
        double cardSim = calculateJaccardSimilarity(file1.getCards(), file2.getCards());
        double refSim = calculateJaccardSimilarity(file1.getReferences(), file2.getReferences());
        double accSim = calculateJaccardSimilarity(file1.getAccounts(), file2.getAccounts());
        double merchSim = calculateJaccardSimilarity(file1.getMerchants(), file2.getMerchants());
        
        // Phone and date similarity for additional context
        double phoneSim = calculateJaccardSimilarity(file1.getPhones(), file2.getPhones());
        double dateSim = calculateJaccardSimilarity(file1.getDates(), file2.getDates());
        
        // Document type bonus
        double typeBonus = file1.getDocumentType().equals(file2.getDocumentType()) ? 0.15 : 0.0;
        
        // Enhanced weighted combination with more criteria
        double similarity = (keywordSim * 0.25) +      // Reduced keyword weight
                           (emailSim * 0.15) +         // Email matches are important
                           (idSim * 0.15) +            // ID matches are important
                           (refSim * 0.12) +           // Reference matches
                           (accSim * 0.10) +           // Account matches
                           (merchSim * 0.08) +         // Merchant matches
                           (cardSim * 0.05) +          // Card matches
                           (phoneSim * 0.05) +         // Phone matches
                           (dateSim * 0.05) +          // Date matches
                           typeBonus;                   // Document type bonus
        
        return Math.min(1.0, similarity);
    }
    
    private double calculateWeightedKeywordSimilarity(EnhancedFileAnalysis file1, EnhancedFileAnalysis file2) {
        Map<String, Integer> freq1 = file1.getKeywordFrequency();
        Map<String, Integer> freq2 = file2.getKeywordFrequency();
        
        Set<String> allKeywords = new HashSet<>(freq1.keySet());
        allKeywords.addAll(freq2.keySet());
        
        if (allKeywords.isEmpty()) return 0.0;
        
        double dotProduct = 0.0;
        double norm1 = 0.0;
        double norm2 = 0.0;
        
        for (String keyword : allKeywords) {
            int f1 = freq1.getOrDefault(keyword, 0);
            int f2 = freq2.getOrDefault(keyword, 0);
            
            dotProduct += f1 * f2;
            norm1 += f1 * f1;
            norm2 += f2 * f2;
        }
        
        if (norm1 == 0.0 || norm2 == 0.0) return 0.0;
        
        return dotProduct / (Math.sqrt(norm1) * Math.sqrt(norm2));
    }
    
    private double calculateJaccardSimilarity(Set<String> set1, Set<String> set2) {
        if (set1.isEmpty() && set2.isEmpty()) return 1.0;
        
        Set<String> intersection = new HashSet<>(set1);
        intersection.retainAll(set2);
        
        Set<String> union = new HashSet<>(set1);
        union.addAll(set2);
        
        return union.isEmpty() ? 0.0 : (double) intersection.size() / union.size();
    }
    
    /**
     * Create enhanced grouping folder structure
     */
    private void createEnhancedGroupingStructure(List<EnhancedFileGroup> groups) throws IOException {
        Path groupingPath = Paths.get(GROUPING_DIR);
        
        // Clean and create grouping directory
        if (Files.exists(groupingPath)) {
            deleteDirectoryRecursively(groupingPath);
        }
        Files.createDirectories(groupingPath);
        
        for (EnhancedFileGroup group : groups) {
            // Create group directory
            Path groupDir = groupingPath.resolve(group.getGroupName());
            Files.createDirectories(groupDir);
            
            // Copy files to group directory
            for (EnhancedFileAnalysis file : group.getFiles()) {
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
            
            // Create enhanced group summary file
            createEnhancedGroupSummary(groupDir, group);
            
            LOGGER.info("Created enhanced group: " + group.getGroupName() + " with " + group.getFileCount() + " files (" + group.getGroupingReason() + ")");
        }
    }
    
    /**
     * Create enhanced summary file for each group
     */
    private void createEnhancedGroupSummary(Path groupDir, EnhancedFileGroup group) throws IOException {
        Path summaryPath = groupDir.resolve("GROUP_SUMMARY.txt");
        
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(summaryPath))) {
            writer.println("=== ENHANCED FILE GROUP SUMMARY ===");
            writer.println("Group Name: " + group.getGroupName());
            writer.println("Group Type: " + group.getGroupType());
            writer.println("Grouping Reason: " + group.getGroupingReason());
            writer.println("File Count: " + group.getFileCount());
            writer.println("Generated: " + new Date());
            writer.println();
            
            writer.println("=== FILES IN GROUP ===");
            for (EnhancedFileAnalysis file : group.getFiles()) {
                writer.println("- " + file.getFileName() + " (Keywords: " + file.getKeywords().size() + ", IDs: " + file.getIds().size() + ", Emails: " + file.getEmails().size() + ")");
            }
            writer.println();
            
            if (!group.getSharedKeywords().isEmpty()) {
                writer.println("=== SHARED KEYWORDS ===");
                group.getSharedKeywords().stream().sorted().forEach(keyword -> writer.println("- " + keyword));
                writer.println();
            }
            
            if (!group.getSharedEmails().isEmpty()) {
                writer.println("=== SHARED EMAILS ===");
                group.getSharedEmails().stream().sorted().forEach(email -> writer.println("- " + email));
                writer.println();
            }
            
            if (!group.getSharedIds().isEmpty()) {
                writer.println("=== SHARED IDs ===");
                group.getSharedIds().stream().sorted().forEach(id -> writer.println("- " + id));
                writer.println();
            }
            
            // Individual file details
            writer.println("=== INDIVIDUAL FILE DETAILS ===");
            for (EnhancedFileAnalysis file : group.getFiles()) {
                writer.println("File: " + file.getFileName());
                writer.println("  Document Type: " + file.getDocumentType());
                writer.println("  Content Length: " + (int)file.getContentLength() + " characters");
                writer.println("  Unique Keywords: " + file.getKeywords().size());
                writer.println("  Emails Found: " + file.getEmails().size());
                writer.println("  IDs Found: " + file.getIds().size());
                writer.println("  Dates Found: " + file.getDates().size());
                writer.println("  Currency Values: " + file.getCurrencies().size());
                writer.println();
            }
        }
    }
    
    /**
     * Generate enhanced overall grouping summary
     */
    private void generateEnhancedGroupingSummary(List<EnhancedFileGroup> groups) throws IOException {
        Path summaryPath = Paths.get(GROUPING_DIR, "ENHANCED_GROUPING_SUMMARY.txt");
        
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(summaryPath))) {
            writer.println("=== ENHANCED FILE GROUPING SUMMARY REPORT ===");
            writer.println("Generated: " + new Date());
            writer.println("Similarity Threshold: " + similarityThreshold);
            writer.println("Total Groups Created: " + groups.size());
            writer.println();
            
            // Enhanced statistics
            Map<String, Integer> typeStats = new HashMap<>();
            Map<String, Integer> reasonStats = new HashMap<>();
            int totalFiles = 0;
            int groupsWithSharedEmails = 0;
            int groupsWithSharedIds = 0;
            
            for (EnhancedFileGroup group : groups) {
                typeStats.merge(group.getGroupType(), group.getFileCount(), Integer::sum);
                reasonStats.merge(group.getGroupingReason().split(":")[0], 1, Integer::sum);
                totalFiles += group.getFileCount();
                
                if (!group.getSharedEmails().isEmpty()) groupsWithSharedEmails++;
                if (!group.getSharedIds().isEmpty()) groupsWithSharedIds++;
            }
            
            writer.println("=== ENHANCED STATISTICS ===");
            writer.println("Total Files Processed: " + totalFiles);
            writer.println("Groups with Shared Emails: " + groupsWithSharedEmails);
            writer.println("Groups with Shared IDs: " + groupsWithSharedIds);
            writer.println();
            
            writer.println("=== STATISTICS BY DOCUMENT TYPE ===");
            for (Map.Entry<String, Integer> entry : typeStats.entrySet()) {
                writer.println(entry.getKey() + ": " + entry.getValue() + " files");
            }
            writer.println();
            
            writer.println("=== STATISTICS BY GROUPING REASON ===");
            for (Map.Entry<String, Integer> entry : reasonStats.entrySet()) {
                writer.println(entry.getKey() + ": " + entry.getValue() + " groups");
            }
            writer.println();
            
            writer.println("=== DETAILED GROUP INFORMATION ===");
            for (EnhancedFileGroup group : groups) {
                writer.println("Group: " + group.getGroupName());
                writer.println("  Type: " + group.getGroupType());
                writer.println("  Reason: " + group.getGroupingReason());
                writer.println("  Files: " + group.getFileCount());
                writer.println("  Shared Keywords: " + group.getSharedKeywords().size());
                writer.println("  Shared Emails: " + group.getSharedEmails().size());
                writer.println("  Shared IDs: " + group.getSharedIds().size());
                
                if (!group.getSharedEmails().isEmpty()) {
                    writer.println("  → Shared Emails: " + String.join(", ", group.getSharedEmails()));
                }
                if (!group.getSharedIds().isEmpty()) {
                    writer.println("  → Shared IDs: " + String.join(", ", group.getSharedIds()));
                }
                writer.println();
            }
        }
    }
    
    /**
     * Create unique folder for ungrouped files
     */
    private void createUniqueFilesFolder(List<EnhancedFileAnalysis> fileAnalyses, List<EnhancedFileGroup> groups) throws IOException {
        // Calculate ungrouped files
        Set<String> groupedFiles = groups.stream()
            .flatMap(g -> g.getFiles().stream())
            .map(f -> f.getFileName())
            .collect(Collectors.toSet());
        
        List<EnhancedFileAnalysis> ungroupedFiles = fileAnalyses.stream()
            .filter(file -> !groupedFiles.contains(file.getFileName()))
            .collect(Collectors.toList());
        
        if (ungroupedFiles.isEmpty()) {
            LOGGER.info("No ungrouped files found - all files were successfully grouped");
            return;
        }
        
        // Create unique folder
        Path groupingPath = Paths.get(GROUPING_DIR);
        Path uniqueDir = groupingPath.resolve("UNIQUE");
        Files.createDirectories(uniqueDir);
        
        LOGGER.info("Creating UNIQUE folder for " + ungroupedFiles.size() + " ungrouped files");
        
        // Copy ungrouped files to unique folder
        for (EnhancedFileAnalysis file : ungroupedFiles) {
            try {
                Path sourcePath = Paths.get(file.getFilePath());
                Path targetPath = uniqueDir.resolve(file.getFileName());
                
                // Copy text file
                Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
                
                // Also copy corresponding JSON file if exists
                String jsonFileName = file.getFileName().replace("_comprehensive.txt", "_comprehensive.json");
                Path jsonSourcePath = sourcePath.getParent().resolve(jsonFileName);
                if (Files.exists(jsonSourcePath)) {
                    Path jsonTargetPath = uniqueDir.resolve(jsonFileName);
                    Files.copy(jsonSourcePath, jsonTargetPath, StandardCopyOption.REPLACE_EXISTING);
                }
                
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error copying unique file: " + file.getFileName(), e);
            }
        }
        
        // Create summary file for unique folder
        createUniqueFilesSummary(uniqueDir, ungroupedFiles);
        
        LOGGER.info("Created UNIQUE folder with " + ungroupedFiles.size() + " files (Files with no significant similarities)");
    }
    
    /**
     * Create summary file for unique files folder
     */
    private void createUniqueFilesSummary(Path uniqueDir, List<EnhancedFileAnalysis> ungroupedFiles) throws IOException {
        Path summaryPath = uniqueDir.resolve("UNIQUE_FILES_SUMMARY.txt");
        
        try (PrintWriter writer = new PrintWriter(Files.newBufferedWriter(summaryPath))) {
            writer.println("=== UNIQUE FILES SUMMARY ===");
            writer.println("Folder: UNIQUE");
            writer.println("Description: Files with no significant similarities to other documents");
            writer.println("File Count: " + ungroupedFiles.size());
            writer.println("Generated: " + new Date());
            writer.println();
            
            writer.println("=== UNIQUE FILES LIST ===");
            for (EnhancedFileAnalysis file : ungroupedFiles) {
                writer.println("- " + file.getFileName() + " (Type: " + file.getDocumentType() + 
                               ", Keywords: " + file.getKeywords().size() + 
                               ", IDs: " + file.getIds().size() + 
                               ", Emails: " + file.getEmails().size() + ")");
            }
            writer.println();
            
            writer.println("=== DOCUMENT TYPE BREAKDOWN ===");
            Map<String, Long> typeCount = ungroupedFiles.stream()
                .collect(Collectors.groupingBy(EnhancedFileAnalysis::getDocumentType, Collectors.counting()));
            
            for (Map.Entry<String, Long> entry : typeCount.entrySet()) {
                writer.println(entry.getKey() + ": " + entry.getValue() + " files");
            }
            writer.println();
            
            writer.println("=== INDIVIDUAL FILE DETAILS ===");
            for (EnhancedFileAnalysis file : ungroupedFiles) {
                writer.println("File: " + file.getFileName());
                writer.println("  Document Type: " + file.getDocumentType());
                writer.println("  Content Length: " + (int)file.getContentLength() + " characters");
                writer.println("  Unique Keywords: " + file.getKeywords().size());
                writer.println("  Emails Found: " + file.getEmails().size());
                writer.println("  IDs Found: " + file.getIds().size());
                writer.println("  Dates Found: " + file.getDates().size());
                writer.println("  Currency Values: " + file.getCurrencies().size());
                writer.println("  Reason for uniqueness: No shared IDs, accounts, or significant content similarity with other files");
                writer.println();
            }
        }
    }
    
    /**
     * Display grouping summary including ungrouped files
     */
    private void displayGroupingSummary(List<EnhancedFileAnalysis> fileAnalyses, List<EnhancedFileGroup> groups) {
        // Calculate ungrouped files
        Set<String> groupedFiles = groups.stream()
            .flatMap(g -> g.getFiles().stream())
            .map(f -> f.getFileName())
            .collect(Collectors.toSet());
        
        List<String> ungroupedFiles = fileAnalyses.stream()
            .map(EnhancedFileAnalysis::getFileName)
            .filter(fileName -> !groupedFiles.contains(fileName))
            .collect(Collectors.toList());
        
        System.out.println("=== ENHANCED FILE GROUPING COMPLETED ===");
        System.out.println("Total files analyzed: " + fileAnalyses.size());
        System.out.println("Total groups created: " + groups.size());
        System.out.println("Total files in groups: " + groups.stream().mapToInt(EnhancedFileGroup::getFileCount).sum());
        System.out.println("Total ungrouped files: " + ungroupedFiles.size());
        System.out.println("Groups with shared emails: " + groups.stream().mapToInt(g -> g.getSharedEmails().isEmpty() ? 0 : 1).sum());
        System.out.println("Groups with shared IDs: " + groups.stream().mapToInt(g -> g.getSharedIds().isEmpty() ? 0 : 1).sum());
        
        if (!ungroupedFiles.isEmpty()) {
            System.out.println("\n=== UNIQUE FILES (Organized in UNIQUE folder) ===");
            ungroupedFiles.forEach(fileName -> System.out.println("- " + fileName));
            System.out.println("These files have been placed in: " + GROUPING_DIR + "\\UNIQUE\\");
        }
        
        System.out.println("\nResults saved in: " + GROUPING_DIR);
        System.out.println("Enhanced summary report: " + GROUPING_DIR + "\\ENHANCED_GROUPING_SUMMARY.txt");
    }
    
    /**
     * Delete directory recursively
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
        ImprovedFileGroupingManager manager = new ImprovedFileGroupingManager();
        
        try {
            manager.analyzeAndGroupFiles();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error during enhanced file grouping process", e);
            System.err.println("Error: " + e.getMessage());
        }
    }
}
