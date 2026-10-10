# AGENTS.md — building apps that run in App²

You are editing a **web app that runs inside App²**, an Android host that renders your files in a
WebView and gives them access to native device features.

**There is no build step.** No npm, no bundler, no TypeScript, no framework. Edit the files in this
workspace directly. The user taps **▶ Run** to reload. Keep everything vanilla and dependency-free.

---

## 1. How this app is structured

| File | Owns |
| :--- | :--- |
| `index.html` | Markup and the shell. Entrypoint referencing `css/style.css` and `js/app.js`. |
| `css/style.css` | All styling. One `--accent` custom property drives the theme colour. |
| `js/app.js` | Entry point. Boots the app, wires modules, handles tab navigation. |
| `js/ai-demo.js` | Fixed-prompt AI demo, recovery and save-before-acknowledgement workflow. |
| `data/ai-demo.json` | Pending request metadata and latest saved response. |
| `js/bridge.js` | **The only file that touches the native bridge.** |
| `js/store.js` | Data: reads/writes `data/entries.json`, photo lifecycle, preferences. |
| `js/ui.js` | DOM rendering. Never calls the bridge directly. |
| `manifest.json` | App metadata. `main` sets the entry point and must stay accurate. |
| `data/entries.json` | The user's saved notes. |

**Dependencies point one way: `ui → store → bridge`.** Follow it and changes land in the right
place: a new visual feature goes in `ui.js`; a new thing to persist goes in `store.js`; a new native
capability goes in `bridge.js`. Never call `window.AndroidStorage` from `ui.js`.

Modules are loaded with `<script type="module" src="js/app.js">`, so:

- **Use relative imports with the `.js` extension**: `import { load } from './store.js'`.
  Bare specifiers (`from 'store'`) do not work — there is no resolver.
- **`type="module"` defers execution.** By the time your module runs, `DOMContentLoaded` has usually
  already fired. Initialise at module top level; do **not** wrap setup in a `DOMContentLoaded`
  listener or it may never run.
- A bad import path fails the whole module graph silently in the UI — check the Guide tab's Console.

---

## 2. Directory structure & relative paths

App² serves each saved subapp from its own origin, `https://<subapp-id>.app.local/`. The ID is stable and independent of the app name. All files live within that app's workspace, and **nested subdirectories are fully supported** (e.g. `css/style.css`, `js/app.js`, `assets/images/photo.jpg`, `data/entries.json`).

Every saved subapp has an independent workspace, history, browser storage, bridge state, notifications, and scheduled reminders. Relative links and module imports continue to work. Exported ZIP files contain only the current app's workspace.

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
| `openNotificationSettings()` | `Boolean` — opens the OS notification screen for this app |
| `showNotification(title, message)` | `Boolean` |
| `cancelNotification(id)` | `Boolean` |
| `scheduleNotification(id, triggerAtMillis, title, message)` | `Boolean` — fires even if the app is closed |
| `cancelScheduledNotification(id)` | `Boolean` |
| `canScheduleExactNotifications()` | `Boolean` |
| `requestExactNotificationPermission()` | `Boolean` — opens Android Alarms & reminders settings after a user action |

`triggerAtMillis` is epoch milliseconds **as a string** (`String(Date.now() + 60000)`) — JS numbers
cross the bridge as doubles and would lose the value. Re-using an `id` replaces that alarm, so
rescheduling is idempotent. Pending alarms survive reboot. `scheduleNotification` returns `false`
until exact alarm access is granted on Android 12+. The app does not schedule an approximate
duplicate. Re-check `canScheduleExactNotifications()` on resume or listen for `app2:alarmperm`
(`event.detail.granted`) after the user returns from Settings. The starter's one-minute example uses
the same host scheduler; background scheduling is unavailable in a plain browser preview.

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
WebGL, Web Audio, CSS animations and `env(safe-area-inset-*)` all work (see §7 for the safe-area caveat). The host has network
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
- Respect the safe areas. The App² `WebView` is edge-to-edge with **zero** native padding, so
  everything below the notch and above the gesture bar is yours to pad.
  - `env(safe-area-inset-*)` covers display cutouts only — Android `WebView` never reports the
    status or navigation bars through it, so it is `0px` on a gesture-nav phone with no notch.
  - App² publishes the real system-bar insets as `--app-inset-top/right/bottom/left` on
    `document.documentElement`, and fires `app2:insets` when they change. Its
    `event.detail.imeVisible` boolean reports whether the on-screen keyboard is visible. Do not
    treat text-field focus as proof that it is open; hardware keyboards can focus fields without
    showing the IME.
  - Take whichever is larger. `css/style.css` already defines the tokens — use them:
    ```css
    --sa-bottom: max(env(safe-area-inset-bottom, 0px), var(--app-inset-bottom));
    ```
    A fixed bottom bar needs `padding-bottom: var(--sa-bottom)` or it sits under the gesture pill.
