// ui.js — DOM rendering, event handling, and user interaction for Working Hours.
// Goes through store.js; never calls bridge directly. See AGENTS.md for platform rules.

import * as store from './store.js';
import * as bridge from './bridge.js';
import { attachTimeField } from './timefield.js';

const $ = (id) => document.getElementById(id);

const ACCENTS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#a855f7', '#ec4899'];
const SAVE_DEBOUNCE = 250;
// Fixed id so every reschedule replaces the previous alarm instead of stacking.
const REMINDER_ID = 1015;

// Set once the host reports a refused prompt: Android won't show it again, so the
// only remaining route is the system notification screen.
let notifPromptRefused = false;

let toastTimer = null;
let onToastExpire = null;

// Attached stepper controls, keyed by field name.
const fields = {};

// --- Date & Time Formatting Helpers ---

export function getTodayIso() {
  return store.localIso();
}

export function getYesterdayIso() {
  const d = new Date();
  d.setDate(d.getDate() - 1);
  return store.localIso(d);
}

export function formatGermanDate(dateStr, includeWeekday = true) {
  if (!dateStr) return '';
  try {
    const parts = dateStr.split('-');
    if (parts.length === 3) {
      const d = new Date(parseInt(parts[0], 10), parseInt(parts[1], 10) - 1, parseInt(parts[2], 10));
      const day = String(d.getDate()).padStart(2, '0');
      const month = String(d.getMonth() + 1).padStart(2, '0');
      const year = d.getFullYear();
      const formatted = `${day}.${month}.${year}`;
      if (includeWeekday) {
        const weekday = d.toLocaleDateString('de-DE', { weekday: 'short' });
        return `${weekday}, ${formatted}`;
      }
      return formatted;
    }
  } catch (_) {}
  return dateStr;
}

export function formatCurrentTime() {
  const now = new Date();
  const h = String(now.getHours()).padStart(2, '0');
  const m = String(now.getMinutes()).padStart(2, '0');
  return `${h}:${m}`;
}

// --- Toast with Non-Blocking Undo ---

export function toast(message, undoLabel = null, onUndo = null, onExpire = null) {
  runPendingExpiry();

  const toastEl = $('toast');
  const textEl = $('toast-text');
  const actionEl = $('toast-action');
  if (!toastEl || !textEl) return;

  textEl.textContent = message;
  actionEl.hidden = !undoLabel;
  if (undoLabel) actionEl.textContent = undoLabel;
  toastEl.hidden = false;

  onToastExpire = onExpire;
  actionEl.onclick = () => {
    onToastExpire = null;
    clearTimeout(toastTimer);
    toastEl.hidden = true;
    if (onUndo) onUndo();
  };

  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    toastEl.hidden = true;
    runPendingExpiry();
  }, undoLabel ? 5000 : 2500);
}

function runPendingExpiry() {
  const pending = onToastExpire;
  onToastExpire = null;
  if (pending) pending();
}

// --- Autosave engine ---
// bridge.writeFile is synchronous, so a flush is durable the moment it returns.
// The debounce only coalesces rapid stepper taps; every exit path flushes it.

let saveTimer = null;
let pendingSave = null;

function scheduleSave(fn) {
  pendingSave = fn;
  clearTimeout(saveTimer);
  saveTimer = setTimeout(flushSave, SAVE_DEBOUNCE);
}

export function flushSave() {
  clearTimeout(saveTimer);
  saveTimer = null;
  const fn = pendingSave;
  pendingSave = null;
  if (fn) fn();
}

// Today's save state lives in the header chip — a checkmark, no timestamp.
const TODAY_STATUS = {
  none: { text: 'Not logged', className: 'badge', label: 'Not logged' },
  pending: { text: '\u2022', className: 'badge', label: 'Saving' },
  saved: { text: '\u2713', className: 'badge positive', label: 'Saved' }
};

function setTodayStatus(state) {
  const chip = $('today-status-chip');
  if (!chip) return;
  const { text, className, label } = TODAY_STATUS[state] || TODAY_STATUS.none;
  chip.textContent = text;
  chip.className = className;
  chip.setAttribute('aria-label', label);
}

// --- Today's Shift Editor ---

let todayDirty = false;

