# AGENTS.md — Developer & AI Agent Guidelines

This document provides architectural context, coding standards, and operational guidelines for AI agents and developers working on the **AppApp (App²)** codebase.

---

## 1. Codebase Summary & Architecture

**AppApp** (stylized as **App²**) is an Android application written in **Kotlin** that allows dynamic runtime creation, editing, and execution of modular web applications (`index.html`, `style.css`, `app.js`, `manifest.json`, and custom data files) inside a hardware-accelerated Android `WebView`, while providing direct platform access via a JavaScript bridge (`window.AndroidStorage`, also aliased as `window.AndroidMemory` and `window.AndroidNotification`).

### Core Modules & Packages

| Package / File | Purpose |
| :--- | :--- |
| `com.example.runtimecompiler.MainActivity` | Main Android Activity. Configures Edge-to-Edge window insets, initializes `WebView`, intercepts relative asset loading (`https://app.local/*`), and hosts the App Editor Hub, File Editor, and App Configuration dialogs. |
| `com.example.runtimecompiler.editor.SyntaxHighlighter` | Fast regex-based syntax highlighter for HTML, CSS, JavaScript, and JSON code in `EditText`. |
| `com.example.runtimecompiler.editor.SearchHelper` | In-file text search and match navigation helper for `EditText`. |
| `com.example.runtimecompiler.workspace.WorkspaceManager` | Manages the unified multi-file project workspace stored in internal storage (`filesDir/workspace/`). Handles file reading, writing, creation, deletion, template initialization, and MIME type resolution. |
| `com.example.runtimecompiler.workspace.WorkspaceHistoryManager` | Manages up to 5 full version snapshots (FIFO) of the workspace in `filesDir/workspace_history.json` with timestamps, enabling one-tap rollback. |
| `com.example.runtimecompiler.workspace.WorkspacePackageManager` | Handles selective ZIP export/packaging, Android Sharesheet integration, direct stream export, and secure ZIP extraction with Zip-Slip defense and auto-backup snapshots. |
| `com.example.runtimecompiler.bridge.NativeStorageBridge` | Main `@JavascriptInterface` exposed to the web runtime as `window.AndroidStorage`, `window.AndroidMemory`, and `window.AndroidNotification`. Manages persistent key-value state, unified file I/O directly in `workspace/`, native system notifications, and `AlarmManager`-backed scheduled notifications. |
| `com.example.runtimecompiler.bridge.ScheduledNotificationReceiver` | Posts a notification armed earlier via `scheduleNotification`. Runs even when the app process is cold, so it re-creates the notification channel itself. |
| `com.example.runtimecompiler.bridge.AlarmBootReceiver` | Re-arms persisted scheduled notifications after `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, which clear pending alarms. |
| `com.example.runtimecompiler.templates.DefaultWebApp` | Loads the starter template files dynamically from standalone assets (`app/src/main/assets/starter_template/`). |

---

## 2. Workspace & Unified Directory Architecture

All project code and runtime app files live together in the unified internal storage directory:
```
filesDir/workspace/
├── index.html       # Primary HTML entrypoint & UI layout
├── manifest.json    # App metadata & identity configuration
├── AGENTS.md        # App-specific feature and business logic description
├── PACKAGING.md     # Platform contract & packaging documentation for AI agents
├── dev_server.py    # Local development server for testing on a computer
├── css/
│   └── style.css    # Stylesheet with safe-area variables
├── js/
│   ├── app.js       # ES module entry point (<script type="module" src="js/app.js">)
│   ├── bridge.js    # Sole wrapper around the AndroidStorage bridge
│   ├── store.js     # Persistence: data files, photos, preferences
│   └── ui.js        # DOM rendering & interactions
├── data/
│   ├── entries.json # User data store (e.g. shifts/notes)
│   └── settings.json# User configuration & defaults
├── icon.png         # Custom app icon asset (if configured)
├── app.log          # Runtime console, error & bridge logs
└── [custom files]   # Created at runtime (e.g. nested assets, photo_*.jpg)
```

The starter template ships its own `AGENTS.md` (describing the app itself) and `PACKAGING.md` (documenting the runtime from the web app's point of view: bridge API, platform limits, packaging rules). **When the JavaScript bridge changes, update them accordingly.**

### File-Based Persistence Standard (JSON in `data/`)
All application data, domain entities, and user settings **must be stored in plain files inside the `data/` folder** (such as `data/entries.json` and `data/settings.json`). Do not rely on opaque host `SharedPreferences` (`saveState`/`loadState`) for domain configuration. File-based storage guarantees that:
- Data and settings are directly inspectable and editable inside the App Editor Hub (`</>`).
- Data is bundled into ZIP archives when using the Import/Export feature or Android Sharesheet.
- Data is versioned and safely rolled back during workspace snapshot restores.
- Data works seamlessly with the standalone local `dev_server.py`.

### Asset Interception
The `WebView` loads `https://app.local/index.html`. Requests to `https://app.local` (e.g. `<link rel="stylesheet" href="style.css">`, `<script src="app.js"></script>`, or JSON/image assets) are intercepted in `WebViewClient.shouldInterceptRequest` and served directly from `filesDir/workspace/` with auto-resolved MIME types.

