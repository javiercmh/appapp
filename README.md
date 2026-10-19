# App² — Import, create, and run your own apps

**Import an app from a ZIP or a public GitHub repository and run it on your Android phone.** App² keeps your apps in one library, lets you edit their files, and can add them to your home screen. You can also create your own apps from the included starter.

<table>
  <tr>
    <td align="center"><img src="docs/images/appapp-library-phone.png" width="260" alt="App² library with Hours, Routines, and Starter Demo"><br><strong>Your app library</strong></td>
    <td align="center"><img src="docs/images/appapp-editor-phone.png" width="260" alt="App² editor showing the Starter Demo’s source and data files"><br><strong>Edit files on your phone</strong></td>
  </tr>
</table>

## What you can do

- **Import apps** from ZIP files or public GitHub repositories and folders. Add apps made by others or bring in your own HTML, CSS, and JavaScript projects.
- **Create your own apps** (called *subapps*) from the included Starter Demo.
- **Edit on your phone** with a file browser, code highlighting, and search. Customize each app’s name and icon.
- **Launch from your home screen** with a shortcut that opens the app in its own screen.
- **Save real files** for notes, settings, photos, and other data. Each app has its own workspace.
- **Use Android features** including notifications, scheduled reminders, and photo selection.
- **Read text aloud** using Android text-to-speech. Subapps can pronounce words in different languages or let you select text and tap Speak.
- **Add AI features** through a shared ChatGPT connection in library Settings → AI. Apps can choose models and reasoning levels, generate text, and cancel requests.
- **Keep and share your apps** with workspace snapshots, ZIP import/export, and Android sharing.

## Get started

1. Open App² and tap **Import**. Choose a ZIP file or paste a public GitHub repository or folder URL. To start from scratch, choose **New app**.
2. Tap an app to run it, or **Edit** to change its files.
3. Choose **Install** to add a home-screen shortcut.

The Starter Demo includes working examples of file storage, photos, notifications, text-to-speech, and AI, plus a guide to building your own app. App² runs on **Android 8.0 or newer**.

## Try App² Dev

App² Dev installs beside App² with its own data and AI connections. Download its APK from a [Dev prerelease](https://github.com/javiercmh/appapp/releases) when available. See [Dev distribution and signing setup](docs/DEV_DISTRIBUTION.md).

## For developers

Subapps use standard HTML, CSS, and JavaScript, with a native bridge for Android features. Store app data as JSON or other files under `data/` so it can be inspected, exported, and restored with the workspace.

- [Subapp packaging and bridge reference](app/src/main/assets/starter_template/PACKAGING.md)
- [AI integration guide](docs/AI.md)
- [Text to Speech guide](docs/TEXT_TO_SPEECH.md)
- [Starter Demo source](app/src/main/assets/starter_template/)
- [Architecture and transaction boundaries](docs/ARCHITECTURE.md)
- [Host development guidelines](AGENTS.md)
- [Pending native verification](docs/DEVICE_CHECKS.md)

The Android host uses Kotlin, Material components, and WebView. Build with JDK 17 or 21 and the checked-in Gradle wrapper:

```sh
./gradlew --version
./gradlew :app:assembleDebug :app:testDebugUnitTest
```

## License

MIT License; see [`LICENSE`](LICENSE). It covers everything in this repository, including the
starter template and the examples under `examples/`, unless a file says otherwise. Bundled
libraries and third-party marks are listed in [`THIRD_PARTY_NOTICES.md`](THIRD_PARTY_NOTICES.md).
This means, you can do whatever you want with App².
Just one personal request: please do not put ads, non-consensual trackers, or other invasive code in your apps.


### Preview the Starter Demo

Run `python3 app/src/main/assets/starter_template/dev_server.py --mock-ai` and open
[localhost:8888](http://localhost:8888). Omit `--mock-ai` to leave AI unavailable, or add a positional
port to change the port. Python 3.9+ is sufficient; no dependencies are needed.
The server writes real notes, photos and AI results to the served workspace, so use a disposable
copy for testing. Mock AI is offline and uses no ChatGPT credentials or plan usage. Android
permissions and exact alarms require device testing.

CI uses JDK 21 for JVM tests, lint, debug assembly and device-test compilation. Native interaction checks still require an emulator.

Run development checks with `python3 -B -m unittest discover -s tests -p 'test_demo_dev_server.py'`
and `node --test tests/*.test.mjs`.

## Updating saved apps

Use an app's library menu → **Update app** to review an update from its public
GitHub repository. Updates replace declared app code and preserve user records.
The editor has a separate update button; Share & Package App only exports/shares.
Library ZIP import creates another app. See [the update and deployment guide](docs/SUBAPP_UPDATES.md).

## Back up all subapps

In the library, open **Settings → Subapp backup → Back up all subapps** and choose a ZIP destination. App² captures every registered workspace, including nested data files, code, manifests, images and logs, and verifies the saved archive before reporting the app count. Finish/save or cancel active AI requests before starting. Keep the verified ZIP outside the phone before tests that could affect app data; the single export replaces manually exporting each app.

**Restore subapp backup** validates the complete archive and adds independent copies with fresh IDs in backup order. Existing apps remain untouched. Each restored copy receives an initial history snapshot and can be linked to its publisher through the existing Update flow. Install launcher shortcuts again if needed.

The backup includes workspace files only: global App² settings, native bridge key-value state, reminders, history, publisher installation metadata, ChatGPT credentials and WebView cookies/localStorage/IndexedDB are excluded. Store subapp data in `data/` as documented above. ZIPs are limited to 2 GB of workspace content, 512 MB per file and fewer than 100,000 files; insufficient temporary storage produces an error instead of a successful backup. This multi-app format is separate from the existing single-app ZIP import/export.
