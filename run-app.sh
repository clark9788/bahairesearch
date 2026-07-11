#!/bin/bash
# Resolution order for KEY_PATH:
# 1) Existing KEY_PATH environment variable (preferred for secret key storage)
# 2) Optional first argument to this script
# 3) Local packaged file: ./bahai-research.properties

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

if [ -z "$KEY_PATH" ]; then
  if [ -n "$1" ]; then
    export KEY_PATH="$1"
  elif [ -f "$SCRIPT_DIR/bahai-research.properties" ]; then
    export KEY_PATH="$SCRIPT_DIR/bahai-research.properties"
  else
    echo "ERROR: No KEY_PATH was provided."
    echo "Set environment variable KEY_PATH, or pass a properties path argument,"
    echo "or place bahai-research.properties next to run-app.sh."
    exit 1
  fi
fi

if [ ! -f "$KEY_PATH" ]; then
  echo "ERROR: KEY_PATH file not found: $KEY_PATH"
  exit 1
fi

JAVA_EXE="$SCRIPT_DIR/runtime/bin/java"
if [ ! -f "$JAVA_EXE" ]; then
  JAVA_EXE="java"
fi

# Dynamically locate the fat JAR under build/libs or target
JAR_PATH=$(find "$SCRIPT_DIR/build/libs" "$SCRIPT_DIR/target" -name "BahaiResearch-*-all.jar" 2>/dev/null | head -n 1)

if [ -z "$JAR_PATH" ]; then
  # Fallback to the default version if not found
  JAR_PATH="$SCRIPT_DIR/build/libs/BahaiResearch-1.3.2-SNAPSHOT-all.jar"
fi

echo "Using KEY_PATH=$KEY_PATH"
echo "Running: $JAVA_EXE -jar $JAR_PATH"
"$JAVA_EXE" -jar "$JAR_PATH"
