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
    "github": { "owner": "owner", "repository": "repo" },
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

The `github` block suggests the source when an app is first linked. Its optional
`branch` defaults to `main`; leave it out, because the branch belongs to each
installation (App² builds before Dev build 11 still require it, so keep it on a
release branch until phones have a newer App²). After that the
user's chosen source decides where updates come from. Tapping Update on a linked
app checks its source right away; **Change source** on the result (up to date,
review, or a failed check) edits the repository (`owner/repository`, a github.com URL also works) and branch
(type it, or tap the arrow to pick from the repository's branches on GitHub),
so a phone can follow a `dev` branch or a fork. The downloaded manifest's `github`
block does not have to match the chosen source; a dev branch can keep the main
branch's manifest. A new source is saved only when an update from it is installed,
and like any source change it is shown as a warning that needs **Update anyway**.
Switching back to `main` works the same way.

Publisher IDs are independent of App²'s stable installed subapp IDs. The host
resolves the branch to a commit and downloads only declared files at that commit,
after the review (see below).
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

## Branches and releases

Subapps such as LingoMage follow App²'s branch model (`dev` for work, `main` for releases; see `AGENTS.md`). Installed apps follow `main`, so a
publisher release bumps `version` in `manifest.json` on `dev` and then fast-forwards
`main`. Leave `app2Update.github.branch` out (it defaults to `main`); dev branches
already do. Before a release fast-forwards a manifest without it to `main`, make sure
an App² release that accepts it (Dev build 11 or later) is out. To try unreleased work on a phone, tap Update, then
**Change source** and set the branch to `dev`, then set it back to `main` to return
to releases. Each switch is a source change that needs **Update anyway**; permissions
and user data are kept. A version bump on `dev` is optional: App² offers any change
to managed files and reports **App is up to date** only when the files and version
match what is installed. A lower `version` is still offered, marked **⚠ Older version** with an
**Install older version** button, so switching back from `dev` to `main` works.
Versions compare as dotted numbers with an optional pre-release suffix:
`1.2.46 < 1.2.47-dev.3 < 1.2.47`, so a dev build can read as newer than its release.

App² first asks GitHub for the branch's newest commit. When that is the installed
commit (or one a full check already found identical), it reports **App is up to
date** without downloading. App² never checks for updates on its own. Otherwise it
reads only the new `manifest.json` and asks GitHub to compare the installed commit
with the new one (one request returning both the commits and the changed files).
The review is built from that, before any package file is downloaded: **What's new**
lists the newest commit subjects (with "+ N more commits"), and **Show changed files**
lists the added (+), changed (~) and removed (−) files. If no managed file changed and
the version is the same, it reports **App is up to date**. Only after **Update** does
App² download the reviewed commit, four files at a time with a progress bar, while
the app keeps running. Managed files the publisher did not touch are copied from the
workspace when they still match the hashes recorded at the last install, so only the
changed files are fetched. That download runs as part of the update operation, so it
survives rotation. Reviewing several apps' updates in a row from the library uses the same
commit-first review for each app.

When GitHub's comparison cannot describe the update exactly, App² downloads the whole
package and compares it file by file before the review, as before: an app being linked
for the first time, a source in another repository (a fork), a commit that is older
than or has diverged from the installed one, a failed comparison, or one listing
GitHub's maximum of 300 files. The review then says why commits are not listed, for
example that the commit is N commits behind the installed version. Downloads left
behind by an interrupted check or update are removed when App² next starts.

## Publishing a subapp update

1. Work in the publisher repo. Update version/ownership metadata and keep user
   records compatible. Publish only when the user explicitly authorizes commit
   and push. GitHub cannot serve local uncommitted changes.
2. For a local preview, run:
   `python3 scripts/package_subapp.py /absolute/subapp/path /tmp/App.zip`.
   For Lingomage use `--preview` to name the separate installation LingoMage Preview.
   The script uses declared empty seed defaults, excludes unlisted files, and
   emits a deterministic ZIP plus `.checks.json` with source revision and hashes.
   `buildDirty` distinguishes a local preview from a published revision.
   To try it on a phone, push the ZIP to Downloads and import it through the library's ZIP
   picker as a separate app; never import over the user's app. To check what landed, export
   that app through the UI and compare its ZIP hashes with `.checks.json`.
3. For a published update, tap Update (it checks GitHub at once), review source,
   commit and file changes, then confirm. If managed code changes after review, check again. Reload occurs
   after installation.
   No APK rebuild is needed for subapp-only changes.
4. Report revision, verification result and any blockers. Inspect verbose logs
   only on failure; avoid repeated repository scans or full UI dumps.

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
