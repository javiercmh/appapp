# Runtime Web Compiler & Memory Bridge for Android

A modern Android application written in **Kotlin** that compiles, executes, and renders web applications (HTML5, CSS3, JavaScript, and WebAssembly) dynamically at runtime inside a hardware-accelerated `WebView`, equipped with **persistent state memory** and **native file storage access**.

---

## Features

- **Dynamic Runtime Web Engine**: Executes raw HTML, CSS, and ES6+ JavaScript dynamically using the Chromium-based Android `WebView` without requiring asset recompilation.
- **Native File & State Bridge (`window.AndroidStorage` / `window.AndroidMemory`)**:
  - Direct file I/O (`writeFile`, `readFile`, `deleteFile`, `listFiles`) in the app's internal protected storage.
  - Key-value state persistence (`saveState`, `loadState`, `removeState`).
  - Off-heap direct memory management (`ByteBuffer.allocateDirect`).
  - Real-time disk space and RAM telemetry.
- **Persistent State Across Sessions**: The default web application retains all saved list items across app restarts and process kills via `saved_items.json`.
- **Default Interactive App**:
  - Text input field with auto-focus and Enter-key submission.
  - **Submit Button**: Adds items to the active list and immediately writes them to disk.
  - **Undo Button**: Safely removes the latest added item and synchronizes disk storage (gracefully disabled when empty).
  - Storage telemetry badge showing restored items count, active storage file, and free disk space.
- **In-App Live Code Editor**:
  - View and edit the running HTML, CSS, and JS directly on your Android device.
  - **Compile & Run**: Hot-reloads and re-executes code in real-time.
  - **Reset to Default**: Instantly restores the original sample application.
- **Console & Storage Logs Terminal**:
  - Intercepts JavaScript `console.log`, `console.warn`, `console.error`, and native storage events.

---

## Tech Stack & Architecture

- **Language**: Kotlin
- **Build System**: Gradle 8.9 with Kotlin DSL (`build.gradle.kts`) and Version Catalog (`gradle/libs.versions.toml`)
- **Target SDK**: Android 35 (Android 15)
- **Minimum SDK**: Android 26 (Android 8.0 Oreo)
- **UI Framework**: Android Material Design 3 with ViewBinding

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
        │   └── templates/
        │       └── DefaultWebApp.kt            # Default HTML/CSS/JS with Submit/Undo & persistence
        └── res/
            ├── layout/
            │   ├── activity_main.xml           # Main layout with Toolbar and WebView
            │   ├── dialog_code_editor.xml      # Runtime live code editor
            │   └── dialog_console_logs.xml     # Console logs & storage telemetry
            ├── values/
            │   ├── strings.xml, colors.xml, themes.xml
            └── drawable/ & mipmap-.../
```

---

## Getting Started

### Prerequisites
- **Android Studio** (Koala, Ladybug, Iguana, or later)
- **JDK 17** or **JDK 21** (Use Android Studio's Embedded JDK: *Settings → Build, Execution, Deployment → Build Tools → Gradle → Gradle JVM*)

### Running the App
1. Open Android Studio and select **Open**.
2. Navigate to this project folder and click **OK**.
3. Allow Android Studio to sync Gradle dependencies.
4. Select an Emulator or connected Android device (API 26+) and click **Run (`Shift + F10`)**.

---

## JavaScript Bridge API Reference

The following APIs are accessible to any JavaScript code running inside the WebView under `window.AndroidStorage` (and aliased as `window.AndroidMemory`):

### Persistent File I/O
```javascript
// Write text/JSON to app's internal protected storage
window.AndroidStorage.writeFile("filename.json", JSON.stringify(data));

// Read content from file (returns empty string if file doesn't exist)
const content = window.AndroidStorage.readFile("filename.json");

// Delete file
window.AndroidStorage.deleteFile("filename.json");

// List all saved files in internal directory (returns JSON string)
const filesJson = window.AndroidStorage.listFiles();
```

### Key-Value State
```javascript
// Save string state
window.AndroidStorage.saveState("my_key", "my_value");

// Load string state with fallback default
const val = window.AndroidStorage.loadState("my_key", "default_value");

// Remove state key
window.AndroidStorage.removeState("my_key");
```

### Storage & Telemetry
```javascript
// Get disk space and file metrics
const stats = JSON.parse(window.AndroidStorage.getStorageStats());
console.log("Free space MB:", stats.usableSpaceBytes / (1024 * 1024));
```

---

## License
MIT License.
