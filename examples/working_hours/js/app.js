// app.js — entry point. Boots the Working Hours app and tab navigation.
// Platform rules and the bridge API are in AGENTS.md in this workspace.

import * as store from './store.js';
import * as bridge from './bridge.js';
import * as ui from './ui.js';

// --- Tab Navigation ---

export function switchTab(paneName) {
  ui.flushSave(); // Leaving a pane commits whatever was being edited.

  document.querySelectorAll('.tab').forEach((t) => t.classList.toggle('active', t.dataset.pane === paneName));
  document.querySelectorAll('.pane').forEach((p) => p.classList.toggle('active', p.id === `pane-${paneName}`));

  if (paneName === 'today') {
    ui.initTodayEditor();
  } else if (paneName === 'dashboard') {
    ui.renderDashboard();
  } else if (paneName === 'settings') {
    ui.initSettings();
  }
  window.scrollTo(0, 0);
}

function initTabs() {
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.onclick = () => switchTab(tab.dataset.pane);
  });
}

// Suppress tab bar when soft keyboard is open
function initKeyboardHandling() {
  addEventListener('focusin', (e) => {
    if (e.target.matches('input, textarea')) document.body.classList.add('kb');
  });
  addEventListener('focusout', () => document.body.classList.remove('kb'));
}

// Every way out of a field or out of the app commits pending edits to disk.
// writeFile is synchronous, so these flushes are durable immediately — which is
// what makes the app safe to background, lock, or kill without saving by hand.
function initAutosaveFlushes() {
  addEventListener('focusout', () => ui.flushSave());
  addEventListener('pagehide', () => ui.flushSave());
  addEventListener('blur', () => ui.flushSave());
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') ui.flushSave();
  });
}

// --- Boot ---

store.loadSettings();
ui.applyTheme(store.settings.theme || 'auto');
store.loadEntries();

initTabs();
initKeyboardHandling();
initAutosaveFlushes();

ui.setupTrackerEvents();
ui.setupDashboardEvents();
ui.setupSettingsEvents();

ui.initTodayEditor();
ui.initSettings();
ui.syncReminder(); // Re-arm today's reminder on every launch.

console.log(`[Working Hours] Ready — ${store.entries.length} shifts loaded, bridge: ${bridge.isNative ? 'native Android' : 'browser fallback'}.`);
