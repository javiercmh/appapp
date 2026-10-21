# Native verification

## Verified on emulator

Medium_Phone, Android 16/API 36, arm64; debug build only. No physical-device verification is implied.

- Ran the connected suite: 23 checks passed with no failures or skips, including workspace integrity, security, backup, updater, runtime Back, reorder, host AI exposure and speech. The out-of-scope LingoMage-preview fixture method was explicitly excluded. The final run also includes the new history and action tests below.
- The first run exposed failed-reorder UI rollback. Added catalog revisions and reran the suite successfully.
- Inspected screenshots of the library and app menu, editor hub and file editor, history and unsaved-change dialogs, preview and standalone runner. Checked normal 1080×2400 and narrow 900×1600 configurations; the narrow pass used 130% system fonts.
- Checked file-editor keyboard clearance and horizontal toolbar reachability. Created a disposable file, saved through the native editor and verified the expected bytes on disk. Opened and cancelled the standalone photo picker.
- Corrected dark status-bar icons on dark editor/runtime backgrounds and the clipped New File action; inspected rebuilt narrow-screen screenshots to confirm both corrections.

- Ran `WorkspaceHistoryDeviceTest` and `WorkspaceActionDeviceTest`: non-UTF-8 text files and damaged history, replacement versus plain-save fencing, factory reset through the reservation, and cleanup of a refused update passed.
- Rechecked the final editor hub and configuration dialog at normal and narrow widths with 130% fonts. Saved a disposable app name, rotated immediately afterwards, and inspected the closed dialog and retained name. This does not establish every possible rotation timing.
- Corrected two test synchronization races before the final successful run: the security test no longer accepts the old document as navigation completion, and the accessibility reorder test waits for asynchronous persistence.

## Dev distribution verification

Local Dev builds were checked on Medium_Phone, Android 16/API 36, arm64.
`assembleDev`, `lintDev`, 133 JVM tests and debug Android-test compilation passed;
the workflow's 14 JavaScript and 5 Python checks passed too. The configured
`assembleSignedDev` path was exercised with the existing local debug key solely
for emulator testing; the missing-Dev-key guard rejected publishing as intended.

App² and App² Dev installed together. Added a disposable Dev subapp and updated
Dev to a higher version code with `install -r`: its two library entries remained,
while production retained one entry. Android resolved `appapp://subapp/…` to
production and `appapp-dev://subapp/…` to Dev. Inspected launcher labels/icons,
the Dev library at 1080×2400, and at 900×1600 with 130% fonts. The Dev title fits;
the original App² artwork is retained with an amber DEV badge inside the adaptive mask.
Emulator display overrides and font scale were restored afterwards.

