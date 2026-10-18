# App² privacy policy

Effective date: 13 October 2026. This policy covers the App² Android application
(`com.wynh.appapp`) published by Javier Melo. App² has no developer-operated servers, accounts,
analytics, advertising or crash reporting. The developer does not receive your data.

## What stays on your device

- **Subapps and their files.** Code, data files, images, icons and `app.log` for each subapp
  are stored in App²'s private app storage. Workspace history snapshots and native key-value
  state are stored there too.
- **Settings and reminders.** App² settings and scheduled notifications are stored locally.
- **AI credentials.** A ChatGPT sign-in token or an OpenRouter API key is encrypted with an
  Android Keystore key and kept in no-backup storage. It is never written into a subapp
  workspace, snapshot, export or backup, and subapps cannot read it.
- **Backups.** App² opts out of Android cloud backup and device transfer. Exports and
  full-library backups are ZIP files you create and save where you choose. They contain
  workspace files and data, not credentials.

Deleting a subapp removes its files, history, state and reminders. Uninstalling App² or
clearing its storage removes everything App² stores.

## What leaves your device, and only when you use it

| Feature | Sent to | What is sent |
| --- | --- | --- |
| ChatGPT connection | OpenAI (`auth.openai.com`, `api.openai.com`) | Sign-in through OpenAI; then, for each generation a subapp starts with your permission, its instructions and input. Requests ask OpenAI not to store responses (`store: false`). OpenAI's own privacy policy applies. |
| OpenRouter connection | OpenRouter (`openrouter.ai`) and the model provider it routes to | Your API key, model catalogue requests, and each generation's instructions and input. OpenRouter's and the routed provider's policies apply. |
| GitHub import and Update app | GitHub (`api.github.com`, `raw.githubusercontent.com`) | Requests for the public repository you choose. No GitHub account is used. |
| Translation | The Android translation app you pick | The text you chose to translate. That app's policy applies. |
| Text to speech | Your device's configured speech engine | Text a subapp asks to speak. Some engines use online voices. |
| Subapp network access | Hosts a subapp loads or contacts | Whatever that subapp sends. Subapps are code you or their authors wrote; App² does not review them. Only install subapps you trust. |
| Links | Your browser | The address of a link you open. |

App² sends nothing in the background on its own. AI requests continue under a visible
notification with a Cancel action if you switch apps while one is running.

## Subapps

Each subapp starts with no permissions. App² asks before a subapp may use AI or send
notifications, and keeps subapps offline (only their own files) unless you allow Internet for that
app in the library: app menu → Permissions. You can revoke any permission at any time.

Subapps are web apps that run inside App². Their authors, not App²'s developer, decide what a
subapp does with the data you enter. App² isolates each subapp's files and browser storage from
other subapps and restricts what native features they can reach, but a subapp can still use the
data you give it and any access you grant it.

## Children

App² is a developer tool and is not directed at children.

## Changes and contact

Changes to this policy are published in this file in the App² repository, with a new effective
date. Questions or requests: open an issue at <https://github.com/Javiercmh/appapp/issues>.


### Network-filter limits and recovery metadata

Internet is off for newly imported apps. Enabling it gives the app broad web networking; host-name and literal-IP filtering does not establish complete local-network isolation. Code with Internet allowed can transmit its workspace data to the destinations it contacts. App² does not claim that this filtering controls every DNS, redirect, or request path.

Native GitHub import metadata retains the public repository/folder URL without query or fragment and the pinned commit beside the workspace. It contains no provider credentials and is not part of subapp exports. Recovery rebuilds library registration from existing workspace directories; it does not upload their contents.
