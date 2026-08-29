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
    "data/settings.json"
  ]
}
```

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
| `scheduleNotification(id, triggerAtMillis, title, message)` | `Boolean` | **Fires with the app closed.** `triggerAtMillis` is epoch ms **as a string**; a JS number crosses the bridge as a double and loses the value. |
| `cancelScheduledNotification(id)` | `Boolean` | Re-using an `id` in `scheduleNotification` also replaces that alarm, so rescheduling is idempotent. |
| `canScheduleExactNotifications()` | `Boolean` | `false` means the OS batches the alarm and it may fire a few minutes late. |

### 3.4. Scheduled Notification Reliability

`scheduleNotification` is backed by `AlarmManager`, and the host persists pending alarms so they are
re-armed after a reboot or app update. Two limits are worth designing around:

1. **Inexact by default on API 33+.** The runtime does not request the Play-restricted
   `USE_EXACT_ALARM` permission, so on newer Android it uses `setAndAllowWhileIdle`. The alarm still
   fires during Doze, but within a batching window rather than to the minute.
2. **Notification permission is checked at fire time.** An alarm armed while notifications were
   allowed silently posts nothing if the user revokes permission later.

Outside the Android host (e.g. under `dev_server.py`), `js/bridge.js` falls back to a `setTimeout`
that only survives while the page is open. Treat "fires while closed" as an Android-only guarantee.