---

## 3. Android UI & Edge-to-Edge Guidelines

1. **Edge-to-Edge & WindowInsets**:
   - `enableEdgeToEdge()` is called in `MainActivity.onCreate()`.
   - Use `ViewCompat.setOnApplyWindowInsetsListener` to dynamically pad status bar / notch / display cutout insets (`systemBars() | displayCutout()`) on top bars, and navigation bar / soft keyboard (`WindowInsetsCompat.Type.ime()`) on bottom containers.
   - Do **not** hardcode top status bar margins or fixed heights.
   - The runtime `WebView` is rendered edge-to-edge with zero native view padding, so the inner web application owns all of its own padding and layout.
2. **Web Safe Areas** — two sources, combined:
   - Android `WebView` derives `env(safe-area-inset-*)` from the **display cutout only**. It never reports the status or navigation bars, so `env(safe-area-inset-bottom)` is `0px` on a gesture-nav phone.
   - `MainActivity.pushInsetsToWebView()` therefore publishes the real `systemBars() | displayCutout()` insets onto `document.documentElement` as `--app-inset-top`, `--app-inset-right`, `--app-inset-bottom` and `--app-inset-left` (CSS px), and fires an `app2:insets` event. The top value is `0` while the App² toolbar is visible, because the toolbar already consumes it, and the full status bar inset in standalone mode.
   - Web stylesheets must combine both: `max(env(safe-area-inset-bottom, 0px), var(--app-inset-bottom, 0px))`. See the `--sa-*` tokens in the starter template's `css/style.css`.
   - Set `viewport-fit=cover` in the `<meta name="viewport">` tag, or `env()` stays zero.
3. **Editor Responsiveness & Flow**:
   - Dialog toolbars must remain horizontally scrollable (`HorizontalScrollView`) to accommodate narrow screens.
   - When closing the single-file editor with unsaved changes, prompt the user for confirmation.

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
5. **Version Control**:
   - Commit only when asked, and commit to the **current** branch — `main` included.
   - Do **not** create a branch for the work unless the request explicitly asks for one.
   - Keep commits focused: split unrelated fixes into separate commits rather than one omnibus change.

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
7. Mirror the change in **both** `AGENTS.md` files and in the template's `bridge.js`.

### Current bridge surface

