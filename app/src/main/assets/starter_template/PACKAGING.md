# App² Runtime Packaging Contract

The starter runs as plain HTML, CSS, and JavaScript inside App²'s Android WebView. The host exposes
native capabilities through `window.AndroidStorage` (also aliased as `window.AndroidMemory` and
`window.AndroidNotification`). The bundled `js/bridge.js` is the only file that should call it.

Each saved subapp runs at its own stable origin, `https://<subapp-id>.app.local/`. Files, browser
storage, bridge key-value state, notifications, and scheduled reminders are isolated by subapp ID.
The ID stays stable when the manifest name changes. Relative asset URLs and module imports continue
to work, and ZIP operations apply only to the selected app.

## Scheduled notifications

Use `scheduleNotification(id, triggerAtMillis, title, message)` for a reminder that must fire while
the app is closed. Pass the epoch-millisecond timestamp as a string. Reusing an ID replaces its
existing alarm; use `cancelScheduledNotification(id)` to remove it.

The host uses exact `AlarmManager` alarms. On Android 12 and later, check
`canScheduleExactNotifications()` and offer `requestExactNotificationPermission()` from a user action
when access is missing. That method opens Android's Alarms & reminders settings. Its Boolean return
reports whether the settings screen opened, not whether permission was granted. On return, listen for
`app2:alarmperm` with `event.detail.granted` or check permission again when the page resumes.
Scheduling returns `false` when exact access is missing; do not create an approximate duplicate.
Android 8 through 11 can schedule exact alarms without special access.

The host preserves pending reminders across reboot and app replacement, and re-arms them when exact
access is granted. Notification permission is separate and is checked again when a reminder fires.
Exact alarms can still be delayed by Android idle-mode limits; force-stopping App² prevents delivery
until it is opened again. A browser preview has no background scheduler and can only use a timer while
the page remains open.
