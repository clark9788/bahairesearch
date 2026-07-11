#!/bin/bash
# Build a Linux installer (.deb) or app-image for BahaiResearch using jpackage.
#
# Prerequisites (run in order):
#   1) ./gradlew shadowJar               (produces fat JAR)
#   2) ./build-runtime-image.sh          (produces runtime/ JRE)
#   3) This script
#
# Usage: ./package-installer.sh [output-folder-name]

PACKAGE_NAME="${1:-installer}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JAR="$SCRIPT_DIR/build/libs/BahaiResearch-1.3.2-SNAPSHOT-all.jar"
RUNTIME="$SCRIPT_DIR/runtime"
STAGING="$SCRIPT_DIR/dist/jpackage-input"
OUT="$SCRIPT_DIR/dist/$PACKAGE_NAME"

if ! command -v jpackage >/dev/null 2>&1; then
  echo "ERROR: jpackage not found."
  echo "Install JDK 21 and ensure jpackage is on PATH."
  exit 1
fi

# -- Prerequisites ---------------------------------------------------------
if [ ! -f "$JAR" ]; then
  echo "ERROR: Missing JAR. Run: ./gradlew shadowJar"
  exit 1
fi
if [ ! -f "$RUNTIME/bin/java" ]; then
  echo "ERROR: Missing runtime image. Run: ./build-runtime-image.sh"
  exit 1
fi
if [ ! -f "$SCRIPT_DIR/data/corpus/curated/en/manifest.csv" ]; then
  echo "ERROR: Missing curated corpus at data/corpus/curated/en/manifest.csv"
  exit 1
fi

# -- Stage input directory -------------------------------------------------
echo "[1/4] Staging jpackage input folder..."
if [ -d "$STAGING" ]; then
  rm -rf "$STAGING"
fi
mkdir -p "$STAGING"

cp "$JAR" "$STAGING/"
cp "$SCRIPT_DIR/bahai-research.local-only.example.properties" "$STAGING/bahai-research.properties"

# Corpus source files
mkdir -p "$STAGING/data/corpus/curated/en"
cp -r "$SCRIPT_DIR/data/corpus/curated/en/"* "$STAGING/data/corpus/curated/en/"

# -- Patch properties for packaged layout ─────────────────────────────────
echo "[2/4] Configuring properties for first-run ingest..."
sed -i 's/^[[:space:]]*corpus\.autoIngestIfEmpty[[:space:]]*=.*/corpus.autoIngestIfEmpty=true/' "$STAGING/bahai-research.properties"
sed -i 's/^[[:space:]]*corpus\.curatedIngestEnabled[[:space:]]*=.*/corpus.curatedIngestEnabled=true/' "$STAGING/bahai-research.properties"
sed -i 's/^[[:space:]]*corpus\.forceReingest[[:space:]]*=.*/corpus.forceReingest=false/' "$STAGING/bahai-research.properties"

# -- Prepare output folder ────────────────────────────────────────────────
if [ -d "$OUT" ]; then
  rm -rf "$OUT"
fi
mkdir -p "$OUT"

# -- Run jpackage (deb installer) ─────────────────────────────────────────
echo "[3/4] Running jpackage (deb installer)..."

jpackage \
  --type deb \
  --name BahaiResearch \
  --app-version 1.3.2 \
  --vendor "BahaiResearch" \
  --description "Baha'i scripture research tool" \
  --input "$STAGING" \
  --main-jar BahaiResearch-1.3.2-SNAPSHOT-all.jar \
  --runtime-image "$RUNTIME" \
  --java-options "-Dbahai.keyPath=\$APPDIR/bahai-research.properties" \
  --java-options "-Dbahai.corpusPath=\$APPDIR/data/corpus" \
  --dest "$OUT"

if [ $? -ne 0 ]; then
  echo ""
  echo "NOTE: deb installer failed or is not supported in this environment."
  echo "Falling back to app-image (portable folder, no installer wizard)..."
  echo ""

  jpackage \
    --type app-image \
    --name BahaiResearch \
    --app-version 1.3.2 \
    --vendor "BahaiResearch" \
    --input "$STAGING" \
    --main-jar BahaiResearch-1.3.2-SNAPSHOT-all.jar \
    --runtime-image "$RUNTIME" \
    --java-options "-Dbahai.keyPath=\$APPDIR/bahai-research.properties" \
    --java-options "-Dbahai.corpusPath=\$APPDIR/data/corpus" \
    --dest "$OUT"

  if [ $? -ne 0 ]; then
    echo "ERROR: jpackage failed. Check output above."
    rm -rf "$STAGING"
    exit 1
  fi

  cp "$SCRIPT_DIR/README-Distribution.md" "$OUT/BahaiResearch/README-Distribution.md"
  cp "$SCRIPT_DIR/Search_flow.md" "$OUT/BahaiResearch/Search_flow.md"

  echo "[4/4] Done (app-image)."
  echo "Portable app folder: $OUT/BahaiResearch"
  echo "Tar this folder for distribution. Users execute $OUT/BahaiResearch/bin/BahaiResearch to launch."
else
  echo "[4/4] Done."
  echo "Installer created at: $OUT"
fi

rm -rf "$STAGING"
exit 0