`NativeStorageBridge` exposes key-value state (`saveState` / `loadState` / `removeState` /
`clearAllState`), file I/O (`writeFile` / `readFile` / `fileExists` / `deleteFile` / `listFiles`),
**binary file I/O (`writeFileBase64` / `readFileBase64`)**, telemetry (`getStorageStats`), and
notifications (`showNotification` / `showNotificationWithId` / `cancelNotification` /
`hasNotificationPermission` / `requestNotificationPermission` / `openNotificationSettings`), and **scheduled notifications
(`scheduleNotification` / `cancelScheduledNotification` / `canScheduleExactNotifications` /
`requestExactNotificationPermission`)**.

`openNotificationSettings()` opens this app's notification screen (falling back to the app details
page). It is the only route left for a user on API 26-32, where no runtime prompt exists, or one who
has already refused the prompt — Android then silently declines to show it again. App²'s own Settings
dialog exposes the same thing in its **Notifications** card.

Scheduled notifications use exact `AlarmManager` alarms, so they fire with the app closed.
`ScheduledNotificationReceiver` posts them (it re-creates the notification channel, since the alarm
can wake a cold process) and `AlarmBootReceiver` re-arms the persisted list after a reboot or app
update. `SCHEDULE_EXACT_ALARM` is user-granted special access on API 31+; the app opens Android's
Alarms & reminders screen only after a user action. A schedule request fails when access is missing
and never silently downgrades to an inexact alarm. API 26-30 can schedule exact alarms without this
special access. Android may still throttle exact alarms while the device is idle, and force-stopping
the app prevents delivery until it is opened again.

`WebChromeClient.onShowFileChooser` is implemented in `MainActivity`, so `<input type="file">` works
inside the runtime web app. Pair it with `writeFileBase64` to persist a picked image as a real
binary file that the `app.local` interceptor can then serve via `<img src="...">`.

---

## 6. Testing & Verification

- **Gradle Validation**: Ensure Gradle files (`build.gradle.kts`, `libs.versions.toml`) sync without syntax or version conflicts.
- **Persistence Verification**: When modifying storage logic, test by adding items, terminating the process, and verifying that state reloads identically on restart.
- **History & Snapshots**: Verify that workspace snapshots are taken before "Run" and that snapshot restoration properly updates the editor and runtime.
- **Example App Device Tests**: `examples/<app>/` is source code in this repository; it is not automatically copied to an installed App² instance. Before reporting a device test, sync the changed app-owned files to that device's `filesDir/workspace/`, compare source and device hashes, then force-stop and relaunch App² so the WebView loads the copied files. Never overwrite `data/`, `app.log`, or other user workspace files just to test code. See `examples/working_hours/AGENTS.md` for the Working Hours sync procedure.

---

## 7. Common Gotchas & Environment Quirks

1. **Missing `./gradlew` Wrapper & CLI `gradle` Command**:
   - The repository does **not** check in the root `./gradlew` / `./gradlew.bat` wrapper scripts or `gradle-wrapper.jar`.
   - Standalone `gradle` is not installed on the system `$PATH`.
   - **Agent Rule**: Do **not** run `./gradlew` commands or spend tool calls hunting for Gradle installations across `/tmp`, `/home`, or SDK folders. Code changes, dependencies, and resources are built and synchronized directly within **Android Studio**.
2. **Terminal Sandbox Isolation**:
   - The execution sandbox isolates filesystem access outside the workspace (`/home/melo/Android/Sdk` etc. cannot be probed from standard sandbox mode).
3. **Resource Binding CamelCase**:
   - XML view IDs like `btn_reset` or `btn_edit_code` map to ViewBinding properties `binding.btnReset` and `binding.btnEditCode`. When updating layouts, ensure all references in `MainActivity.kt` stay synchronized.
4. **MIME Resolution & Asset URLs**:
   - All internal requests to `https://app.local/*` are intercepted by `WebViewClient.shouldInterceptRequest` from `filesDir/workspace/`. Ensure any new file types are registered in `WorkspaceManager.getMimeType()`.
