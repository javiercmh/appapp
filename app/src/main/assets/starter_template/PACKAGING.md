# App² Runtime Packaging Contract

The starter runs as plain HTML, CSS, and JavaScript inside App²'s Android WebView. The host exposes
native capabilities through `window.AndroidStorage` (also aliased as `window.AndroidMemory` and
`window.AndroidNotification`). The bundled `js/bridge.js` is the only file that should call it.

Only top-level documents and same-origin frames of `https://<subapp-id>.app.local` receive these
objects. They are installed before page scripts run and call one token-protected native interface;
iframes of other origins (including opaque `sandbox` and `srcdoc` frames without
`allow-same-origin`) and external web pages have no storage bridge, and raw calls from them are
rejected. Methods stay synchronous with the same names, argument counts and return types; a call
with the wrong argument count or type (for example `null` where a string is expected) throws.
Main-frame navigation cannot leave the app origin: a tapped `http(s)`, `mailto:` or `tel:` link
opens in another Android app, and other navigations are blocked. `file://` and `content://` URLs
are not loadable.

Each saved subapp runs at its own stable origin, `https://<subapp-id>.app.local/`. Files, browser
storage, bridge key-value state, notifications, and scheduled reminders are isolated by subapp ID.
The ID stays stable when the manifest name changes. Relative asset URLs and module imports continue
to work, and ZIP operations apply only to the selected app.

## Subapp AI guide

Manage provider connections exclusively through **library → Settings → AI**. ChatGPT uses OAuth; OpenRouter uses one native encrypted API key. A **custom endpoint** (any OpenAI-compatible service: HTTPS base URL such as `https://host/v1` plus API key, using `/models` and `/chat/completions`) is stored natively and encrypted, is verified by listing models, exposes provider ID `custom`, has no reasoning levels, and is billed by that service. Plain `http://` is refused. Editors and subapps have no credential controls. **Available to subapps** exposes exactly one provider: `chatgpt`, `openrouter`, `custom`, or `combined` (App² AI). Combined is connected when at least one source is connected and lists ChatGPT models first, then OpenRouter models, with source labels. Model IDs are opaque, stable and unique within the exposed provider; do not parse them or hardcode model IDs. Explicit calls to hidden providers fail with `provider`.

The native exposure dropdown lists connected providers; Combined is selectable when both are connected. With no connection it is disabled. An unavailable saved exposure is retained without silently switching providers; a previously selected Combined exposure can still use its remaining connected source.

OpenRouter defaults to explicitly free text models; paid models require native opt-in. Missing or unknown prompt/completion prices are excluded from free mode. Optional pricing fields, including per-request fees, must be zero when supplied; their omission is valid. Pricing is refreshed before generation. The catalog includes OpenRouter’s Free Models Router (`openrouter/free`), which chooses an available free model, as well as individual free models. Free models still have quotas and availability limits. ChatGPT consumes plan usage; OpenRouter consumes quota or credits. There is no automatic model/provider fallback or inference retry. OpenRouter advertises an empty `reasoningLevels` list in this version. Connection/exposure changes dispatch `app2:ai:connection`; rediscover the exposed provider and clear unavailable model/reasoning choices. Already-started requests retain their original route and can be retrieved and acknowledged after an exposure change. Disconnecting a source cancels only its unfinished requests; terminal results remain available for saving.

Browser previews have `ai.available === false` and make no AI requests unless a development mock is explicitly supplied. The wrapper keeps `chatgpt` as its omitted-provider default for compatibility; apps using host exposure must discover and explicitly pass the exposed provider ID.

Import `ai` from `js/bridge.js`. `ai.available` means the native channel exists, **not** that an account is connected. All methods below return Promises:

| Method | Resolved value |
| --- | --- |
| `ai.providers()` | `{providers: [{id, name, connected}]}` (one host-selected provider; connection may be false) |
| `ai.models(providerId)` | `{models: [{id: "provider-model-id", name: "Display name", reasoningLevels: ["low", "medium", "high"]}]}` |
| `ai.start({providerId, model, instructions, input, reasoningEffort?, title?, body?, sessionId?})` | Request ID string |
| `ai.sessions.supported()` | `true`/`false`; asks the host once. Rejects, and is not cached, on anything but a confirmed yes or the old-host answer `invalid_request` "Unsupported AI command." |
| `ai.sessions.begin({providerId, model, title?, body?})` | `{sessionId}`; call from a user action while the page is visible |
| `ai.sessions.end(sessionId)` | `true`; idempotent for your own session, `not_found` for an unknown or foreign one |
| `ai.result(id)` | Pending: `{id, done: false, value: null}`; success: `{id, done: true, value: "completed text"}` |
| `ai.wait(id)` | Completed text string; only for IDs started by this page instance |
| `ai.cancel(id)` | `true`; cancels and releases the request |
| `ai.acknowledge(id)` | `true`; releases a terminal result after saving or handling failure |

