// bridge.js — the ONLY file that talks to the Android host or local dev server.
// Platform rules and the full API are in AGENTS.md in this workspace.
//
// On Android, window.AndroidStorage talks directly to Kotlin (filesDir/workspace/).
// When testing on a laptop, it syncs real files with dev_server.py (or falls back to localStorage).

const N = (typeof window !== 'undefined' && window.AndroidStorage) ? window.AndroidStorage : null;

export const isNative = !!N;

// --- Preferences (Android SharedPreferences / Local Dev Prefs) ---

export function savePref(key, value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (N) return N.saveState(key, text);
  try {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/__bridge/saveState?key=${encodeURIComponent(key)}`, false);
    xhr.send(text);
    if (xhr.status === 200) return true;
  } catch (_e) {}
  localStorage.setItem(key, text);
  return true;
}

// Note: the native loadState has no optional-argument support — both args are always required.
export function loadPref(key, fallback = '') {
  if (N) return N.loadState(key, fallback);
  try {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', `/__bridge/loadState?key=${encodeURIComponent(key)}&fallback=${encodeURIComponent(fallback)}`, false);
    xhr.send();
    if (xhr.status === 200 && xhr.responseText !== '') return xhr.responseText;
  } catch (_e) {}
  const value = localStorage.getItem(key);
  return value === null ? fallback : value;
}

export function removePref(key) {
  if (N) return N.removeState(key);
  localStorage.removeItem(key);
  return true;
}

// --- Files (workspace directory / data/entries.json on disk) ---

export function readFile(name) {
  if (N) return N.readFile(name);
  try {
    const xhr = new XMLHttpRequest();
    xhr.open('GET', `/__bridge/readFile?name=${encodeURIComponent(name)}`, false);
    xhr.send();
    if (xhr.status === 200) return xhr.responseText;
  } catch (_e) {}
  return localStorage.getItem(`file:${name}`) || '';
}

export function writeFile(name, content) {
  const text = typeof content === 'string' ? content : JSON.stringify(content, null, 2);
  if (N) return N.writeFile(name, text);
  try {
    const xhr = new XMLHttpRequest();
    xhr.open('POST', `/__bridge/writeFile?name=${encodeURIComponent(name)}`, false);
    xhr.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
    xhr.send(text);
    if (xhr.status === 200) return true;
  } catch (_e) {}
  localStorage.setItem(`file:${name}`, text);
  return true;
}

// Binary write. Accepts a bare base64 payload or a full `data:...;base64,...` URL.
export function writeFileBase64(name, base64) {
  if (N) return N.writeFileBase64(name, base64);
  localStorage.setItem(`file:${name}`, base64);
  return true;
}

export function deleteFile(name) {
  if (N) return N.deleteFile(name);
  localStorage.removeItem(`file:${name}`);
  return true;
}

export function listFiles() {
  if (N) {
    try {
      return JSON.parse(N.listFiles());
    } catch (err) {
      console.error('[bridge] Could not parse listFiles():', err);
      return [];
    }
  }
  return Object.keys(localStorage)
    .filter((k) => k.startsWith('file:'))
    .map((k) => ({ name: k.slice(5), size: (localStorage.getItem(k) || '').length, lastModified: Date.now() }));
}

export function storageStats() {
  if (N) {
    try {
      return JSON.parse(N.getStorageStats());
    } catch (err) {
      console.error('[bridge] Could not parse getStorageStats():', err);
    }
  }
  return { usableSpaceBytes: 0, totalSpaceBytes: 0, appFilesCount: 0, appFilesBytes: 0 };
}

// --- Notifications ---

export function hasNotificationPermission() {
  if (N) return N.hasNotificationPermission();
  return 'Notification' in window && Notification.permission === 'granted';
}

// Fire-and-forget: the Android dialog is async, so this returns before the user answers.
// Listen for the `app2:notifperm` event on window for the real outcome.
export function requestNotificationPermission() {
  if (N) return N.requestNotificationPermission();
  if ('Notification' in window) {
    Notification.requestPermission().then((result) => {
      window.dispatchEvent(new CustomEvent('app2:notifperm', { detail: { granted: result === 'granted' } }));
    });
  }
  return false;
}

// --- Scheduled notifications ---
// The Android host arms an AlarmManager alarm, so the notification fires even when
// the app is closed. On the dev server / desktop browser there is no such facility,
// so we fall back to an in-page timer that only survives while the tab is open.

const timerFallbacks = new Map();

export const canScheduleWhileClosed = !!(N && typeof N.scheduleNotification === 'function');

/** True when the OS will fire the alarm at the exact minute rather than in a batch window. */
export function schedulesExactly() {
  if (N && typeof N.canScheduleExactNotifications === 'function') {
    return N.canScheduleExactNotifications();
  }
  return false;
}

export function requestExactNotificationPermission() {
  if (N && typeof N.requestExactNotificationPermission === 'function') {
    return N.requestExactNotificationPermission();
  }
  return false;
}

export function scheduleNotification(id, atMillis, title, message) {
  const at = Math.round(atMillis);
  cancelScheduledNotification(id);
  if (at <= Date.now()) return false;

  if (canScheduleWhileClosed) {
    // JS numbers cross the bridge as doubles; the host parses this back to a Long.
    return N.scheduleNotification(id, String(at), title, message);
  }

  const handle = setTimeout(() => {
    timerFallbacks.delete(id);
    notify(title, message);
  }, at - Date.now());
  timerFallbacks.set(id, handle);
  return true;
}

export function cancelScheduledNotification(id) {
  if (timerFallbacks.has(id)) {
    clearTimeout(timerFallbacks.get(id));
    timerFallbacks.delete(id);
  }
  if (N && typeof N.cancelScheduledNotification === 'function') {
    return N.cancelScheduledNotification(id);
  }
  return true;
}

/** True when the host can send the user straight to the OS notification screen. */
export const canOpenNotificationSettings = !!(N && typeof N.openNotificationSettings === 'function');

export function openNotificationSettings() {
  if (canOpenNotificationSettings) return N.openNotificationSettings();
  return false;
}

export function notify(title, message) {
  if (N) return N.showNotification(title, message);
  if ('Notification' in window && Notification.permission === 'granted') {
    new Notification(title, { body: message });
    return true;
  }
  console.log(`[notify] ${title}: ${message}`);
  return false;
}
