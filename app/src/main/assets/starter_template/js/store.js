// store.js — everything that persists: notes, photos, preferences.
// Talks to bridge.js; never touches the DOM. See AGENTS.md for the platform rules.

import * as bridge from './bridge.js';

const FILE = 'data/entries.json';
const ACCENT_KEY = 'demo.accent';
const TIP_KEY = 'demo.tip.v3';

// Long edge in pixels. A raw camera photo is several megabytes; bridge calls are synchronous
// and block the JS thread, so always downscale before saving. See AGENTS.md §4.
const MAX_PHOTO_EDGE = 1280;

export let entries = [];

export function load() {
  const raw = bridge.readFile(FILE);
  if (!raw || !raw.trim()) {
    entries = [];
    return entries;
  }
  try {
    const parsed = JSON.parse(raw);
    entries = Array.isArray(parsed) ? parsed : [];
  } catch (err) {
    console.error(`[store] ${FILE} is not valid JSON, starting empty:`, err);
    entries = [];
  }
  return entries;
}

export function save() {
  bridge.writeFile(FILE, entries);
}

export function add({ text, photo = null }) {
  entries.unshift({
    id: Date.now(),
    text,
    photo,
    createdAt: Date.now()
  });
  save();
}

/** Removes an entry but leaves its photo on disk, so an undo can restore it intact. */
export function remove(id) {
  const index = entries.findIndex((e) => e.id === id);
  if (index === -1) return null;
  const [removed] = entries.splice(index, 1);
  save();
  return { entry: removed, index };
}

export function restore(entry, index) {
  entries.splice(index, 0, entry);
  save();
}

/** Called once the undo window closes — only then is the photo really gone. */
export function discardPhoto(entry) {
  if (entry && entry.photo) bridge.deleteFile(entry.photo);
}

/**
 * Reads a picked File, downscales it, and writes it into the workspace as a real JPEG.
 * Resolves with the filename, which can be used directly as an <img src>.
 */
export function savePhoto(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('Could not read that file'));
    reader.onload = () => {
      const img = new Image();
      img.onerror = () => reject(new Error('That file is not an image'));
      img.onload = () => {
        const scale = Math.min(1, MAX_PHOTO_EDGE / Math.max(img.width, img.height));
        const canvas = document.createElement('canvas');
        canvas.width = Math.round(img.width * scale);
        canvas.height = Math.round(img.height * scale);
        canvas.getContext('2d').drawImage(img, 0, 0, canvas.width, canvas.height);

        const name = `photo_${Date.now()}.jpg`;
        const dataUrl = canvas.toDataURL('image/jpeg', 0.82);
        if (bridge.writeFileBase64(name, dataUrl)) {
          resolve(name);
        } else {
          reject(new Error('That photo was too large to save'));
        }
      };
      img.src = reader.result;
    };
    reader.readAsDataURL(file);
  });
}

// --- Preferences ---

export function accent() {
  return bridge.loadPref(ACCENT_KEY, '#3b82f6');
}

export function setAccent(hex) {
  bridge.savePref(ACCENT_KEY, hex);
}

export function tipDismissed() {
  return bridge.loadPref(TIP_KEY, '') === '1';
}

export function dismissTip() {
  bridge.savePref(TIP_KEY, '1');
}

export function resetTip() {
  bridge.removePref(TIP_KEY);
}
