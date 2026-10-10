# AGENTS.md — Working Hours Subapp Specification & Developer Guide

This document describes the **Working Hours** application — its features, user workflows, strict data schema, and internal code structure.

> For platform integration details, runtime contracts, bridge API references, and packaging instructions, refer to [`PACKAGING.md`](./PACKAGING.md).

---

## 1. App Description & Purpose

**Working Hours** (stylized as **Hours**) is an offline-first, mobile-optimized working hours, break, and overhours tracking application designed for fast daily timekeeping:
- **Today Tracker**: Dedicated interface to log today's working hours with 24h ±15 min time steppers for start, end, and break — no date picker clutter and no preset chips.
- **Autosave (today only)**: The Today card has no Save button — every committed change is written to disk immediately (debounced 250 ms, flushed on blur, tab switch, and app backgrounding), and the header chip reports the state as `✓` (saved), `•` (write pending), or `Not logged`. No timestamps, no status line. An entry is only created after a real user interaction, never on load.
- **Manual Save for Any Other Day**: The Shift Editor Sheet never autosaves, not even when it shows today. Edits live in the sheet until **Save**, which writes and closes; the button is ghost-styled while nothing has changed and turns primary once it does. Closing with unsaved edits (`✕ Close`, backdrop, Esc, Android back) asks `Discard unsaved changes?` first. Merely opening the sheet writes nothing.
- **Shift Editor Sheet**: A single bottom sheet (`<dialog id="sheet-editor">`) edits any day — opened from `Log another day` or by tapping a Dashboard row. It carries the date picker (`max = today`), the steppers, the net preview, and per-entry delete with Undo.
- **Dashboard & Spreadsheet Transfer**: Central dashboard displaying total overhours balance, an editable carryover overhours field, and a shift feed where **pending** (not yet in Excel) rows are the visually salient ones and transferred rows recede. Includes a pending counter and `Mark all transferred` with Undo.
- **End-of-Day Reminder**: A notification a configurable number of minutes before today's end time (default 15, in 5 min steps). Only scheduled when today is actually logged and both notification permission and exact-alarm access are available. The reminder card offers separate controls for each permission. Android reminders use the host's exact `AlarmManager` scheduler and fail visibly when exact access is missing; browser previews can only use an in-page timer while open.
- **Theme**: Auto / Light / Dark in Settings (`Auto` follows the system via `prefers-color-scheme`), plus the accent color swatches.
- **Untracked Days Policy**: Overtime calculations strictly evaluate only explicitly logged shifts plus initial carryover balance. Missing or untracked days are completely ignored (never penalized as -8h) — including non-working days, which only ever count when a shift is actually logged on them.
- **Working Days**: Settings defines which weekdays are working days (default Mon–Fri). Days that are off have a daily target of `00:00`, so every logged minute on them counts as overtime. The day is decided by the entry's own `date`, so an overnight shift follows the rule of the day it started on.
- **Streamlined Settings**: Configurable daily target and default times (all as steppers), appearance, and a confirmation-protected "Delete All Entries" button.
- **File-Based Persistence**: All entries and configuration settings are saved as plain JSON files in `data/entries.json` and `data/settings.json` so they survive resets, are inspectable in `</>`, and are bundled in ZIP exports.

---

## 2. Application Architecture & Layering

The codebase strictly separates concerns into clean modules following a unidirectional dependency flow:

$$\text{UI (ui.js)} \longrightarrow \text{Store (store.js)} \longrightarrow \text{Bridge (bridge.js)}$$

