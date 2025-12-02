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

- **Enhanced Special Character Support**: Uses English + Spanish language models for better recognition of accented characters (á, é, í, ó, ú, ñ, etc.)
- **UTF-8 Support**: Handles special characters and international text with proper encoding
- **Image Preprocessing**: Applies contrast enhancement and grayscale conversion for improved OCR accuracy
- **High Accuracy**: Uses 300 DPI rendering and advanced Tesseract configuration for better OCR results
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

## Configuration

You can modify OCR settings in the `TesseractWordExtractor` constructor:
- `setLanguage()`: Change OCR language (default: "eng+spa" for English + Spanish)
- `setOcrEngineMode()`: Change OCR engine mode
- `setPageSegMode()`: Change page segmentation mode
- `DPI`: Adjust rendering resolution for quality vs. speed trade-off

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
