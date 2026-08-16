# AGENTS.md — building apps that run in App²

You are editing a **web app that runs inside App²**, an Android host that renders your files in a
WebView and gives them access to native device features.

**There is no build step.** No npm, no bundler, no TypeScript, no framework. Edit the files in this
workspace directly. The user taps **▶ Run** to reload. Keep everything vanilla and dependency-free.

---

## 1. How this app is structured

| File | Owns |
| :--- | :--- |
| `index.html` | Markup and the shell. Most content is rendered by JS. |
| `style.css` | All styling. One `--accent` custom property drives the theme colour. |
| `app.js` | Entry point. Boots the app, wires modules, handles tab navigation. |
| `bridge.js` | **The only file that touches the native bridge.** |
| `store.js` | Data: reads/writes `entries.json`, photo lifecycle, preferences. |
| `ui.js` | DOM rendering. Never calls the bridge directly. |
| `manifest.json` | App metadata. `main` sets the entry point and must stay accurate. |
| `entries.json` | The user's saved notes. |

**Dependencies point one way: `ui → store → bridge`.** Follow it and changes land in the right
place: a new visual feature goes in `ui.js`; a new thing to persist goes in `store.js`; a new native
capability goes in `bridge.js`. Never call `window.AndroidStorage` from `ui.js`.

Modules are loaded with `<script type="module" src="app.js">`, so:

- **Use flat relative imports with the `.js` extension**: `import { load } from './store.js'`.
  Bare specifiers (`from 'store'`) do not work — there is no resolver.
- **`type="module"` defers execution.** By the time your module runs, `DOMContentLoaded` has usually
  already fired. Initialise at module top level; do **not** wrap setup in a `DOMContentLoaded`
  listener or it may never run.
- A bad import path fails the whole module graph silently in the UI — check the Guide tab's Console.

---

## 2. Directory structure & relative paths

App² serves your workspace at `https://app.local/`. All files live within your project workspace, and **nested subdirectories are fully supported** (e.g. `css/style.css`, `js/app.js`, `assets/images/photo.jpg`, `data/entries.json`).

- Reference assets by their relative path: `<img src="assets/photo_123.jpg">`, `<link href="style.css">`.
- Relative ES module imports work naturally: `import { load } from './store.js'`.
- Bridge calls like `bridge.writeFile("data/notes.json", ...)` automatically create parent directories as needed.
- Relative paths are protected against directory traversal (`..` outside root is prevented).

The host serves these extensions with a correct MIME type:

```
.html .htm .css .js .mjs .json .md .txt
.svg .png .jpg .jpeg .gif .webp
.woff .woff2 .ttf
```

Anything else is served as `application/octet-stream`. Adding a new type requires changing the
Android host, so stick to this list.

---

## 3. The native bridge

All methods live on `window.AndroidStorage`. `window.AndroidMemory` and
`window.AndroidNotification` are **aliases of the same object** — there is no separate memory or
notification API. When the app runs in a normal desktop browser these globals are absent, which is
how `bridge.js` detects the fallback path.

`bridge.js` already wraps everything below. **Extend `bridge.js` rather than calling these globals
from elsewhere.**

### Key-value preferences (Android SharedPreferences)

| Method | Returns |
| :--- | :--- |
| `saveState(key, value)` | `Boolean` |
| `loadState(key, defaultValue)` | `String` |
| `removeState(key)` | `Boolean` |
| `clearAllState()` | `Boolean` |

### Files (workspace directory)

| Method | Returns |
| :--- | :--- |
| `writeFile(name, text)` | `Boolean` |
| `readFile(name)` | `String` |
| `writeFileBase64(name, base64)` | `Boolean` — binary; accepts a bare payload or a full `data:…;base64,…` URL |
| `readFileBase64(name)` | `String` |
| `fileExists(name)` | `Boolean` |
| `deleteFile(name)` | `Boolean` |
| `listFiles()` | `String` — JSON array of `{name, size, lastModified}` |
| `getStorageStats()` | `String` — JSON with `usableSpaceBytes`, `totalSpaceBytes`, `appFilesCount`, `appFilesBytes`, `filesDir` |

