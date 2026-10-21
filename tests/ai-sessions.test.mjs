import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';

const source = (await readFile(new URL('../app/src/main/assets/starter_template/js/bridge.js', import.meta.url), 'utf8'))
  .replace(/export /g, '') + '\n globalThis.bridge = {ai};';

/** A fake native AppAI channel: `handler(action, data)` returns {type, data} or throws to stay silent. */
function setup(handler) {
  const posted = [];
  const channel = { postMessage(raw) {
    const message = JSON.parse(raw); posted.push(message);
    const response = handler(message.action, message.data, message);
    if (response) queueMicrotask(() => channel.onmessage({ data: JSON.stringify({ id: message.id, ...response }) }));
  } };
  const context = vm.createContext({
    window: { AppAI: channel, addEventListener() {}, AndroidStorage: null },
    XMLHttpRequest: class { open() {} setRequestHeader() {} send() { this.status = 404; this.responseText = ''; } },
    URLSearchParams, localStorage: { getItem: () => null, setItem() {}, removeItem() {} },
    console: { error() {} }, setTimeout, clearTimeout, CustomEvent: class {}, Date, JSON,
  });
  vm.runInContext(source, context);
  return { ai: context.bridge.ai, posted };
}

const unsupported = () => ({ type: 'error', data: { code: 'invalid_request', message: 'Unsupported AI command.' } });

test('supported host: probe is sent once and its yes is cached', async () => {
  const { ai, posted } = setup((action) => action === 'capabilities' ? { type: 'result', data: { sessions: 1 } } : null);
  assert.equal(await ai.sessions.supported(), true);
  assert.equal(await ai.sessions.supported(), true);
  assert.equal(posted.filter((m) => m.action === 'capabilities').length, 1);
});

test('old host: only the exact unsupported response is cached, and no provider action is sent', async () => {
  const { ai, posted } = setup(() => unsupported());
  assert.equal(await ai.sessions.supported(), false);
  assert.equal(await ai.sessions.supported(), false);
  assert.deepEqual(posted.map((m) => m.action), ['capabilities']);
});

for (const [name, response] of [
  ['invalid_request with another message', { type: 'error', data: { code: 'invalid_request', message: 'Invalid request.' } }],
  ['a service error', { type: 'error', data: { code: 'service', message: 'Unsupported AI command.' } }],
  ['an authentication error', { type: 'error', data: { code: 'authentication', message: 'Sign in.' } }],
  ['a malformed result', { type: 'result', data: { sessions: 'yes' } }],
  ['an empty result', { type: 'result', data: null }],
  ['another version', { type: 'result', data: { sessions: 2 } }],
]) {
  test(`${name} rejects, is not cached and never means unsupported`, async () => {
    let answer = response;
    const { ai, posted } = setup(() => answer);
    await assert.rejects(ai.sessions.supported());
    assert.equal(posted.length, 1);
    answer = { type: 'result', data: { sessions: 1 } };
    assert.equal(await ai.sessions.supported(), true, 'a later confirmed answer is honored; the failure was not cached');
    assert.equal(posted.length, 2);
  });
}

test('begin and end carry only the documented fields; start passes the session id', async () => {
  const { ai, posted } = setup((action) => ({
    beginSession: { type: 'result', data: { sessionId: 's1' } }, endSession: { type: 'result', data: true },
    start: { type: 'result', data: 'req-1' },
  })[action] ?? null);
  assert.deepEqual({ ...(await ai.sessions.begin({ model: 'm', title: 'T', body: 'B' })) }, { sessionId: 's1' });
  assert.equal(await ai.sessions.end('s1'), true);
  await ai.start({ model: 'm', input: 'x', sessionId: 's1' });
  const [begin, end, start] = posted;
  assert.deepEqual({ ...begin.data }, { providerId: 'chatgpt', model: 'm', title: 'T', body: 'B' });
  assert.deepEqual({ ...end.data }, { sessionId: 's1' });
  assert.equal(start.data.sessionId, 's1');
});

test('start without a session sends no sessionId (legacy callers are unchanged)', async () => {
  const { ai, posted } = setup(() => ({ type: 'result', data: 'req-1' }));
  await ai.start({ model: 'm', input: 'x' });
  assert.equal('sessionId' in posted[0].data, false);
});
