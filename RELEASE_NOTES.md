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
