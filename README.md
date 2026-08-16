# AppApp (App²) — The App for Creating Apps

An Android application written in **Kotlin** that allows you to create, edit, compile, and run modular web apps (`index.html`, `style.css`, `app.js`, etc.) dynamically inside a hardware-accelerated `WebView`, featuring **persistent state memory**, **native file system bridge**, **notch & cutout safe-area awareness**, and an **in-app App Editor Studio with directory file browser, syntax highlighting, and visual app configuration**.

---

## Key Features

- **AppApp Multi-File Workspace**:
  - Structured modular project directory in internal storage (`workspace/`):
    - `index.html`: Semantic markup and UI container.
    - `style.css`: Design system, CSS variables, and safe-area responsive layouts.
    - `app.js`: Interactive logic and native Android storage bridge calls.
    - `manifest.json`: App metadata and identity configuration.
    - `icon.png`: Custom app icon asset.
  - Create, edit, and delete custom project files (`.js`, `.css`, `.json`, etc.).
- **App Editor Hub & Directory Browser**:
  - Project overview screen listing all workspace files categorized by type (Layout, Stylesheet, Script, Manifest, Data, Asset).
  - Tap any file to open the dedicated focused code editor.
  - Quick file creation (+ New File) and contextual deletion for custom files.
- **Dedicated Code Editor (Syntax Highlighting & Search)**:
  - Fast, zero-dependency regex syntax colorizer for HTML, CSS, JavaScript, and JSON.
  - Inline text search with match highlighting, live counter (e.g., 2 of 5), and previous/next navigation.
  - Character counter and unsaved changes protection on exit.
- **Visual App Configuration Studio**:
  - Configure the app identity without manually editing JSON.
  - Customize the unified **App Name** and **Description**.
  - Choose a custom **App Icon** from the device photo gallery or reset to the default AppApp logo.
  - Instantly synchronizes with `manifest.json` and Android Home Screen shortcuts.
- **Android-Aware Display (Edge-to-Edge & Cutouts)**:
  - Built with modern Android 15 Edge-to-Edge (`enableEdgeToEdge()`) and `WindowInsetsCompat`.
  - Dynamically calculates status bar height, display cutouts/notches, navigation bars, and soft keyboard (IME) insets across phones, foldables, and tablets.
- **Native File & State Bridge (`window.AndroidStorage` / `window.AndroidMemory`)**:
  - Direct file I/O (`writeFile`, `readFile`, `deleteFile`, `listFiles`) in the app's internal protected storage.
  - Key-value state persistence (`saveState`, `loadState`, `removeState`).
  - Workspace inspection (`getWorkspaceFiles`, `readWorkspaceFile`, `writeWorkspaceFile`).
  - Real-time disk space and RAM telemetry.
- **Console & Storage Logs (`app.log`)**:
  - Automatically records JavaScript `console.log`, `console.warn`, `console.error`, and native storage events into `filesDir/workspace/app.log`.
  - Accessible directly in the workspace file list, version history snapshots, and ZIP export/import packages.

---

## Tech Stack & Architecture

- **Language**: Kotlin
- **Build System**: Gradle with Kotlin DSL (`build.gradle.kts`) and Version Catalog (`gradle/libs.versions.toml`)
- **Target SDK**: Android 35 (Android 15)
- **Minimum SDK**: Android 26 (Android 8.0 Oreo)
- **UI Framework**: Android Material Design 3 with ViewBinding & Edge-to-Edge

---

## Project Structure

```
appapp/
├── gradle/
│   ├── libs.versions.toml                       # Dependencies and version catalog
│   └── wrapper/gradle-wrapper.properties        # Gradle wrapper distribution
├── build.gradle.kts                             # Root Gradle build script
├── settings.gradle.kts                          # Module inclusions and repositories
├── gradle.properties                            # JVM parameters and AndroidX flags
└── app/
    ├── build.gradle.kts                         # App module configuration
    ├── proguard-rules.pro                       # Proguard rules for @JavascriptInterface
    └── src/main/
        ├── AndroidManifest.xml
        ├── assets/
        │   └── starter_template/                # "My Day" starter app: index.html, style.css,
        │                                        #   app.js + bridge.js/store.js/ui.js (ES modules),
        │                                        #   manifest.json, entries.json, AGENTS.md
        ├── java/com/example/runtimecompiler/
        │   ├── MainActivity.kt                  # Main Activity hosting WebView & dialogs
        │   ├── bridge/
        │   │   ├── NativeStorageBridge.kt      # JavaScript interface for file I/O, state, and notifications
        │   │   ├── MemoryBlock.kt              # Off-heap direct ByteBuffer native memory wrapper
        │   │   └── SystemMemoryManager.kt      # RAM & disk storage telemetry
        │   ├── editor/
        │   │   ├── SyntaxHighlighter.kt        # In-editor syntax coloring for HTML, CSS, JS, JSON
        │   │   └── SearchHelper.kt             # In-file text search & match navigation
        │   ├── templates/
        │   │   └── DefaultWebApp.kt            # Dynamic asset template loader
        │   └── workspace/
        │       ├── WorkspaceManager.kt         # Multi-file workspace manager
        │       ├── WorkspaceHistoryManager.kt  # Snapshots & version history
        │       └── WorkspacePackageManager.kt   # Selective ZIP export/import
        └── res/
            ├── layout/
            │   ├── activity_main.xml           # Main layout with Toolbar and WebView
            │   ├── dialog_workspace_hub.xml    # App Editor directory hub & project card
            │   ├── dialog_file_editor.xml      # Single-file code editor with search & syntax highlighting
            │   ├── dialog_app_config.xml       # App Configuration dialog (manifest & icon)
            │   ├── dialog_import_export.xml    # Share & package ZIP dialog
            │   ├── dialog_version_history.xml  # Version history snapshot dialog
            │   ├── item_workspace_file.xml     # Workspace file item in directory list
            │   ├── item_export_file_checkbox.xml # Checkbox file item for export
            │   └── item_history_snapshot.xml   # Snapshot item in history list
            ├── values/
            │   ├── strings.xml, colors.xml, themes.xml
            │   └── bg_*.xml drawables
            └── drawable/
                ├── ic_app_logo.xml             # App² brand logo
                └── ic_*.xml                    # Material vector icons
```

---

## License
MIT License.
