# BahaiResearch

Desktop research assistant for finding sourced Bahá’í quotes from a **local authenticated corpus** (with optional AI-assisted intent/reranking).

## What this project does

- Uses a local SQLite corpus for quote retrieval. Source titles are from baha.org/library
- Supports curated source ingest (DOCX/HTML/PDF) from `data/corpus/curated/en`
- Returns structured results (quote, author, book, locator/page, URL)
- Can run in local-only mode (no web lookup at query time)
- Orginally was set up to use Gemini AI and look for quotes on the web, but there were hallucinations
- BahaResearchofResults.docx has a list of quotes returned when the AI was producing quotes from the web
- AI now evaluates research input and ranks words for search. It also tries to guess a good source -- author, title
- AI also ranks the returned quotes in order of most relevant
- Search uses a NEAR → AND → OR query tier: 2-keyword searches try proximity matching first (both words within 15 words of each other), then fall back to AND, then OR
- Author dropdown drives the Title dropdown — selecting an author loads only that author's titles from the DB; Title is disabled until an author is chosen
- AI can be returned to web search by setting research.localOnlyMode=false in .properties file
- Future plans to research other AI api's to see if they perform better. 
- Each result includes a clickable Source link that opens the browser at the exact paragraph. Locators are HTML anchor IDs embedded in the bahai.org xhtml source files. For the 4 non-xhtml files (docx/pdf), Source opens the file in the registered OS handler (Word, Edge, etc.), but goes to the beginnin of file. Use Search for these.
- Used chatgpt-5.3-codex for original design and coding. Used sonnet-4.6 for improvements to search algorithm and finish. 

## Android Version

An Android port of this app exists at `D:/AI-Python/BahaiResearchA` (also on GitHub as BahaiResearchA).

**That project is dependent on this one.** The Android app ships with a pre-built copy of `data/corpus/corpus.db` as a static asset. It does not perform ingestion. If new books are added to this corpus and re-ingested, the updated `corpus.db` must be copied to the Android project's `app/src/main/assets/corpus.db` to pick up the new content.

## BahaiSearchCommon ##
This is the common search routines used by this Windows version and the Android version.
It is a separater project and is imported by gradle during the build of this project.


---

## Tech stack

- Java 21
- JavaFX
- SQLite
- Gradle
- Inno Setup
- Optional Gemini API integration for intent/reranking

---

## Build

All commands below are run from project root.

### First-time build run (new machine / clean setup)

1) Build app jar:

``` cmd
gradlew.bat shadowJar
build.gradle needs to be updated with new verion numbers.
```

2) (Optional but recommended for end-user distribution) Build private Java runtime:

```cmd
build-runtime-image.bat
```

3) (Optional for release packaging):

```cmd
package-installer.bat //This must be updated in 6 places for new version

4) Run inno with BahaiResearch.iss for installable Windows file

```

### Follow-on build runs (normal development/release updates)

Most updates only need:

```cmd
gradlew.bat shadowJar
package-installer.bat
```

You only need to rerun `build-runtime-image.bat` when:
- Java version changes,
- runtime image options/modules change,
- or `runtime/` is missing/corrupted.

### Output artifacts

Primary jar:

```cmd
gradlew.bat shadowJar
```

produces:

```text
target/BahaiResearch-1.0.0-SNAPSHOT-all.jar
or 
build/libs/BahaiResearch-1.0.0-SNAPSHOT-all.jar
```

Packaging script (if used) produces:

```text
dist/installer/BahaiResearch/

```

---

## Runtime configuration (`KEY_PATH`)

The app reads settings from a properties file pointed to by environment variable `KEY_PATH`. If KEY_PATH is not set, reads from project root .properties file.

Create a local file like `bahai-research.example.properties` (do **not** commit real secrets):

