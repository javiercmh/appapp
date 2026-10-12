// bridge.js — the ONLY file that talks to the Android host.
// Platform rules and the full API are in AGENTS.md in this workspace.
//
// window.AndroidStorage, window.AndroidMemory and window.AndroidNotification are all the same
// native object. Browser previews use the local development server when present, otherwise localStorage.

const N = window.AndroidStorage || null;

export const isNative = !!N;

// Probe once. Once selected, a failed disk operation must never become a localStorage save.
function devRequest(action, params = {}, body) {
  const xhr = new XMLHttpRequest();
  const query = new URLSearchParams(params);
  xhr.open(body === undefined ? 'GET' : 'POST', `/__bridge/${action}?${query}`, false);
  if (body !== undefined) xhr.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
  xhr.send(body);
  if (xhr.status !== 200) throw new Error(`Development storage ${action} failed (${xhr.status}).`);
  return xhr.responseText;
}
let DEV = null;
if (!N && !window.AppAI) {
  try {
    const info = JSON.parse(devRequest('info'));
    if (info.server === 'app2-demo-dev') DEV = info;
  } catch { /* Ordinary browser previews keep their localStorage backend. */ }
}
if (DEV?.mockAi) {
  const indicator = document.createElement('div');
  indicator.textContent = 'Development mock AI · offline · no ChatGPT usage';
  indicator.setAttribute('role', 'status');
  indicator.style.cssText = 'padding:8px;text-align:center;background:#fef3c7;color:#713f12';
  document.body.prepend(indicator);
}
function devWrite(action, params, body = '') {
  try { return JSON.parse(devRequest(action, params, body)) === true; }
  catch (error) { console.error(error); return false; }
}


// --- Native AI requests (available only in supported App² WebViews) ---
const AI = window.AppAI || null;
let aiCounter = 0;
const aiPending = new Map();
const aiResults = new Map();
if (AI) {
  window.addEventListener('message', (event) => {
    let message;
    try { message = JSON.parse(event.data); } catch { return; }
    if (message?.event === 'connection') {
      window.dispatchEvent(new CustomEvent('app2:ai:connection', { detail: message.data }));
      return;
    }
    if (message?.event === 'status') {
      window.dispatchEvent(new CustomEvent('app2:ai:status', { detail: { id: message.id, ...message.data } }));
      return;
    }
    if (message?.event !== 'complete' && message?.event !== 'error') return;
    const request = aiResults.get(message.id);
    if (request) {
      request.done = true;
      request.value = message.data;
      request.error = message.event === 'error' ? message.data : null;
      request.resolve?.(request);
      request.notify?.(request);
    }
  });
}

function aiCall(action, extra = {}) {
  if (!AI && DEV?.mockAi) {
    return fetch('/__bridge/ai', {
      method: 'POST', headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ action, ...extra }),
    }).then(async (response) => {
      const value = await response.json();
      if (!response.ok) throw Object.assign(new Error(value.error?.message || 'Development mock failed.'), { code: value.error?.code || 'service' });
      return value;
    });
  }
  if (!AI) return Promise.reject(new Error('AI is unavailable in this browser.'));
  const id = `ai-${Date.now()}-${++aiCounter}`;
  return new Promise((resolve, reject) => {
    aiPending.set(id, { resolve, reject });
    AI.postMessage(JSON.stringify({ id, action, ...extra }));
    setTimeout(() => {
      const pending = aiPending.get(id);
      if (pending) { aiPending.delete(id); reject(new Error('AI request timed out.')); }
    }, 30000);
  }).then((response) => {
    if (response.type === 'error') {
      const error = new Error(response.data?.message || String(response.data));
      error.code = response.data?.code || 'service';
      throw error;
    }
    return response.data;
  });
}

if (AI) {
  AI.onmessage = (event) => {
    let response;
    try { response = JSON.parse(event.data); } catch { return; }
    const pending = aiPending.get(response.id);
    if (!pending) return;
    aiPending.delete(response.id);
    pending.resolve(response);
  };
}

