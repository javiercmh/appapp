// ui.js — DOM rendering and interaction. Goes through store.js; never calls the bridge directly.
// See AGENTS.md for the platform rules.

import * as store from './store.js';
import * as bridge from './bridge.js';

const $ = (id) => document.getElementById(id);

const ACCENTS = ['#3b82f6', '#10b981', '#f59e0b', '#ef4444', '#a855f7', '#ec4899'];

const PROMPTS = [
  'Add a search field at the top of the Demo tab that filters notes as I type.',
  'Group the saved notes by day with a date heading above each group.',
  'Add a Kotlin AlarmManager bridge to App² so notifications still fire when the app is closed.'
];

let pendingPhoto = null;   // filename of a photo saved but not yet attached to a note
let toastTimer = null;
let onToastExpire = null;
let notifCountdownTimer = null;

// --- Toast (replaces alert/confirm — see AGENTS.md §4) ---

export function toast(message, undoLabel = null, onUndo = null, onExpire = null) {
  runPendingExpiry();

  $('toast-text').textContent = message;
  const action = $('toast-action');
  action.hidden = !undoLabel;
  if (undoLabel) action.textContent = undoLabel;
  $('toast').hidden = false;

  onToastExpire = onExpire;
  action.onclick = () => {
    onToastExpire = null;
    clearTimeout(toastTimer);
    $('toast').hidden = true;
    if (onUndo) onUndo();
  };

  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    $('toast').hidden = true;
    runPendingExpiry();
  }, undoLabel ? 5000 : 2500);
}

/** Runs a queued expiry callback exactly once (e.g. deleting a photo after the undo window). */
function runPendingExpiry() {
  const pending = onToastExpire;
  onToastExpire = null;
  if (pending) pending();
}

// --- Feed ---

export function renderFeed() {
  const feed = $('feed');
  feed.textContent = '';
  const entries = store.entries;

  $('entry-count').textContent = `${entries.length} ${entries.length === 1 ? 'note' : 'notes'}`;

  if (!entries.length) {
    const empty = document.createElement('div');
    empty.className = 'empty';
    empty.innerHTML = '<span>✨</span>Nothing saved yet. Write something above &mdash; it is saved to a real file on this phone.';
    feed.appendChild(empty);
    return;
  }

  entries.forEach((entry) => feed.appendChild(entryRow(entry)));
}

function entryRow(entry) {
  const row = document.createElement('div');
  row.className = 'entry';

  if (entry.photo) {
    const img = document.createElement('img');
    img.className = 'entry-photo';
    img.src = entry.photo;   // served straight from the workspace
    img.alt = '';
    row.appendChild(img);
  }

  const body = document.createElement('div');
  body.className = 'entry-body';

  const text = document.createElement('div');
  text.className = 'entry-text';
  text.textContent = entry.text;      // textContent, not innerHTML
  body.appendChild(text);

  const meta = document.createElement('div');
  meta.className = 'entry-meta';
  const when = document.createElement('span');
  when.textContent = relativeTime(entry.createdAt);
  meta.appendChild(when);
  body.appendChild(meta);
  row.appendChild(body);

  const del = document.createElement('button');
  del.className = 'entry-delete';
  del.textContent = '✕';
  del.setAttribute('aria-label', 'Delete note');
  del.onclick = () => deleteEntry(entry.id);
  row.appendChild(del);

  return row;
}

function deleteEntry(id) {
  const removed = store.remove(id);
  if (!removed) return;
  renderFeed();
  toast(
    'Note deleted',
    'Undo',
    () => { store.restore(removed.entry, removed.index); renderFeed(); },
    () => store.discardPhoto(removed.entry)   // photo survives until undo expires
  );
}

function relativeTime(ts) {
  const mins = Math.floor((Date.now() - ts) / 60000);
  if (mins < 1) return 'just now';
  if (mins < 60) return `${mins} min ago`;
  const hours = Math.floor(mins / 60);
  if (hours < 24) return `${hours} h ago`;
  return new Date(ts).toLocaleDateString();
}

// --- Composer ---

export function initComposer() {
  const input = $('note-input');
  const fileInput = $('photo-input');
  const photoBtn = $('btn-photo');

  input.addEventListener('input', () => {
    input.classList.remove('invalid');
    $('note-hint').textContent = '';
  });

  photoBtn.onclick = () => fileInput.click();

  fileInput.onchange = async () => {
    const file = fileInput.files && fileInput.files[0];
    fileInput.value = '';           // so picking the same file twice still fires
    if (!file) return;
    try {
      pendingPhoto = await store.savePhoto(file);
      $('photo-thumb').src = pendingPhoto;
      $('photo-preview').hidden = false;
      photoBtn.textContent = '📷 Change photo';
    } catch (err) {
      toast(err.message);
    }
  };

  $('photo-remove').onclick = () => clearPhoto();

  $('btn-save').onclick = () => {
    const text = input.value.trim();
    if (!text) {
      input.classList.add('invalid');
      $('note-hint').textContent = 'Write something first';
      input.focus();
      return;
    }
    store.add({ text, photo: pendingPhoto });
    input.value = '';
    clearPhoto();
    renderFeed();
  };
}

