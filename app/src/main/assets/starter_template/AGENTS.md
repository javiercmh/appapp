# AGENTS.md — building apps that run in App²

You are editing a **web app that runs inside App²**, an Android host that renders your files in a
WebView and gives them access to native device features.

The starter is vanilla HTML/CSS/JavaScript and **needs no build step**. Edit its workspace files;
the user taps **▶ Run** to reload. Keep the starter dependency-free. For an existing subapp, follow
its own project instructions and build process; App² executes the resulting static files, not
TypeScript or a development server.

Read this guide first, then [PACKAGING.md](PACKAGING.md) for the capability you need. In the host
repository these files live at `app/src/main/assets/starter_template/`; the root `AGENTS.md` is for
Android host developers. Each new subapp receives its own copies. Existing copies can be older;
consult the [host development reference](https://github.com/javiercmh/appapp/blob/dev/app/src/main/assets/starter_template/PACKAGING.md)
and compare the installed host and wrapper before adopting a new API.

Before editing an existing app, identify its entry point, data schema, persistence layer and tests.
Preserve user records and unrelated files; the starter's notes/demo modules are examples, not a
required architecture or domain model.

---

## 1. How this app is structured

| File | Owns |
| :--- | :--- |
| `index.html` | Markup and the shell. Entrypoint referencing `css/style.css` and `js/app.js`. |
| `css/style.css` | All styling. One `--accent` custom property drives the theme colour. |
| `js/app.js` | Entry point. Boots the app, wires modules, handles tab navigation. |
| `js/speech-demo.js` | Multilingual pronunciation buttons using native speech. |
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
- **Static module scripts are deferred by default.** Their synchronous top-level setup can access
  the parsed DOM. Setup after an `await` or dynamic import may run after `DOMContentLoaded`;
  check `document.readyState` before relying on that event.
- A bad import path fails the whole module graph silently in the UI — check the Guide tab's Console.

---

## 2. Directory structure & relative paths

App² serves each saved subapp from its own origin, `https://<subapp-id>.app.local/`. The ID is stable and independent of the app name. All files live within that app's workspace, and **nested subdirectories are fully supported** (e.g. `css/style.css`, `js/app.js`, `assets/images/photo.jpg`, `data/entries.json`).

Every saved subapp has an independent workspace, history, browser storage, bridge state, notifications, and scheduled reminders. Relative links and module imports continue to work. Exported ZIP files contain only the current app's workspace.

- Reference assets by their relative path: `<img src="assets/photo_123.jpg">`, `<link href="style.css">`.
- Relative ES module imports work naturally: `import { load } from './store.js'`.
- Imported helpers such as `bridge.writeFile("data/notes.json", ...)` create parent directories as needed.
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

## 3. Storage and host APIs

Read [PACKAGING.md](PACKAGING.md) for the runtime contract and exact methods. Import the public
helpers from `js/bridge.js`; use `import * as bridge from './bridge.js'` from a module in `js/`.
Do not invent methods or edit the Android host to implement an ordinary subapp feature.

Keep domain entities **and user settings** in JSON files under `data/`. Browser storage and native
key-value preferences are not portable app data: they do not travel with workspace exports or text
history. Legacy preference helpers still exist, but use file-based storage for new features.

Validate loaded data and distinguish missing files from corrupt data. Do not replace corrupt user
data with an empty collection and immediately save it. Build the next state separately, check the
write succeeded, then publish it to the UI. A failed save must preserve both the previous file and
the user's unsaved changes. See [storage](PACKAGING.md#storage-and-persistence).

For common file actions:

| User intent | Use |
| --- | --- |
| Open/import a file from the phone | Standard `<input type="file">`, then `file.text()` or `file.arrayBuffer()` |
| Save an export to a phone folder | `save.text(...)` or `save.file(...)` |
| Send text or a text file to another app | `share.text(...)` or `share.file(...)` |
| Save app records internally | `writeFile('data/…', ...)` |
| Install an entire subapp | App² library import; this is separate from a subapp importing its own records |

No incoming-share receiver or manifest opt-in is needed for a file input. Read the corresponding
runtime section before implementing a capability and feature-detect APIs on older hosts.

---

## 4. Gotchas that bite silently

1. **`loadState` needs both arguments.** `loadState('key')` throws. Always pass a default:
   `loadState('key', '')`.
2. **`requestNotificationPermission()` does not wait for a new grant.** It returns `false` while
   approval is pending, even if the user is about to grant it; it can return `true` when already allowed. Listen for the
   editor's `app2:notifperm` event on `window` (`event.detail.granted`) when available. Always re-check
   `hasNotificationPermission()` on `focus`/visible `visibilitychange`; the standalone activity does
   not dispatch that event. Never treat the request's return value as the answer.
3. **`readFile` returning `""` does not prove a file is missing.** It can mean empty, failed or
   temporarily unavailable storage. `listFiles()` can check existence across wrapper backends, but
   an empty listing can also be an unavailable workspace. See the storage recovery guidance in
   `PACKAGING.md`; never erase known data because of one empty read.
4. **Storage and notification bridge calls are synchronous and block the JS thread.** AI, speech,
   translation, sharing, saving exports and update wrappers are asynchronous. Keep payloads small.
   Downscale large photos through a `<canvas>` before saving. Text file I/O is capped at 16 MiB
   UTF-8; binary file I/O at 6 MiB decoded bytes. Other APIs have their own limits in `PACKAGING.md`.
5. **`writeFile` is text only.** Images and other binaries must go through `writeFileBase64`, or the
   bytes get mangled by UTF-8 encoding.
6. **File paths are validated.** Use relative paths built from `A-Z a-z 0-9 . _ -` segments
   joined by `/` (a leading `/` or `./` is accepted). Any other character, such as a space or a
   non-ASCII letter, is stored as `_`. Empty paths, `..`, backslashes, control characters,
   symbolic links, segments over 255 characters and nesting over 16 levels make file calls fail (`""` / `false`) with a reason in `app.log`.
   Text reads and writes are capped at 16 MiB, binary at 6 MiB, and bridge writes fail once the
   workspace would exceed 512 MiB in total.
7. **Do not use `alert()` or `confirm()`.** They render as jarring system dialogs on mobile. Use
   inline validation and the toast pattern in `ui.js`.

---

## 5. What the platform can and cannot do

**Available:** standard DOM, Canvas, WebGL, Web Audio, CSS and browser storage, subject to the
installed WebView. Keep records/settings in `data/` even though browser storage is available.
Internet is **off for new/imported apps**. Bundle scripts, fonts and assets locally; remote
requests require the user to grant Internet in App² and can still fail offline or because of CORS.
Native AI has its own provider setup and permission; it does not require a subapp Internet grant.

**File selection works:** `<input type="file">` opens Android's picker. Read a selected JSON file
with `file.text()`; binary files with `file.arrayBuffer()` or `FileReader`. Choosing a file does
not persist it. Camera capture is not implemented by the picker; do not promise a camera option.

**Unavailable or constrained:** camera/microphone capture via `getUserMedia`, geolocation, printing
and receiving Android Sharesheet deliveries are not exposed by App². Do not invent native APIs
for them. Notifications/reminders, AI, speech, translation, outgoing sharing and Save as are the
explicit host capabilities documented in `PACKAGING.md`.

**Background behavior:** the page is not a general Android background worker. Timers may freeze
while off screen and stop when closed. Use native scheduled notifications for closed-app reminders;
for multi-step AI operations use the documented operation sessions and `ai.wait`. Native requests
and JavaScript workflow lifetimes differ. Do not add a service worker as an assumed host-supported
background/offline solution; workspace assets already work offline.

---

## 6. Debugging

Runtime console messages and errors are recorded in the bounded `app.log` **in this workspace**. That means the app can read its own logs:

```js
import {readFile} from './js/bridge.js'; // use './bridge.js' from a module in js/
const log = readFile('app.log');
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
  - Include `<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">`.
  - Take whichever is larger. `css/style.css` already defines the tokens — use them:
    ```css
    --sa-bottom: max(env(safe-area-inset-bottom, 0px), var(--app-inset-bottom, 0px));
    ```
    A fixed bottom bar needs `padding-bottom: var(--sa-bottom)` or it sits under the gesture pill.
- Give feedback on tap (`:active` states); a phone has no hover to signal interactivity.

---

## 8. House rules

The `js/bridge.js` wrapper exposes asynchronous AI methods: `ai.providers()`, `ai.models(providerId)`,
`ai.start()`, `ai.result(id)`, `ai.wait(id)`, `ai.cancel(id)` and `ai.acknowledge(id)`. The host owns
ChatGPT login, OpenRouter API keys, provider routing, model requests and completed response text. Manage accounts exclusively in the library’s Settings → AI;
subapps receive model choices and completed response text only. AI requests require the bound app
origin and may continue while the app is in the background.

- Keep `index.html` and `manifest.json` at the package root. If `main` points elsewhere, keep it
  valid and keep the root entry file for packaging compatibility.
- Keep `manifest.json.files` in sync with shipped files. Do not publish real user records,
  credentials, logs or local development state. See [packaging](PACKAGING.md#app-package-and-manifest).
- **Do not maintain legacy code unless explicitly stated otherwise.** Refactor cleanly and drop dead patterns.
- Keep the starter vanilla; follow an existing app’s documented tooling rather than replacing its architecture.
- Prefer `textContent` over `innerHTML` when inserting user-entered text.

## 9. Runtime reference

[`PACKAGING.md`](PACKAGING.md) in this folder is the full runtime contract. Read the relevant section
before using a capability:

- [Subapp AI guide](PACKAGING.md#subapp-ai-guide): providers, models, requests, operation sessions, failures
- [Permissions and offline mode](PACKAGING.md#permissions-and-offline-mode): AI, notifications and Internet grants
- [Scheduled notifications](PACKAGING.md#scheduled-notifications)
- [Sharing](PACKAGING.md#sharing): share story text or UTF-8 files through Android’s Sharesheet.
- [Save as](PACKAGING.md#save-as) and [choosing a file from the phone](PACKAGING.md#choosing-a-file-from-the-phone).
- [Text to Speech](PACKAGING.md#text-to-speech) and [Device Translation](PACKAGING.md#device-translation)
- [Text Selection Menu](PACKAGING.md#text-selection-menu)
- [Native GitHub subapp updates](PACKAGING.md#native-github-subapp-updates): the `manifest.json` publisher contract
- [Starter Demo browser development](PACKAGING.md#starter-demo-browser-development): `dev_server.py` and the AI mock
- [Workspace operation guarantees](PACKAGING.md#workspace-operation-guarantees)

## 10. Verify before handing back the app

- Run the subapp's existing tests. Add behavioral tests for data changes, import/export validation,
  failed saves and cancellation; do not freeze UI wording.
- Preview a disposable workspace copy with `python3 dev_server.py` (optionally `--mock-ai`). Do not
  test destructive actions against real records. Confirm no credentials or remote inference are
  needed for deterministic tests.
- Check phone-width layout, safe areas, keyboard-visible and hidden states, and standalone mode.
- Test an export/import round trip and persistence after reload when changing storage. Keep invalid
  files and cancelled operations from altering existing data.
- Browser previews do not prove native permissions, notifications, picker lifecycle or Android
  behavior. Host integration tests belong in the App² repository and use disposable emulator apps;
  domain-format tests belong in the subapp repository.
- Report what actually ran and any unsupported-host behavior. Do not claim a native feature exists
  merely because its JavaScript wrapper is present; handle connection and capability failures.
