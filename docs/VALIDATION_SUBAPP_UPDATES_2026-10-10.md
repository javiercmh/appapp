# Subapp updater validation — 2026-10-10

## Build and automated checks

- Checked-in Gradle wrapper 9.6.0; existing host JVM 25.0.4.1; Kotlin target 17.
- `:app:assembleDebug :app:testDebugUnitTest :app:assembleDebugAndroidTest :app:assembleRelease` passed.
- 24 JVM tests passed (10 updater/download tests plus 14 existing AI tests).
- Medium_Phone, Android 36, emulator-5554: matching debug APK update with `-r`;
  `UpdaterDeviceTest` passed both tests. Test files lived only in isolated cache
  directories. Covered real binary preservation and crash-journal recovery, valid
  library ZIP import, traversal, aliases/duplicates, missing entrypoint, oversized
  extraction and staging cleanup.
- JVM tests covered obsolete managed file removal, unowned code preservation,
  missing seeds, local edits, identity mismatch, stale review, directory conflict,
  code rollback retaining new stories, and rejecting rollback across a change to
  user ownership. Fake HTTP responses verified commit-pinned declared-file-only
  downloads, redirects, unsupported host versions and asset size limits.
- JS syntax checks passed for starter bridge and Lingomage app/bridge. Isolated
  Node fixtures verified unavailable browser updates reject and native messages
  correlate and resolve; no AI inference was performed.
- Packaging the same source twice produced byte-identical ZIPs. Preview contained
  ten declared runtime files, with empty seed defaults; no Android reference
  project, repository internals or local development records were included.

## Emulator UI

- Imported LingoMage Preview through library ZIP picker; existing library apps
  were left in place. Preview ID: 885953b4-3a52-4be6-9bf1-c791b9b7e863.
- Installed HTML, app JS, bridge JS and shipped learning profiles matched source
  SHA-256 values via `run-as` on this disposable emulator preview.
- Editor Share & Package modal showed file selection, Share ZIP, Save to Device
  and Close. No import section/action. The separate editor Update app button
  opened native linking/review with the declared source and main branch.

## Galaxy S24 delivery

- Explicitly selected serial: `adb-R3CXC04E53K-qSdWHG (2)._adb-tls-connect._tcp`.
- Pulled the installed APK read-only and verified its certificate. Release output
  matched SHA-256 d3a30c1acecc9008f11711c39013353f00fc58b540a26eba95bb3556eb02e89a.
- Installed final App² 1.3.0, version code 4, using `adb install -r`. No uninstall,
  storage clear, workspace reset or import over an existing app was used.
- Existing configured library was visible before installation; the update did not
  introduce a new legacy migration. Hours, Routines and LingoMage remained present.
- Transferred and imported a separate LingoMage Preview. Phone-side archive SHA-256
  matched d21fe7f3d73ada00bdd910ebb8842adeeb0955234796d6a785df4546d352b7c3.
  Local source revision was 8b8c0e316bd91211fe18decebb072b748a73eee9 plus uncommitted
  changes, marked buildDirty in the preview manifest.
- Preview rendered the current dark interface. Settings discovered GPT-6-Luna and
  provider-default reasoning. Its Update app button opened native review with
  javiercmh/lingomage, main. No live generation was run.
- The publisher repository initially returned anonymous 404; later it was public
  and reachable. Native Check fetched published 1.0.0 and correctly rejected it
  with “This app has no app2Update metadata”. Publication of tested local 1.1.0
  changes requires explicit commit/push authorization.

- Exported only the new preview through Share & Package → Save to Device. After
  the Android picker returned and file writing completed, retrieved its ZIP and
  compared all eight managed/runtime files against package hashes: all matched.
  Preview settings naturally changed after model discovery; user seeds were not
  used as a code-verification baseline.

## Pending

- A real successful GitHub update requires the new metadata/code to be published.
  Then validate on the disposable emulator preview before updating the original
  phone Lingomage. Do not report the original app as updated until this happens.
- Original phone user records have not been read or overwritten. No claim is made
  that hidden record hashes were independently inspected. Current delivery uses
  a separate preview and leaves the original workspace untouched.
- Full long-running AI generation and large-workspace performance are not part of
  this validation. The local install transaction currently pauses views and runs
  synchronously; large binary-heavy workspaces can temporarily block the UI.