export const ai = {
  // Channel presence only; discover connection separately via providers().
  available: !!AI || !!DEV?.mockAi,
  developmentMock: !AI && !!DEV?.mockAi,
  // -> {providers:[{id,name,connected}]} without account identifiers.
  providers: () => aiCall('providers'),
  // -> {models:[{id,name,reasoningLevels}]} in account-specific server order.
  models: (providerId = 'chatgpt') => aiCall('models', { providerId }),
  // -> request ID. Omit reasoningEffort for provider default; use advertised levels.
  start: async ({ providerId = 'chatgpt', model, instructions = '', input, reasoningEffort } = {}) => {
    const id = await aiCall('start', { data: { providerId, model, instructions, input, reasoningEffort } });
    const request = { id, done: false, value: null, error: null, listeners: new Set() };
    request.notify = (value) => request.listeners.forEach((listener) => listener(value));
    aiResults.set(id, request);
    if (!AI && DEV?.mockAi) window.dispatchEvent(new CustomEvent('app2:ai:status', { detail: { id, status: 'running' } }));
    return id;
  },
  // -> {id,done,value,error?}; native failures may reject with Error.code instead.
  // Poll persisted IDs here after recreation. Never serialize wrapper internals.
  result: async (id) => {
    const cached = aiResults.get(id);
    if (cached?.done) return cached;
    const value = await aiCall('result', { requestId: id });
    if (cached && value !== null) Object.assign(cached, { done: true, value });
    return cached || { id, done: value !== null, value };
  },
  // -> completed text. Only supports requests started in this page instance.
  wait: (id) => new Promise((resolve, reject) => {
    const request = aiResults.get(id);
    if (!request) { reject(new Error('Request not found.')); return; }
    if (request.done) { request.error ? reject(Object.assign(new Error(request.error.message), { code: request.error.code })) : resolve(request.value); return; }
    const poll = async () => {
      try {
        const result = await ai.result(id);
        if (result?.done) return result.error ? reject(Object.assign(new Error(result.error.message), { code: result.error.code })) : resolve(result.value);
        setTimeout(poll, 400);
      } catch (error) { reject(error); }
    };
    poll();
  }),
  // Cancels and releases the native request; subsequent result() may be not_found.
  cancel: async (id) => { const result = await aiCall('cancel', { requestId: id }); aiResults.delete(id); return result; },
  // Release terminal results only after durable save or handled failure. -> true
  acknowledge: async (id) => { const result = await aiCall('acknowledge', { requestId: id }); aiResults.delete(id); return result; },
};

// --- Preferences (Android SharedPreferences) ---

export function savePref(key, value) {
  const text = typeof value === 'string' ? value : JSON.stringify(value);
  if (N) return N.saveState(key, text);
  if (DEV) return devWrite('saveState', { key }, text);
  localStorage.setItem(key, text);
  return true;
}

// Note: the native loadState has no optional-argument support — both args are always required.
export function loadPref(key, fallback = '') {
  if (N) return N.loadState(key, fallback);
  if (DEV) return devRequest('loadState', { key, fallback });
  const value = localStorage.getItem(key);
  return value === null ? fallback : value;
}

export function removePref(key) {
  if (N) return N.removeState(key);
  if (DEV) return devWrite('removeState', { key });
  localStorage.removeItem(key);
  return true;
}

// --- Files (workspace directory) ---

export function readFile(name) {
  if (N) return N.readFile(name);
  if (DEV) return devRequest('readFile', { name });
  return localStorage.getItem(`file:${name}`) || '';
}

export function writeFile(name, content) {
  const text = typeof content === 'string' ? content : JSON.stringify(content, null, 2);
  if (N) return N.writeFile(name, text);
  if (DEV) return devWrite('writeFile', { name }, text);
  localStorage.setItem(`file:${name}`, text);
  return true;
}

// Binary write. Accepts a bare base64 payload or a full `data:...;base64,...` URL.
export function writeFileBase64(name, base64) {
  if (N) return N.writeFileBase64(name, base64);
  if (DEV) return devWrite('writeFileBase64', { name }, base64);
  localStorage.setItem(`file:${name}`, base64);
  return true;
}

export function deleteFile(name) {
  if (N) return N.deleteFile(name);
  if (DEV) return devWrite('deleteFile', { name });
  localStorage.removeItem(`file:${name}`);
  return true;
}

