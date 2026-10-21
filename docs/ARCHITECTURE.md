# App² architecture

App² is the native runner and editor for plain-file web apps. Subapp domain behavior belongs to each workspace, not to the host.

## Ownership

- Activities own Android presentation, navigation, and deliberate user confirmation. `LibraryController` owns asynchronous library metadata and document-operation state. `WorkspaceActionController` owns a selected app's save, restore, reset, and update operation across rotation, and the follow-up each one asks of the screen: a successful operation leaves a completion in controller state, which the first started screen (possibly a new instance after rotation) handles and acknowledges. Follow-ups never live in a coroutine of the screen that started the operation. Controllers use the application context for filesystem work.
- `RuntimeSession` installs the same storage, AI, speech, translation, update, file-picker, logging, and renderer-recovery behavior in editor preview and standalone execution. `RuntimeFileChooser` owns exactly-once picker completion. `WorkspaceOperationUi` renders retained operation state; sessions own capability disposal.
- `WorkspaceFiles` is the common path validator, regular-file enumerator, strict UTF-8 reader, and atomic byte writer. `BridgeWorkspaceFiles` adds the existing bridge caps and failure values; it is not a second filesystem implementation. `WorkspaceManager` adds template and editor operations. Constructing or listing a manager never initializes a starter.
- `SubappRepository` owns identities and the library index. `LibraryIndex` distinguishes loaded, missing, and unreadable state. `LibraryPublication` journals new-app publication so interruption cannot silently leave a registered partial app. Normal mutations refuse a damaged index. Deliberate recovery preserves valid ordering and appends discovered workspace directories. Damaged app entrypoints remain visible for editing; they are never replaced with a starter merely by opening the library.
- `WorkspaceOperations` owns workspace reservation, document generation, staged replacement, and interruption recovery. ZIP import and text-history restore use the same replacement implementation. Publisher updates retain their metadata-aware transaction and verified recovery backup. Per-subapp permission grants live outside the workspace and are never modified by an update, failed update or recovery.

These boundaries stay in one application module. Dependency injection uses constructors and Android ViewModels; no additional framework or parallel legacy implementation is required.

## Transactions and threads

The repository monitor comes before workspace monitors. A workspace operation reserves ownership before the UI suspends its sessions, then runs filesystem work on `Dispatchers.IO`. An operation that has entered publication completes or recovers even if its screen closes; it runs in an application-lifetime scope, so closing or rotating the screen cannot cancel it or report a finished operation as failed. UI session callbacks run outside filesystem monitors. Reservations reject competing mutations instead of depending on a blocked UI thread.

A reservation is either a *replacement* or not. Restore, publisher update, factory reset, and app deletion replace code or data wholesale: they advance the document generation (pages loaded earlier can no longer write), suspend the app's live sessions, and make them reload. A single-file save, create or delete, a run snapshot, a settings save, and a read-only backup only exclude competing operations: the generation does not change, sessions are not suspended, and running pages keep their in-memory state. While any reservation is held, bridge calls fail fast and the asset server answers 503. A session finished by a replacement reloads at once only when its activity is resumed; otherwise its WebView stays paused and it reloads in the next `onResume`, so a backgrounded app neither runs timers nor marks speech or translation as foreground.

Interrupted-work recovery runs once per process for each app, not on every library refresh, and never waits for a workspace that an operation owns; an operation recovers at its own start. An app is deleted before its reminders, AI requests, shortcut, and open screens are touched, so a refused deletion leaves it fully working. Export holds the workspace lock only while the ZIP is built in the cache; copying to the chosen destination happens outside it. Log appends never create a workspace directory.

Bridge writes validate paths and caps, write a temporary sibling file, sync its bytes, and atomically rename it over the destination. A failed write preserves the previous file. Temporary names are reserved, hidden from enumeration, and removed during explicit recovery. The bridge retains its existing synchronous signatures and failure values. An old document cannot write after an app operation changes its generation; the resumed document must reload. Own-origin asset requests during an operation receive 503 rather than a partly replaced app.

Directory replacement prepares and syncs all new files before moving the previous workspace aside; new-app publication syncs its staged files before the app is registered. Directory entries themselves are not synced. If interruption happens before activation, startup restores the old directory. If activation completed, startup retains the complete new directory and cleans the old one. Missing or unreadable files are not interpreted as permission to seed a starter. Native import provenance records the sanitized public GitHub URL and pinned commit beside the workspace; this does not automatically establish publisher-update trust.

ZIP and GitHub imports can be cancelled until publication begins: cancel interrupts the worker (a GitHub request already in flight finishes first) and, once publication has begun, is refused rather than reported as cancelling.

Library icons are sampled, loaded off the UI thread, and reused while metadata stamps are unchanged. RecyclerView receives changes for affected rows. Runtime logging uses a bounded worker queue and the existing bounded log file. Notification, authentication, and Android permission presentation remain native.

## History and compatibility

History defaults to four recent snapshots and one initial baseline. Retention is bounded; no unbounded archive is introduced. Only known UTF-8 text formats are captured and restored. Images, audio, PDFs, fonts, unknown formats, and logs remain untouched by text-history restore. A file with a text extension that is not valid UTF-8 (a Windows-1252 CSV, say) is likewise left alone: capture skips it with a log line, so it cannot block saving, and restore neither deletes nor overwrites it. Snapshot entries of unsupported types are skipped on restore and logged. A history file that cannot be parsed is renamed to `workspace_history.damaged-<time>.json`, never deleted, and a new list starts; an unreadable library index is handled the same way (`index.damaged-<time>.json`) when deliberately recovered, keeping every valid identity in order. A snapshot taken before a destructive action remains mandatory: if it cannot be saved for a real reason such as a full disk, the action does not run.

Full-library backup remains the way to capture all workspace bytes.

The current JSON history shape and public bridge calls stay usable. Internal implementations are replaced directly. Preserve a compatibility path only for a specific installed format or API contract, with an explicit removal decision; do not add speculative adapters.

## Security boundary

Storage, update, AI, speech, and translation remain restricted to their owning origin as documented in AGENTS.md. Internet is off for newly imported apps. With Internet granted, host-name and literal-IP filtering is a limited safeguard, not complete local-network isolation: DNS results, redirects, and request types outside the interceptor's coverage are not all controlled by this filter. A stricter network architecture is outside the current runner design. Never describe the existing filter as a complete network sandbox.

## Verification

CI runs JVM, JavaScript, Python, lint, debug assembly, and device-test compilation on JDK 21. Shared fixtures exercise native and development-server path rules. Fault-injection tests cover failed file publication, interrupted directory activation, corrupt indexes, competing reservations, and byte preservation. Device-test compilation is not device execution. See [pending native checks](DEVICE_CHECKS.md).