- Give feedback on tap (`:active` states); a phone has no hover to signal interactivity.

---

## 8. House rules

The `js/bridge.js` wrapper exposes asynchronous AI methods: `ai.providers()`, `ai.models(providerId)`,
`ai.start()`, `ai.result(id)`, `ai.wait(id)`, `ai.cancel(id)` and `ai.acknowledge(id)`. The host owns
ChatGPT login, credentials, model requests and response streams. Manage accounts exclusively in the library’s Settings → AI;
subapps receive model choices and completed response text only. AI requests require the bound app
origin and may continue while the app is in the background.

- **Do not rename or delete `index.html` or `manifest.json`.** The host protects and depends on them.
- **Update `manifest.json`'s `files` array** when you add a file.
- **Do not maintain legacy code unless explicitly stated otherwise.** Refactor cleanly and drop dead patterns.
- Keep it vanilla — no frameworks, no build tooling, no package manager.
- Prefer `textContent` over `innerHTML` when inserting user-entered text.


## Subapp AI guide

Connect ChatGPT exclusively through the **library → Settings → AI**. Editors and subapps have no account controls. All local subapps may use the connected provider. Live generation consumes ChatGPT plan usage; browser previews have `ai.available === false` and do not make AI requests unless a development mock is explicitly supplied.

Import `ai` from `js/bridge.js`. `ai.available` means the native channel exists, **not** that an account is connected. All methods below return Promises:

| Method | Resolved value |
| --- | --- |
| `ai.providers()` | `{providers: [{id: "chatgpt", name: "ChatGPT", connected: true}]}` (connection may be false) |
| `ai.models("chatgpt")` | `{models: [{id: "provider-model-id", name: "Display name", reasoningLevels: ["low", "medium", "high"]}]}` |
| `ai.start({providerId, model, instructions, input, reasoningEffort?})` | Request ID string |
| `ai.result(id)` | Pending: `{id, done: false, value: null}`; success: `{id, done: true, value: "completed text"}` |
| `ai.wait(id)` | Completed text string; only for IDs started by this page instance |
| `ai.cancel(id)` | `true`; cancels and releases the request |
| `ai.acknowledge(id)` | `true`; releases a terminal result after saving or handling failure |

Model lists are account-specific and preserve server order. Treat IDs as provider-specific; use the returned list rather than hardcoding models. `reasoningLevels` may be empty. Omit `reasoningEffort` for the provider default; explicit efforts must come from the selected model's list. The examples' model IDs and capabilities above are illustrative.

Native command failures reject with `Error`, usually with `.code` and `.message`. A cached terminal failure from `ai.result()` may instead have `{id, done: true, error: {code, message}}` (its `value` is not response text). Handle both forms. Results created in the current page may contain additional wrapper internals; persist your own metadata, not the result object. Common failure codes include `authentication`, `permission`, `usage_limit`, `network`, `service`, `invalid_response`, `models`, `invalid_request`, `provider`, `request_in_progress` and `not_found`. An unavailable browser or command timeout may reject without a code. Show failures and require a deliberate new Generate action; never automatically retry inference.

This runnable module example (placed under `js/`) saves before acknowledging:

```js
import { ai, writeFile } from './bridge.js';
export async function generateExample() {
  if (!ai.available) throw new Error('Open this app in App².');
  const { providers } = await ai.providers();
  if (!providers.some(p => p.id === 'chatgpt' && p.connected))
    throw new Error('Connect ChatGPT in the library Settings → AI.');
  const { models } = await ai.models('chatgpt');
  if (!models.length) throw new Error('No models available.');
  const id = await ai.start({providerId: 'chatgpt', model: models[0].id,
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
code rollback, deterministic packaging and the phone deployment checklist.
