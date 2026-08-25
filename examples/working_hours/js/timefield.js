// timefield.js — 24h HH:MM stepper control with ±15 min increments.
// Replaces <input type="time">, whose 12h/24h format follows the device locale
// and which offers no coarse stepping. See AGENTS.md for platform rules.
//
// Reuses the pure time math in store.js; never touches persistence itself.

import { timeToMinutes, minutesToTime } from './store.js';

const DAY_MINS = 24 * 60;
const HOLD_DELAY = 400;
const HOLD_INTERVAL = 120;
const HOLD_FAST_AFTER = 8;
const HOLD_FAST_INTERVAL = 55;

/**
 * Parses loose user input into minutes: "830" / "8:3" / "08:30" / "8" -> 510.
 * Returns null when nothing sensible can be read.
 */
export function parseLooseTime(text) {
  const raw = String(text ?? '').trim();
  if (!raw) return null;

  const cleaned = raw.replace(/[^\d:]/g, '');
  if (!cleaned) return null;

  let h;
  let m;
  if (cleaned.includes(':')) {
    const [hPart, mPart = '0'] = cleaned.split(':');
    h = parseInt(hPart, 10);
    m = parseInt(mPart.padEnd(2, '0').slice(0, 2), 10);
  } else if (cleaned.length <= 2) {
    h = parseInt(cleaned, 10);
    m = 0;
  } else {
    const digits = cleaned.slice(0, 4);
    h = parseInt(digits.slice(0, digits.length - 2), 10);
    m = parseInt(digits.slice(-2), 10);
  }

  if (isNaN(h) || isNaN(m)) return null;
  if (m > 59) m = 59;
  return h * 60 + m;
}

/**
 * Upgrades a `.stepper` container into a working time field.
 * Expects `.stepper-value` (input) and `.stepper-btn[data-delta]` children.
 *
 * mode 'clock'    -> wraps around midnight (23:45 + 15min = 00:00)
 * mode 'duration' -> clamps between 00:00 and `max` (breaks, daily target)
 */
export function attachTimeField(el, options = {}) {
  const {
    mode = el?.dataset.mode || 'clock',
    step = parseInt(el?.dataset.step, 10) || 15,
    max = 12 * 60,
    onCommit = null
  } = options;

  const input = el?.querySelector('.stepper-value');
  if (!el || !input) return null;

  let mins = normalize(timeToMinutes(input.value || '00:00'));
  input.value = minutesToTime(mins);

  function normalize(value) {
    if (mode === 'duration') return Math.min(max, Math.max(0, value));
    return ((value % DAY_MINS) + DAY_MINS) % DAY_MINS;
  }

  function render() {
    input.value = minutesToTime(mins);
  }

  function commit() {
    render();
    if (onCommit) onCommit(input.value);
  }

  // Snap to the step grid first, then step. From 14:07: + -> 14:15, − -> 14:00.
  function bump(dir) {
    const offGrid = mins % step !== 0;
    if (offGrid) {
      mins = normalize(dir > 0 ? Math.ceil(mins / step) * step : Math.floor(mins / step) * step);
    } else {
      mins = normalize(mins + dir * step);
    }
    commit();
  }

  function readInput() {
    const parsed = parseLooseTime(input.value);
    if (parsed === null) {
      render(); // Unparseable — revert to the last good value.
      return;
    }
    mins = normalize(parsed);
    commit();
  }

  // --- Buttons: tap steps once, press-and-hold repeats (and accelerates). ---
  el.querySelectorAll('.stepper-btn').forEach((btn) => {
    const dir = parseInt(btn.dataset.delta, 10) >= 0 ? 1 : -1;
    let holdTimer = null;
    let repeatTimer = null;
    let ticks = 0;
    let viaPointer = false;

    const stopHold = () => {
      clearTimeout(holdTimer);
      clearInterval(repeatTimer);
      holdTimer = null;
      repeatTimer = null;
      ticks = 0;
    };

    btn.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      viaPointer = true;
      bump(dir);
      holdTimer = setTimeout(() => {
        repeatTimer = setInterval(() => {
          bump(dir);
          ticks += 1;
          if (ticks === HOLD_FAST_AFTER) {
            clearInterval(repeatTimer);
            repeatTimer = setInterval(() => bump(dir), HOLD_FAST_INTERVAL);
          }
        }, HOLD_INTERVAL);
      }, HOLD_DELAY);
    });

    ['pointerup', 'pointercancel', 'pointerleave'].forEach((evt) => {
      btn.addEventListener(evt, stopHold);
    });

    // Keyboard activation still fires click without any pointer event.
    btn.addEventListener('click', (e) => {
      e.preventDefault();
      if (viaPointer) {
        viaPointer = false;
        return;
      }
      bump(dir);
    });
  });

  // --- Direct typing stays available (numeric keypad, normalised on commit). ---
  input.addEventListener('focus', () => input.select());
  input.addEventListener('blur', readInput);
  input.addEventListener('keydown', (e) => {
    if (e.key === 'Enter') {
      e.preventDefault();
      input.blur();
    } else if (e.key === 'ArrowUp') {
      e.preventDefault();
      bump(1);
    } else if (e.key === 'ArrowDown') {
      e.preventDefault();
      bump(-1);
    }
  });

  return {
    get: () => minutesToTime(mins),
    getMinutes: () => mins,
    set: (value, { silent = true } = {}) => {
      mins = normalize(timeToMinutes(value));
      render();
      if (!silent && onCommit) onCommit(input.value);
    },
    element: el,
    input
  };
}
