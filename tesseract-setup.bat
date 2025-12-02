@echo off
echo Tesseract OCR Setup Helper
echo =========================

echo.
echo Checking if Tesseract is installed...
tesseract --version >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo ✓ Tesseract is installed and accessible
    tesseract --version
) else (
    echo ✗ Tesseract is not found in PATH
    echo.
    echo Please install Tesseract OCR:
    echo 1. Download from: https://github.com/UB-Mannheim/tesseract/wiki
    echo 2. Install to default location: C:\Program Files\Tesseract-OCR
    echo 3. Add to system PATH or update the Java code with the correct path
    echo.
    pause
    exit /b 1
)

echo.
echo Checking Java installation...
java -version >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo ✓ Java is installed
    java -version
) else (
    echo ✗ Java is not found
    echo Please install Java 11 or higher
    pause
    exit /b 1
)

echo.
echo Checking Maven installation...
mvn -version >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo ✓ Maven is installed
    mvn -version
) else (
    echo ✗ Maven is not found
    echo Please install Apache Maven
    pause
    exit /b 1
)

echo.
echo ✓ All prerequisites are installed!
echo You can now run the application using run.bat
echo.
pause
