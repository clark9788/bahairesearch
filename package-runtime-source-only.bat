@echo off
setlocal

REM Build a source-only runtime package (no prebuilt corpus.db).
REM Includes curated corpus source files under data\corpus\curated\en.
REM Usage: package-runtime-source-only.bat [output-folder-name]

set "PACKAGE_NAME=%~1"
if "%PACKAGE_NAME%"=="" set "PACKAGE_NAME=BahaiResearch-runtime-source-only"

set "ROOT=%~dp0"
set "OUT=%ROOT%dist\%PACKAGE_NAME%"

echo [1/5] Preparing output folder: %OUT%
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"
REM mkdir "%OUT%\target"
mkdir "%OUT%\build\libs"
mkdir "%OUT%\data"
mkdir "%OUT%\data\corpus"

echo [2/5] Copying runtime artifacts...
REM copy "%ROOT%target\BahaiResearch-1.0.0-SNAPSHOT-all.jar" "%OUT%\target\" >nul || goto :missingjar
copy "%ROOT%build\libs\BahaiResearch-1.3.2-SNAPSHOT-all.jar" "%OUT%\build\libs\" >nul || goto :missingjar
copy "%ROOT%run-app.bat" "%OUT%\" >nul
copy "%ROOT%bahai-research.local-only.example.properties" "%OUT%\bahai-research.properties" >nul

REM Include private runtime if present
if exist "%ROOT%runtime\bin\java.exe" (
  xcopy "%ROOT%runtime" "%OUT%\runtime\" /E /I /Q /Y >nul
)

echo [3/5] Copying curated source corpus (manifest + en source files)...
if not exist "%ROOT%data\corpus\curated\en\manifest.csv" goto :missingmanifest
xcopy "%ROOT%data\corpus\curated\en" "%OUT%\data\corpus\curated\en\" /E /I /Q /Y >nul || goto :missingcurated

echo [4/5] Configuring packaged properties for first-run local ingest...
powershell -NoProfile -ExecutionPolicy Bypass -Command ^
  "$p='%OUT%\\bahai-research.properties';" ^
  "$c=Get-Content -Raw $p;" ^
  "$c=$c -replace '(?m)^\s*corpus\.autoIngestIfEmpty\s*=.*$','corpus.autoIngestIfEmpty=true';" ^
  "$c=$c -replace '(?m)^\s*corpus\.curatedIngestEnabled\s*=.*$','corpus.curatedIngestEnabled=true';" ^
  "$c=$c -replace '(?m)^\s*corpus\.forceReingest\s*=.*$','corpus.forceReingest=false';" ^
  "Set-Content -Path $p -Value $c -Encoding UTF8"

echo [5/5] Writing quick-start...
(
  echo BahaiResearch Runtime Package ^(Source-only^)
  echo.
  echo 1^) Package includes curated source files under data\corpus\curated\en and no corpus.db.
  echo 2^) On first run, the app initializes corpus.db locally and ingests from the curated manifest.
  echo 3^) If runtime\bin\java.exe exists, app uses bundled Java automatically.
  echo 4^) Run:
  echo    run-app.bat .\bahai-research.properties
) > "%OUT%\README-runtime.txt"

echo Done.
echo Package created at:
echo %OUT%
exit /b 0

:missingjar
REM echo ERROR: Missing JAR at target\BahaiResearch-1.0.0-SNAPSHOT-all.jar
echo ERROR: Missing JAR at build\libs\BahaiResearch-1.3.2-SNAPSHOT-all.jar
REM echo Build first: mvn -DskipTests package
echo Build first: gradlew.bat shadowJar
exit /b 1

:missingmanifest
echo ERROR: Missing curated manifest at data\corpus\curated\en\manifest.csv
exit /b 1

:missingcurated
echo ERROR: Failed copying curated source files from data\corpus\curated\en
exit /b 1
