# Subapp updates and phone delivery

Share & Package App exports selected files or opens Android sharing. It cannot
import a ZIP into an existing workspace. Library → Import → ZIP installs another
app. Library app menu → Update app, the editor download button, and a subapp's
`updates.open()` all open the same native GitHub review.

## Publisher contract

A public repository's root `manifest.json` must declare:

```json
{
  "version": "1.1.0",
  "app2Update": {
    "appId": "com.example.myapp",
    "minimumHostVersionCode": 4,
    "github": { "owner": "owner", "repository": "repo", "branch": "main" },
    "managedFiles": ["index.html", "manifest.json", "css/style.css", "js/app.js", "js/bridge.js", "data/definitions.json"],
    "seedFiles": ["data/records.json", "data/settings.json"],
    "seedDefaults": { "data/records.json": [], "data/settings.json": {} }
  }
}
```

`minimumHostVersionCode` is a wire-format name kept for compatibility; its value is
a **host compatibility level**, not an APK version code. The host compares it with
`BuildConfig.HOST_COMPATIBILITY_LEVEL` (currently `11`, defined once in
`defaultConfig` and identical in release, Dev and debug builds). The check is strict:
a package requiring a higher level than the host supports (for example `12` on a
level-`11` host) is rejected after reading the manifest, before downloading package assets, with a message naming
both levels and advising the user to update App². Packages requiring a lower or equal
level (LingoMage's `4`) install. Dev builds use the run number as `versionCode`, so
that counter no longer affects update compatibility. Raise the level only when the
update contract or bridge gains behavior that publishers can require; releases and
Dev builds do not change it.

Publisher IDs are independent of App²'s stable installed subapp IDs. The host
resolves the branch to a commit and downloads only declared files at that commit.
It records commit, source and managed hashes beside the workspace. Private repos,
repository subfolders and automatic data migrations are not supported in v1.
The repository must be anonymously readable from the phone; SSH access on the
development computer alone does not grant phone access. Every package must manage `index.html` and `manifest.json`. File paths must be
relative, unique, without hidden components or traversal, and cannot overlap as
files/directories. Update limits: 256 files, 8 MiB per asset, 256 KiB manifest and 32 MiB
per download. The host rejects redirects. A changed publisher ID or source is shown in the review dialog as a warning and applies only after the user chooses Update anyway; the warning names the permissions the new code will inherit. Library ZIP installation
also rejects traversal, duplicate paths, missing root index.html and archives over
1024 entries, 8 MiB per file or 32 MiB expanded total.

Managed files are replaced; previously managed files omitted from the new package
are removed (unless reclassified as seeds). Seed files are copied only when
absent. All other files are preserved. Ownership is explicit: `data/` alone does
not imply user ownership. Lingomage's stories/settings are seeds; its learning
profiles are managed shipped definitions. New managed paths can replace existing
files only after native review; never declare a user's domain records managed.
App authors must maintain saved-record compatibility and bump readable versions.
Actual changes are identified by commit and hashes.

Older installs require explicit linking to a repository and confirmation of the
publisher ID. Locally edited/missing code is listed before replacement. Do not
use display names to identify an update target. Updates cannot run with unfinished
or unacknowledged AI requests. Save/acknowledge or cancel them first.

## Android Back and LingoMage

Standalone subapp tasks send Android Back through WebView history when an entry is
available; the app's `popstate` handler can then close an in-app reader and return
to its shelf. With no WebView history, Back exits the task normally. LingoMage
keeps its existing history and `popstate` behavior, and release `1.2.7` must set
`app2Update.minimumHostVersionCode` to `8`, a compatibility level introduced with
App² `1.5.1` (the current host supports level `11`).

## Recovery

The host pauses live subapp screens, serializes bridge file operations, verifies a
binary-inclusive full workspace backup, builds a candidate preserving unowned
files, then swaps directories with a synced recovery journal. Startup recovery
restores the full pre-install workspace and metadata after an incomplete swap.
The backup is `files/subapps/<id>/update-backup/`, outside the web workspace and
ordinary workspace ZIP exports. It retains only the most recent recovery point;
ordinary History remains independent and is not a complete binary backup.

GitHub updates save the workspace before and after installation in **Edit → Version history**.
Manual editor saves and app settings changes also save versions immediately, without
requiring Run. Restore earlier versions exclusively from that dialog; Update app
only checks, reviews and installs publisher updates. If publisher content matches the
last installed update, it reports **App is up to date** with no Update action, even
when local edits differ or unrelated repository commits exist. Restoring also saves the current
workspace first. History restores text files (including JSON data/settings); binary
assets and runtime logs remain untouched. Export current data before restoring if
it needs to be retained. Installed publisher metadata remains the last GitHub update,
so restored code is reported as local changes during the next update review.

## Low-token routine

1. Work in the publisher repo. Update version/ownership metadata and keep user
   records compatible. Publish only when the user explicitly authorizes commit
   and push. GitHub cannot serve local uncommitted changes.
2. For a local preview, run:
   `python3 scripts/package_subapp.py /absolute/subapp/path /tmp/App.zip`.
   For Lingomage use `--preview` to name the separate installation LingoMage Preview.
   The script uses declared empty seed defaults, excludes unlisted files, and
   emits a deterministic ZIP plus `.checks.json` with source revision and hashes.
   `buildDirty` distinguishes a local preview from a published revision.
3. For a published update, open Update app and Check GitHub, review source,
   commit and file changes, then confirm. If managed code changes after review, check again. Reload occurs
   after installation.
   No APK rebuild is needed for subapp-only changes.
4. Report revision, verification result and any blockers. Inspect verbose logs
   only on failure; avoid repeated repository scans or full UI dumps.

## Host APK delivery

- Run `./gradlew --version` before builds; use the checked-in wrapper. The current
  wrapper is Gradle 9.6 and this host currently runs JDK 25; the wrapper has already
  been upgraded. Kotlin bytecode remains JVM 17. Do not change JVM settings merely
  to deliver an update.
- Build/check on the emulator first. For routine validation use
  `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest`.
  Install the matching APK and test APK on the selected emulator and run
  `com.wynh.appapp.updates.UpdaterDeviceTest` with AndroidJUnitRunner.
- Build signed release with `./gradlew :app:assembleSignedRelease`.
- Run `adb devices -l`; always use `-s <serial>`. Check `pm path com.wynh.appapp`
  and inspect installed version/certificate. Pull the installed APK read-only if
  necessary. Verify both APKs with the SDK's `apksigner verify --print-certs`.
- Before touching a physical phone, record its exact serial/model and run
  `adb -s <serial> shell pm list users`. Select the intended Android user/profile
  and verify AppApp is already installed there with
  `adb -s <serial> shell pm list packages --user <userId> com.wynh.appapp`.
  Secure Folder and work profiles have separate package data;
  if the expected installation is absent in the intended profile, stop. Do not
  create a fresh installation in another profile as a recovery or test shortcut.
- Keep physical-phone testing read-only by default; run imports, edits, reorder,
  deletion, migrations, and other workspace mutations on `Medium_Phone`. If a
  phone-specific mutation is necessary, first export the relevant apps from the
  library, verify each ZIP includes its manifest and user `data/`, and copy the
  exports off-device. Android cloud backup is not a verified workspace backup.
  Use only a separate, named disposable app with test data. Never import over,
  delete, recreate, or clean up a user's existing phone app.
- The Galaxy S24 installation uses SHA-256
  `d3a30c1acecc9008f11711c39013353f00fc58b540a26eba95bb3556eb02e89a`.
  Stop on any mismatch; never uninstall or clear storage.
- Use `adb -s <serial> install -r app/build/outputs/apk/release/app-release.apk`.
  `-r` preserves data only if package, profile/user, and signing certificate
  match; it does not restore data that is missing. Do not install a debug APK on
  a phone unless its certificate is verified to match. Check migration
  implications before launching. A configured library's normal update does not
  create a new legacy migration. For routine phone updates, make and verify one
  full-library backup before installation and record app names/count. After
  installation, open the library and confirm the same apps are present. A second
  backup, file-hash comparison, and data-sentinel checks are not required. If the
  library is empty or apps are unexpectedly missing, stop and report the state;
  restore or alter entries only when the owner instructs you to do so. This
  library check does not verify every app's internal data.
- For first preview delivery, push the ZIP to Downloads and use the library's
  ZIP picker to create a separate app. Never import over the user's app or use
  `run-as` to bypass a signed release's sandbox.
- Verify actual UI controls and source hashes. When release sandbox access is
  unavailable, export the selected preview through the public UI and compare ZIP
  file hashes. Do not claim hidden device data was inspected or preserved unless
  the chosen path leaves it untouched or verification was actually performed.

## Web API

```js
import { updates } from './bridge.js';
if (updates.available) await updates.open();
```

The Promise resolves `true` when native review opens, not when installation
completes. It rejects on an unavailable channel or timeout. Only the owning HTTPS
origin and main frame can open review. Callers cannot supply download endpoints
or silently install. Browsers and development mocks report unavailable.


## Runtime coordination

Update installation runs as an owned background workspace operation. Only sessions of the affected app are suspended; App² does not pause process-wide WebView timers. The affected document reloads after the transaction. Updates never change the app's permissions (AI, notifications, Internet): decisions are kept after ordinary updates, an accepted publisher identity change, failed updates and recovery, and nothing is granted automatically. Native permission storage is outside the workspace and is not part of the update transaction or recovery backup.

The shared text-history restore now stages a complete replacement and preserves binary/unknown files. It remains a bounded text history, not an all-file backup. See [architecture](ARCHITECTURE.md) and [pending device checks](DEVICE_CHECKS.md).
