// app.js — entry point. Boots the app, handles tab navigation and notifications.
// Platform rules and the bridge API are in AGENTS.md in this workspace.
//
// Loaded as <script type="module">, which defers execution — DOMContentLoaded has usually already
// fired by now, so setup runs at top level rather than inside a listener. See AGENTS.md §1.

import * as store from './store.js';
import * as bridge from './bridge.js';
import * as ui from './ui.js';

const $ = (id) => document.getElementById(id);

// --- Tab navigation ---

function initTabs() {
  document.querySelectorAll('.tab').forEach((tab) => {
    tab.onclick = () => {
      document.querySelectorAll('.tab').forEach((t) => t.classList.remove('active'));
      document.querySelectorAll('.pane').forEach((p) => p.classList.remove('active'));
      tab.classList.add('active');
      $(`pane-${tab.dataset.pane}`).classList.add('active');

      if (tab.dataset.pane === 'guide') { ui.renderFiles(); ui.renderConsole(); }
      if (tab.dataset.pane === 'demo') refreshNotifications();
      window.scrollTo(0, 0);
    };
  });
}

// The host window uses adjustResize, so the keyboard would otherwise shove the tab bar up.
function initKeyboardHandling() {
  addEventListener('focusin', (e) => {
    if (e.target.matches('input, textarea')) document.body.classList.add('kb');
  });
  addEventListener('focusout', () => document.body.classList.remove('kb'));
}

// --- Notification permission ---
// requestNotificationPermission() returns before the user answers, so never trust its return
// value. The host fires `app2:notifperm` with the real outcome; polling is only a fallback.

let permPoll = null;

function refreshNotifications() {
  const granted = bridge.hasNotificationPermission();
  const pill = $('notif-pill');
  const btnNotif = $('btn-notif');
  const controls = $('notif-controls');

  if (pill) {
    pill.textContent = granted ? 'Active' : 'Off';
  }
  if (btnNotif) {
    btnNotif.hidden = granted;
  }
  if (controls) {
    controls.hidden = !granted;
  }
  if (granted) {
    const hint = $('notif-hint');
    if (hint) hint.textContent = '';
    stopPolling();
  }
  return granted;
}

function stopPolling() {
  if (permPoll) { clearInterval(permPoll); permPoll = null; }
}

function initNotifications() {
  const btnNotif = $('btn-notif');
  if (btnNotif) {
    btnNotif.onclick = () => {
      bridge.requestNotificationPermission();
      const pill = $('notif-pill');
      if (pill) pill.textContent = 'Waiting for Android…';
      btnNotif.disabled = true;

      stopPolling();
      let waited = 0;
      permPoll = setInterval(() => {
        waited += 500;
        if (refreshNotifications()) {
          btnNotif.disabled = false;
        } else if (waited >= 30000) {
          stopPolling();
          btnNotif.disabled = false;
          const p = $('notif-pill');
          if (p) p.textContent = 'Off';
          const hint = $('notif-hint');
          if (hint) {
            hint.textContent =
              'Android did not grant it. If you dismissed the prompt twice, enable notifications in Settings → Apps → App².';
          }
        }
      }, 500);
    };
  }

  addEventListener('app2:notifperm', () => {
    const btn = $('btn-notif');
    if (btn) btn.disabled = false;
    refreshNotifications();
  });

  addEventListener('focus', refreshNotifications);
  addEventListener('pageshow', refreshNotifications);
  refreshNotifications();
}

// --- Boot ---

store.load();
initTabs();
initKeyboardHandling();
ui.initComposer();
ui.initSwatches();
ui.initNotificationPlayground();
ui.initGuide();
ui.initTip();
initNotifications();
ui.renderFeed();

console.log(`[Starter Demo] Ready — ${store.entries.length} notes, native bridge ${bridge.isNative ? 'connected' : 'unavailable (browser fallback)'}.`);
