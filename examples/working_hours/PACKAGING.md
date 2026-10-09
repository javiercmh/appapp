# PACKAGING.md — App² (AppApp) Subapp Packaging & Runtime Contract

This document provides the technical specification, runtime environment details, and packaging guidelines for web applications built to run inside the **App² (AppApp)** Android host runtime.

---

## 1. Directory Structure & Required Files

All project code and runtime app assets reside within the root workspace directory:

```
[workspace_root]/
├── index.html       # Primary HTML entrypoint (referenced in manifest.json)
├── manifest.json    # App identity & packaging metadata
├── AGENTS.md        # App-specific feature and business logic description
├── PACKAGING.md     # Platform integration & packaging documentation (this file)
├── css/
│   └── style.css    # Stylesheet with safe-area variables
├── js/
│   ├── app.js       # Main ES module bootstrap (<script type="module" src="js/app.js">)
│   ├── bridge.js    # Native Android bridge wrapper (window.AndroidStorage)
│   ├── store.js     # Data persistence & state management
│   ├── timefield.js # 24h HH:MM stepper control (±15 min)
│   └── ui.js        # DOM rendering & UI interaction
├── data/
│   ├── entries.json # Shift entries JSON data store
│   └── settings.json# Settings & default times JSON data store
├── icon.png         # Home screen / launcher icon (referenced by manifest.json `icons`)
└── dev_server.py    # Local computer dev server
```

---

## 2. `manifest.json` Specification

The `manifest.json` file is required by the host and defines the app identity, shortcut metadata, entrypoint, and packaged files:

```json
{
  "name": "Working Hours",
  "short_name": "Hours",
  "version": "1.0.0",
  "templateVersion": 3,
  "description": "Daily working hours, breaks, and overhours balance tracker",
  "main": "index.html",
  "files": [
    "index.html",
    "css/style.css",
    "js/app.js",
    "js/bridge.js",
    "js/store.js",
    "js/timefield.js",
    "js/ui.js",
    "manifest.json",
    "AGENTS.md",
    "PACKAGING.md",
    "dev_server.py",
    "data/entries.json",
    "data/settings.json",
    "icon.png"
  ],
  "icons": [
    {
      "src": "icon.png",
      "sizes": "512x512",
      "type": "image/png"
    }
  ]
}
```

Notes:

- `name` is the full app name; `short_name` is the launcher label. They are distinct, and the host's
  Configure App dialog no longer collapses one into the other.
- `icons[0].src` supplies the pinned-shortcut icon. Setting one from the Configure App dialog also
  appends the file to `files`.
- The host enumerates the workspace directory directly, so `files` does not gate what ships — keep it
  accurate anyway; it is the manifest of record for anyone reading the project.

---

## 3. Android Runtime & Bridge Architecture

### 3.1. Local Asset Interception
- App² loads `https://app.local/index.html` inside a hardware-accelerated Android `WebView`.
- Requests to `https://app.local/*` (e.g. `css/style.css`, `js/app.js`, `data/entries.json`, images) are intercepted by Android's `WebViewClient.shouldInterceptRequest` and served directly from the device's internal storage directory: `filesDir/workspace/`.
- The host automatically resolves MIME types for standard web extensions:
  `.html`, `.htm`, `.css`, `.js`, `.mjs`, `.json`, `.md`, `.txt`, `.svg`, `.png`, `.jpg`, `.jpeg`, `.gif`, `.webp`, `.woff`, `.woff2`, `.ttf`.

### 3.2. Safe Areas & Window Insets

The `WebView` is edge-to-edge with **zero** native padding — the subapp owns all of its own padding.
Two sources describe the unusable edges, and a subapp must combine them:

| Source | Covers | Notes |
|---|---|---|
| `env(safe-area-inset-*)` | Display cutout only | Requires `viewport-fit=cover` on the viewport meta tag. Android `WebView` never reports the status or navigation bars here, so it is `0px` on a gesture-nav phone with no notch. |
| `--app-inset-top/right/bottom/left` | Status bar, navigation bar, cutout | Set by the host on `document.documentElement` in CSS px. `--app-inset-top` is `0` while the App² toolbar is visible (it already consumes that inset) and the full status bar inset in standalone mode. |

The host republishes the variables whenever the insets change and after every page load, and fires an
`app2:insets` event on `window` for layout that cannot be expressed in CSS.

`css/style.css` derives one token per edge and uses it everywhere:

```css
:root {
  --app-inset-bottom: 0px; /* default, so desktop and dev_server.py render identically */
  --sa-bottom: max(env(safe-area-inset-bottom, 0px), var(--app-inset-bottom));
}
.tabbar { padding-bottom: var(--sa-bottom); }
```

### 3.3. JavaScript Bridge Surface (`window.AndroidStorage`)
App² exposes a unified `@JavascriptInterface` bridge object aliased as `window.AndroidStorage`, `window.AndroidMemory`, and `window.AndroidNotification`.

| Method | Returns | Notes |
| :--- | :--- | :--- |
| `saveState(key, value)` / `loadState(key, fallback)` / `removeState(key)` | `Boolean` / `String` / `Boolean` | `loadState` **requires both arguments**. |
| `writeFile(name, text)` / `readFile(name)` / `deleteFile(name)` / `fileExists(name)` | `Boolean` / `String` / `Boolean` / `Boolean` | Synchronous. `readFile` returns `""` for both missing and empty. |
| `writeFileBase64(name, base64)` / `readFileBase64(name)` | `Boolean` / `String` | Binary assets (photos, icons). |
| `listFiles()` / `getStorageStats()` | JSON `String` | Parse before use. |
| `hasNotificationPermission()` / `requestNotificationPermission()` | `Boolean` | The request is async: listen for `app2:notifperm` on `window` for the real answer. |
| `showNotification(title, message)` / `cancelNotification(id)` | `Boolean` | Posts immediately. |
| `openNotificationSettings()` | `Boolean` | Opens the OS notification screen for App². The only way back for a user on API 26-32 (no runtime prompt exists) or who already refused the prompt. Absent on the dev server — feature-detect it. |
| `requestExactNotificationPermission()` | `Boolean` | Opens Android's Alarms & reminders screen after a user action. Returns whether Android accepted the settings launch, not whether access was granted. |
| `scheduleNotification(id, triggerAtMillis, title, message)` | `Boolean` | **Fires with the app closed.** `triggerAtMillis` is epoch ms **as a string**; a JS number crosses the bridge as a double and loses the value. |
| `cancelScheduledNotification(id)` | `Boolean` | Re-using an `id` in `scheduleNotification` also replaces that alarm, so rescheduling is idempotent. |
| `canScheduleExactNotifications()` | `Boolean` | Check before scheduling and after returning from Settings. |

### 3.4. Scheduled Notification Reliability

`scheduleNotification` is backed by exact `AlarmManager` alarms, and the host persists pending alarms
so they are re-armed after exact access is granted, reboot, app update, and app startup. Listen for
`app2:alarmperm` (`event.detail.granted`) or re-check access when the page resumes. A schedule request
returns `false` and creates no alarm while exact access is missing:

1. **Exact access is user controlled on API 31+.** Call `requestExactNotificationPermission()` only
   after an explicit user action. Do not create an approximate duplicate when access is missing.
   API 26-30 supports exact scheduling without special access.
2. **Notification permission is checked at fire time.** An alarm armed while notifications were
   allowed silently posts nothing if the user revokes permission later.

Outside the Android host (e.g. under `dev_server.py`), `js/bridge.js` falls back to a `setTimeout`
that only survives while the page is open. Treat "fires while closed" as an Android-only guarantee.
Exact alarms remain subject to Android idle-mode limits, and force-stopping the app prevents delivery
until it is opened again.