5. **Workspace Files & Nested Subdirectories**:
   - `WorkspaceManager` and `NativeStorageBridge` support real nested subdirectories (e.g. `css/style.css`, `js/app.js`, `assets/images/photo.jpg`).
   - Slashes (`/`) are preserved for subdirectories, while path traversal (`..` escaping root) is strictly prevented. Parent directories are auto-created when writing files.
6. **Binary Files & History Snapshots**:
   - `WorkspaceHistoryManager` stores file contents as JSON strings, so binary assets would be
     destroyed by a UTF-8 round-trip. `WorkspaceManager.isBinaryAsset()` excludes them from
     snapshots and from both restore sweeps. Consequence: **images are not version-controlled**,
     but a code rollback no longer corrupts `icon.png` or the user's photos.
7. **Starter Template Assets Are Read As Text**:
   - `DefaultWebApp.getStarterFile()` decodes assets as UTF-8, so **never** place a binary file in
     `assets/starter_template/` — it would be silently corrupted at install time.
8. **Bridge Signature Quirks** (all documented in the workspace `AGENTS.md` too):
   - `loadState(key, defaultValue)` has a Kotlin default argument but no `@JvmOverloads`, so
     JavaScript **must** pass both arguments.
   - `readFile` returns `""` for both "missing" and "empty" — pair with `fileExists`.
   - `showNotificationWithId` takes an `Int`; `Date.now()` overflows it. Prefer `showNotification`.
   - `scheduleNotification` takes the trigger time as a **`String`** of epoch milliseconds — a JS
     number arrives as a double and loses the value. Its `id` is an `Int`, same overflow caveat.
   - **Do not maintain legacy code unless explicitly stated otherwise.** Refactor cleanly and delete dead code rather than keeping migration shims.
9. **Kotlin Block Comments Nest — `/*` and `*/` Inside a Comment Will Break the Build**:
   - **Kotlin block comments nest**, unlike Java, C, or JavaScript. The lexer tracks depth, so a
     `/*` appearing *inside* a comment opens a **second** level, and the `*/` that looks like it
     closes the comment only unwinds one level. Everything after it is swallowed as comment —
     often to end of file.
   - This codebase is a minefield for it: the strings `https://app.local/*` and the MIME wildcard
     `*/*` appear constantly in prose, and both contain a comment delimiter.

     ```kotlin
     /**
      * Served by the `https://app.local/*` interceptor.   // ← opens a NESTED comment
      */                                                   // ← only closes the nested one
     fun stillInsideAComment() {}                          // silently not compiled
     ```

     The mirror-image case is a `*/` closing a comment **early**, which turns the remaining
     KDoc prose into code:

     ```kotlin
     /**
      * Falls back to */* when the types disagree.   // ← comment ends at the `*/`
      */                                             // ← now a syntax error
     ```
   - **Symptoms are misleading.** The nesting case reports no error at the comment. It reports
     `Unresolved reference` on members far below — including things the comment swallowed, like a
     `companion object` — so the reported line has nothing to do with the real cause. If you see a
     cluster of unresolved references in one file, check for a stray `/*` above them **first**.
   - **Rule**: never write a bare `/*` or `*/` inside a comment. Backticks and markdown do not help
     — the lexer does not know what markdown is. Reword instead: write `https://app.local`, or
     "a full wildcard" rather than `*/*`. Inside **string literals** these sequences are perfectly
     safe (`return "*/*"` is fine); the hazard is comments only.
   - **Do not verify by counting delimiters.** Comparing counts of `/*` and `*/` in a file produces
     false positives from string literals like `"*/*"` and false negatives from nesting, and a
     checker that only looks for early-closing comments cannot detect the nesting case at all.
     A correct check must lex the file: track nesting depth while skipping `//` line comments and
     `"`, `'`, and `"""` literals, then report any comment left unclosed at EOF and any `*/`
     encountered at depth 0.
10. **Git Commit & Push Restrictions**:
    - **Agent Rule**: Do **not** run `git commit` or `git push` autonomously. Only execute commit and push commands when the user explicitly gives a direct instruction to do so.
