import assert from 'node:assert/strict';
import fs from 'node:fs';
import vm from 'node:vm';
import test from 'node:test';
const source = fs.readFileSync('app/src/main/assets/starter_template/js/bridge.js', 'utf8').replace(/export /g, '') + '\nglobalThis.api = share;';
function setup(native = true) {
  const requests = [], timers = new Map();
  let timerId = 0;
  const channel = {postMessage: raw => requests.push(JSON.parse(raw))};
  const context = vm.createContext({window: {AndroidStorage: {}, ...(native ? {AppShare: channel} : {})},
    setTimeout: fn => {timers.set(++timerId, fn); return timerId;}, clearTimeout: id => timers.delete(id)});
  vm.runInContext(source, context);
  return {api: context.api, requests, timers, channel, reply: value => channel.onmessage({data: JSON.stringify(value)})};
}
test('absent share channel has explicit fallback', async () => {
  const {api, timers} = setup(false);
  assert.equal(api.available, false);
  assert.equal(JSON.stringify(await api.availability()), '{"available":false,"file":false}');
  await assert.rejects(api.text({text:'story'}), {code:'unavailable'});
  await assert.rejects(api.file({name:'story.json',mimeType:'application/json',content:'{}'}), {code:'unavailable'});
  assert.equal(timers.size, 0);
});
test('share replies correlate without claiming completion', async () => {
  const {api, requests, reply, channel, timers} = setup();
  assert.equal(api.available, true);
  const availability = api.availability();
  reply({id:requests[0].id, value:{available:true,file:true}});
  assert.equal(JSON.stringify(await availability), '{"available":true,"file":true}');
  const options = {title:'Story', text:'日本語'};
  const text = api.text(options);
  const fileOptions = {name:'story.json',mimeType:'application/json',content:'{"text":"日本語"}'};
  const file = api.file(fileOptions);
  assert.deepEqual(requests[1].options, options);
  assert.deepEqual(requests[2].options, fileOptions);
  assert.equal(requests[1].action, 'text'); assert.equal(requests[2].action, 'file');
  channel.onmessage({data:'malformed'}); reply({id:'unknown',value:{launched:true}});
  reply({id:requests[2].id,value:{launched:true}});
  reply({id:requests[1].id,value:{launched:true}});
  assert.equal(JSON.stringify(await text), '{"launched":true}');
  assert.equal(JSON.stringify(await file), '{"launched":true}');
  reply({id:requests[1].id,error:{code:'failed',message:'late'}});
  assert.equal(timers.size, 0);
});
test('host errors, timeout and posting failure release pending requests', async () => {
  const {api, requests, reply, timers, channel} = setup();
  for (const code of ['unavailable','invalid_arguments','too_large','busy','not_foreground','failed']) {
    const pending = api.text({text:'x'});
    reply({id:requests.at(-1).id,error:{code,message:'detail'}});
    await assert.rejects(pending, {code});
    assert.equal(timers.size, 0);
  }
  const pending = api.text({text:'x'});
  const timeout = [...timers.values()][0]; timers.clear(); timeout();
  await assert.rejects(pending, {code:'timeout'});
  reply({id:requests.at(-1).id,value:{launched:true}});
  channel.postMessage = () => {throw new Error('cannot post');};
  await assert.rejects(api.text({text:'x'}), {message:'cannot post'});
  assert.equal(timers.size, 0);
});
test('JSON serialization costs at most six units per host-encoded UTF-8 byte', () => {
  // The host encodes a lone surrogate as one replacement byte; the host message cap relies on this ratio.
  const hostBytes = unit => unit < 0x80 ? 1 : unit < 0x800 ? 2 : unit >= 0xd800 && unit <= 0xdfff ? 1 : 3;
  for (let unit = 0; unit <= 0xffff; unit++) {
    const units = JSON.stringify(String.fromCharCode(unit)).length - 2;
    assert.ok(units <= 6 * hostBytes(unit), `U+${unit.toString(16)}`);
  }
  assert.equal(JSON.stringify('\u{1F600}').length - 2, 2);
});
