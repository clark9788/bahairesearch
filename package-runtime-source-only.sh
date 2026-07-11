#!/bin/bash
# Build a source-only runtime package (no prebuilt corpus.db).
# Includes curated corpus source files under data/corpus/curated/en.
# Usage: ./package-runtime-source-only.sh [output-folder-name]

PACKAGE_NAME="${1:-BahaiResearch-runtime-source-only}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT="$SCRIPT_DIR/dist/$PACKAGE_NAME"

echo "[1/5] Preparing output folder: $OUT"
if [ -d "$OUT" ]; then
  rm -rf "$OUT"
fi
mkdir -p "$OUT/build/libs"
mkdir -p "$OUT/data/corpus"

echo "[2/5] Copying runtime artifacts..."
if [ ! -f "$SCRIPT_DIR/build/libs/BahaiResearch-1.3.2-SNAPSHOT-all.jar" ]; then
  echo "ERROR: Missing JAR at build/libs/BahaiResearch-1.3.2-SNAPSHOT-all.jar"
  echo "Build first: ./compile.sh"
  exit 1
fi

cp "$SCRIPT_DIR/build/libs/BahaiResearch-1.3.2-SNAPSHOT-all.jar" "$OUT/build/libs/"
cp "$SCRIPT_DIR/run-app.sh" "$OUT/"
chmod +x "$OUT/run-app.sh"
cp "$SCRIPT_DIR/bahai-research.local-only.example.properties" "$OUT/bahai-research.properties"

# Include private runtime if present
if [ -f "$SCRIPT_DIR/runtime/bin/java" ]; then
  mkdir -p "$OUT/runtime"
  cp -r "$SCRIPT_DIR/runtime/"* "$OUT/runtime/"
fi

echo "[3/5] Copying curated source corpus (manifest + en source files)..."
if [ ! -f "$SCRIPT_DIR/data/corpus/curated/en/manifest.csv" ]; then
  echo "ERROR: Missing curated manifest at data/corpus/curated/en/manifest.csv"
  exit 1
fi
mkdir -p "$OUT/data/corpus/curated/en"
cp -r "$SCRIPT_DIR/data/corpus/curated/en/"* "$OUT/data/corpus/curated/en/"

echo "[4/5] Configuring packaged properties for first-run local ingest..."
sed -i 's/^[[:space:]]*corpus\.autoIngestIfEmpty\s*=.*/corpus.autoIngestIfEmpty=true/' "$OUT/bahai-research.properties"
sed -i 's/^[[:space:]]*corpus\.curatedIngestEnabled\s*=.*/corpus.curatedIngestEnabled=true/' "$OUT/bahai-research.properties"
sed -i 's/^[[:space:]]*corpus\.forceReingest\s*=.*/corpus.forceReingest=false/' "$OUT/bahai-research.properties"

echo "[5/5] Writing quick-start..."
cat << EOF > "$OUT/README-runtime.txt"
BahaiResearch Runtime Package (Source-only)

1) Package includes curated source files under data/corpus/curated/en and no corpus.db.
2) On first run, the app initializes corpus.db locally and ingests from the curated manifest.
3) If runtime/bin/java exists, app uses bundled Java automatically.
4) Run:
   ./run-app.sh ./bahai-research.properties
EOF

echo "Done."
echo "Package created at:"
echo "$OUT"
exit 0
