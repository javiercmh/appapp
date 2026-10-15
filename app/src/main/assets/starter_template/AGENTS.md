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

Only top-level documents and same-origin frames of `https://<subapp-id>.app.local` receive these
objects. They are installed before page scripts run and call one token-protected native interface;
iframes of other origins (including opaque `sandbox` and `srcdoc` frames without
`allow-same-origin`) and external web pages have no storage bridge, and raw calls from them are
rejected. Methods stay synchronous with the same names, argument counts and return types; a call
with the wrong argument count or type (for example `null` where a string is expected) throws.
Main-frame navigation cannot leave the app origin: a tapped `http(s)`, `mailto:` or `tel:` link
opens in another Android app, and other navigations are blocked. `file://` and `content://` URLs
are not loadable.

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

### Text selection menu

| Method | Returns |
| :--- | :--- |
| `setSelectionMenuSuppressed(suppressed)` | `Boolean` — hides Android's floating selection menu for this page load; use `selectionMenu.setSuppressed()` from `js/bridge.js` |

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
4. **Storage and notification bridge calls are synchronous and block the JS thread.** AI, speech, and update wrapper methods are asynchronous. Keep payloads small. Before saving a
   photo, downscale it through a `<canvas>` (max ~1280px, `toDataURL('image/jpeg', 0.82)`) — a raw
   camera photo is several megabytes and will jank the UI. Writes over ~6 MB are rejected outright.
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
ChatGPT login, OpenRouter API keys, provider routing, model requests and completed response text. Manage accounts exclusively in the library’s Settings → AI;
subapps receive model choices and completed response text only. AI requests require the bound app
origin and may continue while the app is in the background.

- **Do not rename or delete `index.html` or `manifest.json`.** The host protects and depends on them.
- **Update `manifest.json`'s `files` array** when you add a file.
- **Do not maintain legacy code unless explicitly stated otherwise.** Refactor cleanly and drop dead patterns.
- Keep it vanilla — no frameworks, no build tooling, no package manager.
- Prefer `textContent` over `innerHTML` when inserting user-entered text.


## Permissions and offline mode

The user grants capabilities to each app in the library (app menu → Permissions). Without a grant:
- **AI**: the first `ai.start` opens a native dialog; if refused it rejects with `code: 'permission'`.
  Expect that failure and show a retry affordance. Generation starts are limited to six per minute.
- **Notifications**: `showNotification`, `showNotificationWithId` and `scheduleNotification` return
  `false` and the host asks the user. `hasNotificationPermission()` stays `false` until allowed.
- **Internet**: the app runs offline. Everything must come from files in the workspace; remote
  scripts, fonts, images, `fetch`, WebSockets and links to private-network addresses are blocked
  (the reason is written to `app.log`). Bundle libraries and assets inside the app instead. Apps that
  truly need web APIs ask the user to enable Internet in Permissions; it applies on the next launch.

Imported apps, and apps updated from GitHub with changed code, start with no permissions.

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
- `await speech.speak({text, language, voiceId?, rate?, pitch?, showControl?})` requires a BCP 47 language
  such as `fr-FR`, `de-DE`, or `ja-JP`. No language detection is performed. An explicit voice
  must match the requested language and script. Regional fallback within that language is allowed.
  Rate and pitch default to 1 and accept finite values from 0.5 to 2. `showControl: false`
  hides the native Speaking bar; the subapp must then provide its own Stop control.
- The speak Promise resolves with `{status: "completed"}` after playback or
  `{status: "cancelled"}` after replacement/Stop. New playback replaces previous playback.
- `await speech.capabilities()` returns `{features: [...]}` listing optional speak options the host
  honours (`rate`, `pitch`, `showControl`). Older hosts reject it with `invalid_request`.
- `await speech.stop()` resolves to `true`, including when idle.
- Errors reject with `.code` and `.message`: `unavailable`, `invalid_request`, `language`,
  `missing_data`, `voice`, `network`, `synthesis`, `cancelled`, or command `timeout`.
  Missing data must be installed through Android TTS settings; do not retry automatically.
  Empty text and text beyond Android's engine input limit are rejected.

Subapps provide their own pronunciation buttons using `speech.speak({text, language})`.
App² does not add a Speak action to Android’s text selection menu.
A native **Stop** control appears during playback unless the call passes `showControl: false`. Speech stops on backgrounding, page reload,
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
