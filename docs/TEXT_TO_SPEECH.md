# Text to Speech in subapps

App² lets subapps pronounce words and sentences through Android's configured text-to-speech
engine. It works in the editor preview and standalone runtime, without a ChatGPT connection.
Languages and voices depend on the installed engine and its voice data.

## Read selected text

Long-press selectable text, select a word or sentence, and choose **Speak** in the Android
selection menu. Tap the native **Stop** control to interrupt playback.

The subapp supplies the pronunciation language through HTML `lang`. Use a document language
for a single-language page, or annotate individual words and passages on a multilingual page:

```html
<html lang="en">
  <!-- Inside the page body: -->
  <p>French: <span lang="fr-FR">Bonjour</span></p>
  <p>German: <span lang="de-DE">Guten Tag</span></p>
  <p>Japanese: <span lang="ja-JP">こんにちは</span></p>
</html>
```

Language is inherited from the nearest ancestor with a `lang` attribute. Mark foreign-language
text explicitly: the surrounding English page language would otherwise be used. App² does
not infer language from the text. Selections spanning conflicting languages, or without a
language annotation, show an explanation instead of speaking. Inputs and textareas support
selection speech; password fields are excluded. Text must remain selectable in the subapp's CSS.

## Add a pronunciation button

Import `speech` from the starter wrapper. This example belongs in a module under `js/`:

```html
<button id="pronounce">Hear Bonjour</button>
<button id="stop-speech">Stop</button>
<p id="speech-status" aria-live="polite"></p>
```

```js
import { speech } from './bridge.js';

const status = document.getElementById('speech-status');
const pronounce = document.getElementById('pronounce');
pronounce.disabled = !speech.available;

pronounce.addEventListener('click', async () => {
  status.textContent = 'Speaking…';
  try {
    const result = await speech.speak({ text: 'Bonjour', language: 'fr-FR' });
    status.textContent = result.status === 'completed' ? 'Finished.' : 'Stopped.';
  } catch (error) {
    status.textContent = error.message;
  }
});

document.getElementById('stop-speech').addEventListener('click', async () => {
  try { await speech.stop(); }
  catch (error) { status.textContent = error.message; }
});
```

`speech.available` indicates the native channel exists. Engine initialization, missing languages,
and playback can still fail. In ordinary browser previews it is `false`, and speech calls reject
with `unavailable`; the development server does not simulate speech.

## API reference

| Member | Result or behavior |
| --- | --- |
| `speech.available` | Boolean indicating native channel availability. |
| `await speech.voices()` | `{voices: [{id, name, language, requiresNetwork}]}` for Android's configured engine. |
| `await speech.speak({text, language, voiceId?, rate?, pitch?, showControl?})` | Resolves after playback with `{status: "completed"}`, or `{status: "cancelled"}` when interrupted by Stop or replacement. |
| `await speech.capabilities()` | `{features: ["rate", "pitch", "showControl"]}`; hosts without this call reject it with `invalid_request`, so treat that as no optional features. |
| `await speech.stop()` | Resolves to `true`, including when nothing is playing. |

`text` must be nonempty and fit Android's speech input limit. `language` is a required BCP 47
tag, such as `fr-FR`, `de-DE`, or `ja-JP`. Regional fallback within the same language is allowed;
explicit scripts must match. Optional `rate` and `pitch` default to `1` and accept finite values
from `0.5` through `2`. `showControl` defaults to `true`, which shows the native Speaking bar with Stop;
with `false` the host shows no bar and the subapp must provide its own Stop control. It must be a boolean.

## Choose a voice

Discover voices rather than hardcoding engine-specific IDs. Omitting `voiceId` uses the engine's
default voice for the requested locale. An explicit voice must match the requested language
and any explicit script.

```js
const { voices } = await speech.voices();
const voice = voices.find(v => v.language === 'fr-FR' && !v.requiresNetwork);
if (!voice) throw new Error('No offline French voice is available in this engine.');

await speech.speak({
  text: 'Bonjour',
  language: 'fr-FR',
  voiceId: voice.id,
  rate: 0.8,
  pitch: 1
});
```

Offline and online voices are exposed. `requiresNetwork` describes the engine's voice;
availability can still depend on downloaded voice data. Online voices may send spoken text
through the configured engine and need connectivity. Save subapp voice preferences in JSON
under `data/`, and rediscover voices when applying saved choices because engine changes can
invalidate IDs.

## Failures and playback lifetime

Failures reject with an `Error` carrying `.code` and `.message`:

| Code | Meaning or response |
| --- | --- |
| `unavailable` | Unsupported host, inactive runtime, or engine initialization failure. |
| `invalid_request` | Invalid text, language tag, rate, pitch, or options. |
| `language` | The engine has no compatible voice for the requested language/script. |
| `missing_data` | Install voice data through Android's text-to-speech settings. |
| `voice` | Requested voice is unavailable, incompatible, or rejects its settings. |
| `network` | The engine reports a network failure. |
| `synthesis` | The engine cannot start or finish speech. |
| `cancelled` | A pending discovery operation was interrupted. |
| `timeout` | Voice discovery or a Stop command did not respond in time. |

Show failures and let the user deliberately try again. Do not automatically retry speech or
switch to another language.

One utterance plays per runtime; a new request replaces the previous one. Speech stops when
the runtime leaves the foreground, reloads/navigates, replaces its workspace, or closes.
It is not persisted or resumed. Page reloads discard that page's outstanding JavaScript Promises.
Pause/resume, narration queues, background playback, and audio-file export are not provided.

The native channel accepts only the owning subapp's HTTPS origin and main frame. Existing
subapps can use selection speech by adding language markup. To add buttons, adopt the current
starter `speech` wrapper explicitly; App² does not rewrite existing workspaces. See the
[starter pronunciation demo](../app/src/main/assets/starter_template/js/speech-demo.js) and
[packaged runtime reference](../app/src/main/assets/starter_template/PACKAGING.md#text-to-speech).