The GitHub prerelease workflow passed in [run 39197454905](https://github.com/javiercmh/appapp/actions/runs/39197454905),
publishing [Dev build 3](https://github.com/javiercmh/appapp/releases/tag/dev-3).
Downloaded its APK and verified the asset checksum, expected Dev signing
certificate, package ID, version code 3 and name `App² Dev`. Replaced only the
emulator's disposable debug-signed Dev installation, installed that published
APK, recreated two tiny fixtures and verified both survived a same-APK signed
reinstall. Cold-opened both libraries: production retained its original APK and
one entry. Inspected the published Dev library screenshot and the final original
App² artwork with amber DEV badge side by side on the launcher. The Dev Settings
title also fit at 900×1600 and 130% fonts. No physical-phone installation was
performed. Upgrading between distinct persistent-key published versions remains
pending; this check establishes a same-version signed reinstall only.

## Sharing and update permissions verification

Medium_Phone, API 37, arm64, emulator-5554; debug build only. The canonical boot
command in AGENTS.md selects the installed Android 37 image explicitly. No physical
device or real AI provider was used.

- 139 JVM tests, debug build, debug Android-test build and debug lint passed. The
  existing 14 JavaScript and 5 Python development-server tests also passed.
- Five targeted instrumented checks passed: `SharePackageDialogDeviceTest`,
  `UpdatePermissionDeviceTest` and the three `UpdaterDeviceTest` checks. The real
  update review used locally staged packages, with no live GitHub download. Cancel
  preserved code and publisher identity; Update anyway installed the new identity.
  Existing Notifications/Internet grants and persisted reminder records survived;
  AI remained off. The updater tests cover file preservation and journal recovery.
- Inspected collapsed/expanded sharing and identity-review screenshots at
  1080×2400 with normal text. At 840×1800 (320dp) with 150% text, inspected sharing
  and identity review and reran the confirmation test successfully. Both update
  choices remained completely visible and clickable. The message scrolls separately.
  Review corrected a disappearing Share ZIP label and a clipped native Cancel button.
- Selected only `index.html`, collapsed the file list and used Save to Device.
  Pulled the resulting ZIP, checked its integrity and verified that its only entry
  was `index.html`. The share-dialog test also checks that a partial selection and
  the master checkbox state survive collapsing and expanding.
- Restored emulator display size and font scale after checking. Actual alarm
  delivery, TalkBack speech, and a process kill during an update remain pending;
  preservation of seeded reminder preferences does not establish alarm delivery.

## Operation sessions (emulator Test37, debug build, fake provider)

Independent final verification: the combined notification, exposure, session, bridge and actual-package continuation run reported 46 cases, with 44 exercised and two assumption skips (the denied-notification-permission case and the optional LingoMage-preview fixture). After revoking notification permission on the disposable emulator, the isolated `backgroundedRequestStillCompletesWithoutNotificationPermission` case passed; permission was restored afterwards. This covers the existing request path with notification permission denied, not the first-use session permission dialog or LingoMage with denied notification permission.

Latest combined run: `AiRequestNotificationDeviceTest` (7 methods), `AiExposureDeviceTest` (3), `AiSessionDeviceTest` (30), `AiSessionBridgeDeviceTest` (5) and `LingoMageContinuationDeviceTest` (1) reported "OK (46 tests)". Two of those are skipped by their own assumptions and were not exercised: the denied-notification-permission test (needs `POST_NOTIFICATIONS` revoked first) and the LingoMage-preview fixture test (needs a staged fixture). `LingoMageContinuationDeviceTest` ran against a real package (`-e lingomageZip /data/local/tmp/lingomage-background-fixture.zip`, LingoMage's manifest files with empty `seedDefaults`) and passed in each of three runs. The earlier 43-test run likewise included 3 skips and is not a count of exercised tests. No real provider, account or physical device was involved. Covered: foreground start denial and a start that never confirms (provider never reached), a late start of an already timed-out attempt (ignored, service stops), provider reached only after the service is foreground, later foreground loss, a two-stage operation with an idle gap and one service start, task and app ownership, adoption of a running request, notification Cancel on idle and running sessions, completed results kept across cancellation, idle expiry, session cap, Android service timeout, grant revocation, page release versus final close, two-app isolation, and the real WebView bridge including a stage started while the activity was stopped.

Also covered after review: the unexpected destruction of a confirmed service (running requests and sessions end with `background_service`, completed results are kept, a normal idle stop is not mistaken for it, and the next start works); Cancel, revocation, final close and a replacing begin during a delayed foreground handshake (the pending begin fails with the accurate cancellation or `not_found`, zero provider calls, no service left behind); and a start command from an earlier attempt arriving after its replacement (it neither stops nor confirms anything).

Notification publication finding. Android publishes notifications on its own thread (visible a few milliseconds after the service posts them, longer under load) and **silently sheds** updates above a few per second per app. Because unchanged notifications are not re-posted, a shed update left a stale or unflagged notification, which first showed up as intermittent failures when many tests ran in one process. `AiRequestService` now verifies once after each change (about 1.2 s later, at most three repairs) that each expected notification is present with the intended fingerprint (a short hash of title, body, app name and the Cancel target, kept in the notification's extras) and, for the foreground one, the foreground-service flag, and re-posts only what is missing. A test replaces a notification with an impostor of identical visible text and a different identity and checks it is restored. A leased notification's Cancel names its session, not the request, so an action kept from stage one still cancels the live session during the gap or the next stage (no later inference), while an action from an ended session cannot cancel a new one; legacy requests keep request-scoped Cancel. The tests await publication and then require the notification to stay up, so a real gap still fails. The one failure seen on an independent run (`replacedPageReleasesItsSessionButNotARunningRequest`) could not be reproduced in 40+ local attempts including 15 timed samples every 3 ms; this explanation (asynchronous publication and shed updates) is inferred from the later reproducible failures, not shown for that exact run.

`LingoMageContinuationDeviceTest` runs LingoMage's own pages and bridge with a fake provider and is skipped unless a staged package is given: `adb push <lingomage-test.zip> /data/local/tmp/` (a ZIP of LingoMage's manifest `files` with empty `app2Update.seedDefaults`, as `scripts/package_subapp.py` produces), then `adb shell am instrument -w -e class com.wynh.appapp.ai.LingoMageContinuationDeviceTest -e lingomageZip /data/local/tmp/lingomage-test.zip com.wynh.appapp.test/androidx.test.runner.AndroidJUnitRunner`. It imports the ZIP as a disposable app with its own seeded story, goes through Library, Continue and submit with the real DOM, holds the summary and then the plan until after Home, and checks the request order (summary, plan, prose, notes), a single service start, the saved chapter and cleared checkpoint, and what was durable before each stage. **It first failed at start-up because its seeded settings did not name a model (LingoMage deliberately never auto-selects one); the fixture now saves `providerId` `chatgpt` and `model` `fake`, and it passes against the real package. What it shows about ordering: each stage's result was durable when the next request started. The order of save versus acknowledge within a stage is not shown here; LingoMage's pipeline ordering tests cover it.**

Not established:
- Whether Android denies a plain `startForegroundService` from a backgrounded app on this image. The characterization is not asserted; the session path does not depend on it.
- Behaviour on other Android versions or vendors, and Android 15's data-sync time limit (only the callback path is simulated).
- That a suspended or frozen page runs JavaScript. The bridge test starts stage two while the activity is stopped and the page ran; this is observed behaviour, not a guarantee.
- The first-use AI permission dialog path of `beginSession` on a device (grants were pre-set; the permission refusal is covered at manager level).

## Pending checks
- With one app open standalone (split screen) and saved from the editor: a plain file save, Run, and settings save must not reload the standalone page or lose its in-memory state; restore, update and factory reset must reload it. Repeat with the standalone app in the background: the page reloads once on return, and speech stays stopped while it is away.
- Rotate while a file save, a settings save, a restore, a reset and an update finish: the settings dialog closes and the shortcut refreshes, the editor no longer shows the saved draft as unsaved, and the update toast appears once.
- Cancel a ZIP import during extraction and a GitHub import between files: no app is registered. Press Cancel as publication starts: the app is registered and the dialog never says it is cancelling.
- Delete an app while another operation holds its workspace: the delete fails, and its reminders, shortcut and open screens are untouched. Export to a slow cloud destination while the app runs: the app stays responsive.

- Inspect recovery, operation progress/error, and renderer-reload dialogs at narrow widths and large fonts; verify TalkBack names and touch targets. Routine create/save finished too quickly to establish progress-dialog layout.
- Import ZIP and GitHub apps; rotate during download, cancel before publication, and confirm that cancellation does not register an app. Verify the downloaded commit in native import metadata.
- Rotate during save/restore/update; progress must remain attached to the operation, and disk publication must complete or recover. Check editor drafts independently.
- Run two independent subapps. Save, restore, update, or back up one and verify that timers and interaction in the other continue.
- Exercise single/multiple file inputs, mixed MIME accepts, cancelled picker, page change while picking, and console/error logs in preview and standalone execution.
- Terminate a renderer and use Reload; workspace data must remain unchanged.
- Recover a damaged library index using only disposable apps. Verify preservation of workspace directories, then restart and check registration/order.
- Force a process exit during staged restore/import and reopen. Verify complete old or complete new bytes, never a mixed workspace.
- Verify scheduled reminder delivery after an accepted publisher update, and force a process exit during a native update to confirm recovery and permission retention. The staged-package confirmation and journal-recovery checks above do not establish those timings. Network-filter limits remain documented; this list does not claim complete local-network isolation.

## Pending: host compatibility level

Not yet run on a device or emulator. On `Medium_Phone`, with App² Dev
(low run-number version code) and a disposable app linked to a package declaring
`minimumHostVersionCode` 4, confirm Update app reaches the review dialog; then with a
package declaring 12, confirm the error names levels 12 and 11 and advises updating App².

Also inspect the error dialog at normal and narrow widths with large fonts, and
confirm cancelling update review leaves the disposable workspace unchanged.

## Pending speech controls

Verify speech creates no native Speaking bar in editor and runtime, including old calls with showControl. Verify the starter web Speaking/Stop bar, replacement, errors, backgrounding and navigation; Stop must cancel playback.
