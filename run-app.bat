@echo off
setlocal

REM Resolution order for KEY_PATH:
REM 1) Existing KEY_PATH environment variable (preferred for secret key storage)
REM 2) Optional first argument to this script
REM 3) Local packaged file: .\bahai-research.properties

if defined KEY_PATH goto :validate_key_path

if not "%~1"=="" (
  set "KEY_PATH=%~1"
  goto :validate_key_path
)

set "DEFAULT_LOCAL_KEY_PATH=%~dp0bahai-research.properties"
if exist "%DEFAULT_LOCAL_KEY_PATH%" (
  set "KEY_PATH=%DEFAULT_LOCAL_KEY_PATH%"
  goto :validate_key_path
)

echo ERROR: No KEY_PATH was provided.
echo Set environment variable KEY_PATH, or pass a properties path argument,
echo or place bahai-research.properties next to run-app.bat.
exit /b 1

:validate_key_path
if not exist "%KEY_PATH%" (
  echo ERROR: KEY_PATH file not found: %KEY_PATH%
  exit /b 1
)

set "JAVA_EXE=%~dp0runtime\bin\java.exe"
if not exist "%JAVA_EXE%" (
  set "JAVA_EXE=java"
)

REM "%JAVA_EXE%" -jar "%~dp0target\BahaiResearch-1.0.0-SNAPSHOT-all.jar"
"%JAVA_EXE%" -jar "%~dp0build\libs\BahaiResearch-1.3.0-SNAPSHOT-all.jar"