export function initTodayEditor() {
  const todayDate = getTodayIso();
  const dateChip = $('today-date-chip');
  if (dateChip) dateChip.textContent = formatGermanDate(todayDate, true);

  const existing = store.findEntryByDate(todayDate);

  fields.todayStart?.set(existing?.startTime || store.settings.defaultStartTime || '08:30');
  fields.todayEnd?.set(existing?.endTime || store.settings.defaultEndTime || '17:00');
  fields.todayBreak?.set(existing?.breakTime || store.settings.defaultBreakTime || '00:30');

  todayDirty = !!existing;
  setTodayStatus(existing ? 'saved' : 'none');

  updateTodayPreview();
}

export function updateTodayPreview() {
  renderPreview({
    date: getTodayIso(),
    start: fields.todayStart?.get() || '00:00',
    end: fields.todayEnd?.get() || '00:00',
    brk: fields.todayBreak?.get() || '00:00',
    netEl: $('today-net-time'),
    overtimeEl: $('today-overtime'),
    overnightEl: $('today-overnight'),
    hintEl: $('today-nonworking-hint')
  });
}

function renderPreview({ date, start, end, brk, netEl, overtimeEl, overnightEl, hintEl }) {
  const netMins = store.calcNetMinutes(start, end, brk);
  const targetMins = store.dayTargetMinutes(date, store.settings.dailyTargetTime);
  const diffMins = netMins - targetMins;

  if (netEl) netEl.textContent = store.minutesToTime(netMins);
  if (overtimeEl) {
    overtimeEl.textContent = store.minutesToTime(diffMins, true);
    overtimeEl.className = `overtime-value ${diffMins >= 0 ? 'positive' : 'negative'}`;
  }
  if (overnightEl) overnightEl.hidden = !store.isOvernight(start, end);
  if (hintEl) hintEl.hidden = store.isWorkingDay(date);
}

function saveTodayNow() {
  const date = getTodayIso();
  store.addOrUpdateEntry({
    date,
    startTime: fields.todayStart?.get(),
    endTime: fields.todayEnd?.get(),
    breakTime: fields.todayBreak?.get()
  });

  setTodayStatus('saved');
  syncReminder();
}

// Only a real interaction may create today's entry — otherwise merely opening
// the app would silently log a full default day.
function onTodayEdit() {
  todayDirty = true;
  updateTodayPreview();
  setTodayStatus('pending');
  scheduleSave(saveTodayNow);
}

// --- Shift Editor Sheet (any day) ---

let sheetDate = null;
let sheetDirty = false;

function sheetEntry() {
  return sheetDate ? store.findEntryByDate(sheetDate) : null;
}

export function openSheet(dateStr) {
  const sheet = $('sheet-editor');
  if (!sheet) return;

  const today = getTodayIso();
  sheetDate = (!dateStr || dateStr > today) ? getYesterdayIso() : dateStr;

  const dateInput = $('input-sheet-date');
  if (dateInput) {
    dateInput.value = sheetDate;
    dateInput.max = today;
  }
  loadSheetDate(sheetDate);

  if (typeof sheet.showModal === 'function' && !sheet.open) sheet.showModal();
  else sheet.setAttribute('open', '');
}

function loadSheetDate(dateStr) {
  sheetDate = dateStr;
  const existing = sheetEntry();

  const display = $('sheet-date-display');
  if (display) display.textContent = formatGermanDate(sheetDate, true);

  const title = $('sheet-title');
  if (title) title.textContent = existing ? 'Edit day' : 'New day';

  fields.sheetStart?.set(existing?.startTime || store.settings.defaultStartTime || '08:30');
  fields.sheetEnd?.set(existing?.endTime || store.settings.defaultEndTime || '17:00');
  fields.sheetBreak?.set(existing?.breakTime || store.settings.defaultBreakTime || '00:30');

  const deleteBtn = $('btn-sheet-delete');
  if (deleteBtn) deleteBtn.hidden = !existing;

  setSheetDirty(false);
  updateSheetPreview();
}

export function updateSheetPreview() {
  renderPreview({
    date: sheetDate,
    start: fields.sheetStart?.get() || '00:00',
    end: fields.sheetEnd?.get() || '00:00',
    brk: fields.sheetBreak?.get() || '00:00',
    netEl: $('sheet-net-time'),
    overtimeEl: $('sheet-overtime'),
    overnightEl: $('sheet-overnight'),
    hintEl: $('sheet-nonworking-hint')
  });
}