function clearPhoto() {
  pendingPhoto = null;
  $('photo-preview').hidden = true;
  $('photo-thumb').removeAttribute('src');
  $('btn-photo').textContent = '📷 Add photo';
}

// --- Style & Notifications tab ---

export function applyAccent(hex) {
  document.documentElement.style.setProperty('--accent', hex);
  document.querySelectorAll('.swatch').forEach((el) => {
    el.classList.toggle('selected', el.dataset.hex === hex);
  });
}

export function initSwatches() {
  const wrap = $('swatches');
  ACCENTS.forEach((hex) => {
    const swatch = document.createElement('button');
    swatch.className = 'swatch';
    swatch.style.background = hex;
    swatch.dataset.hex = hex;
    swatch.setAttribute('aria-label', `Accent ${hex}`);
    swatch.onclick = () => { store.setAccent(hex); applyAccent(hex); };
    wrap.appendChild(swatch);
  });
  applyAccent(store.accent());
}

export function initNotificationPlayground() {
  const statusBox = $('notif-status-box');
  const statusText = $('notif-status-text');
  const cancelBtn = $('btn-notif-cancel');

  function clearScheduled() {
    if (notifCountdownTimer) {
      clearInterval(notifCountdownTimer);
      notifCountdownTimer = null;
    }
    if (statusBox) statusBox.hidden = true;
  }

  function scheduleTestNotification(seconds, label) {
    if (!bridge.hasNotificationPermission()) {
      toast('Turn on notifications above first');
      return;
    }
    clearScheduled();
    let remaining = seconds;
    if (statusText) statusText.textContent = `⏰ Scheduled in ${remaining}s...`;
    if (statusBox) statusBox.hidden = false;
    toast(`Notification scheduled (${label})`);

    notifCountdownTimer = setInterval(() => {
      remaining--;
      if (remaining > 0) {
        if (statusText) statusText.textContent = `⏰ Scheduled in ${remaining}s...`;
      } else {
        clearScheduled();
        bridge.notify('App² Demo', `⏰ Here is your ${label} scheduled notification!`);
        toast('Notification delivered');
      }
    }, 1000);
  }

  const sampleBtn = $('btn-notif-sample');
  if (sampleBtn) {
    sampleBtn.onclick = () => {
      if (!bridge.hasNotificationPermission()) {
        toast('Turn on notifications above first');
        return;
      }
      bridge.notify('App² Demo', '✨ Sample notification from the native Android bridge!');
      toast('Sample notification sent');
    };
  }

  const btn10s = $('btn-notif-10s');
  if (btn10s) btn10s.onclick = () => scheduleTestNotification(10, '10-second');

  const btn1m = $('btn-notif-1m');
  if (btn1m) btn1m.onclick = () => scheduleTestNotification(60, '1-minute');

  if (cancelBtn) {
    cancelBtn.onclick = () => {
      clearScheduled();
      toast('Scheduled notification cancelled');
    };
  }
}

// --- Guide tab ---

const FILE_ROLES = {
  'index.html': { icon: '🌐', desc: 'Web markup & UI shell', group: 'code' },
  'style.css': { icon: '🎨', desc: 'Theme & safe-area styles', group: 'code' },
  'app.js': { icon: '⚡', desc: 'Entry point & bootstrap', group: 'code' },
  'ui.js': { icon: '🖌️', desc: 'DOM rendering & interactions', group: 'code' },
  'store.js': { icon: '💾', desc: 'Data persistence & preferences', group: 'code' },
  'bridge.js': { icon: '🔌', desc: 'Native Android bridge wrapper', group: 'code' },
  'manifest.json': { icon: '📦', desc: 'App metadata & settings', group: 'config' },
  'AGENTS.md': { icon: '🤖', desc: 'AI developer instructions', group: 'config' },
  'entries.json': { icon: '📝', desc: 'User saved notes database', group: 'data' },
  'app.log': { icon: '📜', desc: 'Runtime console logs', group: 'data' }
};

