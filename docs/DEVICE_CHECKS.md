# Device checks

Manual checks on the `Medium_Phone` emulator that automated tests do not cover yet. Remove an item
once it is verified or covered by a test; past verification runs live in git history and release
notes, not here. Device-test compilation is not device execution.

## Pending

- **Standalone and editor together.** With one app open standalone (split screen) and saved from
  the editor: a plain file save, Run and a settings save must not reload the standalone page or lose
  its in-memory state; restore, update and factory reset must reload it. With the standalone app in
  the background, the page reloads once on return and speech stays stopped while it is away.
- **Two apps at once.** Save, restore, update or back up one app; timers and interaction in the
  other continue.
- **Cancelling imports.** Cancel a ZIP import during extraction and a GitHub import between files:
  no app is registered. Cancel as publication starts: the app is registered and the dialog never
  says it is cancelling. Check the downloaded commit in the native import metadata.
- **Deleting a busy app.** Delete an app while another operation holds its workspace: the delete
  fails and its reminders, shortcut and open screens are untouched.
- **Slow export.** Export to a slow cloud destination while the app runs: the app stays responsive.
- **File inputs.** Single and multiple files, mixed MIME accepts, a cancelled picker, a page change
  while picking, and console/error logs, in preview and standalone.
- **Renderer recovery.** Terminate a renderer and use Reload; workspace data is unchanged.
- **Library recovery.** Recover a damaged library index using only disposable apps; workspace
  directories are preserved and registration and order survive a restart.
- **Interrupted replacement.** Force a process exit during a staged restore or import and reopen:
  the workspace holds the complete old or the complete new bytes, never a mix.
- **Reminders after an update.** Scheduled reminders are still delivered after an accepted
  publisher update.
- **Host compatibility level.** With App² Dev and a disposable app linked to a package declaring
  `minimumHostVersionCode` 4, Update app reaches the review; with 12, the error names both levels
  and advises updating App². Check the error at narrow widths with large fonts.
- **Speech controls.** No native Speaking bar appears in editor or runtime, including for old calls
  with `showControl`. The starter's web Speaking/Stop bar handles replacement, errors,
  backgrounding and navigation, and Stop cancels playback.
- **Dialogs and accessibility.** Recovery, operation progress and error, and renderer-reload
  dialogs at narrow widths with large fonts; TalkBack names and touch targets.

## Not established

Known limits of what has been verified, so nobody reports them as covered:

- Other Android versions and vendors, older WebView versions, and physical devices in general.
- Android 15's data-sync foreground-service time limit (only the callback path is simulated), and
  whether Android denies a plain `startForegroundService` from a backgrounded app on this image.
- That a suspended or frozen page keeps running JavaScript.
- The first-use AI permission dialog of `beginSession` on a device (covered at manager level only).
- Network filtering is a limited safeguard, not complete local-network isolation
  (see [architecture](ARCHITECTURE.md#security-boundary)).