// The sheet has no status text: the Save button is the only state indicator.
function setSheetDirty(dirty) {
  sheetDirty = dirty;
  const saveBtn = $('btn-sheet-save');
  if (!saveBtn) return;
  saveBtn.classList.toggle('btn-primary', dirty);
  saveBtn.classList.toggle('btn-ghost', !dirty);
}

function saveSheetNow() {
  if (!sheetDate) return;
  store.addOrUpdateEntry({
    date: sheetDate,
    startTime: fields.sheetStart?.get(),
    endTime: fields.sheetEnd?.get(),
    breakTime: fields.sheetBreak?.get()
  });

  const deleteBtn = $('btn-sheet-delete');
  if (deleteBtn) deleteBtn.hidden = false;
  const title = $('sheet-title');
  if (title) title.textContent = 'Edit day';

  setSheetDirty(false);
  syncReminder();
  renderDashboard();
  if (sheetDate === getTodayIso()) refreshTodayFromStore();
}

// Any other day is saved by hand — editing only marks the sheet dirty.
function onSheetEdit() {
  updateSheetPreview();
  setSheetDirty(true);
}

function closeSheet({ force = false } = {}) {
  if (sheetDirty && !force && !window.confirm('Discard unsaved changes?')) return false;

  const sheet = $('sheet-editor');
  if (sheet) {
    if (typeof sheet.close === 'function' && sheet.open) sheet.close();
    else sheet.removeAttribute('open');
  }
  sheetDate = null;
  setSheetDirty(false);
  renderDashboard();
  refreshTodayFromStore();
  return true;
}

// Re-sync the Today card without wiping an in-progress edit elsewhere.
function refreshTodayFromStore() {
  if (document.getElementById('pane-today')?.classList.contains('active')) {
    initTodayEditor();
  }
}

// --- Setup Event Listeners for Tracker View ---

export function setupTrackerEvents() {
  // Today's steppers
  fields.todayStart = attachTimeField($('stepper-today-start'), { mode: 'clock', onCommit: onTodayEdit });
  fields.todayEnd = attachTimeField($('stepper-today-end'), { mode: 'clock', onCommit: onTodayEdit });
  fields.todayBreak = attachTimeField($('stepper-today-break'), { mode: 'duration', onCommit: onTodayEdit });

  $('btn-today-now-start')?.addEventListener('click', () => {
    fields.todayStart?.set(formatCurrentTime());
    onTodayEdit();
  });
  $('btn-today-now-end')?.addEventListener('click', () => {
    fields.todayEnd?.set(formatCurrentTime());
    onTodayEdit();
  });

  // --- Sheet editor ---
  fields.sheetStart = attachTimeField($('stepper-sheet-start'), { mode: 'clock', onCommit: onSheetEdit });
  fields.sheetEnd = attachTimeField($('stepper-sheet-end'), { mode: 'clock', onCommit: onSheetEdit });
  fields.sheetBreak = attachTimeField($('stepper-sheet-break'), { mode: 'duration', onCommit: onSheetEdit });

  $('btn-sheet-now-start')?.addEventListener('click', () => {
    fields.sheetStart?.set(formatCurrentTime());
    onSheetEdit();
  });
  $('btn-sheet-now-end')?.addEventListener('click', () => {
    fields.sheetEnd?.set(formatCurrentTime());
    onSheetEdit();
  });

  const sheetDateInput = $('input-sheet-date');
  sheetDateInput?.addEventListener('change', () => {
    if (sheetDirty && !window.confirm('Discard unsaved changes?')) {
      sheetDateInput.value = sheetDate;
      return;
    }
    const today = getTodayIso();
    if (sheetDateInput.value > today) {
      sheetDateInput.value = today;
      toast('Future days can\'t be logged');
    }
    loadSheetDate(sheetDateInput.value || getYesterdayIso());
  });

  $('btn-sheet-save')?.addEventListener('click', () => {
    saveSheetNow();
    closeSheet({ force: true });
  });

  $('btn-open-prev-day')?.addEventListener('click', () => openSheet(getYesterdayIso()));
  $('btn-sheet-close')?.addEventListener('click', () => closeSheet());

  // Backdrop tap and the Android back button both close via `cancel`/`close`.
  const sheet = $('sheet-editor');
  sheet?.addEventListener('cancel', (e) => {
    e.preventDefault();
    closeSheet();
  });
  sheet?.addEventListener('click', (e) => {
    if (e.target === sheet) closeSheet();
  });


  $('btn-sheet-delete')?.addEventListener('click', () => {
    const entry = sheetEntry();
    if (!entry) return;
    const removed = store.removeEntry(entry.id);
    syncReminder();
    closeSheet({ force: true });
    if (removed) {
      toast('Entry deleted', 'Undo', () => {
        store.restoreEntry(removed.entry, removed.index);
        syncReminder();
        renderDashboard();
        refreshTodayFromStore();
      });
    }
  });
}