| File | Module Role | Responsibility |
| :--- | :--- | :--- |
| `index.html` | UI Shell | Markup structure for Today (`#pane-today`), Dashboard (`#pane-dashboard`), and Settings (`#pane-settings`). |
| `css/style.css` | Styling | Light/dark design tokens, mobile-first layout, steppers, editor sheet, shift rows, and the `--sa-*` safe-area tokens (see [`PACKAGING.md`](./PACKAGING.md) §3.2). |
| `js/app.js` | Bootstrap | Application entry point, tab switching, and hiding the bottom bar only while the on-screen keyboard is visible. |
| `js/ui.js` | Presentation | DOM rendering, German date formatting, live net previews, autosave scheduling, the editor sheet, transfer checkboxes, theme application, and toast notifications. Never calls the bridge directly. |
| `js/bridge.js` | Host Bridge | Also wraps the scheduled-notification API (`scheduleNotification` / `cancelScheduledNotification`) and exact-alarm access. A `setTimeout` is used only in browser previews, where it lasts only while the page remains open. |
| `js/timefield.js` | Time Control | Reusable 24h `HH:MM` stepper (±15 min, snap-to-grid, hold-to-repeat, free typing). Replaces `<input type="time">`, whose 12h/24h format follows device locale. |
| `js/store.js` | Data & State | Business logic, `HH:MM` time math, overhours calculations, and file synchronization with `data/*.json`. |
| `js/bridge.js` | Host Bridge | Sole wrapper around `window.AndroidStorage` (with desktop browser fallback). |
| `data/entries.json`| Shifts Data | Persistent JSON storage for all daily shift records. |
| `data/settings.json`| App Settings | Persistent JSON storage for defaults, work targets, and initial overtime carryover. |
| `manifest.json` | Packaging Metadata | App identity and file manifest (see [`PACKAGING.md`](./PACKAGING.md)). |

---

## 3. Data Schema & Persistence Rules

### ⚠️ Critical Standard: All Data & Settings Must Be File-Based
All domain records and application configuration **must be stored as plain JSON files in the `data/` directory**. Avoid storing app configuration in opaque `SharedPreferences` (`saveState`/`loadState`) so that settings and records are transparent, inspectable in the File Editor (`</>`), included in ZIP backups, and preserved in history snapshots.

### 3.1. Shift Entry Schema (`data/entries.json`)
The output visualization strictly tracks and displays these exact core fields in `HH:MM` format:
```json
[
  {
    "id": "wh_1724659200000",
    "date": "2026-08-25",
    "startTime": "08:30",
    "endTime": "17:00",
    "breakTime": "00:30",
    "transferred": false,
    "createdAt": 1724659200000
  }
]
```

- **`date`**: ISO date string (`YYYY-MM-DD`).
- **`startTime`**: Shift start time in `HH:MM` (24h format).
- **`endTime`**: Shift end time in `HH:MM` (24h format).
- **`breakTime`**: Total break duration in `HH:MM` (e.g. `00:30` for 30 minutes).
- **`transferred`**: Boolean flag indicating whether the entry has been manually transferred into an external spreadsheet.

### 3.2. Settings Schema (`data/settings.json`)
```json
{
  "defaultStartTime": "08:30",
  "defaultEndTime": "17:00",
  "defaultBreakTime": "00:30",
  "dailyTargetTime": "08:00",
  "workingDays": [1, 2, 3, 4, 5],
  "initialOvertime": "+00:00",
  "themeAccent": "#3b82f6",
  "theme": "auto",
  "reminderEnabled": true,
  "reminderLeadMinutes": 15
}
```

- **`initialOvertime`**: Carryover balance from prior tracking/Excel, formatted with sign and `HH:MM` (e.g. `"+14:30"` or `"-02:15"`). Editable directly in the Dashboard tab.
- **`dailyTargetTime`**: Target daily working duration in `HH:MM` (default: `"08:00"`).
- **`workingDays`**: Weekday indices counted as working days, matching `Date.getDay()` (`0` = Sunday … `6` = Saturday; default `[1, 2, 3, 4, 5]` = Mon–Fri). Any day not listed has a target of `00:00`. An empty array is valid and makes every logged hour overtime.
- **`reminderLeadMinutes`**: Whole minutes before the end time to notify (number, not `HH:MM`). The Settings stepper edits it as `HH:MM` and converts on commit.
- **`theme`**: `"auto"` (follow system), `"light"`, or `"dark"`. Applied as `data-theme` on `<html>`; `"auto"` removes the attribute so the CSS `prefers-color-scheme` block decides.

