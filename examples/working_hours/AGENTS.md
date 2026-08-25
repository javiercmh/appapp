# AGENTS.md — Working Hours Subapp Specification & Developer Guide

This document describes the **Working Hours** application — its features, user workflows, strict data schema, and internal code structure.

> For platform integration details, runtime contracts, bridge API references, and packaging instructions, refer to [`PACKAGING.md`](./PACKAGING.md).

---

## 1. App Description & Purpose

**Working Hours** (stylized as **Hours**) is an offline-first, mobile-optimized working hours, break, and overhours tracking application designed for fast daily timekeeping:
- **Today Tracker**: Dedicated interface to log today's working hours with 24h ±15 min time steppers for start, end, and break — no date picker clutter and no preset chips.
- **Autosave**: There is no Save button. Every committed change is written to disk immediately (debounced 250 ms, flushed on blur, tab switch, sheet close, and app backgrounding). A tappable status line reports `Saved HH:MM`. An entry is only created after a real user interaction, never on load.
- **Shift Editor Sheet**: A single bottom sheet (`<dialog id="sheet-editor">`) edits any day — opened from `Log another day` or by tapping a Dashboard row. It carries the date picker (`max = today`), the steppers, the net preview, and per-entry delete with Undo.
- **Dashboard & Spreadsheet Transfer**: Central dashboard displaying total overhours balance, an editable carryover overhours field, and a shift feed where **pending** (not yet in Excel) rows are the visually salient ones and transferred rows recede. Includes a pending counter and `Mark all transferred` with Undo.
- **End-of-Day Reminder**: A notification a configurable number of minutes before today's end time (default 15, in 5 min steps). Only scheduled when today is actually logged. The card leads with a permission row (status dot, `Allow`, and `Open Android settings` when the host supports it) that re-checks itself whenever the app regains focus. Backed by the host's `AlarmManager` bridge so it fires with the app closed; falls back to an in-page timer on the dev server.
- **Theme**: Auto / Light / Dark in Settings (`Auto` follows the system via `prefers-color-scheme`), plus the accent color swatches.
- **Untracked Days Policy**: Overtime calculations strictly evaluate only explicitly logged shifts plus initial carryover balance. Missing or untracked days are completely ignored (never penalized as -8h).
- **Streamlined Settings**: Configurable daily target and default times (all as steppers), appearance, and a confirmation-protected "Delete All Entries" button.
- **File-Based Persistence**: All entries and configuration settings are saved as plain JSON files in `data/entries.json` and `data/settings.json` so they survive resets, are inspectable in `</>`, and are bundled in ZIP exports.

---

## 2. Application Architecture & Layering

The codebase strictly separates concerns into clean modules following a unidirectional dependency flow:

$$\text{UI (ui.js)} \longrightarrow \text{Store (store.js)} \longrightarrow \text{Bridge (bridge.js)}$$

| File | Module Role | Responsibility |
| :--- | :--- | :--- |
| `index.html` | UI Shell | Markup structure for Today (`#pane-today`), Dashboard (`#pane-dashboard`), and Settings (`#pane-settings`). |
| `css/style.css` | Styling | Light/dark design tokens, mobile-first layout, steppers, editor sheet, shift rows, and safe-area insets. |
| `js/app.js` | Bootstrap | Application entry point, tab switching, and keyboard suppression. |
| `js/ui.js` | Presentation | DOM rendering, German date formatting, live net previews, autosave scheduling, the editor sheet, transfer checkboxes, theme application, and toast notifications. Never calls the bridge directly. |
| `js/bridge.js` | Host Bridge | Also wraps the scheduled-notification API (`scheduleNotification` / `cancelScheduledNotification`), falling back to a `setTimeout` when no native scheduler exists. |
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
  "initialOvertime": "+00:00",
  "themeAccent": "#3b82f6",
  "theme": "auto",
  "reminderEnabled": true,
  "reminderLeadMinutes": 15
}
```

- **`initialOvertime`**: Carryover balance from prior tracking/Excel, formatted with sign and `HH:MM` (e.g. `"+14:30"` or `"-02:15"`). Editable directly in the Dashboard tab.
- **`dailyTargetTime`**: Target daily working duration in `HH:MM` (default: `"08:00"`).
- **`reminderLeadMinutes`**: Whole minutes before the end time to notify (number, not `HH:MM`). The Settings stepper edits it as `HH:MM` and converts on commit.
- **`theme`**: `"auto"` (follow system), `"light"`, or `"dark"`. Applied as `data-theme` on `<html>`; `"auto"` removes the attribute so the CSS `prefers-color-scheme` block decides.

---

## 4. Guidelines for Developing & Modifying Apps

1. **Keep Core Fields in `HH:MM`**: Always maintain `startTime`, `endTime`, `breakTime`, and `dailyTargetTime` in standard `HH:MM` strings.
2. **Never Use `toISOString()` for Dates**: It converts to UTC, so after midnight in UTC+X the app would log the previous day. Use `store.localIso()`.
3. **Use `attachTimeField()` for Any Time Input**: Do not reintroduce `<input type="time">` — its 12h/24h rendering is decided by the device locale and it offers no 15 min stepping.
4. **Autosave, Don't Ask**: New editors must call the `scheduleSave()` / `flushSave()` pair in `ui.js` rather than adding a Save button, and must not persist anything until the user actually interacts.
5. **Reminders Are Rescheduled, Never Accumulated**: `ui.syncReminder()` cancels and re-arms the single `REMINDER_ID` alarm. Call it after anything that could move today's end time; never schedule a second alarm.
6. **Keep Status Messages Short**: Toasts are confirmations (`Saved`, `Target updated`), not sentences — never echo the full date back to the user.
7. **Isolate Logic in `store.js`**: DOM manipulation belongs in `ui.js`; math and JSON file reads/writes belong in `store.js`.
8. **No External Dependencies**: Keep everything vanilla HTML5, modern CSS, and ES Modules (`type="module"`).
9. **Update `manifest.json`**: Always add newly created files to the `"files"` array in `manifest.json`.