// --- End-of-Day Reminder ---

/**
 * Re-arms the reminder from today's logged entry. Cheap and idempotent, so it runs
 * after every change that could move the end time, and at boot.
 */
export function syncReminder() {
  bridge.cancelScheduledNotification(REMINDER_ID);

  const entry = store.findEntryByDate(getTodayIso());
  const lead = parseInt(store.settings.reminderLeadMinutes, 10) || 0;
  let scheduledAt = null;
  let scheduleFailed = false;

  if (store.settings.reminderEnabled && entry && bridge.hasNotificationPermission() && bridge.schedulesExactly()) {
    const at = store.reminderTimestamp(entry, lead);
    if (at && at > Date.now()) {
      const body = lead > 0
        ? `${lead} min left — your day ends at ${entry.endTime}.`
        : `Your day ends now (${entry.endTime}).`;
      if (bridge.scheduleNotification(REMINDER_ID, at, 'Working Hours', body)) scheduledAt = at;
      else scheduleFailed = true;
    }
  }

  renderNotificationPermission();
  renderExactAlarmPermission();
  updateReminderHint(scheduledAt, scheduleFailed);
  return scheduledAt;
}

function renderExactAlarmPermission() {
  const row = $('reminder-alarm-row');
  if (!row) return;
  const supported = bridge.canScheduleWhileClosed;
  const granted = bridge.schedulesExactly();
  row.classList.toggle('granted', granted);
  const label = $('reminder-alarm-label');
  if (label) label.textContent = !supported ? 'Precise alarms unavailable here' : granted ? 'Precise alarms allowed' : 'Precise alarm access needed';
  const detail = $('reminder-alarm-detail');
  if (detail) {
    detail.hidden = !supported || granted;
    detail.textContent = 'Android needs Alarms & reminders access before this reminder can be scheduled.';
  }
  const button = $('btn-reminder-alarm-perm');
  if (button) button.hidden = !supported || granted;
}

function renderNotificationPermission() {
  const row = $('reminder-perm-row');
  if (!row) return;

  const granted = bridge.hasNotificationPermission();
  row.classList.toggle('granted', granted);

  const label = $('reminder-perm-label');
  if (label) label.textContent = granted ? 'Notifications allowed' : 'Notifications not allowed';

  const detail = $('reminder-perm-detail');
  if (detail) {
    detail.hidden = granted;
    detail.textContent = notifPromptRefused
      ? 'Denied — turn them on in Android settings.'
      : 'Reminders can\'t be delivered until you allow them.';
  }

  const allowBtn = $('btn-reminder-perm');
  if (allowBtn) allowBtn.hidden = granted || notifPromptRefused;

  const settingsBtn = $('btn-reminder-perm-settings');
  if (settingsBtn) settingsBtn.hidden = granted || !bridge.canOpenNotificationSettings;
}

function updateReminderHint(scheduledAt, scheduleFailed = false) {
  const hint = $('reminder-hint');
  const group = $('reminder-lead-group');
  if (group) group.hidden = !store.settings.reminderEnabled;
  if (!hint) return;

  if (!store.settings.reminderEnabled) {
    hint.textContent = 'Reminder off.';
    return;
  }
  if (!bridge.hasNotificationPermission()) {
    hint.textContent = 'Reminder paused until notifications are allowed.';
    return;
  }
  if (bridge.canScheduleWhileClosed && !bridge.schedulesExactly()) {
    hint.textContent = 'Reminder paused until precise alarm access is allowed.';
    return;
  }

  const entry = store.findEntryByDate(getTodayIso());
  if (!entry) {
    hint.textContent = 'Today isn\'t logged yet — the reminder is set as soon as you log it.';
    return;
  }
  if (scheduleFailed) {
    hint.textContent = 'Reminder could not be scheduled. Check notification and precise alarm access.';
    return;
  }
  if (!scheduledAt) {
    hint.textContent = 'Today\'s reminder time has already passed.';
    return;
  }

  const at = new Date(scheduledAt);
  const clock = `${String(at.getHours()).padStart(2, '0')}:${String(at.getMinutes()).padStart(2, '0')}`;
  hint.textContent = bridge.canScheduleWhileClosed
    ? `Today: precise reminder scheduled for ${clock}.`
    : `Today: notification at ${clock} — only while the app stays open (no native scheduler here).`;
}