---

## 4. Guidelines for Developing & Modifying Apps

1. **Keep Core Fields in `HH:MM`**: Always maintain `startTime`, `endTime`, `breakTime`, and `dailyTargetTime` in standard `HH:MM` strings.
2. **Never Use `toISOString()` for Dates**: It converts to UTC, so after midnight in UTC+X the app would log the previous day. Use `store.localIso()`.
3. **Use `attachTimeField()` for Any Time Input**: Do not reintroduce `<input type="time">` — its 12h/24h rendering is decided by the device locale and it offers no 15 min stepping.
4. **Autosave Today, Ask for Other Days**: Editors for *today* must call the `scheduleSave()` / `flushSave()` pair in `ui.js` rather than adding a Save button, and must not persist anything until the user actually interacts. Editors for any *other* day are manual: track a dirty flag, write only from an explicit Save, and confirm before discarding.
5. **Reminders Are Rescheduled, Never Accumulated**: `ui.syncReminder()` cancels and re-arms the single `REMINDER_ID` alarm. Call it after anything that could move today's end time; never schedule a second alarm.
6. **Keep Status Messages Short**: Toasts are confirmations (`Saved`, `Target updated`), not sentences — never echo the full date back to the user.
7. **Isolate Logic in `store.js`**: DOM manipulation belongs in `ui.js`; math and JSON file reads/writes belong in `store.js`.
8. **No External Dependencies**: Keep everything vanilla HTML5, modern CSS, and ES Modules (`type="module"`).
9. **Update `manifest.json`**: Always add newly created files to the `"files"` array in `manifest.json`.

### Device Testing & Source Synchronization

The tracked source for Working Hours is `examples/working_hours/`. An installed App² app runs a separate private copy from `filesDir/workspace/`; editing this example does not update that copy, and restarting App² alone does not sync it. This separation caused a recent UI fix to appear ineffective because the device still had the old `js/app.js`.

For an Android device check:

1. Confirm the intended device with `adb devices -l`. If more than one device is listed, pass `-s <serial>` to every ADB command. Confirm App² is installed and debuggable (`com.example.runtimecompiler`; `adb shell run-as com.example.runtimecompiler pwd` should succeed).
2. Copy each changed app-owned file from `examples/working_hours/` to the same relative path below the returned app data directory's `files/workspace/`. For example, to sync `js/app.js` on a Linux development host:

   ```sh
   base64 -w 0 examples/working_hours/js/app.js \
     | adb shell 'run-as com.example.runtimecompiler sh -c "base64 -d > /data/user/0/com.example.runtimecompiler/files/workspace/js/app.js"'
   ```

   Replace the source and target relative paths for other changed code/assets. Do not copy `data/`, `app.log`, or user-created files; those belong to the installed workspace and must survive code testing. The target path above is for Android user 0; use the app data path printed by `run-as ... pwd` if it differs.
3. Verify the copy before testing by comparing `sha256sum examples/working_hours/<path>` with `adb exec-out run-as com.example.runtimecompiler cat files/workspace/<path> | sha256sum`. Matching hashes are the precondition for claiming the device ran the current source.
4. Run `adb shell am force-stop com.example.runtimecompiler`, then launch App² again. This clears the live WebView and reloads the workspace files. Exercise the relevant behavior and record the device/model and checks actually completed; do not treat a local browser check or an old installed workspace as Android validation.

Use `python3 examples/working_hours/dev_server.py` for quick browser checks against the tracked source. Browser checks do not cover the Android WebView, bridge, keyboard, or native permission flows; use the device procedure for those.
