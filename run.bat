@echo off
echo Building the project...
call mvn clean package

if %ERRORLEVEL% NEQ 0 (
    echo Build failed!
    pause
    exit /b 1
)

echo Running Enhanced Tesseract Word Extractor...
echo The extractor will process all PDF and image files in the 'data' folder
java -jar target\word-extractor-1.0.0.jar

echo.
echo Process completed. Check the output folder for results.
echo Supported formats: PDF, JPG, JPEG, PNG, TIFF, TIF, BMP, GIF, WEBP
pause
