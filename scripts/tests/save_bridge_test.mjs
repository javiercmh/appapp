import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
const source = fs.readFileSync('app/src/main/assets/starter_template/js/bridge.js', 'utf8').replace(/export /g, '') + '\nglobalThis.api = {save};';
function setup(native = true) {
  const sent = [], timers = [];
  const window = {AndroidStorage: {}};
  if (native) window.AppSave = {postMessage: raw => sent.push(JSON.parse(raw))};
  const context = vm.createContext({window, setTimeout: fn => {timers.push(fn); return timers.length;}, clearTimeout() {}});
  vm.runInContext(source, context);
  const reply = value => window.AppSave.onmessage({data: JSON.stringify(value)});
  return {api: context.api, sent, reply, timers, window};
}
test('absent save channel has an explicit fallback and no timers', async () => {
  const {api, timers} = setup(false);
  assert.equal(api.save.available, false);
  assert.equal(JSON.stringify(await api.save.availability()), '{"available":false,"file":false}');
  await assert.rejects(api.save.text({}), {code: 'unavailable'});
  await assert.rejects(api.save.file({}), {code: 'unavailable'});
  assert.equal(timers.length, 0);
});
test('save correlates replies and keeps cancellation distinct from success', async () => {
  const {api, sent, reply, timers} = setup();
  const saved = api.save.text({name: 'a', mimeType: 'text/html', content: '<p>x</p>'});
  const cancelled = api.save.file({path: 'data/a.bin'});
  const failed = api.save.file({path: 'data/b.bin'});
  assert.deepEqual(sent.map(m => m.action), ['text', 'file', 'file']);
  assert.deepEqual(sent[1].options, {path: 'data/a.bin'});
  reply({id: 'unknown', value: {saved: true}});
  reply({id: sent[1].id, value: {saved: false, cancelled: true}});
  reply({id: sent[2].id, error: {code: 'write_failed', message: 'no'}});
  reply({id: sent[0].id, value: {saved: true, name: 'a.html'}});
  assert.equal(JSON.stringify(await saved), '{"saved":true,"name":"a.html"}');
  assert.equal(JSON.stringify(await cancelled), '{"saved":false,"cancelled":true}');
  await assert.rejects(failed, {code: 'write_failed'});
  assert.equal(timers.length, 0, 'a user-paced save must never be failed by a timer');
});
test('malformed host messages and posting failures are contained', async () => {
  const {api, window, sent} = setup();
  window.AppSave.onmessage({data: 'not json'});
  window.AppSave.postMessage = () => {throw new Error('cannot post');};
  await assert.rejects(api.save.text({}), {message: 'cannot post'});
  assert.equal(sent.length, 0);
});