export function listFiles() {
  if (DEV) return JSON.parse(devRequest('listFiles'));
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
  if (DEV) return JSON.parse(devRequest('storageStats'));
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

export const canScheduleWhileClosed = !!(N && typeof N.scheduleNotification === 'function');

export function canScheduleExactly() {
  return !!(N && typeof N.canScheduleExactNotifications === 'function' && N.canScheduleExactNotifications());
}

export function requestExactNotificationPermission() {
  return !!(N && typeof N.requestExactNotificationPermission === 'function' && N.requestExactNotificationPermission());
}

export function scheduleNotification(id, atMillis, title, message) {
  if (!canScheduleWhileClosed) return false;
  return N.scheduleNotification(id, String(Math.round(atMillis)), title, message);
}

export function cancelScheduledNotification(id) {
  if (!canScheduleWhileClosed) return false;
  return N.cancelScheduledNotification(id);
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

// Native review only: installing an update always requires a host confirmation.
const UPDATES = window.AppUpdates || null;
const updateRequests = new Map();
if (UPDATES) UPDATES.onmessage = event => {
  let result;
  try { result = JSON.parse(event.data); } catch { return; }
  const pending = updateRequests.get(result.id);
  if (!pending) return;
  clearTimeout(pending.timer);
  updateRequests.delete(result.id);
  pending.resolve(Boolean(result.opened));
};
export const updates = {
  available: Boolean(UPDATES),
  open() {
    if (!UPDATES) return Promise.reject(new Error('App updates require a supported App² host.'));
    const id = `update-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => { updateRequests.delete(id); reject(new Error('Update review did not open.')); }, 10000);
      updateRequests.set(id, { resolve, timer });
      try { UPDATES.postMessage(JSON.stringify({ id, action: 'open' })); }
      catch (error) { clearTimeout(timer); updateRequests.delete(id); reject(error); }
    });
  }
};

// Native speech; the language always comes from the subapp.
const SPEECH = window.AppSpeech || null;
const speechRequests = new Map();
if (SPEECH) SPEECH.onmessage = event => {
  let message;
  try { message = JSON.parse(event.data); } catch { return; }
  const pending = speechRequests.get(message.id);
  if (!pending) return;
  speechRequests.delete(message.id);
  clearTimeout(pending.timer);
  if (message.error) {
    const error = new Error(message.error.message);
    error.code = message.error.code;
    pending.reject(error);
  } else pending.resolve(message.value);
};
function speechCommand(action, options) {
  if (!SPEECH) return Promise.reject(Object.assign(new Error('Speech requires a supported App² host.'), {code: 'unavailable'}));
  const id = `speech-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return new Promise((resolve, reject) => {
    // Playback finishes through the native utterance callback, not a command timeout.
    const timer = action === 'speak' ? null : setTimeout(() => {
      speechRequests.delete(id);
      reject(Object.assign(new Error('Speech command timed out.'), {code: 'timeout'}));
    }, 15000);
    speechRequests.set(id, {resolve, reject, timer});
    try { SPEECH.postMessage(JSON.stringify({id, action, options})); }
    catch (error) { clearTimeout(timer); speechRequests.delete(id); reject(error); }
  });
}
export const speech = {
  available: Boolean(SPEECH),
  voices: () => speechCommand('voices'),
  speak: options => speechCommand('speak', options),
  stop: () => speechCommand('stop')
};

// Opens the device translation handler; no translated result is returned.
const TRANSLATION = window.AppTranslation || null;
const translationRequests = new Map();
if (TRANSLATION) TRANSLATION.onmessage = event => {
  let message;
  try { message = JSON.parse(event.data); } catch { return; }
  const pending = translationRequests.get(message.id);
  if (!pending) return;
  translationRequests.delete(message.id);
  clearTimeout(pending.timer);
  if (message.error) {
    const error = new Error(message.error.message);
    error.code = message.error.code;
    pending.reject(error);
  } else pending.resolve(message.value);
};
function translationCommand(action, options) {
  if (!TRANSLATION) return Promise.reject(Object.assign(new Error('Translation requires a supported App² host.'), {code: 'unavailable'}));
  const id = `translation-${Date.now()}-${Math.random().toString(36).slice(2)}`;
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => {
      translationRequests.delete(id);
      reject(Object.assign(new Error('Translation command timed out.'), {code: 'timeout'}));
    }, 15000);
    translationRequests.set(id, {resolve, reject, timer});
    try { TRANSLATION.postMessage(JSON.stringify({id, action, options})); }
    catch (error) { clearTimeout(timer); translationRequests.delete(id); reject(error); }
  });
}
export const translation = {
  availability: () => TRANSLATION ? translationCommand('availability') : Promise.resolve({available: false}),
  open: options => translationCommand('open', options)
};

// Hides Android's floating text-selection menu for this page load. Selection handles and
// selection events keep working, so the app must provide its own actions (Copy included).
export const selectionMenu = {
  available: Boolean(N && typeof N.setSelectionMenuSuppressed === 'function'),
  setSuppressed(suppressed) {
    try { return selectionMenu.available && N.setSelectionMenuSuppressed(Boolean(suppressed)) === true; }
    catch { return false; }
  }
};
