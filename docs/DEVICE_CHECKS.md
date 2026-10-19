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
- Validate permissions after publisher updates, including Internet reset. Network-filter limits are documented; this list does not claim complete local-network isolation.
