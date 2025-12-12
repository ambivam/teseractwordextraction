@echo off
echo ========================================
echo Tesseract Word Extractor - Memory Optimized
echo ========================================
echo.

echo Building project with dependencies...
call mvn clean package
if %errorlevel% neq 0 (
    echo Build failed!
    pause
    exit /b 1
)

echo.
echo Starting OCR processing with memory optimization...
echo - Processing Quality: MAXIMUM_QUALITY (1000 DPI) - Comprehensive extraction
echo - Heap Size: 8GB
echo - Thread Pool: 10-16 threads (optimized for high performance)
echo - Batch Processing: Enabled with memory monitoring
echo - Garbage Collection: Automatic between batches
echo.

java -Xmx8g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -jar target\tesseract-extractor-with-dependencies.jar

echo.
echo Processing completed!
pause