function getFileInfo(name) {
  if (FILE_ROLES[name]) return FILE_ROLES[name];
  const lower = name.toLowerCase();
  if (lower.endsWith('.html') || lower.endsWith('.htm')) return { icon: '🌐', desc: 'HTML document', group: 'code' };
  if (lower.endsWith('.css')) return { icon: '🎨', desc: 'Stylesheet', group: 'code' };
  if (lower.endsWith('.js') || lower.endsWith('.mjs')) return { icon: '⚡', desc: 'JavaScript module', group: 'code' };
  if (lower.endsWith('.json')) return { icon: '📄', desc: 'JSON data file', group: 'data' };
  if (lower.endsWith('.md') || lower.endsWith('.txt')) return { icon: '📝', desc: 'Documentation text', group: 'config' };
  if (/\.(png|jpe?g|gif|webp|svg|ico)$/i.test(name)) return { icon: '🖼️', desc: 'Image asset', group: 'data' };
  return { icon: '📄', desc: 'Project file', group: 'code' };
}

const FILE_GROUPS = [
  { id: 'code', title: '⚡ Core Code', badge: 'Source' },
  { id: 'config', title: '⚙️ App Config & Docs', badge: 'Config' },
  { id: 'data', title: '💾 Data & Media', badge: 'Runtime' }
];

export function renderFiles() {
  const container = $('file-list');
  container.textContent = '';
  const rawFiles = bridge.listFiles();

  const grouped = { code: [], config: [], data: [] };
  rawFiles.forEach((file) => {
    const info = getFileInfo(file.name);
    if (!grouped[info.group]) grouped[info.group] = [];
    grouped[info.group].push({ ...file, ...info });
  });

  FILE_GROUPS.forEach((grp) => {
    const items = grouped[grp.id] || [];
    if (!items.length) return;

    items.sort((a, b) => a.name.localeCompare(b.name));

    const groupEl = document.createElement('div');
    groupEl.className = 'file-group';

    const header = document.createElement('div');
    header.className = 'file-group-header';
    header.innerHTML = `<span>${grp.title}</span><span class="badge">${items.length} ${items.length === 1 ? 'file' : 'files'}</span>`;
    groupEl.appendChild(header);

    const list = document.createElement('div');
    list.className = 'file-list';

    items.forEach((file) => {
      const row = document.createElement('div');
      row.className = 'file-row';

      const main = document.createElement('div');
      main.className = 'file-row-main';

      const nameEl = document.createElement('span');
      nameEl.className = 'file-row-name';
      nameEl.textContent = `${file.icon} ${file.name}`;

      const descEl = document.createElement('span');
      descEl.className = 'file-row-desc';
      descEl.textContent = file.desc;

      main.append(nameEl, descEl);

      const sizeEl = document.createElement('span');
      sizeEl.className = 'file-row-size';
      sizeEl.textContent = formatBytes(file.size);

      row.append(main, sizeEl);
      list.appendChild(row);
    });

    groupEl.appendChild(list);
    container.appendChild(groupEl);
  });

  const stats = bridge.storageStats();
  $('storage-line').textContent =
    `This app uses ${formatBytes(stats.appFilesBytes)} across ${stats.appFilesCount} files · ${formatBytes(stats.usableSpaceBytes)} free on device`;
}

export function renderConsole() {
  const log = bridge.readFile('app.log');
  const lines = log.trim().split('\n').slice(-40);
  $('console-out').textContent = log.trim() ? lines.join('\n') : 'Nothing logged yet.';
  $('console-out').scrollTop = $('console-out').scrollHeight;
}

export function initGuide() {
  $('btn-log-refresh').onclick = () => renderConsole();
  $('btn-log-clear').onclick = () => { bridge.writeFile('app.log', ''); renderConsole(); };

  const wrap = $('prompts');
  PROMPTS.forEach((text) => {
    const btn = document.createElement('button');
    btn.className = 'prompt';
    btn.textContent = text;
    btn.onclick = () => copy(text);
    wrap.appendChild(btn);
  });
}

function copy(text) {
  if (navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(text).then(() => toast('Prompt copied'), () => fallbackCopy(text));
  } else {
    fallbackCopy(text);
  }
}

function fallbackCopy(text) {
  const area = document.createElement('textarea');
  area.value = text;
  area.style.position = 'fixed';
  area.style.opacity = '0';
  document.body.appendChild(area);
  area.select();
  try {
    document.execCommand('copy');
    toast('Prompt copied');
  } catch (err) {
    toast('Could not copy — select it manually');
  }
  document.body.removeChild(area);
}

function formatBytes(bytes) {
  if (!bytes) return '0 B';
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  if (bytes < 1024 * 1024 * 1024) return `${(bytes / 1048576).toFixed(1)} MB`;
  return `${(bytes / 1073741824).toFixed(1)} GB`;
}

// --- Tip banner ---

export function showTip() { $('tip').hidden = false; }

export function initTip() {
  const dismiss = () => { store.dismissTip(); $('tip').hidden = true; };
  $('tip-dismiss').onclick = dismiss;
  const closeBtn = $('tip-close');
  if (closeBtn) closeBtn.onclick = dismiss;
  $('btn-show-tip').onclick = () => { store.resetTip(); showTip(); };
  if (!store.tipDismissed()) showTip();
}
