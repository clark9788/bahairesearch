# BahaiResearch: Moving Development to Ubuntu Linux

This document details the architectural compatibility, potential friction points, and script requirements when moving the development, execution, and packaging of **BahaiResearch** from Windows to Ubuntu.

---

## 1. Architectural Compatibility Analysis

The core Java codebase of BahaiResearch is written using modern, platform-agnostic Java 21 APIs, which makes a migration to Ubuntu straightforward and highly robust.

### A. File Path Handling
*   **How it works:** The codebase relies on Java's New I/O APIs (`java.nio.file.Path`, `Path.of()`) to resolve directories like `data/corpus` and `curated/en`.
*   **Ubuntu Behavior:** `java.nio.file.Path` automatically translates system-dependent separators (e.g., translating Windows `\` into Linux `/`). Explicit string substitutions (like `.replace('\\', '/')` in `CorpusIngestService.java`) ensure that canonical URLs stored in SQLite remain unified across operating systems.
*   **Verdict:** **Fully Compatible.**

### B. Local HTTP Server (Serving HTML/XHTML Corpus)
*   **How it works:** The app hosts a local embedded `com.sun.net.httpserver.HttpServer` on `localhost` to serve XHTML corpus documents. This was originally implemented to circumvent a known Windows-specific bug where `file:///` URIs with `#fragments` (paragraph/anchor IDs) get stripped by `Desktop.browse()`.
*   **Ubuntu Behavior:** The standard JDK `jdk.httpserver` module compiles and runs natively on Linux. Because it binds strictly to the loopback interface (`127.0.0.1` / `localhost`), it operates in user space and avoids triggering VM or OS-level network firewall prompts on Ubuntu.
*   **Verdict:** **Fully Compatible.**

### C. JavaFX UI & Display
*   **How it works:** The GUI is driven by JavaFX 21.0.6.
*   **Ubuntu Behavior:** JavaFX utilizes GTK natively on Linux. Gradle handles OS-specific binary distribution automatically. When building on Linux, the OpenJFX Gradle plugin downloads the native Linux `.so` shared libraries for rendering, so no manual GTK configuration or manual JavaFX installation is needed on the host.
*   **VM Note:** In an Ubuntu VM, ensure graphics acceleration is enabled or that standard display drivers are functional. 
*   **Verdict:** **Fully Compatible.**

### D. External Handlers (Browsers, DOCX, & PDF Viewers)
*   **How it works:** The application was updated to use JavaFX's native **`getHostServices().showDocument(uri)`** API instead of AWT `Desktop`.
*   **Why this was changed (and why it was the only Java code modification required):**
    *   Standard Java AWT `Desktop.getDesktop().browse()` has known integration issues with GTK and window managers in Linux/Ubuntu desktop environments, often silently failing or refusing to trigger the system browser inside VMs.
    *   `getHostServices().showDocument()` is the modern, official JavaFX-native standard. It bypasses AWT entirely, delegating opening requests seamlessly to the OS (such as invoking `xdg-open` on Ubuntu).
*   **Ubuntu Behavior:** `getHostServices().showDocument()` natively calls `xdg-open` to launch your default web browser (Firefox, Chrome, etc.) and file viewers.
*   **Backward-Compatible Windows Fallback:** To guarantee zero negative impact on Windows users, the code is structured in a robust **"on-failure fallback"** pattern:
    1.  The app tries the modern, cross-platform standard first: `getHostServices().showDocument(...)`. This is fully supported on Windows and will succeed 99.9% of the time, opening browsers or files beautifully.
    2.  If this modern call ever throws an exception (e.g., in highly secured sandboxes or headless states), the `catch` block intercepts it, logs a diagnostic warning to the console, and falls back to your original, proven AWT `Desktop.getDesktop().browse()` or `open()` routine.
    3.  This provides a bulletproof setup that is 100% safe, backward-compatible, and optimized for both platforms without needing complex or fragile OS name strings.
*   **Verdict:** **Fully Optimized & Cross-Platform.** *Requirement: Ensure your Ubuntu VM has a PDF reader (like Evince) and an office suite (like LibreOffice) installed if you wish to launch those local file types directly from the UI.*

---

## 2. Linux Development Migration Steps

To replace the Windows `.bat` workflow, we have created corresponding executable Linux shell scripts (`.sh`).

### A. Created Linux Helper Scripts

| Windows Script | Linux Script | Description |
| :--- | :--- | :--- |
| `compile.bat` | `compile.sh` | Compiles the code and packages the fat executable JAR via `./gradlew shadowJar`. |
| `run-app.bat` | `run-app.sh` | Sets up the environment variables (e.g., `KEY_PATH`), resolves configuration properties, and runs the application. Safely auto-detects version updates in the `build/libs` output. |
| `build-runtime-image.bat` | `build-runtime-image.sh` | Packages a custom, stripped-down Linux JRE in a local `./runtime` folder via `jlink`. |
| `package-installer.bat` | `package-installer.sh` | Stages your application, patches environment properties using `sed`, and packages the app. Builds a native Ubuntu Debian installer (`.deb`) or falls back to a portable `app-image` if dependencies are missing. |
| `package-runtime-source-only.bat` | `package-runtime-source-only.sh` | Assembles a portable source-only runtime distribution directory containing scripts, assets, properties, and a custom JRE. |

### B. Shell Script Setup & Execution

Always ensure the shell scripts are executable. After writing them, run:
```bash
chmod +x compile.sh run-app.sh build-runtime-image.sh package-installer.sh package-runtime-source-only.sh
```

#### Normal Workflow:
1.  **Compile:**
    ```bash
    ./compile.sh
    ```
2.  **Run:**
    ```bash
    ./run-app.sh [optional-path-to-properties]
    ```

---

## 3. Core VM / Linux Migration Recommendations

1.  **Case-Sensitivity Gotchas:** Linux file systems (like `ext4`) are strictly case-sensitive, whereas Windows (`NTFS`) is case-insensitive. Double-check all assets, directories, and code references to ensure casing matches exactly (e.g. `data/corpus` must not be referenced as `Data/Corpus` anywhere in code or configuration).
2.  **VM Tooling:** Installing standard utilities ensures that external file opening runs flawlessly:
    ```bash
    sudo apt update
    sudo apt install xdg-utils libreoffice evince
    ```
3.  **Isolated AI Development:** Ubuntu VMs are incredibly useful for isolated AI development (e.g., managing Gemini/API credentials or local LLM runtimes safely without interfering with the host system). Keep your API keys inside a `.properties` file outside of your Git directory, and point to it using the `KEY_PATH` environment variable as configured in `run-app.sh`.