// --- Dashboard Pane ---

export function renderDashboard() {
  renderOverhours();
  renderShiftFeed();
  initDashboardCarryover();
}

export function renderOverhours() {
  const balance = store.calcTotalOvertime();
  const totalEl = $('overhours-total');
  const badgeEl = $('overhours-badge');
  const initialEl = $('overhours-initial-label');
  const loggedEl = $('overhours-logged-label');

  const isPos = balance.totalMins >= 0;
  if (totalEl) {
    totalEl.textContent = balance.totalTime;
    totalEl.className = `overhours-value ${isPos ? 'positive' : 'negative'}`;
  }
  if (badgeEl) {
    badgeEl.textContent = balance.totalTime;
    badgeEl.className = `badge ${isPos ? 'positive' : 'negative'}`;
  }
  if (initialEl) initialEl.textContent = `Carryover: ${balance.initialTime}`;
  if (loggedEl) loggedEl.textContent = `Logged: ${balance.loggedTime}`;
}

function initDashboardCarryover() {
  const carryoverInput = $('dashboard-carryover');
  if (!carryoverInput) return;

  carryoverInput.value = store.settings.initialOvertime || '+00:00';
  carryoverInput.onchange = () => {
    let val = carryoverInput.value.trim();
    if (!val) val = '+00:00';
    if (!val.startsWith('+') && !val.startsWith('-')) val = `+${val}`;
    store.updateSetting('initialOvertime', val);
    carryoverInput.value = val;
    renderOverhours();
    toast('Carryover updated');
  };
}

export function renderShiftFeed() {
  const feed = $('entries-feed');
  if (!feed) return;
  feed.textContent = '';
  const entries = store.entries;

  const countChip = $('entry-count-chip');
  if (countChip) {
    countChip.textContent = `${entries.length} ${entries.length === 1 ? 'day' : 'days'}`;
  }

  if (!entries.length) {
    const empty = document.createElement('div');
    empty.className = 'empty-state';
    empty.innerHTML = '<span>✨</span>No work days logged yet. Track your hours in the <strong>Today</strong> tab.';
    feed.appendChild(empty);
    updatePendingSummary();
    return;
  }

  entries.forEach((entry) => feed.appendChild(createShiftRow(entry)));
  updatePendingSummary();
}

// The pending count is the point of this screen: what still has to go into Excel.
function updatePendingSummary() {
  const pendingCount = store.entries.filter((e) => !e.transferred).length;
  const chip = $('pending-chip');
  if (chip) {
    chip.hidden = pendingCount === 0;
    chip.textContent = `${pendingCount} pending`;
  }
  const markAll = $('btn-mark-all');
  if (markAll) markAll.hidden = pendingCount === 0;
}

