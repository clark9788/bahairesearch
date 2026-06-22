@echo off
setlocal

REM Build a Windows installer (.exe) for BahaiResearch using jpackage.
REM
REM Prerequisites (run in order):
REM   1) gradlew.bat shadowJar             (produces fat JAR)
REM   2) build-runtime-image.bat          (produces runtime\ JRE)
REM   3) This script
REM
REM For a signed .exe installer, WiX Toolset 3.x must be installed.
REM Without WiX, falls back to a standalone app-image folder you can zip.
REM
REM Usage: package-installer.bat [output-folder-name]

set "PACKAGE_NAME=%~1"
if "%PACKAGE_NAME%"=="" set "PACKAGE_NAME=installer"

set "ROOT=%~dp0"
REM set "JAR=%ROOT%target\BahaiResearch-1.2.0-SNAPSHOT-all.jar"
set "JAR=%ROOT%build\libs\BahaiResearch-1.3.2-SNAPSHOT-all.jar"
set "RUNTIME=%ROOT%runtime"
set "STAGING=%ROOT%dist\jpackage-input"
set "OUT=%ROOT%dist\%PACKAGE_NAME%"

REM Locate jpackage — bundled JDK first, then PATH
set "JPACKAGE_EXE=D:\Program Files\Microsoft\jdk-21.0.9.10-hotspot\bin\jpackage.exe"
if not exist "%JPACKAGE_EXE%" (
  where jpackage >nul 2>&1
  if errorlevel 1 (
    echo ERROR: jpackage not found.
    echo Install JDK 21 and ensure jpackage is on PATH.
    exit /b 1
  )
  set "JPACKAGE_EXE=jpackage"
)

REM ── Prerequisites ─────────────────────────────────────────────────────────
if not exist "%JAR%" (
  REM echo ERROR: Missing JAR. Run: mvn -DskipTests package
  echo ERROR: Missing JAR. Run: gradlew.bat shadowJar
  exit /b 1
)
if not exist "%RUNTIME%\bin\java.exe" (
  echo ERROR: Missing runtime image. Run: build-runtime-image.bat
  exit /b 1
)
if not exist "%ROOT%data\corpus\curated\en\manifest.csv" (
  echo ERROR: Missing curated corpus at data\corpus\curated\en\manifest.csv
  exit /b 1
)

REM ── Stage input directory ─────────────────────────────────────────────────
echo [1/4] Staging jpackage input folder...
if exist "%STAGING%" rmdir /s /q "%STAGING%"
mkdir "%STAGING%"

copy "%JAR%" "%STAGING%\" >nul
copy "%ROOT%bahai-research.local-only.example.properties" "%STAGING%\bahai-research.properties" >nul

REM Corpus source files — on first launch the app ingests them into corpus.db
xcopy "%ROOT%data\corpus\curated\en" "%STAGING%\data\corpus\curated\en\" /E /I /Q /Y >nul

REM ── Patch properties for packaged layout ─────────────────────────────────
echo [2/4] Configuring properties for first-run ingest...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p='%STAGING%\bahai-research.properties';" ^
  "$c=Get-Content -Raw $p;" ^
  "$c=$c -replace '(?m)^\s*corpus\.autoIngestIfEmpty\s*=.*$','corpus.autoIngestIfEmpty=true';" ^
  "$c=$c -replace '(?m)^\s*corpus\.curatedIngestEnabled\s*=.*$','corpus.curatedIngestEnabled=true';" ^
  "$c=$c -replace '(?m)^\s*corpus\.forceReingest\s*=.*$','corpus.forceReingest=false';" ^
  "Set-Content -Path $p -Value $c -Encoding UTF8"

REM ── Prepare output folder ────────────────────────────────────────────────
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

REM ── Run jpackage (exe installer) ─────────────────────────────────────────
echo [3/4] Running jpackage (exe installer -- requires WiX Toolset 3.x)...

"%JPACKAGE_EXE%" ^
  --type exe ^
  --name BahaiResearch ^
  --app-version 1.3.2 ^
  --vendor "BahaiResearch" ^
  --description "Baha'i scripture research tool" ^
  --input "%STAGING%" ^
  --main-jar BahaiResearch-1.3.2-SNAPSHOT-all.jar ^
  --runtime-image "%RUNTIME%" ^
  --java-options "-Dbahai.keyPath=$APPDIR\bahai-research.properties" ^
  --java-options "-Dbahai.corpusPath=$APPDIR\data\corpus" ^
  --win-shortcut ^
  --win-menu ^
  --win-dir-chooser ^
  --dest "%OUT%"

if errorlevel 1 (
  echo.
  echo NOTE: exe installer failed. WiX Toolset 3.x may not be installed.
  echo Falling back to app-image ^(portable folder, no installer wizard^)...
  echo.

  "%JPACKAGE_EXE%" ^
    --type app-image ^
    --name BahaiResearch ^
    --app-version 1.3.2 ^
    --vendor "BahaiResearch" ^
    --input "%STAGING%" ^
    --main-jar BahaiResearch-1.3.2-SNAPSHOT-all.jar ^
    --runtime-image "%RUNTIME%" ^
    --java-options "-Dbahai.keyPath=$APPDIR\bahai-research.properties" ^
    --java-options "-Dbahai.corpusPath=$APPDIR\data\corpus" ^
    --dest "%OUT%"

  if errorlevel 1 (
    echo ERROR: jpackage failed. Check output above.
    goto :cleanup
  )
  
  copy "%ROOT%README-Distribution.md" "%OUT%\BahaiResearch\README-Distribution.md" >nul 
  copy "%ROOT%Search_flow.md" "%OUT%\BahaiResearch\Search_flow.md" >nul 

  echo [4/4] Done ^(app-image^).
  echo Portable app folder: %OUT%\BahaiResearch
  echo Zip this folder for distribution. Users double-click BahaiResearch.exe to launch.
  goto :cleanup
)

echo [4/4] Done.
echo Installer: %OUT%
echo Upload the .exe to GitHub Releases for distribution.

:cleanup
rmdir /s /q "%STAGING%" >nul 2>&1
exit /b 0
