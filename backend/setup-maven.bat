@REM Maven Setup Script for Java 24 Upgrade
@REM This script downloads and installs Maven 3.9.8 for the Java 24 project

@echo off
setlocal enabledelayedexpansion

set "MAVEN_HOME=%USERPROFILE%\.m2\maven"
set "MAVEN_VERSION=3.9.8"
set "MAVEN_URL=https://archive.apache.org/dist/maven/maven-3/%MAVEN_VERSION%/binaries/apache-maven-%MAVEN_VERSION%-bin.zip"
set "TEMP_ZIP=%TEMP%\maven-%MAVEN_VERSION%.zip"

echo ========================================
echo Maven Setup for Java 24 Upgrade
echo ========================================
echo.

if exist "%MAVEN_HOME%\bin\mvn.cmd" (
    echo Maven is already installed at: %MAVEN_HOME%
    call "%MAVEN_HOME%\bin\mvn.cmd" --version
    goto end
)

echo Downloading Maven %MAVEN_VERSION%...
echo URL: %MAVEN_URL%
echo.

REM Download Maven using BitsTransfer (PowerShell fallback if needed)
powershell -Command ^
  "[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12; ^
   $wc = New-Object System.Net.WebClient; ^
   $wc.DownloadFile('%MAVEN_URL%', '%TEMP_ZIP%'); ^
   Write-Host 'Downloaded successfully'"

if not exist "%TEMP_ZIP%" (
    echo ERROR: Failed to download Maven
    goto end
)

echo Extracting Maven...
if not exist "%USERPROFILE%\.m2" mkdir "%USERPROFILE%\.m2"
powershell -Command "[System.IO.Compression.ZipFile]::ExtractToDirectory('%TEMP_ZIP%', '%USERPROFILE%\.m2', $true)"

if exist "%MAVEN_HOME%\bin\mvn.cmd" (
    del /q "%TEMP_ZIP%"
    echo.
    echo SUCCESS: Maven installed to %MAVEN_HOME%
    echo.
    echo Maven version:
    call "%MAVEN_HOME%\bin\mvn.cmd" --version
    echo.
    echo To use Maven, set JAVA_HOME and Maven paths:
    echo   set JAVA_HOME=C:\Users\Manikanta\.jdk\jdk-24
    echo   set M2_HOME=%MAVEN_HOME%
    echo   set PATH=%MAVEN_HOME%\bin;!PATH!
    echo.
    echo Then run:
    echo   mvn clean test-compile
    echo   mvn clean test
) else (
    echo ERROR: Maven extraction failed
)

:end
pause
