// store.js — Data persistence, overtime calculations, and settings for Working Hours.
// Talks to bridge.js; never touches the DOM. See AGENTS.md for platform rules.

import * as bridge from './bridge.js';

const ENTRIES_FILE = 'data/entries.json';
const SETTINGS_FILE = 'data/settings.json';

export const DEFAULT_SETTINGS = {
  defaultStartTime: '08:30',
  defaultEndTime: '17:00',
  defaultBreakTime: '00:30',
  dailyTargetTime: '08:00',
  initialOvertime: '+00:00',
  themeAccent: '#3b82f6',
  theme: 'auto',
  reminderEnabled: true,
  reminderLeadMinutes: 15
};

export let entries = [];
export let settings = { ...DEFAULT_SETTINGS };

// --- Date Helpers ---

/**
 * Formats a Date as a local "YYYY-MM-DD" string.
 * Never use toISOString() for this — it converts to UTC, so after midnight in
 * UTC+X the app would log to the previous day.
 */
export function localIso(d = new Date()) {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, '0');
  const day = String(d.getDate()).padStart(2, '0');
  return `${y}-${m}-${day}`;
}

// --- Time Parsing & Math (HH:MM) ---

/**
 * Parses "HH:MM" (or "±HH:MM", or bare integer minutes) into integer minutes.
 * Handles "+01:30" -> 90, "-02:15" -> -135, "08:00" -> 480.
 */
export function timeToMinutes(str) {
  if (!str) return 0;
  if (typeof str === 'number') return isNaN(str) ? 0 : str;
  const s = String(str).trim();
  const isNegative = s.startsWith('-');
  const clean = s.replace(/^[+-]/, '');
  const parts = clean.split(':');
  if (parts.length >= 2) {
    const h = parseInt(parts[0], 10) || 0;
    const m = parseInt(parts[1], 10) || 0;
    const total = h * 60 + m;
    return isNegative ? -total : total;
  }
  const rawNum = parseInt(clean, 10);
  if (!isNaN(rawNum)) {
    return isNegative ? -rawNum : rawNum;
  }
  return 0;
}

/**
 * Formats integer minutes into "HH:MM" or "±HH:MM".
 */
export function minutesToTime(totalMins, showSign = false) {
  const mins = Math.round(totalMins || 0);
  const isNegative = mins < 0;
  const abs = Math.abs(mins);
  const h = Math.floor(abs / 60);
  const m = abs % 60;
  const padH = String(h).padStart(2, '0');
  const padM = String(m).padStart(2, '0');
  const timeStr = `${padH}:${padM}`;

  if (showSign) {
    return isNegative ? `-${timeStr}` : `+${timeStr}`;
  }
  return timeStr;
}

/**
 * Calculates net worked minutes: (endTime - startTime) - breakTime.
 * Supports overnight shifts where endTime <= startTime, including an end of 00:00.
 */
export function calcNetMinutes(startTime, endTime, breakTime) {
  const start = timeToMinutes(startTime);
  let end = timeToMinutes(endTime);
  const brk = timeToMinutes(breakTime);

  if (end <= start && start !== 0) {
    end += 24 * 60; // Overnight shift
  }

  const gross = Math.max(0, end - start);
  return Math.max(0, gross - brk);
}

/**
 * Calculates net worked duration formatted as "HH:MM".
 */
/**
 * Returns true when the shift crosses midnight (end lands on the next day).
 */
export function isOvernight(startTime, endTime) {
  const start = timeToMinutes(startTime);
  const end = timeToMinutes(endTime);
  return end <= start && start !== 0;
}

export function calcNetDuration(startTime, endTime, breakTime) {
  return minutesToTime(calcNetMinutes(startTime, endTime, breakTime));
}

/**
 * Calculates daily overtime minutes relative to dailyTargetTime.
 */
export function calcDayOvertimeMinutes(entry, dailyTargetTime = null) {
  const target = timeToMinutes(dailyTargetTime || settings.dailyTargetTime || '08:00');
  const net = calcNetMinutes(entry.startTime, entry.endTime, entry.breakTime);
  return net - target;
}

/**
 * Calculates overall overtime balance: initial overtime carryover + sum of daily overtime.
 * Untracked days are completely ignored (not in entries, so 0 penalty).
 */
export function calcTotalOvertime() {
  const initialMins = timeToMinutes(settings.initialOvertime || '+00:00');
  const loggedOvertimeMins = entries.reduce((sum, entry) => {
    return sum + calcDayOvertimeMinutes(entry, settings.dailyTargetTime);
  }, 0);
  const totalMins = initialMins + loggedOvertimeMins;

  return {
    totalMins,
    totalTime: minutesToTime(totalMins, true),
    initialTime: minutesToTime(initialMins, true),
    loggedTime: minutesToTime(loggedOvertimeMins, true)
  };
}

/**
 * Epoch milliseconds at which to remind, `leadMinutes` before an entry's end time.
 * Returns null when the entry has no usable end time. Overnight shifts end the next day.
 */
