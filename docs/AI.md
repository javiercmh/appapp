# Subapp AI guide


Connect ChatGPT exclusively through the **library → Settings → AI**. Editors and subapps have no account controls. All local subapps may use the connected provider. Live generation consumes ChatGPT plan usage; browser previews have `ai.available === false` and do not make AI requests unless a development mock is explicitly supplied.

Import `ai` from `js/bridge.js`. `ai.available` means the native channel exists, **not** that an account is connected. All methods below return Promises:

| Method | Resolved value |
| --- | --- |
| `ai.providers()` | `{providers: [{id: "chatgpt", name: "ChatGPT", connected: true}]}` (connection may be false) |
| `ai.models("chatgpt")` | `{models: [{id: "provider-model-id", name: "Display name", reasoningLevels: ["low", "medium", "high"]}]}` |
| `ai.start({providerId, model, instructions, input, reasoningEffort?, title?, body?})` | Request ID string |
| `ai.result(id)` | Pending: `{id, done: false, value: null}`; success: `{id, done: true, value: "completed text"}` |
| `ai.wait(id)` | Completed text string; only for IDs started by this page instance |
| `ai.cancel(id)` | `true`; cancels and releases the request |
| `ai.acknowledge(id)` | `true`; releases a terminal result after saving or handling failure |

`title` and `body` are optional presentation text for the request's own notification (strings, at most 60 and 140 characters; control characters are removed). Omit them to get "AI request in progress". The host adds the app's name itself and the text never changes the prompt. Each running request has its own notification with Open and Cancel actions, and Cancel affects only that request. Different subapps may generate at the same time; one subapp still has at most one unfinished or unacknowledged request. Non-string values reject with `invalid_request`.

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

AI demo validation uses deterministic mocks and never consumes plan usage:

```sh
node tests/ai-demo.test.mjs
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

Starter updates apply to newly created subapps. Existing installed workspaces are not automatically overwritten. For device validation, create a disposable emulator subapp and sync only its code/assets, verifying hashes before relaunching; preserve its data and all other subapps.
