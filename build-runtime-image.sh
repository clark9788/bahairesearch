#!/bin/bash
# Build a private Java runtime image for BahaiResearch (Linux).
# Usage: ./build-runtime-image.sh [runtime-folder-name]

RUNTIME_NAME="${1:-runtime}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/$RUNTIME_NAME"

if ! command -v jlink >/dev/null 2>&1; then
  echo "ERROR: jlink not found on PATH."
  echo "Install JDK 21 and ensure jlink is available."
  exit 1
fi

echo "Removing existing runtime folder (if any): $OUT"
if [ -d "$OUT" ]; then
  rm -rf "$OUT"
fi

echo "Building private runtime image..."
jlink --add-modules java.base,java.desktop,java.logging,java.net.http,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.httpserver --output "$OUT" --strip-debug --no-man-pages --no-header-files --compress=2

if [ $? -ne 0 ]; then
  echo "ERROR: jlink runtime build failed."
  exit 1
fi

echo "Runtime image created at:"
echo "$OUT"
exit 0
