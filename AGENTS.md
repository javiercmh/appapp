# AGENTS.md — Developer & AI Agent Guidelines

This document provides architectural context, coding standards, and operational guidelines for AI agents and developers working on the **AppApp (App²)** codebase.

---

## 1. Codebase Summary & Architecture

**AppApp** (stylized as **App²**) is an Android application written in **Kotlin** that allows dynamic runtime creation, editing, and execution of modular web applications (`index.html`, `style.css`, `app.js`, `manifest.json`, and custom data files) inside a hardware-accelerated Android `WebView`, while providing direct platform access via JavaScript bridges (`window.AndroidStorage` / `window.AndroidMemory`).

### Core Modules & Packages

| Package / File | Purpose |
| :--- | :--- |
| `com.example.runtimecompiler.MainActivity` | Main Android Activity. Configures Edge-to-Edge window insets, initializes `WebView`, intercepts relative asset loading (`https://app.local/*`), and hosts the tabbed multi-file studio editor and console logs dialogs. |
| `com.example.runtimecompiler.workspace.WorkspaceManager` | Manages the unified multi-file project workspace stored in internal storage (`filesDir/workspace/`). Handles file reading, writing, creation, deletion, template initialization, and MIME type resolution. |
| `com.example.runtimecompiler.workspace.WorkspaceHistoryManager` | Manages up to 5 full version snapshots (FIFO) of the workspace in `filesDir/workspace_history.json` with timestamps, enabling one-tap rollback. |
| `com.example.runtimecompiler.bridge.NativeStorageBridge` | Main `@JavascriptInterface` exposed to the web runtime as `window.AndroidStorage` and `window.AndroidMemory`. Manages persistent key-value state and unified file I/O directly in `workspace/`. |
| `com.example.runtimecompiler.bridge.MemoryBlock` | Off-heap direct `java.nio.ByteBuffer` wrapper for low-level byte operations. |
| `com.example.runtimecompiler.bridge.SystemMemoryManager` | Queries device RAM (`ActivityManager.MemoryInfo`), JVM heap, and native memory allocations. |
| `com.example.runtimecompiler.templates.DefaultWebApp` | Contains the default starter templates for `index.html`, `style.css`, `app.js`, and `manifest.json`. |

---

## 2. Workspace & Unified Directory Architecture

All project code and runtime app files live together in the unified internal storage directory:
```
filesDir/workspace/
├── index.html       # Primary HTML entrypoint & UI layout
├── style.css        # Modular CSS stylesheet with safe-area variables
├── app.js           # JavaScript logic & AndroidStorage bridge calls
├── manifest.json    # App metadata
└── [data files]     # Dynamic files created by app (e.g. saved_items.json)
```

### Asset Interception
The `WebView` loads `https://app.local/index.html`. Requests to `https://app.local/*` (e.g. `<link rel="stylesheet" href="style.css">`, `<script src="app.js"></script>`, or JSON/image assets) are intercepted in `WebViewClient.shouldInterceptRequest` and served directly from `filesDir/workspace/` with auto-resolved MIME types.

---

## 3. Android UI & Edge-to-Edge Guidelines

1. **Edge-to-Edge & WindowInsets**:
   - `enableEdgeToEdge()` is called in `MainActivity.onCreate()`.
   - Use `ViewCompat.setOnApplyWindowInsetsListener` to dynamically pad status bar / notch / display cutout insets (`systemBars() | displayCutout()`) on top bars, and navigation bar / soft keyboard (`WindowInsetsCompat.Type.ime()`) on bottom containers.
   - Do **not** hardcode top status bar margins or fixed heights.
2. **Web Safe Areas**:
   - Web stylesheets should use `env(safe-area-inset-top)` and `env(safe-area-inset-bottom)` to adapt when rendered full-bleed.
3. **Editor Responsiveness**:
   - Dialog toolbars must remain horizontally scrollable (`HorizontalScrollView`) to accommodate narrow screens.
   - When closing the editor with unsaved changes, prompt the user for confirmation.

---

## 4. Development & Tooling Standards

1. **Language & JDK**:
   - **Kotlin** with JVM target `17`.
   - Compatible with **JDK 17** or **JDK 21** (embedded in Android Studio). Do **not** configure Gradle to use JDK 23+ or 25 directly without updating Gradle wrapper.
2. **Build System**:
   - **Gradle Kotlin DSL** (`*.gradle.kts`).
   - Root project name: `"AppApp"`.
   - Dependency versions must be defined in `gradle/libs.versions.toml` (Gradle Version Catalog).
3. **Android Target / Min SDK**:
   - `compileSdk = 35`, `targetSdk = 35`, `minSdk = 26`.
4. **Android UI Framework**:
   - Material Design 3 components.
   - **ViewBinding** enabled (`buildFeatures { viewBinding = true }`).

---

## 5. Extending the JavaScript Bridge

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

## 6. Testing & Verification

- **Gradle Validation**: Ensure Gradle syncs without version conflicts.
- **Persistence Verification**: When modifying storage logic, test by adding items, terminating the process, and verifying that state reloads identically on restart.
- **History & Snapshots**: Verify that workspace snapshots are taken before "Run" and that snapshot restoration properly updates the editor and runtime.
