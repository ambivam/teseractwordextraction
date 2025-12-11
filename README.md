# Tesseract Word Extractor

A Java application that uses Tesseract OCR to extract text and individual words from PDF documents, outputting results in both .txt and .json formats with UTF-8 encoding support.

## Prerequisites

### 1. Install Tesseract OCR
Download and install Tesseract OCR from: https://github.com/UB-Mannheim/tesseract/wiki

**For Windows:**
- Download the installer from the releases page
- Install to the default location: `C:\Program Files\Tesseract-OCR`
- Add Tesseract to your system PATH

### 2. Install Java 11 or higher
Ensure you have Java 11+ installed and configured.

### 3. Install Maven
Download and install Apache Maven from: https://maven.apache.org/

## Setup

1. **Clone or download this project**
2. **Verify Tesseract installation:**
   ```bash
   tesseract --version
   ```
3. **If Tesseract is not in your PATH, update the Java code:**
   - Open `src/main/java/com/tesseract/wordextractor/TesseractWordExtractor.java`
   - Uncomment and modify the `setDatapath` line with your Tesseract installation path

## Usage

### Method 1: Using the batch script (Windows)
```bash
run.bat
```

### Method 2: Using Maven directly
```bash
# Build the project
mvn clean compile

# Run with a specific PDF file
mvn exec:java -Dexec.args="your-pdf-file.pdf"

# Example with the provided PDF
mvn exec:java -Dexec.args="PDF for Automation Testing.pdf"
```

### Method 3: Using Java directly (after compilation)
```bash
java -cp "target/classes;target/dependency/*" com.tesseract.wordextractor.TesseractWordExtractor "PDF for Automation Testing.pdf"
```

## Output

The application generates two files in the `output` folder:

### 1. Text File (.txt)
- Contains all extracted text with UTF-8 encoding
- Preserves page breaks and formatting
- Example: `PDF for Automation Testing.txt`

### 2. JSON File (.json)
- Contains detailed word-level information with UTF-8 encoding
- Includes for each word:
  - `text`: The extracted word
  - `page`: Page number (1-based)
  - `x`, `y`: Coordinates on the page
  - `width`, `height`: Bounding box dimensions
  - `confidence`: OCR confidence score (0-100)

Example JSON structure:
```json
{
  "totalWords": 1250,
  "extractionTimestamp": 1701234567890,
  "words": [
    {
      "text": "Example",
      "page": 1,
      "x": 100,
      "y": 200,
      "width": 50,
      "height": 12,
      "confidence": 95.5
    }
  ]
}
```

## Features

- **Configurable Processing Modes**: Choose between speed and accuracy
  - **FAST** (300 DPI): Quick processing with good accuracy
  - **BALANCED** (400 DPI): Optimal speed/accuracy balance (default)
  - **HIGH_QUALITY** (600 DPI): Maximum accuracy, slower processing
- **Optimized Image Processing**: Fast single-pass enhancement with contrast boost and edge sharpening
- **Enhanced Special Character Support**: Uses English + Spanish language models for better recognition of accented characters (á, é, í, ó, ú, ñ, etc.)
- **UTF-8 Support**: Handles special characters and international text with proper encoding
- **Optimized Tesseract Configuration**: 20+ advanced parameters tuned for high-resolution text recognition
- **Word-level Extraction**: Provides coordinates and confidence for each word
- **Multi-page Support**: Processes all pages in the PDF
- **Error Handling**: Comprehensive logging and error reporting
- **Configurable**: Easy to modify OCR settings and output formats

## Troubleshooting

### Common Issues:

1. **"Tesseract not found" error:**
   - Ensure Tesseract is installed and in your PATH
   - Or uncomment and set the correct path in the Java code

2. **Out of memory errors:**
   - Increase JVM heap size: `java -Xmx2g ...`
   - Process large PDFs page by page

3. **Poor OCR accuracy:**
   - Ensure the PDF has good quality text/images
   - Try different page segmentation modes
   - Consider preprocessing the images

4. **Build failures:**
   - Ensure Java 11+ and Maven are properly installed
   - Check internet connection for dependency downloads

5. **Performance considerations:**
   - High resolution (600 DPI) processing requires more memory and time
   - For large PDFs, consider increasing JVM heap size: `java -Xmx4g -jar ...`
   - Processing time is approximately 2-3x longer than 300 DPI but with significantly better accuracy

## Configuration

You can modify OCR settings in the `TesseractWordExtractor` class:

### Processing Mode Configuration
To change the processing mode, modify the `PROCESSING_MODE` constant:
```java
// For fastest processing (good for large documents)
private static final ProcessingMode PROCESSING_MODE = ProcessingMode.FAST;

// For balanced performance (default - recommended)
private static final ProcessingMode PROCESSING_MODE = ProcessingMode.BALANCED;

// For maximum accuracy (best for critical documents)
private static final ProcessingMode PROCESSING_MODE = ProcessingMode.HIGH_QUALITY;
```

### Other OCR Settings
- `setLanguage()`: Change OCR language (default: "eng+spa" for English + Spanish)
- `setOcrEngineMode()`: Change OCR engine mode
- `setPageSegMode()`: Change page segmentation mode

### Special Character Support

The application is configured to handle special characters including:
- **Spanish accented characters**: á, é, í, ó, ú, ñ, Á, É, Í, Ó, Ú, Ñ
- **Other Latin characters**: ç, ü, etc.
- **Punctuation**: ¿, ¡, etc.

To add support for other languages:
1. Ensure the language pack is installed in Tesseract (check tessdata folder)
2. Modify the `setLanguage()` call in the code (e.g., "eng+spa+fra" for French support)
3. Available language codes: spa (Spanish), fra (French), deu (German), ita (Italian), por (Portuguese), etc.

## Dependencies

- **Tess4J 5.8.0**: Java wrapper for Tesseract OCR
- **Apache PDFBox 3.0.1**: PDF processing and rendering
- **Jackson 2.16.0**: JSON processing
- **SLF4J**: Logging framework

#********************************
# Build the project first
mvn clean package

# Then run with Maven
mvn exec:java -Dexec.mainClass="com.tesseract.wordextractor.TesseractWordExtractor" -Dexec.args="PDF for Automation Testing.pdf"

#*******************************
run.bat

#*******************************
# Build the project
mvn package

# Run the JAR file
java -jar target\word-extractor-1.0.0.jar "PDF for Automation Testing.pdf"

# For large PDFs, increase memory:
java -Xmx4g -jar target\word-extractor-1.0.0.jar "PDF for Automation Testing.pdf"

#*********************************
# Skip clean, just package
mvn package

# Then run with high quality (600 DPI)
java -Xmx4g -jar target\word-extractor-1.0.0.jar "PDF for Automation Testing.pdf"

#*******************
# 1. Kill Java processes
taskkill /f /im java.exe

# 2. Wait a moment
timeout /t 2

# 3. Try building again
mvn clean package

#*******************

java -cp "target/word-extractor-1.0.0.jar;target/lib/*" com.tesseract.wordextractor.TesseractWordExtractor "PDF for Automation Testing.pdf"

#*******************
# 1. Build the project
mvn clean package

# 2. Run the application
java -jar target\word-extractor-1.0.0.jar


mvn exec:java

#*******************

java -cp "target/classes" com.tesseract.wordextractor.FileGroupingManager

java -cp "target/classes" com.tesseract.wordextractor.ImprovedFileGroupingManager

#*******************