export function reminderTimestamp(entry, leadMinutes = 15) {
  if (!entry || !entry.date || !entry.endTime) return null;
  const parts = String(entry.date).split('-');
  if (parts.length !== 3) return null;

  const at = new Date(
    parseInt(parts[0], 10),
    parseInt(parts[1], 10) - 1,
    parseInt(parts[2], 10),
    0, 0, 0, 0
  );
  const endMins = timeToMinutes(entry.endTime) + (isOvernight(entry.startTime, entry.endTime) ? 24 * 60 : 0);
  at.setMinutes(endMins - (parseInt(leadMinutes, 10) || 0));
  const stamp = at.getTime();
  return isNaN(stamp) ? null : stamp;
}

// --- Entries File Operations (data/entries.json) ---

export function loadEntries() {
  const raw = bridge.readFile(ENTRIES_FILE);
  if (!raw || !raw.trim()) {
    entries = [];
    return entries;
  }
  try {
    const parsed = JSON.parse(raw);
    entries = Array.isArray(parsed) ? parsed : [];
    // Normalize any legacy format entries
    entries.forEach((e) => {
      if (e.breakMinutes !== undefined && !e.breakTime) {
        e.breakTime = minutesToTime(e.breakMinutes);
      }
      if (e.transferred === undefined) {
        e.transferred = false;
      }
    });
    sortEntries();
  } catch (err) {
    console.error(`[store] ${ENTRIES_FILE} parse error, initializing empty:`, err);
    entries = [];
  }
  return entries;
}

export function saveEntries() {
  sortEntries();
  bridge.writeFile(ENTRIES_FILE, entries);
}

function sortEntries() {
  // Sort descending by date, then by startTime
  entries.sort((a, b) => (b.date || '').localeCompare(a.date || '') || (b.startTime || '').localeCompare(a.startTime || ''));
}

export function findEntryByDate(dateStr) {
  return entries.find((e) => e.date === dateStr) || null;
}

export function findEntryById(id) {
  return entries.find((e) => e.id === id) || null;
}

/**
 * Adds a new entry or updates an existing entry by id or date.
 * Preserves or sets the transferred status.
 */
export function addOrUpdateEntry({ id = null, date, startTime, endTime, breakTime, transferred = undefined }) {
  const d = date || localIso();
  let existingIdx = id !== null ? entries.findIndex((e) => e.id === id) : -1;
  if (existingIdx === -1 && d) {
    existingIdx = entries.findIndex((e) => e.date === d);
  }

  const existing = existingIdx !== -1 ? entries[existingIdx] : null;
  const isTransferred = transferred !== undefined ? transferred : (existing?.transferred || false);

  const entryData = {
    id: existing?.id || id || `wh_${Date.now()}_${Math.floor(Math.random() * 1000)}`,
    date: d,
    startTime: startTime || settings.defaultStartTime || '08:30',
    endTime: endTime || settings.defaultEndTime || '17:00',
    breakTime: breakTime || settings.defaultBreakTime || '00:30',
    transferred: isTransferred,
    createdAt: existing?.createdAt || Date.now()
  };

  if (existingIdx !== -1) {
    entries[existingIdx] = entryData;
  } else {
    entries.unshift(entryData);
  }

  saveEntries();
  return entryData;
}

export function setTransferred(id, value) {
  const entry = entries.find((e) => e.id === id);
  if (!entry) return null;
  entry.transferred = !!value;
  saveEntries();
  return entry;
}

export function toggleTransferred(id) {
  const entry = entries.find((e) => e.id === id);
  return entry ? setTransferred(id, !entry.transferred) : null;
}

export function clearAllEntries() {
  entries = [];
  saveEntries();
  return entries;
}

export function removeEntry(id) {
  const index = entries.findIndex((e) => e.id === id);
  if (index === -1) return null;
  const [removed] = entries.splice(index, 1);
  saveEntries();
  return { entry: removed, index };
}

export function restoreEntry(entry, index) {
  entries.splice(index, 0, entry);
  saveEntries();
}

// --- Settings File Operations (data/settings.json) ---

export function loadSettings() {
  const raw = bridge.readFile(SETTINGS_FILE);
  if (!raw || !raw.trim()) {
    settings = { ...DEFAULT_SETTINGS };
    saveSettings(settings);
    return settings;
  }
  try {
    const parsed = JSON.parse(raw);
    settings = { ...DEFAULT_SETTINGS, ...parsed };
  } catch (err) {
    console.error(`[store] ${SETTINGS_FILE} parse error, using defaults:`, err);
    settings = { ...DEFAULT_SETTINGS };
  }
  return settings;
}

export function saveSettings(newSettings) {
  settings = { ...settings, ...newSettings };
  bridge.writeFile(SETTINGS_FILE, settings);
  return settings;
}

export function updateSetting(key, value) {
  settings[key] = value;
  bridge.writeFile(SETTINGS_FILE, settings);
  return settings;
}

// Aliases for compatibility
export const load = () => {
  loadSettings();
  return loadEntries();
};
export const save = saveEntries;