function createShiftRow(entry) {
  const row = document.createElement('div');
  row.className = `entry-item ${entry.transferred ? 'transferred' : 'pending'}`;
  row.dataset.date = entry.date;
  row.dataset.id = entry.id;

  const netTime = store.calcNetDuration(entry.startTime, entry.endTime, entry.breakTime);
  const overtimeMins = store.calcDayOvertimeMinutes(entry, store.settings.dailyTargetTime);
  const overtimeStr = store.minutesToTime(overtimeMins, true);
  const isPos = overtimeMins >= 0;

  // Round checkbox: filled once the shift is in the spreadsheet.
  const check = document.createElement('button');
  check.type = 'button';
  check.className = 'entry-check';
  check.setAttribute('role', 'checkbox');
  check.setAttribute('aria-checked', String(!!entry.transferred));
  check.setAttribute('aria-label', `Transferred to spreadsheet — ${formatGermanDate(entry.date, false)}`);
  check.onclick = () => {
    const updated = store.toggleTransferred(entry.id);
    if (!updated) return;
    row.classList.toggle('transferred', updated.transferred);
    row.classList.toggle('pending', !updated.transferred);
    check.setAttribute('aria-checked', String(updated.transferred));
    pill.hidden = updated.transferred;
    updatePendingSummary();
  };
  row.appendChild(check);

  // Main tappable area opens the editor sheet for this day.
  const open = document.createElement('button');
  open.type = 'button';
  open.className = 'entry-open';
  open.onclick = () => openSheet(entry.date);

  const main = document.createElement('div');
  main.className = 'entry-main';

  const headerRow = document.createElement('div');
  headerRow.className = 'entry-header-row';

  const dateEl = document.createElement('span');
  dateEl.className = 'entry-date';
  dateEl.textContent = formatGermanDate(entry.date, true);
  headerRow.appendChild(dateEl);

  const pill = document.createElement('span');
  pill.className = 'pending-pill';
  pill.textContent = 'Pending';
  pill.hidden = !!entry.transferred;
  headerRow.appendChild(pill);

  const timesRow = document.createElement('div');
  timesRow.className = 'entry-times-row';
  timesRow.innerHTML = `
    <span><strong>${entry.startTime}</strong>–<strong>${entry.endTime}</strong></span>
    <span>·</span>
    <span>Break <strong>${entry.breakTime}</strong></span>
  `;

  main.appendChild(headerRow);
  main.appendChild(timesRow);
  open.appendChild(main);

  const side = document.createElement('div');
  side.className = 'entry-side';
  const netBadge = document.createElement('div');
  netBadge.className = 'entry-net-badge';
  netBadge.innerHTML = `
    <span class="entry-net-time">${netTime}</span>
    <span class="badge ${isPos ? 'positive' : 'negative'}">${overtimeStr}</span>
  `;
  side.appendChild(netBadge);
  open.appendChild(side);

  row.appendChild(open);
  return row;
}

export function setupDashboardEvents() {
  $('btn-mark-all')?.addEventListener('click', () => {
    const pendingIds = store.entries.filter((e) => !e.transferred).map((e) => e.id);
    if (!pendingIds.length) return;
    pendingIds.forEach((id) => store.setTransferred(id, true));
    renderShiftFeed();
    toast('All marked transferred', 'Undo', () => {
      pendingIds.forEach((id) => store.setTransferred(id, false));
      renderShiftFeed();
    });
  });
}

// --- Settings Pane ---

export function initSettings() {
  fields.settingTarget?.set(store.settings.dailyTargetTime || '08:00');
  const reminderToggle = $('setting-reminder-enabled');
  if (reminderToggle) reminderToggle.checked = !!store.settings.reminderEnabled;
  fields.reminderLead?.set(store.minutesToTime(parseInt(store.settings.reminderLeadMinutes, 10) || 0));
  syncReminder();
  fields.settingStart?.set(store.settings.defaultStartTime || '08:30');
  fields.settingEnd?.set(store.settings.defaultEndTime || '17:00');
  fields.settingBreak?.set(store.settings.defaultBreakTime || '00:30');

  renderWorkingDays();
  renderThemeSegments();
  applyAccent(store.settings.themeAccent || '#3b82f6');
}