### Notifications

| Method | Returns |
| :--- | :--- |
| `hasNotificationPermission()` | `Boolean` |
| `requestNotificationPermission()` | `Boolean` — see gotcha below |
| `showNotification(title, message)` | `Boolean` |
| `cancelNotification(id)` | `Boolean` |

---

## 4. Gotchas that bite silently

1. **`loadState` needs both arguments.** `loadState('key')` throws. Always pass a default:
   `loadState('key', '')`.
2. **`requestNotificationPermission()` returns `false` immediately** even when the user is about to
   grant it — the Android dialog is asynchronous. Listen for the
   `app2:notifperm` event on `window` (`event.detail.granted`), and re-check
   `hasNotificationPermission()` on `focus` as a fallback. Never treat the return value as the answer.
3. **`readFile` returns `""` for both "missing" and "empty".** Pair it with `fileExists` when the
   difference matters.
4. **Bridge calls are synchronous and block the JS thread.** Keep payloads small. Before saving a
   photo, downscale it through a `<canvas>` (max ~1280px, `toDataURL('image/jpeg', 0.82)`) — a raw
   camera photo is several megabytes and will jank the UI. Writes over ~6 MB are rejected outright.
5. **`writeFile` is text only.** Images and other binaries must go through `writeFileBase64`, or the
   bytes get mangled by UTF-8 encoding.
6. **Do not use `alert()` or `confirm()`.** They render as jarring system dialogs on mobile. Use
   inline validation and the toast pattern in `ui.js`.

---

## 5. What the platform can and cannot do

**Available.** The app runs on a real secure origin, so `localStorage`, IndexedDB, `fetch`, Canvas,
WebGL, Web Audio, CSS animations and `env(safe-area-inset-*)` all work. The host has network
permission, so remote URLs load — but **prefer offline-first**: this is a phone app that should work
with no signal. Do not add CDN dependencies for things you can write inline.

`<input type="file">` **works** and opens the Android file/photo picker. Read the result with
`FileReader` and persist it with `writeFileBase64`.

**Not available.**

- **Camera and microphone.** `getUserMedia()` is auto-denied — the host has no camera permission.
  Use `<input type="file" accept="image/*">` instead; the picker offers the camera on most devices.
- **Geolocation.** Denied for the same reason.
- **Background execution.** Timers stop when the app is closed. To schedule anything, persist a
  target timestamp and check for due items on startup — `app.js` demonstrates this pattern with
  reminders. Do not rely on `setTimeout` surviving a close.
- **Service workers** are not registered, so there is no offline cache layer beyond the workspace.

---

## 6. Debugging

Every `console.log` / `console.error` and every uncaught exception is captured by the host and
appended to `app.log` **in this workspace**. That means the app can read its own logs:

```js
const log = AndroidStorage.readFile('app.log');
```

The Guide tab's Console does exactly this. Use that pattern instead of building a logger.

---

## 7. What the user is trying to do

The workflow is **edit → ▶ Run → Install**. "Install" pins a home-screen shortcut; launched from
there, the App² toolbar is hidden and the app fills the whole screen. So the app must look finished
on its own — it should never depend on App² chrome being visible.

Design for that:

- Mobile-first. Assume a phone held in one hand.
- Put primary navigation and actions within thumb reach, near the bottom.
- Touch targets ≥48px. No hover-only affordances — there is no cursor.
- Respect `env(safe-area-inset-*)` for notches and gesture bars.
- Give feedback on tap (`:active` states); a phone has no hover to signal interactivity.

---

## 8. House rules

- **Do not rename or delete `index.html` or `manifest.json`.** The host protects and depends on them.
- **Update `manifest.json`'s `files` array** when you add a file.
- Keep it vanilla — no frameworks, no build tooling, no package manager.
- Prefer `textContent` over `innerHTML` when inserting user-entered text.
- Do not reference `NativeMemoryBridge` or call `AndroidMemory.allocate()`. That class exists in the
  Android host but is never registered, so those methods do not exist at runtime.
