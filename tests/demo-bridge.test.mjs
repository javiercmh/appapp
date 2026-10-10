import { readFile } from 'node:fs/promises';
import vm from 'node:vm';
import test from 'node:test';
import assert from 'node:assert/strict';
const source = (await readFile(new URL('../app/src/main/assets/starter_template/js/bridge.js', import.meta.url), 'utf8'))
  .replace(/export /g, '') + '\n globalThis.bridge = {readFile, writeFile, writeFileBase64, deleteFile, savePref, loadPref, removePref, listFiles, storageStats, ai, isNative};';
function setup(info, native = null) {
  let failure = false;
  const storage = new Map();
  const calls = [];
  class XHR {
    open(method, url) { this.url = url; this.method = method; }
    setRequestHeader() {}
    send(body) {
      calls.push([this.method, this.url, body]);
      this.status = failure ? 500 : 200;
      const action = this.url.split('?')[0].split('/').pop();
      this.responseText = action === 'info' ? JSON.stringify(info) : action === 'readFile' ? 'disk contents' : action === 'loadState' ? '' : action === 'listFiles' ? '[]' : action === 'storageStats' ? '{}' : 'true';
    }
  }
  const context = vm.createContext({window: {AndroidStorage: native}, XMLHttpRequest: XHR, URLSearchParams,
    localStorage: {getItem: k => storage.get(k) ?? null, setItem: (k,v) => storage.set(k,v), removeItem: k => storage.delete(k)}, console: {error() {}}, setTimeout});
  vm.runInContext(source, context);
  return {bridge: context.bridge, storage, calls, fail: () => {failure = true;}};
}
test('ordinary preview uses localStorage and AI remains unavailable', () => {
  const {bridge, storage} = setup({});
  assert.equal(bridge.writeFile('data/test.json', 'browser'), true);
  assert.equal(bridge.readFile('data/test.json'), 'browser');
  assert.equal(storage.size, 1);
  assert.equal(bridge.ai.available, false);
});
test('dev storage covers files and preferences without fallback on failure', () => {
  const {bridge, storage, calls, fail} = setup({server:'app2-demo-dev', mockAi:false});
  assert.equal(bridge.readFile('data/test.json'), 'disk contents');
  for (const method of ['writeFile', 'writeFileBase64', 'savePref']) assert.equal(bridge[method]('a','b'), true);
  assert.equal(bridge.loadPref('a', 'fallback'), '');
  assert.equal(bridge.removePref('a'), true);
  assert.equal(bridge.deleteFile('a'), true);
  assert.equal(bridge.listFiles().length, 0);
  bridge.storageStats();
  assert.ok(calls.some(c => c[1].includes('writeFileBase64')));
  fail();
  assert.equal(bridge.writeFile('a', 'b'), false);
  assert.throws(() => bridge.readFile('a'), /failed/);
  assert.equal(storage.size, 0);
});
test('native storage takes precedence and is not probed', () => {
  const {bridge, calls} = setup({}, {readFile: () => 'native', writeFile: () => true});
  assert.equal(bridge.isNative, true);
  assert.equal(bridge.readFile('a'), 'native');
  assert.equal(bridge.writeFile('a','b'), true);
  assert.equal(calls.length, 0);
});