export function setupSettingsEvents() {
  fields.settingTarget = attachTimeField($('stepper-setting-target'), {
    mode: 'duration',
    onCommit: (value) => {
      store.updateSetting('dailyTargetTime', value);
      updateTodayPreview();
      updateSheetPreview();
      renderOverhours();
      renderShiftFeed();
      toast('Target updated');
    }
  });

  fields.settingStart = attachTimeField($('stepper-setting-start'), {
    mode: 'clock',
    onCommit: (value) => store.updateSetting('defaultStartTime', value)
  });
  fields.settingEnd = attachTimeField($('stepper-setting-end'), {
    mode: 'clock',
    onCommit: (value) => store.updateSetting('defaultEndTime', value)
  });
  fields.settingBreak = attachTimeField($('stepper-setting-break'), {
    mode: 'duration',
    onCommit: (value) => store.updateSetting('defaultBreakTime', value)
  });

  // --- End-of-day reminder ---
  fields.reminderLead = attachTimeField($('stepper-reminder-lead'), {
    mode: 'duration',
    step: 5,
    max: 4 * 60,
    onCommit: (value) => {
      store.updateSetting('reminderLeadMinutes', store.timeToMinutes(value));
      syncReminder();
    }
  });

  const reminderToggle = $('setting-reminder-enabled');
  reminderToggle?.addEventListener('change', () => {
    store.updateSetting('reminderEnabled', reminderToggle.checked);
    if (reminderToggle.checked && !bridge.hasNotificationPermission()) {
      // Android answers asynchronously via the app2:notifperm event.
      bridge.requestNotificationPermission();
    }
    syncReminder();
    toast(reminderToggle.checked ? 'Reminder on' : 'Reminder off');
  });

  $('btn-reminder-perm')?.addEventListener('click', () => {
    bridge.requestNotificationPermission();
    syncReminder();
  });

  $('btn-reminder-perm-settings')?.addEventListener('click', () => {
    bridge.openNotificationSettings();
  });

  $('btn-reminder-alarm-perm')?.addEventListener('click', () => bridge.requestExactNotificationPermission());

  addEventListener('app2:alarmperm', () => syncReminder());

  addEventListener('app2:notifperm', (e) => {
    if (!e.detail?.granted) {
      notifPromptRefused = true;
      if (store.settings.reminderEnabled) toast('Notifications not allowed');
    } else {
      notifPromptRefused = false;
    }
    syncReminder();
  });

  // Coming back from the system settings screen must not leave a stale "blocked" row.
  const recheckPermission = () => {
    if (bridge.hasNotificationPermission()) notifPromptRefused = false;
    syncReminder();
  };
  addEventListener('focus', recheckPermission);
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') recheckPermission();
  });

  document.querySelectorAll('#working-days .segment').forEach((btn) => {
    btn.onclick = () => {
      const day = parseInt(btn.dataset.day, 10);
      const current = store.normalizeWorkingDays(store.settings.workingDays);
      const next = current.includes(day) ? current.filter((d) => d !== day) : [...current, day];
      store.updateSetting('workingDays', store.normalizeWorkingDays(next));
      renderWorkingDays();
      // Every badge can flip sign, so the feed has to be rebuilt, not just the total.
      updateTodayPreview();
      updateSheetPreview();
      renderOverhours();
      renderShiftFeed();
      toast('Working days updated');
    };
  });

  document.querySelectorAll('#theme-segmented .segment').forEach((seg) => {
    seg.onclick = () => {
      store.updateSetting('theme', seg.dataset.theme);
      applyTheme(seg.dataset.theme);
      renderThemeSegments();
    };
  });

  initSwatches();

  $('btn-delete-all')?.addEventListener('click', () => {
    const confirmed = window.confirm('Delete all logged shifts? This cannot be undone.');
    if (!confirmed) return;
    store.clearAllEntries();
    syncReminder();
    initTodayEditor();
    renderDashboard();
    toast('All entries deleted');
  });
}

// --- Theme ---

export function applyTheme(mode) {
  const root = document.documentElement;
  if (mode === 'light' || mode === 'dark') root.dataset.theme = mode;
  else delete root.dataset.theme;
}

function renderThemeSegments() {
  const current = store.settings.theme || 'auto';
  document.querySelectorAll('#theme-segmented .segment').forEach((seg) => {
    const isActive = seg.dataset.theme === current;
    seg.classList.toggle('active', isActive);
    seg.setAttribute('aria-checked', String(isActive));
  });
}

export function renderWorkingDays() {
  const days = store.normalizeWorkingDays(store.settings.workingDays);
  document.querySelectorAll('#working-days .segment').forEach((btn) => {
    const isWorking = days.includes(parseInt(btn.dataset.day, 10));
    btn.classList.toggle('active', isWorking);
    btn.setAttribute('aria-pressed', String(isWorking));
  });
}

export function applyAccent(hex) {
  document.documentElement.style.setProperty('--accent', hex);
  document.querySelectorAll('.swatch').forEach((el) => {
    el.classList.toggle('selected', el.dataset.hex === hex);
  });
}

export function initSwatches() {
  const wrap = $('swatches');
  if (!wrap) return;
  wrap.textContent = '';
  ACCENTS.forEach((hex) => {
    const swatch = document.createElement('button');
    swatch.type = 'button';
    swatch.className = 'swatch';
    swatch.style.background = hex;
    swatch.dataset.hex = hex;
    swatch.setAttribute('aria-label', `Accent ${hex}`);
    swatch.onclick = () => {
      store.updateSetting('themeAccent', hex);
      applyAccent(hex);
    };
    wrap.appendChild(swatch);
  });
  applyAccent(store.settings.themeAccent || '#3b82f6');
}

// Aliases for compatibility
export const renderAll = () => {
  initTodayEditor();
  renderDashboard();
  initSettings();
};
export const renderExcelTransfer = renderDashboard;
export const renderTrackerWeek = initTodayEditor;
export const renderSettings = initSettings;
