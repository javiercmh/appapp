// bridge.js — the ONLY file that talks to the Android host.
// Platform rules and the full API are in AGENTS.md in this workspace.
//
// window.AndroidStorage, window.AndroidMemory and window.AndroidNotification are all the same
// native object. When they are absent we are in a plain browser, so fall back to localStorage.

const N = window.AndroidStorage || null;

export const isNative = !!N;

// --- Preferences (Android SharedPreferences) ---

export function savePref(key, value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (N) return N.saveState(key, text);
  localStorage.setItem(key, text);
  return true;
}

// Note: the native loadState has no optional-argument support — both args are always required.
export function loadPref(key, fallback = '') {
  if (N) return N.loadState(key, fallback);
  const value = localStorage.getItem(key);
  return value === null ? fallback : value;
}

export function removePref(key) {
  if (N) return N.removeState(key);
  localStorage.removeItem(key);
  return true;
}

// --- Files (workspace directory) ---

export function readFile(name) {
  if (N) return N.readFile(name);
  return localStorage.getItem(`file:${name}`) || '';
}

export function writeFile(name, content) {
  const text = typeof content === 'string' ? content : JSON.stringify(content, null, 2);
  if (N) return N.writeFile(name, text);
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

export function notify(title, message) {
  if (N) return N.showNotification(title, message);
  if ('Notification' in window && Notification.permission === 'granted') {
    new Notification(title, { body: message });
    return true;
  }
  console.log(`[notify] ${title}: ${message}`);
  return false;
}
