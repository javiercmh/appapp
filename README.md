# App²

**Run web apps on your Android phone.** Import an app from GitHub or a ZIP file, launch it from your home screen, and edit its files right on your phone. Apps can use Android features like notifications and text-to-speech, and even AI through your own account.

<p align="center">
  <img src="docs/images/appapp-runtime-phone.png" width="23%" alt="Your app: the Starter Demo running full screen, with notes, photos, pronunciation and AI">
  <img src="docs/images/appapp-library-phone.png" width="23%" alt="Library: your apps, with LingoMage and Starter Demo">
  <img src="docs/images/appapp-editor-phone.png" width="23%" alt="Editor: the Starter Demo’s source and data files">
  <img src="docs/images/appapp-settings-phone.png" width="23%" alt="Settings: backup and AI provider connections">
</p>
<p align="center"><sub><b>Your app</b> · <b>Library</b> · <b>Editor</b> · <b>Settings</b></sub></p>

## Download

Get the latest APK from [Releases](https://github.com/javiercmh/appapp/releases/latest) and open it on your phone. App² runs on **Android 8.0 or newer**.

## Get started

1. Open App² and tap **New app**. Import a ZIP file, paste a public GitHub repository or folder URL, or start from the included **Starter Demo**.
2. Tap an app to run it, or choose **Edit** to change its files.
3. Choose **Install** to put the app on your home screen.

## What you can do

| | |
| :--- | :--- |
| 📥 **Import** | Add apps (called *subapps*) from ZIP files or public GitHub repositories. |
| 🔄 **Update** | Pull new versions from GitHub. Your data is kept. |
| ✏️ **Edit** | Browse files, with code highlighting and search. Change each app’s name and icon. |
| 🏠 **Launch** | Each app gets its own home-screen shortcut and screen. |
| 💾 **Save files** | Each app has its own private folder for notes, settings, photos and other data. |
| 🔔 **Use Android** | Notifications, scheduled reminders, photo selection and text-to-speech. |
| ✨ **Use AI** | Connect ChatGPT, OpenRouter or any OpenAI-compatible endpoint once in **Settings → AI**. Every app can use it; your credentials never enter an app’s files. |
| 📦 **Back up & share** | Snapshots, ZIP export and Android sharing for single apps, plus one ZIP for all of them. |

## Example apps

Paste a repository URL into App² to import it.

- **[LingoMage](https://github.com/javiercmh/lingomage)** — learn languages with AI stories that match your interests and reading level.
- **[Working Hours](https://github.com/javiercmh/working-hours)** — track shifts, breaks and overtime, with end-of-day reminders.
- **[Starter Demo](app/src/main/assets/starter_template/)** — included in App². Shows storage, photos, notifications, speech and AI, with a guide to building your own app.

Example repositories have their own licences.

## Updates and backups

- **Check for updates:** **Settings → About App² → Check for updates** checks App² and linked subapps. It shows your installed App² version and the latest release, with a GitHub link only when newer. App² Dev checks Dev releases.
- **Review subapp updates:** choose **Review updates** to review each newer version before installing, with **Skip** and **Stop reviewing** options. Updates are marked by a dot on the app’s menu and a highlighted **Update available** action. Checks run only when requested; same-version file changes do not count as a new version.
- **Update an app:** open its menu in the library and choose **Update app**. The code is replaced and your data is kept.
- **Back up everything:** **Settings → Subapp backup** saves all your apps, with their data, in one ZIP. Restoring adds them as new copies. App² settings, reminders and AI credentials are not included.

## Try App² Dev

App² Dev installs next to App² with its own data, so you can try new features early. Get it from a [Dev prerelease](https://github.com/javiercmh/appapp/releases).

## Make your own app

Apps are plain HTML, CSS and JavaScript. Start from the [Starter Demo](app/src/main/assets/starter_template/).

**For an agent building or editing a subapp:** read the [subapp author guide](app/src/main/assets/starter_template/AGENTS.md),
then the relevant section of the [runtime contract](app/src/main/assets/starter_template/PACKAGING.md).
The repository-root `AGENTS.md` is for Android host development.

For file workflows, [open a phone file](app/src/main/assets/starter_template/PACKAGING.md#choosing-a-file-from-the-phone),
[save an export](app/src/main/assets/starter_template/PACKAGING.md#save-as), or
[share with another app](app/src/main/assets/starter_template/PACKAGING.md#sharing).
Records and settings belong in [workspace data files](app/src/main/assets/starter_template/PACKAGING.md#storage-and-persistence).

To preview an app in your browser (Python 3.9+), run:

```sh
python3 app/src/main/assets/starter_template/dev_server.py --mock-ai
```

Then open [localhost:8888](http://localhost:8888).

## Build App²

Needs JDK 17 or 21:

```sh
./gradlew :app:assembleDebug
```

Developer documentation is indexed in [docs/README.md](docs/README.md).

## License

[MIT](LICENSE). Third-party notices are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).

You can do whatever you want with App². One personal request: please don’t put ads, non-consensual trackers or other invasive code in your apps.