```properties
gemini.apiKey=YOUR_API_KEY
gemini.model=gemini-2.5-flash

research.requiredSite=https://oceanlibrary.com/
research.localOnlyMode=true                      ** uses local database when true, uses local and web search when false
research.debugIntent=false
research.noResultsText=No Results                ** text for No Results in output
research.maxQuotes=8
research.requestTimeoutSeconds=90

corpus.basePath=data/corpus
corpus.databaseFileName=corpus.db
corpus.snapshotsDirName=snapshots
corpus.ingestDirName=ingest
corpus.autoInitialize=true

corpus.sourceBaseUrl=https://www.bahai.org
corpus.ingestSeedUrl=https://www.bahai.org/library/
corpus.ingestMaxPages=5000
corpus.ingestRequestDelayMillis=150
corpus.minPassageLength=80

corpus.autoIngestIfEmpty=false
corpus.forceReingest=false

corpus.curatedIngestEnabled=true
corpus.curated.baseDir=curated/en
corpus.curated.manifestFileName=manifest.csv
```

---

## Run

### Option A: direct command

```cmd
set KEY_PATH=c:\path\to\bahai-research.properties && java -jar target\BahaiResearch-1.0.0-SNAPSHOT-all.jar
```

### Option B: helper script

```cmd
run-app.bat c:\path\to\bahai-research.properties
```

---

## Packaging recommendations

Use `package-installer.bat` for end-user distribution. It includes:

- `target/BahaiResearch-1.0.0-SNAPSHOT-all.jar`
- `data/corpus/curated/en/` (xhtml source files + manifest — required for Source deep links)
- your local properties file (distributed privately)
- optional bundled Java runtime (`runtime/`)

On first run the app ingests from the curated source files and builds `corpus.db` locally.

Recommended runtime flags:

- `corpus.autoIngestIfEmpty=true`
- `corpus.forceReingest=false`
- `research.localOnlyMode=true`

Note: the curated xhtml source files must always be co-located with the app. The Source deep links resolve files at runtime using `corpus.basePath`.

---

## Git notes

- `.gitignore` excludes build output, runtime DB artifacts, ingest/snapshot outputs, and `*.properties`
- Keep secrets out of Git (especially API keys)

Typical workflow:

```cmd
git add .
git commit -m "Describe change"
git push
```

---

## Useful docs

- `DEPLOYMENT.md` – packaging and run details
- `SEARCH.md` – full search algorithm: query tiers, ranking, filters, debug logging

Version 2 notes:
Rough effort estimate:

- __Core v2 (safe split, no blending):__ low-to-medium effort
- __Polished v2 (ranking, source quality controls, better UI toggles):__ medium effort

The key is design:

1. keep authoritative quotes pipeline exactly as-is,
2. add optional commentary retrieval pipeline,
3. render in separate section with explicit label.

So yes — manageable, and your current local-first design makes it much easier and safer to add. If you want, I can draft a v2 implementation plan in phases (MVP → hardened).Not too difficult — __very doable for v2__ if you keep your current architecture and add commentary as a separate lane.

Rough effort estimate:

- __Core v2 (safe split, no blending):__ low-to-medium effort
- __Polished v2 (ranking, source quality controls, better UI toggles):__ medium effort

The key is design:

1. keep authoritative quotes pipeline exactly as-is,
2. add optional commentary retrieval pipeline,
3. render in separate section with explicit label.

## Warnings on Install and Firewall warning
This installer is safe, but it is not yet code‑signed.
Because of this, Windows SmartScreen may display a warning such as “Windows protected your PC” or “Unknown publisher.”
To install:

Click More info or Keep Anyway
Click Run anyway or Delete(DownArrow) -Keep Anyway

This happens because the installer is newly published and has not yet built up Windows reputation.
No system files are modified outside the application’s install directory.

On first use, Windows Firewall may ask for permission for the Java HTML server to run. This server provides access to the HTML source files #anchor numbers to open file to exact location of quote, but does not need access through the firewall, so deny the firewall rule setup.