`title` and `body` are optional presentation text for the request's own notification (strings, at most 60 and 140 characters; control characters are removed). Omit them to get "AI request in progress". The host adds the app's name itself and the text never changes the prompt. Each running request has its own notification with Open and Cancel actions, and Cancel affects only that request. Different subapps may generate at the same time; one subapp still has at most one unfinished or unacknowledged request. Non-string values reject with `invalid_request`.

Model lists preserve each source’s server order, with ChatGPT before OpenRouter in Combined mode. Treat IDs as provider-specific; use the returned list rather than hardcoding models. `reasoningLevels` may be empty. Omit `reasoningEffort` for the provider default; explicit efforts must come from the selected model's list. The examples' model IDs and capabilities above are illustrative.

Native command failures reject with `Error`, usually with `.code` and `.message`. A cached terminal failure from `ai.result()` may instead have `{id, done: true, error: {code, message}}` (its `value` is not response text). Handle both forms. Results created in the current page may contain additional wrapper internals; persist your own metadata, not the result object. Common failure codes include `authentication`, `permission`, `usage_limit`, `network`, `timeout`, `service`, `invalid_response`, `response_too_large`, `incomplete`, `models`, `invalid_request`, `provider`, `request_in_progress`, `not_found`, `background_service`, `session_ended`, `session_cancelled`, `session_idle` and `session_timeout`. An unavailable browser or command timeout may reject without a code. Show `.message`, which names the cause; codes not listed here may be added. Show failures and require a deliberate new Generate action; never automatically retry inference.

**Request limits.** Each code below has its own message, so show it as given:

| Code | When |
| --- | --- |
| `response_too_large` | The answer's text is longer than this device accepts: at least 4,194,304 UTF-16 units (larger on devices with more memory; read `maxOutputUnits` below). The size of the event stream itself is not limited. |
| `incomplete` | The provider ended the answer early, for example at the model's output-token limit; the message names the reason when the provider gives one. Partial text is never returned. |
| `timeout` | The connection could not be made in time, the service sent nothing for 45 seconds during an answer (10 minutes for OpenRouter and custom endpoints, which send the whole answer at the end), or one request ran longer than 30 minutes. A long answer that keeps arriving does not time out. |
| `invalid_response` | The service sent something that is not a valid answer (malformed events or JSON, a missing event stream, no text). Not used for size or time limits. |
| `network` | The connection failed or broke. |

`instructions` and `input` accept at most 100,000 UTF-16 units each (`invalid_request`). The host's `capabilities` answer (the probe behind `ai.sessions.supported()`) also reports `maxOutputUnits`, the exact text limit on this device, and `idle: "visible"` (see operation sessions below).

**Operation sessions.** A multi-request operation (dependent generations separated by web work) can hold one bounded session so the host's foreground service stays up between requests. Android does not allow starting a foreground service from the background, so an `ai.start` made after the app was backgrounded, with no service running, can fail with `background_service`. `ai.sessions.begin` starts no inference. It takes the first request's real `providerId` and `model`, applies the same checks and the same first-use AI permission dialog as `ai.start`, and must be called while the page's activity is resumed (otherwise `background_service`). Pass the returned `sessionId` to every `ai.start` of the operation. A session ends after about 90 seconds on screen without a running request, after 30 minutes, at Android's own foreground-service time limit (which can come earlier), when the user cancels from its notification, when AI is revoked, and when the page is replaced or its task closes. Cancelling, revoking, the cap and the Android limit give an unfinished request of the session a terminal error (`session_cancelled`, `session_timeout`) and never touch a completed result; the notification Cancel therefore aborts the whole operation. A page release, reload or rotation (`session_ended`) leaves a running request running, so a recreated page can poll, save and acknowledge it by ID before it begins a new session; `begin` adopts a running request of the same task and never reissues or discards it. A later `ai.start` with an ended session fails with `session_cancelled`, `session_idle`, `session_timeout` or `session_ended` before any provider call; an unknown, foreign or lost session (including after process death) is `not_found`. The host remembers one ended session per subapp. The 90-second idle window runs only while the app's screen is visible (its activity is started): while the app is in the background or the screen is off, a session without a running request does not expire for being idle; it still ends at the 30-minute cap and every other end above. Each return to the app gives a full 90 seconds again.

