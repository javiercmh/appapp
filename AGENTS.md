# AGENTS.md — Developer & AI Agent Guidelines

This document provides architectural context, coding standards, and operational guidelines for AI agents and developers working on this codebase.

---

## 1. Codebase Summary & Architecture

The application is an Android project written in **Kotlin** that allows dynamic runtime execution of web code (HTML, CSS, JavaScript) inside an Android `WebView` while providing access to Android platform capabilities (state persistence, file storage, system memory telemetry) via JavaScript bridges.

### Core Modules & Packages

| Package / File | Purpose |
| :--- | :--- |
| `com.example.runtimecompiler.MainActivity` | Main Android Activity. Configures the `WebView`, initializes bridges, hooks the `WebChromeClient` for log interception, and hosts in-app editor dialogs. |
| `com.example.runtimecompiler.bridge.NativeStorageBridge` | Main `@JavascriptInterface` exposed to web runtime as `window.AndroidStorage` and `window.AndroidMemory`. Handles internal file I/O and persistent key-value state. |
| `com.example.runtimecompiler.bridge.MemoryBlock` | Off-heap direct `java.nio.ByteBuffer` wrapper for low-level byte operations. |
| `com.example.runtimecompiler.bridge.SystemMemoryManager` | Queries device RAM (`ActivityManager.MemoryInfo`), JVM heap, and native memory allocations. |
| `com.example.runtimecompiler.templates.DefaultWebApp` | Contains the default HTML/CSS/JS template featuring text input, Submit, Undo, and state persistence. |

---

## 2. Development & Tooling Standards

1. **Language & JDK**:
   - **Kotlin** with JVM target `17`.
   - Compatible with **JDK 17** or **JDK 21**. Do **not** configure Gradle to use JDK 23+ or 25 directly without updating Gradle wrapper.
2. **Build System**:
   - **Gradle Kotlin DSL** (`*.gradle.kts`).
   - Dependency versions must be defined in `gradle/libs.versions.toml` (Gradle Version Catalog).
3. **Android Target / Min SDK**:
   - `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`.
4. **Android UI**:
   - Material Design 3 components.
   - **ViewBinding** enabled (`buildFeatures { viewBinding = true }`).

---

## 3. Extending the JavaScript Bridge

When adding new bridge methods for the runtime web app:

1. Add the method in `NativeStorageBridge.kt` (or a specialized bridge class).
2. Annotate the method with `@android.webkit.JavascriptInterface`.
3. Keep return types primitive (`String`, `Boolean`, `Int`, `Double`) or JSON-encoded strings for complex objects.
4. Ensure methods run safely and handle exceptions gracefully without crashing the UI thread.
5. If creating a new bridge class, update `app/proguard-rules.pro` to retain `@JavascriptInterface` methods:
   ```proguard
   -keepclassmembers class com.example.runtimecompiler.bridge.YourNewBridge {
       @android.webkit.JavascriptInterface <methods>;
   }
   ```
6. Register the bridge in `MainActivity.kt`:
   ```kotlin
   webView.addJavascriptInterface(yourBridgeInstance, "BridgeName")
   ```

---

## 4. WebView Security & Performance Guidelines

- **File Path Sanitization**: Always sanitize file names passed from JavaScript before accessing the filesystem (e.g., `fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")`).
- **Base URL**: Use a secure local base URL like `"https://app.local/"` in `loadDataWithBaseURL` to ensure consistent origin and relative asset handling.
- **Hardware Acceleration**: Keep `webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)` enabled for smooth CSS transitions, canvas rendering, and animations.
- **Thread Safety**: Remember that `@JavascriptInterface` methods run on a background thread (`JavaBridge`). Any interaction touching Android UI views must be posted to the main thread via `runOnUiThread { ... }` or `Handler(Looper.getMainLooper())`.

---

## 5. Testing & Verification

- **Gradle Validation**: Ensure Gradle syncs without version conflicts.
- **Persistence Verification**: When modifying storage logic, test by adding items, terminating the process, and verifying that state reloads identically on restart.
