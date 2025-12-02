@echo off
echo Building the project...
call mvn clean package

if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    pause
    exit /b 1
)

echo Running Tesseract Word Extractor...
java -jar target\word-extractor-1.0.0.jar "PDF for Automation Testing.pdf"

echo.
echo Process completed. Check the output folder for results.
pause