**JavaScript in the background.** About a minute after the app leaves the screen, the WebView stops the page's timers: `setTimeout`, `setInterval` and anything waiting on them no longer run until the app returns. Messages from the host still reach the page, and promises they resolve still run. `ai.wait` resolves from the host's completion message, so an operation whose next step follows from `ai.wait(...).then(...)` keeps going in the background. Do not put a timer (a delay, a debounce, a polling loop such as `setTimeout(() => ai.result(id), …)`) between one step's result and the next `ai.start`, or the operation pauses until the user returns. Apps with an older copy of `js/bridge.js`, whose `ai.wait` polls with a timer, need the current wrapper for this. A session without a running request keeps one ongoing notification (body "Waiting for the next step." unless `begin` supplies one). Sessions keep native inference running and let the next request start; they do not keep the page's timers running, so save results as soon as each step completes. Hosts without sessions answer `ai.sessions.supported()`'s probe with `invalid_request` "Unsupported AI command."; use unsessioned requests there, and never treat any other failure as "unsupported".

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

The native host binds requests to the exact subapp HTTPS origin and main frame and derives subapp/task ownership. Another subapp cannot retrieve, cancel or acknowledge your request. Callers cannot provide arbitrary endpoints or authentication headers. A subapp may run any number of requests at once (for example independent stages of one operation); each has its own ID to poll, save and acknowledge, and only the start rate limit applies (6 starts per app per minute by default, `usage_limit`; the user can raise it or turn it off in App² Settings → AI). Requests under one operation session share its notification. Native credentials remain encrypted outside workspaces, snapshots and exports.

Requests use native Responses streaming with `store:false`; text succeeds only after `response.completed`. There is no whole-request time limit below 30 minutes (see request limits above). Switching apps or opening the sign-in browser allows the active request to continue under a foreground notification. Rotation preserves the native request. Explicit reload, final screen closure, deletion and swiping the owning task from Recent Apps cancel its requests; closing another task does not. Completed results remain available until acknowledgement or task closure. Process death, force-stop and reboot do not restart inference. Persisted IDs can become `not_found`. Web workflows after inference (validation and auto-save) resume when the web app runs again; the foreground service does not execute the whole web app in the background.

## Permissions and offline mode

The user grants capabilities to each app in the library (app menu → Permissions). Without a grant:
- **AI**: the first `ai.start` opens a native dialog; if refused it rejects with `code: 'permission'`.
  Expect that failure and show a retry affordance. Generation starts are limited to six per minute.
- **Notifications**: `showNotification`, `showNotificationWithId` and `scheduleNotification` return
  `false` and the host asks the user. `hasNotificationPermission()` stays `false` until allowed.
- **Internet**: the app runs offline. Everything must come from files in the workspace; remote
  scripts, fonts, images, `fetch` and WebSockets are blocked without the grant
  (the reason is written to `app.log`). Bundle libraries and assets inside the app instead. Apps that
  truly need web APIs ask the user to enable Internet in Permissions; it applies on the next launch.

Imported apps start with no permissions. GitHub updates keep the user's existing permission decisions and never grant a capability the user has not allowed.

## Scheduled notifications

Use `scheduleNotification(id, triggerAtMillis, title, message)` for a reminder that must fire while
the app is closed. Pass the epoch-millisecond timestamp as a string. Reusing an ID replaces its
existing alarm; use `cancelScheduledNotification(id)` to remove it.

The host uses exact `AlarmManager` alarms. On Android 12 and later, check
`canScheduleExactNotifications()` and offer `requestExactNotificationPermission()` from a user action
when access is missing. That method opens Android's Alarms & reminders settings. Its Boolean return
reports whether the settings screen opened, not whether permission was granted. On return, listen for
`app2:alarmperm` with `event.detail.granted` or check permission again when the page resumes.
Scheduling returns `false` when exact access is missing; do not create an approximate duplicate.
Android 8 through 11 can schedule exact alarms without special access.

The host preserves pending reminders across reboot and app replacement, and re-arms them when exact
access is granted. Notification permission is separate and is checked again when a reminder fires.
Exact alarms can still be delayed by Android idle-mode limits; force-stopping App² prevents delivery
until it is opened again. A browser preview has no background scheduler and can only use a timer while
the page remains open.


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
  Rate and pitch default to 1 and accept finite values from 0.5 to 2. Subapps own their playback controls.
- The speak Promise resolves with `{status: "completed"}` after playback or
  `{status: "cancelled"}` after replacement/Stop. New playback replaces previous playback.
- `await speech.capabilities()` returns `{features: [...]}` listing optional speak options the host
  honours (`rate`, `pitch`). Older hosts reject it with `invalid_request`.
