# App² 1.6.0

## AI providers

- Connect OpenRouter with an encrypted API key alongside existing ChatGPT accounts.
- Choose ChatGPT, OpenRouter, or Combined in Settings → AI. The dropdown offers connected providers; Combined presents their models together with source labels.
- Free OpenRouter models are available by default, including the Free Models Router. Paid models require an explicit Settings opt-in.
- Add or replace a key from the main button, get a key from its dialog, and remove a saved key through the three-dot menu.
- Keep credentials native, preserve pending results across exposure changes, and route every request to its selected provider and model.
- Update the Starter Demo to discover the exposed provider. LingoMage 1.2.14 applies the same discovery to reader help and clears unavailable model selections.

## Installation

Download `AppApp-1.6.0.apk` and install it as an update to App². The package uses the existing release signing certificate and application ID `com.wynh.appapp`. Existing subapp workspaces and ChatGPT credentials are preserved. Update LingoMage separately through its normal update flow.

Version: `1.6.0` (version code `10`).

## Verification

51 JVM tests passed, alongside starter and LingoMage regression checks, focused emulator tests, and signed debug/release builds. The free-model fix was installed on SM-S921B after verified full-library backups; library names and user data matched before and after. Live model quality was not evaluated.

---

# App² 1.5.2

## Translation and text selection

- Let subapps open Android's installed translation handler with selected text through the new `translation` API. Availability depends on Android 10 or later and an installed translation handler.
- Let subapps hide Android's floating text-selection menu while retaining selection handles and events. The setting resets on each page load.
- Remove App²'s contextual Speak action. Subapps can continue to provide pronunciation buttons through the speech API.

## Library improvements

- Keep app menus visible when opened near the bottom of the library.

## Installation

Download `AppApp-1.5.2.apk` and install it as an update to App². It uses the existing release signing certificate and application ID `com.wynh.appapp`. Existing subapp workspaces are not rewritten; adopt the updated `js/bridge.js` explicitly to use the new APIs.

Version: `1.5.2` (version code `9`).

---

# App² 1.5.1

## Library and editor improvements

- Create apps from one New app menu with GitHub, ZIP, and Starter demo options.
- Simplify app cards and give descriptions the full card width and up to four lines.
- Access Install, Update, Share, Edit, and Delete from each app’s three-dot menu.
- Open Edit directly to files, with Configure app and History nearby. Run previews the app; Files returns to editing.
- Share and save ZIP packages from the library. GitHub linking and updates are also managed there.
- Android Back follows standalone WebView history, so modal readers can return to their shelf and run their existing `popstate` cleanup, including saving position and stopping speech. Apps without WebView history exit normally.

LingoMage 1.2.7 should require App² host version code 8 for this behavior.

## Installation

Download `AppApp-1.5.1.apk` and install it as an update to App². It uses the existing release signing certificate and application ID `com.wynh.appapp`. Existing subapp workspaces are not rewritten.

Version: `1.5.1` (version code `8`).

---

# App² 1.5.0

## Text to Speech

- Hear selected words and sentences through Android's native speech engine using **Speak** in the text-selection menu.
- Give subapps pronunciation buttons with explicit language, voice selection, speed, and pitch through the new `speech` API.
- Discover offline and online voices from the phone's configured engine. Speech stops when its runtime leaves the foreground or reloads.
- Try the Starter Demo's Spanish (Chile), German, and Japanese pronunciation samples. The demo title now comes first, and the speech card follows storage and notes.
- Read the new Text to Speech developer guide and packaged subapp API documentation.

## Library improvements

- Back up all subapp workspaces in one verified ZIP and restore them as independent copies.
- Reorder library apps with smoother drag interactions.
- Detect publisher updates correctly after restoring workspace history.

## Installation

Download `AppApp-1.5.0.apk` and install it as an update to App². It uses the existing release signing certificate and application ID `com.wynh.appapp`.

Create a new app from Starter Demo to get the revised template. Existing subapp workspaces are not rewritten. Available speech languages and voices depend on the installed Android TTS engine and its voice data; no ChatGPT connection is needed.

Version: `1.5.0` (version code `6`).

---

# App² 1.2.0

- Create and manage multiple saved web apps in the redesigned app library.
- Keep each app's workspace, history, browser storage, and notifications isolated.
- Open individual apps through their own shortcuts and Recent Apps tasks.
- Manage global settings from the app library.
- Import web apps directly from GitHub repositories.
- Show bottom tabs correctly when using an on-screen or hardware keyboard.
- Use precise alarms for scheduled reminders.

## Installation

The Kotlin namespace and application ID are now both `com.wynh.appapp`. Android installs this release as a
separate app from releases using `com.example.runtimecompiler`; existing data
is not copied automatically. Export any apps you want to retain from the old
installation and import them into the new one.

Version: `1.2.0` (version code `3`).
