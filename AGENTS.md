# AGENTS.md — Developer & AI Agent Guidelines

This document provides architectural context, coding standards, and operational guidelines for AI agents and developers working on the **AppApp (App²)** codebase.

---

## 1. Codebase Summary & Architecture

**AppApp** (stylized as **App²**) is an Android application written in **Kotlin** that allows dynamic runtime creation, editing, and execution of modular web applications (`index.html`, `style.css`, `app.js`, `manifest.json`, and custom data files) inside a hardware-accelerated Android `WebView`, while providing direct platform access via a JavaScript bridge (`window.AndroidStorage`, also aliased as `window.AndroidMemory` and `window.AndroidNotification`).

### Core Modules & Packages

| Package / File | Purpose |
| :--- | :--- |
| `com.wynh.appapp.SubappLibraryActivity` | Launcher library for opening, editing, installing, importing, and deleting saved subapps. |
| `com.wynh.appapp.MainActivity` | Editor whose tools and WebView remain bound to one subapp ID. |
| `com.wynh.appapp.SubappRuntimeActivity` | Standalone document task launched by each subapp shortcut. |
| `com.wynh.appapp.editor.SyntaxHighlighter` | Fast regex-based syntax highlighter for HTML, CSS, JavaScript, and JSON code in `EditText`. |
| `com.wynh.appapp.editor.SearchHelper` | In-file text search and match navigation helper for `EditText`. |
| `com.wynh.appapp.workspace.SubappRepository` | Owns stable app IDs and migrates the former single workspace into the library. |
| `com.wynh.appapp.workspace.WorkspaceManager` | Manages one app workspace under `filesDir/subapps/<id>/workspace/`. |
| `com.wynh.appapp.workspace.WorkspaceHistoryManager` | Manages snapshots in that app's `workspace_history.json`. |
| `com.wynh.appapp.workspace.WorkspacePackageManager` | Handles selective ZIP export/packaging, Android Sharesheet integration, direct stream export, and secure ZIP extraction with Zip-Slip defense and auto-backup snapshots. |
| `com.wynh.appapp.bridge.NativeStorageBridge` | Main `@JavascriptInterface` exposed to the web runtime as `window.AndroidStorage`, `window.AndroidMemory`, and `window.AndroidNotification`. Manages persistent key-value state, unified file I/O directly in `workspace/`, native system notifications, and `AlarmManager`-backed scheduled notifications. |
| `com.wynh.appapp.ai` | App-owned AI provider settings, encrypted ChatGPT credentials and OpenRouter API key, native model/inference requests, background request service and the origin-restricted asynchronous web message API. |
| `com.wynh.appapp.bridge.ScheduledNotificationReceiver` | Posts a notification armed earlier via `scheduleNotification`. Runs even when the app process is cold, so it re-creates the notification channel itself. |
| `com.wynh.appapp.bridge.AlarmBootReceiver` | Re-arms persisted scheduled notifications after `BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`, which clear pending alarms. |
| `com.wynh.appapp.templates.DefaultWebApp` | Loads the starter template files dynamically from standalone assets (`app/src/main/assets/starter_template/`). |

The Kotlin namespace and installed Android application ID are both `com.wynh.appapp`. Use this package for source declarations, ProGuard rules, and ADB package commands such as `pm path`, `run-as`, `force-stop`, and `monkey`.

---

## 2. Workspace & Unified Directory Architecture

Each saved app has an isolated internal directory:
```
filesDir/subapps/<id>/
├── workspace/
│   ├── index.html   # Primary HTML entrypoint & UI layout
│   ├── manifest.json# App metadata & identity configuration
│   ├── data/        # App data and settings
│   ├── css/         # Stylesheets
│   ├── js/          # App modules and bridge wrapper
│   ├── icon.png     # Optional app icon
│   └── app.log      # Runtime console, error & bridge logs
└── workspace_history.json
```
Stable IDs are stored in `filesDir/subapps/index.json`. Runtime pages use
`https://<id>.app.local/`, isolating WebView origin storage. Bridge state and alarms are scoped to
the same ID. Shortcuts and Recent Apps document tasks carry that ID, so renaming an app does not
change its identity. On first launch, a verified migration moves the former single workspace into
the library.