- `await speech.stop()` resolves to `true`, including when idle.
- Errors reject with `.code` and `.message`: `unavailable`, `invalid_request`, `language`,
  `missing_data`, `voice`, `network`, `synthesis`, `cancelled`, or command `timeout`.
  Missing data must be installed through Android TTS settings; do not retry automatically.
  Empty text and text beyond Android's engine input limit are rejected.

Subapps provide their own pronunciation buttons using `speech.speak({text, language})`.
App² does not add a Speak action to Android’s text selection menu.
The starter demo implements its own web Speaking/Stop bar. App² supplies no playback bar. Speech stops on backgrounding, page reload,
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

## Text Selection Menu

Import `selectionMenu` from `js/bridge.js`. `selectionMenu.available` reports a host that supports it.

- `selectionMenu.setSuppressed(true)` hides Android's floating selection menu (Copy, Select all,
  Share…) for the current page load and returns `true` when the host applied it. `false` restores it.
- Selection handles, `selectionchange` and `getSelection()` keep working. The subapp must provide
  its own actions, including Copy. The setting resets on reload or navigation: call it at startup.
- Browser previews and older hosts return `false`; keep the subapp's own selection UI visible
  in that case.



## Workspace operation guarantees

Native text and binary file replacements are atomic: a failed write keeps the previous file. Paths use the same validator in the editor, asset server, and bridge. Temporary names starting with `.app2-write-` are reserved. Existing size limits and public return values are unchanged.

During native save, restore, backup capture, or update operations, the affected document's file calls can return their existing failure values. Keep unsaved content and offer deliberate retry. App² reloads the affected runtime after the operation; a document from an earlier generation cannot write into the replacement workspace. Do not automatically repeat inference.

Text version history covers known UTF-8 text formats only. Binary and unknown files, and files with a text extension that are not valid UTF-8, remain untouched on text-history restore; use full-library backup for all workspace bytes. Preview and standalone execution share single/multiple file selection and renderer recovery.

Internet-granted apps have broad web networking. Known local/private host names and literal IP addresses are filtered, but this is not complete isolation against DNS, redirect, or other networking paths. Do not treat the host's filter as a complete network sandbox.

## Sharing

Import `share` from `js/bridge.js`. `share.available` reports whether `window.AppShare`
exists. `await share.availability()` returns `{available: true, file: true}` on a supported
host, or `{available: false, file: false}` in a browser or older host.

- `await share.text({text, title?})` opens Android's Sharesheet with `text/plain` text.
  Text must be nonempty and at most 100,000 UTF-16 units. The optional title (at most
  200 UTF-16 units) is sent as the subject and chooser title; Android controls its display.
- `await share.file({name, mimeType, content})` shares UTF-8 text as a temporary file,
  capped at 10 MiB of encoded bytes. Empty content is allowed. Supported MIME types
  are `text/plain`, `application/json`, `text/markdown`, and `text/csv`; the host forces
  `.txt`, `.json`, `.md`, and `.csv` respectively. Names are sanitized to ASCII letters,
  digits, dots, underscores, and hyphens and capped at 120 characters, without path separators.

Both operations resolve `{launched: true}` when the chooser launches. This does not
confirm target selection, delivery, or completion; dismissal produces no cancellation event.
The user chooses the external recipient, which can read the supplied text or file. No
separate App² capability grant is required. Shared files are outside the workspace and
backups, remain available after dismissal, and are swept after 24 hours on startup or
subsequent use. Android may evict cache files earlier.

Errors carry `error.code`: `unavailable`, `invalid_arguments`, `too_large`, `busy`,
`not_foreground`, or `failed`. The wrapper can also report `timeout`. Every command,
including `availability()`, requires an active foreground page with no workspace
reservation: while the app is not resumed it rejects with `not_foreground`, and while a
save, restore, backup, or update holds the workspace it rejects with `busy`. Such a
rejection says nothing about host support; use `share.available` for that, and retry
later. Only one file preparation or share chooser may occupy an activity; backgrounding
and rotation do not release an outstanding chooser. Retry after returning to the app.
A message larger than the host limit (sized for a maximal file with every character
escaped) is refused with `too_large`, and too many messages still being read are refused
with `busy`.

The raw owning-main-frame channel is `window.AppShare`. Send JSON strings through
`postMessage({id, action, options})` after JSON serialization, with a string ID of 1–100
UTF-16 units and action `availability`, `text`, or `file`. Replies are JSON strings
`{id, value}` or `{id, error: {code, message}}`. The channel is absent on foreign origins
and unsupported WebViews; same-origin child frames cannot issue commands. A document
change or session disposal invalidates pending preparation and replies.
