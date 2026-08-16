# AppApp (App²) — The App for Creating Apps

An Android application written in **Kotlin** that allows you to create, edit, compile, and run modular web apps (`index.html`, `style.css`, `app.js`, etc.) dynamically inside a hardware-accelerated `WebView`, featuring **persistent state memory**, **native file system bridge**, **notch & cutout safe-area awareness**, and an **in-app multi-file studio**.

---

## Key Features

- **AppApp Multi-File Workspace**:
  - Structured modular project directory in internal storage (`workspace/`):
    - `index.html`: Semantic markup and UI container.
    - `style.css`: Design system, CSS variables, and safe-area responsive layouts.
    - `app.js`: Interactive logic and native Android storage bridge calls.
    - `manifest.json`: App metadata.
  - Create, edit, rename, and delete custom project files (`.js`, `.css`, `.json`, etc.).
- **Android-Aware Display (Edge-to-Edge & Cutouts)**:
  - Built with modern Android 15 Edge-to-Edge (`enableEdgeToEdge()`) and `WindowInsetsCompat`.
  - Dynamically calculates status bar height, display cutouts/notches, navigation bars, and soft keyboard (IME) insets across phones, foldables, and tablets.
- **In-App Tabbed Studio Editor**:
  - Horizontal tab switcher with file extension badges (`HTML`, `CSS`, `JS`, `JSON`).
  - Fast tab switching with in-memory caching of unsaved edits.
  - One-tap "Run App" to save and hot-reload changes instantly.
  - "Reset File" and "Reset Project" options.
- **Native File & State Bridge (`window.AndroidStorage` / `window.AndroidMemory`)**:
  - Direct file I/O (`writeFile`, `readFile`, `deleteFile`, `listFiles`) in the app's internal protected storage.
  - Key-value state persistence (`saveState`, `loadState`, `removeState`).
  - Workspace inspection (`getWorkspaceFiles`, `readWorkspaceFile`, `writeWorkspaceFile`).
  - Real-time disk space and RAM telemetry.
- **Console & Storage Logs (`app.log`)**:
  - Automatically records JavaScript `console.log`, `console.warn`, `console.error`, and native storage events into `filesDir/workspace/app.log`.
  - Directly accessible and editable as an editor tab (`📜 app.log`), included in version history snapshots, and selectively packageable into ZIP exports.

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
        ├── java/com/example/runtimecompiler/
        │   ├── MainActivity.kt                  # Main Activity hosting WebView & dialogs
        │   ├── bridge/
        │   │   ├── NativeStorageBridge.kt      # JavaScript interface for file I/O & state persistence
        │   │   ├── MemoryBlock.kt              # Off-heap direct ByteBuffer native memory wrapper
        │   │   └── SystemMemoryManager.kt      # RAM & disk storage telemetry
        │   ├── templates/
        │   │   └── DefaultWebApp.kt            # Modular starter template files
        │   └── workspace/
        │       └── WorkspaceManager.kt         # Multi-file workspace manager
        └── res/
            ├── layout/
            │   ├── activity_main.xml           # Main layout with Toolbar and WebView
            │   ├── dialog_code_editor.xml      # Multi-file tabbed code editor
            │   ├── dialog_console_logs.xml     # Console logs & storage telemetry
            │   └── item_editor_tab.xml         # Editor file tab item
            ├── values/
            │   ├── strings.xml, colors.xml, themes.xml
            └── drawable/ & mipmap-.../
                ├── ic_app_logo.xml             # App² brand logo
                └── ic_launcher_foreground.xml  # App² launcher icon
```

---

## Getting Started

### Prerequisites
- **Android Studio** (Koala, Ladybug, Iguana, or later)
- **JDK 17** or **JDK 21** (Embedded in Android Studio)

### Running the App
1. Open Android Studio and select **Open**.
2. Navigate to this project folder (`appapp`) and click **OK**.
3. Allow Android Studio to sync Gradle dependencies.
4. Select an Emulator or connected Android device (API 26+) and click **Run (`Shift + F10`)**.

---

## JavaScript Bridge API Reference

The following APIs are accessible to any JavaScript code running inside the WebView under `window.AndroidStorage` (and aliased as `window.AndroidMemory`):

### Workspace Project Files
```javascript
// List all files in the current workspace project
const workspaceFiles = JSON.parse(window.AndroidStorage.getWorkspaceFiles());

// Read any workspace file
const css = window.AndroidStorage.readWorkspaceFile("style.css");

// Write to a workspace file
window.AndroidStorage.writeWorkspaceFile("style.css", "body { background: #000; }");
```

### Persistent Data Storage
```javascript
// Write text/JSON to app's internal protected storage
window.AndroidStorage.writeFile("saved_items.json", JSON.stringify(items));

// Read content from file
const content = window.AndroidStorage.readFile("saved_items.json");

// Key-value state persistence
window.AndroidStorage.saveState("theme_preference", "dark");
const theme = window.AndroidStorage.loadState("theme_preference", "dark");
```

### Storage Telemetry
```javascript
// Get disk space and metrics
const stats = JSON.parse(window.AndroidStorage.getStorageStats());
console.log("Free MB:", Math.round(stats.usableSpaceBytes / (1024 * 1024)));
```

---

## License
MIT License.