The starter template ships its own `AGENTS.md` (describing the app itself) and `PACKAGING.md` (documenting the runtime from the web app's point of view: bridge API, platform limits, packaging rules). **When the JavaScript bridge changes, update them accordingly.**

The library’s Settings → AI section exclusively owns AI provider setup. ChatGPT and OpenRouter connections are independent. Settings selects which provider subapps see: ChatGPT (the default), OpenRouter, or Combined. Subapps can
use the origin-limited `window.AppAI` asynchronous channel through `js/bridge.js`; credentials and
request headers remain native and are never written into a subapp workspace.

The starter wrapper exports `ai.providers()`, `ai.models(providerId)`, `ai.start({ providerId, model,
instructions, input, reasoningEffort })`, `ai.result(id)`, `ai.wait(id)`, `ai.cancel(id)` and
`ai.acknowledge(id)`. It dispatches `app2:ai:connection` and `app2:ai:status` events. Native inference
uses ChatGPT Responses streaming or OpenRouter non-streaming Chat Completions and returns text only after a completed response. Results remain
available for retrying a subapp save until acknowledgement or task closure.

### File-Based Persistence Standard (JSON in `data/`)
All application data, domain entities, and user settings **must be stored in plain files inside the `data/` folder** (such as `data/entries.json` and `data/settings.json`). Do not rely on opaque host `SharedPreferences` (`saveState`/`loadState`) for domain configuration. File-based storage guarantees that:
- Data and settings are directly inspectable and editable inside the App Editor Hub (`</>`).
- Data is bundled into ZIP archives when using the Import/Export feature or Android Sharesheet.
- Data is versioned and safely rolled back during workspace snapshot restores.
- Data works seamlessly with the standalone local `dev_server.py`.

### Asset Interception
Each app's `WebView` loads `https://<id>.app.local/index.html`. Requests for that exact app host (e.g. `<link rel="stylesheet" href="style.css">`, `<script src="app.js"></script>`, or JSON/image assets) are intercepted in `WebViewClient.shouldInterceptRequest` and served from that app's `filesDir/subapps/<id>/workspace/` with auto-resolved MIME types.

---

## 3. Android UI & Edge-to-Edge Guidelines

1. **Edge-to-Edge & WindowInsets**:
   - `enableEdgeToEdge()` is called in `MainActivity.onCreate()`.
   - Use `ViewCompat.setOnApplyWindowInsetsListener` to dynamically pad status bar / notch / display cutout insets (`systemBars() | displayCutout()`) on top bars, and navigation bar / soft keyboard (`WindowInsetsCompat.Type.ime()`) on bottom containers.
   - Do **not** hardcode top status bar margins or fixed heights.
   - The runtime `WebView` is rendered edge-to-edge with zero native view padding, so the inner web application owns all of its own padding and layout.
2. **Web Safe Areas** — two sources, combined:
   - Android `WebView` derives `env(safe-area-inset-*)` from the **display cutout only**. It never reports the status or navigation bars, so `env(safe-area-inset-bottom)` is `0px` on a gesture-nav phone.
   - `MainActivity.pushInsetsToWebView()` therefore publishes the real `systemBars() | displayCutout()` insets onto `document.documentElement` as `--app-inset-top`, `--app-inset-right`, `--app-inset-bottom` and `--app-inset-left` (CSS px), and fires an `app2:insets` event whose `detail.imeVisible` reports whether Android's on-screen keyboard is visible. The IME inset is not included in the safe-area variables. The top value is `0` while the App² toolbar is visible, because the toolbar already consumes it, and the full status bar inset in standalone mode.
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
   - Release signing reads `APPAPP_RELEASE_STORE_FILE`, `APPAPP_RELEASE_STORE_PASSWORD`,
     `APPAPP_RELEASE_KEY_ALIAS`, and `APPAPP_RELEASE_KEY_PASSWORD` from Gradle properties.
     Keep these values and the keystore outside the repository (for example, in
     `~/.gradle/gradle.properties`); never commit signing secrets or keystore files.
     Build a signed APK with `./gradlew :app:assembleRelease`; the output is
     `app/build/outputs/apk/release/app-release.apk`.
   - To update an existing installation, the release APK must be signed with the same key as
     that installation. The current AppApp phone installation uses SHA-256 certificate
     fingerprint `d3a30c1acecc9008f11711c39013353f00fc58b540a26eba95bb3556eb02e89a`.
     Verify an APK with `apksigner verify --print-certs <apk>` before installing. A newly
     generated key cannot update an installation signed by the previous key; do not uninstall
     the app to work around a signing mismatch.
3. **Android Target / Min SDK**:
   - `compileSdk = 37`, `targetSdk = 37`, `minSdk = 26` (see the current Gradle configuration).
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
   -keepclassmembers class com.wynh.appapp.bridge.YourNewBridge {
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

`setSelectionMenuSuppressed(Boolean)` hides Android's floating text-selection menu for the current
page load (handles and selection events remain); `MainActivity` and `SubappRuntimeActivity` reset it in `onPageStarted`.

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
binary file that the current subapp's origin interceptor can then serve via `<img src="...">`.

---

## 6. Testing & Verification

- **Gradle Validation**: Use the checked-in Gradle Wrapper for CLI builds, or build/sync in Android Studio. First check `./gradlew --version` so the Gradle version and JVM are known; then run `./gradlew :app:assembleDebug`. Run `./gradlew test` or the relevant module task when JVM tests exist. Include `--warning-mode all` when diagnosing Gradle deprecations; fix the originating build logic/plugin rather than hiding warnings. Do not install or update Gradle outside the wrapper just to build this project.
- **Persistence Verification**: When modifying storage logic, test by adding items, terminating the process, and verifying that state reloads identically on restart.
- **History & Snapshots**: Verify that workspace snapshots are taken before "Run" and that snapshot restoration properly updates the editor and runtime.
- **Example App Device Tests**: `examples/<app>/` is source code in this repository; it is not automatically copied to an installed App² instance. Before reporting a device test, sync changed app-owned files to the selected app's `filesDir/subapps/<id>/workspace/`, compare source and device hashes, then relaunch that app. Never overwrite `data/`, `app.log`, or other user files just to test code. See `examples/working_hours/AGENTS.md` for the Working Hours sync procedure.
- **Keyboard-Aware Bottom Bars**: Do not infer that the on-screen keyboard is open from input focus. On Android, use `app2:insets` event `detail.imeVisible`; a hardware keyboard can focus a text field while the IME remains hidden. For browser previews without the host event, compare the viewport height to its pre-keyboard baseline.

### Android emulator and device testing

- Check `adb devices -l` before starting or installing. Use `-s <serial>` on every ADB command whenever an emulator and an external device may both be connected. Confirm the selected serial is the intended target; never rely on whichever device ADB happens to choose.
- Emulator tests are the default for flows that create, edit, import, reset, delete, migrate, or otherwise mutate subapp data. The development host's Android SDK is at `/home/melo/Android/Sdk`; its emulator executable is `/home/melo/Android/Sdk/emulator/emulator` and is not on `PATH`. The installed AVD is named `Medium_Phone` (Android 36, x86_64). Do not search for another SDK or install another emulator.
- To start the AVD when it is not running, run this in a terminal and leave it open:
  ```sh
  /home/melo/Android/Sdk/emulator/emulator -avd Medium_Phone -no-snapshot-load -no-boot-anim -gpu swangle
  ```
  On this host, the default software renderer repeatedly exited with status 139; `-gpu swangle` booted successfully. `-no-snapshot-load` requests a cold boot while preserving AVD data. Do not add `-wipe-data` for routine tests.
- Wait for the selected device to be in state `device`, and, for an emulator, wait for `adb -s <serial> shell getprop sys.boot_completed` to print `1`. If the target does not become ready, report that instead of claiming an app test ran. In the examples below, replace `<serial>` with the exact serial shown by `adb devices -l` and `<id>` with the selected test subapp ID.
- **External-device data protection is mandatory.** Treat a connected physical device as containing valuable user data. Use `Medium_Phone` for every test that creates, edits, imports, reorders, resets, deletes, migrates, or otherwise mutates subapp data. Physical-phone tests should be read-only launch/smoke checks by default. Before any phone update, record the exact ADB serial and model. Phone updates target only the existing AppApp installation in the main profile, user 0. Check that AppApp is already installed in that same user (`pm list packages --user <userId> com.wynh.appapp` and `pm path --user <userId> com.wynh.appapp`). Samsung Secure Folder is outside the work scope and has no AppApp data. Do not access, inspect, launch, install into, or modify Secure Folder, and do not enumerate profiles as part of routine phone checks. Never use another profile as a substitute for the main-profile installation. If the expected package is absent in the intended profile, or the profile cannot be identified, stop. Do not install a fresh copy into another profile as a substitute.
- **Phone control is user-gated by wireless debugging.** The owner keeps wireless debugging off and enables it only when they want agents to work on the phone. While the phone is connected over ADB, that is standing permission for agents to control its interface (`adb shell input`, `screencap`, `uiautomator dump`) in the main profile, user 0, to run the backup, install the verified release update, and perform the read-only checks described here. It does not relax any other rule in this section: Secure Folder and other profiles stay off limits, and never uninstall, clear data, or alter user subapps. If the phone is not connected, ask the owner to enable wireless debugging rather than working around it.
- Before a phone update or any phone test that could write app data, use library → Settings → Subapp backup → Back up all subapps. Prefer this single full-library ZIP over exporting apps individually. Copy it off the phone, verify ZIP integrity, and check `backup.json`: every listed app must have its `manifest.json` and user `data/` files, and every file must match its recorded size and SHA-256. Record the library's app names/count before updating. Full-library backup includes workspace files, data and binary assets, but excludes App² settings, history, native state, reminders, AI credentials and browser storage; restoration adds new copies instead of overwriting existing apps. Use individual exports only when the full-library backup flow is unavailable. A Google/Android device backup is not a verified backup of AppApp workspaces. If an export cannot be made and verified, run the mutating test on the emulator. Never use a user's app as a test fixture, import over an existing app, delete/recreate a phone subapp during cleanup, or alter personal records, order, history, notifications, alarms, or bridge state. A phone-specific mutation is allowed only in a clearly named separate disposable subapp with test-only data, and it must remain separate from existing apps.
- `adb install -r` retains app data only when package name, Android user/profile, and signing certificate all match; it does not prove that data exists in that profile or restore missing data. For phone updates, build the signed release APK and compare its `apksigner verify --print-certs` SHA-256 with the installed package's certificate before installing. Use the release signing key; do not install a debug APK to a phone unless its certificate is verified to match. If package/profile/certificate checks fail or installation reports a signing mismatch, stop and report it. Never use `adb uninstall`, `pm clear`, clear-storage UI, factory reset, or reinstall without `-r` on an external device. Never work around a mismatch by uninstalling.
- For routine phone updates, make and verify one full-library backup before installing, and record the library's app names/count. After installing, open the library and confirm the same apps are present. Do not create a second backup, compare before/after file hashes, or inspect data sentinels as a routine step. If the library is empty or apps are unexpectedly missing, stop and report the observed state; do not restore, import, delete, or recreate entries without the owner's instruction. Report the library check accurately; it does not verify every app's internal data.
- Confirm AppApp is installed before testing:
  ```sh
  adb -s <serial> shell pm path com.wynh.appapp
  ```
  Install a debug update with `adb -s <serial> install -r app/build/outputs/apk/debug/app-debug.apk` only on an emulator. On a physical phone use the signed release APK and verify the certificate first. If replacement fails due to a signing mismatch, stop and report it—do not uninstall to work around it.
  For a release update on a device, use the matching signed APK:
  `adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk`.
  Confirm its certificate matches the installed app first; `-r` preserves app data.
- For a web-app JavaScript/CSS/HTML change, the WebView reads the selected app's `filesDir/subapps/<id>/workspace/`, not the repository or APK assets directly. An initialized workspace can be stale, including after editing the starter template. On the emulator, sync only changed app-owned code/assets into a disposable test subapp's matching workspace path; do not sync over user data. Identify its `<id>` from the library/repository before copying. With a debuggable build, stage a file and copy it to that exact workspace path with `run-as`, then compare hashes:
  ```sh
  adb -s <serial> push path/to/changed-file /data/local/tmp/appapp-test-file
  adb -s <serial> shell run-as com.wynh.appapp cp /data/local/tmp/appapp-test-file files/subapps/<id>/workspace/path/to/changed-file
  sha256sum path/to/changed-file
  adb -s <serial> shell run-as com.wynh.appapp sha256sum files/subapps/<id>/workspace/path/to/changed-file
  ```
  Only use direct sync on an emulator or a dedicated test subapp on an external device; never copy to an unknown ID or a user's app. If `run-as` is unavailable, use the app's editor or rebuild/reinstall; do not work around the app sandbox. For native Android code or manifest changes, build and install the APK rather than copying files into a web workspace.
- After syncing, restart the app so the WebView reloads the files, then launch it on the explicitly selected serial:
  ```sh
  adb -s <serial> shell am force-stop com.wynh.appapp
  adb -s <serial> shell monkey -p com.wynh.appapp 1
  ```
  Verify the behavior in the UI. Use `adb -s <serial> exec-out screencap -p > /tmp/appapp-device.png` when a screenshot helps. Report the emulator/device model and exactly which controls and flows were exercised; do not claim an interaction was tested if the installed app does not expose it.

---

## 7. Common Gotchas & Environment Quirks

1. **Use the checked-in Gradle Wrapper**:
   - The repository includes `./gradlew`, `gradlew.bat`, and `gradle/wrapper/gradle-wrapper.jar`; the selected Gradle distribution is in `gradle/wrapper/gradle-wrapper.properties`.
   - Use `./gradlew` for CLI validation or Android Studio's configured Gradle integration. Do not hunt for or invoke a separate Gradle installation.
2. **Terminal Sandbox Isolation**:
   - The execution sandbox isolates filesystem access outside the workspace (`/home/melo/Android/Sdk` etc. cannot be probed from standard sandbox mode).
3. **Resource Binding CamelCase**:
   - XML view IDs like `btn_reset` or `btn_edit_code` map to ViewBinding properties `binding.btnReset` and `binding.btnEditCode`. When updating layouts, ensure all references in `MainActivity.kt` stay synchronized.
4. **MIME Resolution & Asset URLs**:
   - Each app's `https://<id>.app.local/` requests are intercepted from its own workspace. Ensure any new file types are registered in `WorkspaceManager.getMimeType()`.
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


## Subapp AI guide

Manage provider connections exclusively through **library → Settings → AI**. ChatGPT uses OAuth; OpenRouter uses one native encrypted API key. Editors and subapps have no credential controls. **Available to subapps** exposes exactly one provider: `chatgpt`, `openrouter`, or `combined` (App² AI). Combined is connected when at least one source is connected and lists ChatGPT models first, then OpenRouter models, with source labels. Model IDs are opaque, stable and unique within the exposed provider; do not parse them or hardcode model IDs. Explicit calls to hidden providers fail with `provider`.

The native exposure dropdown lists connected providers; Combined is selectable when both are connected. With no connection it is disabled. An unavailable saved exposure is retained without silently switching providers; a previously selected Combined exposure can still use its remaining connected source.

OpenRouter defaults to explicitly free text models; paid models require native opt-in. Missing or unknown prompt/completion prices are excluded from free mode. Optional pricing fields, including per-request fees, must be zero when supplied; their omission is valid. Pricing is refreshed before generation. The catalog includes OpenRouter’s Free Models Router (`openrouter/free`), which chooses an available free model, as well as individual free models. Free models still have quotas and availability limits. ChatGPT consumes plan usage; OpenRouter consumes quota or credits. There is no automatic model/provider fallback or inference retry. OpenRouter advertises an empty `reasoningLevels` list in this version. Connection/exposure changes dispatch `app2:ai:connection`; rediscover the exposed provider and clear unavailable model/reasoning choices. Already-started requests retain their original route and can be retrieved and acknowledged after an exposure change. Disconnecting a source cancels only its unfinished requests; terminal results remain available for saving.

Browser previews have `ai.available === false` and make no AI requests unless a development mock is explicitly supplied. The wrapper keeps `chatgpt` as its omitted-provider default for compatibility; apps using host exposure must discover and explicitly pass the exposed provider ID.

Import `ai` from `js/bridge.js`. `ai.available` means the native channel exists, **not** that an account is connected. All methods below return Promises:

| Method | Resolved value |
| --- | --- |
| `ai.providers()` | `{providers: [{id, name, connected}]}` (one host-selected provider; connection may be false) |
| `ai.models(providerId)` | `{models: [{id: "provider-model-id", name: "Display name", reasoningLevels: ["low", "medium", "high"]}]}` |
| `ai.start({providerId, model, instructions, input, reasoningEffort?})` | Request ID string |
| `ai.result(id)` | Pending: `{id, done: false, value: null}`; success: `{id, done: true, value: "completed text"}` |
| `ai.wait(id)` | Completed text string; only for IDs started by this page instance |
| `ai.cancel(id)` | `true`; cancels and releases the request |
| `ai.acknowledge(id)` | `true`; releases a terminal result after saving or handling failure |

Model lists preserve each source’s server order, with ChatGPT before OpenRouter in Combined mode. Treat IDs as provider-specific; use the returned list rather than hardcoding models. `reasoningLevels` may be empty. Omit `reasoningEffort` for the provider default; explicit efforts must come from the selected model's list. The examples' model IDs and capabilities above are illustrative.

Native command failures reject with `Error`, usually with `.code` and `.message`. A cached terminal failure from `ai.result()` may instead have `{id, done: true, error: {code, message}}` (its `value` is not response text). Handle both forms. Results created in the current page may contain additional wrapper internals; persist your own metadata, not the result object. Common failure codes include `authentication`, `permission`, `usage_limit`, `network`, `service`, `invalid_response`, `models`, `invalid_request`, `provider`, `request_in_progress` and `not_found`. An unavailable browser or command timeout may reject without a code. Show failures and require a deliberate new Generate action; never automatically retry inference.

This runnable module example (placed under `js/`) saves before acknowledging:

```js
import { ai, writeFile } from './bridge.js';
export async function generateExample() {
  if (!ai.available) throw new Error('Open this app in App².');
  const { providers } = await ai.providers();
  const provider = providers.find(p => p.connected);
  if (!provider) throw new Error('Connect the selected provider in library Settings → AI.');
  const { models } = await ai.models(provider.id);
  if (!models.length) throw new Error('No models available.');
  const id = await ai.start({providerId: provider.id, model: models[0].id,
    instructions: 'Use plain language.',
    input: 'Write one short sentence explaining what a subapp is.'});
  // Persist id immediately for recovery in a production workflow.
  let text;
  try { text = await ai.wait(id); }
  catch (error) {
    await ai.acknowledge(id).catch(() => {});
    throw error;
  }
  if (!writeFile('data/example.json', {id, text}))
    throw new Error('Save failed: retain id and retry saving before acknowledgement.');
  await ai.acknowledge(id);
  return text;
}
```

The Starter Demo's `js/ai-demo.js` provides the complete recoverable workflow: `data/ai-demo.json` holds `pending` request metadata and one `latest` result containing its ID, provider, model, requested reasoning, prompt, timestamp and text. The request ID is the idempotency key. Save the JSON result first, then acknowledge, then clear pending metadata. A save failure retains the request and offers **Retry saving** without generating again. If the page is recreated or resumes, poll `ai.result(id)` for the persisted ID; `ai.wait(id)` cannot recover a prior page's ID. A missing request is interrupted; preserve any saved result and require deliberate generation. Release handled terminal failures with acknowledgement. Render generated text through `textContent`.

Listen for `app2:ai:connection` with `event.detail = {providerId, connected}` and refresh provider/model discovery. `app2:ai:status` currently reports `{id, status: "running"}`; use result polling for completion/failure, rather than assuming a completion status event. Refresh/recover on `pageshow` and `visibilitychange` when visible. These events expose no account identifiers or credentials.

The native host binds requests to the exact subapp HTTPS origin and main frame and derives subapp/task ownership. Another subapp cannot retrieve, cancel or acknowledge your request. Callers cannot provide arbitrary endpoints or authentication headers. Only one unfinished **or unacknowledged** request per subapp is allowed. Native credentials remain encrypted outside workspaces, snapshots and exports.

Requests use native Responses streaming with `store:false`; text succeeds only after `response.completed`. Switching apps or opening the sign-in browser allows the active request to continue under a foreground notification. Rotation preserves the native request. Explicit reload, final screen closure, deletion and swiping the owning task from Recent Apps cancel its requests; closing another task does not. Completed results remain available until acknowledgement or task closure. Process death, force-stop and reboot do not restart inference. Persisted IDs can become `not_found`. Web workflows after inference (validation and auto-save) resume when the web app runs again; the foreground service does not execute the whole web app in the background.


## Starter Demo browser development

The Starter Demo includes `dev_server.py`. From the repository, run:

```sh
python3 app/src/main/assets/starter_template/dev_server.py
# Optional deterministic AI simulation, without credentials or ChatGPT usage:
python3 app/src/main/assets/starter_template/dev_server.py --mock-ai
```

Open `http://localhost:8888`. A positional port is also accepted, e.g. `dev_server.py 9000 --mock-ai`.
For an exported workspace, run `python3 dev_server.py` inside that workspace. The server always
serves the script's directory and binds only to localhost. Use Python 3.9 or newer, with no dependencies.

Browser file operations read/write actual workspace files, including JPEG photos; development
preferences live in `data/dev-preferences.json`. Use a disposable workspace copy for tests because
notes, photos and AI results change files on disk. The bridge probes `/__bridge/info` once; ordinary
static previews retain localStorage. After selecting the server, failed writes return false and
failed reads throw; they never silently fall back to localStorage.

`--mock-ai` enables a visibly labelled development mock and one offline model. Generate returns
fixed text after 1.5 seconds. Requests survive browser reloads while the server runs and occupy
one slot until cancellation or acknowledgement. Restarting the server loses requests; persisted
IDs then return `not_found`. No external requests are made. Mock requests do not reproduce native
task closure or background service behavior. Notifications keep browser behavior; Android
permissions, exact alarms, WebView insets and real AI must be tested on Android.

## Native GitHub subapp updates

Share & Package App is export-only. ZIP installation is available in the library
and creates a separate app. Existing apps update through native Update app,
following public GitHub package metadata in `manifest.json.app2Update`. The host
pins downloads to one commit, reviews managed changes/local edits and requires
confirmation. Managed files update; seeds copy only when absent; all unowned files
remain. `data/` can contain managed shipped definitions as well as user records,
so declare ownership explicitly. Domain records and preferences must be seeds,
never managed replacements. Publisher app identity differs from installed App² ID.

The wrapper exports `updates.available` and `updates.open()` (Promise resolving
true when native review opens). Browsers report unavailable. The owning HTTPS
origin and main frame are required; web callers cannot supply endpoints or install
silently. Finish/save/acknowledge or cancel AI requests before updating. App² 1.3.0
(version code 4) adds this channel. Native update state, full binary backups and
recovery journals live beside the workspace; ordinary history is not a full backup.
See the App² repository's `docs/SUBAPP_UPDATES.md` for publisher metadata, recovery,
version history, deterministic packaging and the phone deployment checklist.


## Text to Speech

Import `speech` from `js/bridge.js`. `speech.available` reports a supported native channel,
not engine readiness. Browser previews reject calls with `unavailable`.

- `await speech.voices()` returns `{voices: [{id, name, language, requiresNetwork}]}` from
  Android's configured TTS engine. Discover voice IDs; do not hardcode them. Online voices
  may transmit text through the configured engine and require connectivity.
- `await speech.speak({text, language, voiceId?, rate?, pitch?})` requires a BCP 47 language
  such as `fr-FR`, `de-DE`, or `ja-JP`. No language detection is performed. An explicit voice
  must match the requested language and script. Regional fallback within that language is allowed.
  Rate and pitch default to 1 and accept finite values from 0.5 to 2.
- The speak Promise resolves with `{status: "completed"}` after playback or
  `{status: "cancelled"}` after replacement/Stop. New playback replaces previous playback.
- `await speech.stop()` resolves to `true`, including when idle.
- Errors reject with `.code` and `.message`: `unavailable`, `invalid_request`, `language`,
  `missing_data`, `voice`, `network`, `synthesis`, `cancelled`, or command `timeout`.
  Missing data must be installed through Android TTS settings; do not retry automatically.
  Empty text and text beyond Android's engine input limit are rejected.

Subapps provide their own pronunciation buttons using `speech.speak({text, language})`.
App² does not add a Speak action to Android’s text selection menu.
A native **Stop** control appears during playback. Speech stops on backgrounding, page reload,
navigation, workspace replacement, or closure; it is not persisted or resumed.
The channel accepts only the owning subapp HTTPS origin and main frame. No AI account is needed.
Store any subapp voice preferences in plain JSON under `data/`. The Starter Demo includes
`js/speech-demo.js`; existing apps adopt the speech API explicitly.


### Pronunciation example

Use this in a JavaScript module under `js/`:

```js
import { speech } from './bridge.js';
if (!speech.available) throw new Error('Open this app in App² to hear pronunciation.');
try {
  const result = await speech.speak({text: 'Bonjour', language: 'fr-FR', rate: 0.8});
  // result.status is "completed" or "cancelled".
} catch (error) {
  // Show error.message; require a deliberate retry.
  console.error(error.code, error.message);
}
```

Existing workspaces are not rewritten; adopt the current wrapper explicitly to add speech buttons.


## Device Translation

Import `translation` from `js/bridge.js`. These methods return Promises:

- `await translation.availability()` returns `{available: true}` only when Android 10/API 29
  or later has an installed activity that handles `Intent.ACTION_TRANSLATE`.
- `await translation.open({text})` opens the default handler, or Android's resolver when a
  handler must be selected, and resolves to `{opened: true}` after launching it.
  It does not return translated text or confirm translation completion.

Supply canonical selected text from the subapp's own selection model. Text is forwarded unchanged;
blank text and text longer than 100000 JavaScript UTF-16 code units are rejected. Android's standard
translation intent only accepts text (`Intent.EXTRA_TEXT`): it has no source or target language
parameters. The receiving app controls language detection, target language, and translation UI.
No contextual menu action is added by App². Call `open` only after a deliberate user action.
Opening translation sends the supplied text to the selected external application.

The channel is restricted to the owning subapp HTTPS origin and main frame, and requires a
foreground app. Browser previews and unsupported hosts report `{available: false}` from
`availability()`; `open()` rejects with `unavailable`. Native failures reject with `.code` and
`.message`: `invalid_request` or `unavailable`; the wrapper may also reject with `timeout`.
Handle errors and require deliberate retry. Availability can change between checking and opening.

```js
import { translation } from './bridge.js';
translateButton.hidden = !(await translation.availability()).available;
translateButton.addEventListener('click', async () => {
  try {
    await translation.open({text: canonicalSelectedText});
  } catch (error) {
    status.textContent = error.message;
  }
});
```

Existing workspaces must explicitly adopt the updated `js/bridge.js` to use this API.
