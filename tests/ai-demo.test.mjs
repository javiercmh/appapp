import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
const source = await readFile(new URL('../app/src/main/assets/starter_template/js/ai-demo.js', import.meta.url), 'utf8');
const { createAiDemoSession, AI_SAMPLE_PROMPT } = await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
const models = [{id:'second',name:'Second',reasoningLevels:['low','high']},{id:'first',name:'First',reasoningLevels:[]}];
function setup(overrides = {}, saved = {pending:null,latest:null}) {
  const calls=[]; let disk=structuredClone(saved); let failSave=false;
  const ai={available:true,providers:async()=>({providers:[{id:'chatgpt',connected:true}]}),models:async()=>({models}),
    start:async r=>{calls.push(['start',r]);return 'r1';},result:async id=>({id,done:true,value:'A subapp is a small app.'}),
    cancel:async id=>{calls.push(['cancel',id]);return true;},acknowledge:async id=>{calls.push(['ack',id]);return true;},...overrides};
  const session=createAiDemoSession({ai,load:()=>disk,save:value=>{calls.push(['save',structuredClone(value)]);if(failSave)throw Error('disk');disk=structuredClone(value);}});
  return {session,calls,disk:()=>disk,fail:value=>{failSave=value;}};
}
test('browser unavailable and disconnected do not discover models or generate',async()=>{
 const x=setup({available:false,providers:()=>{throw Error('unexpected');}});await x.session.refresh();assert.match(x.session.state.message,/browser previews/);await x.session.generate('second','');assert.equal(x.calls.length,0);
 const y=setup({providers:async()=>({providers:[{id:'chatgpt',connected:false}]}),models:()=>{throw Error('unexpected');}});await y.session.refresh();assert.match(y.session.state.message,/Settings/);
});
test('server order, provider default, durable save before ack and one latest result',async()=>{
 const x=setup();await x.session.refresh();assert.deepEqual(x.session.state.models,models);await x.session.generate('second','');
 const req=x.calls.find(c=>c[0]==='start')[1];assert.equal(req.input,AI_SAMPLE_PROMPT);assert.equal(Object.hasOwn(req,'reasoningEffort'),false);
 const ack=x.calls.findIndex(c=>c[0]==='ack');assert.equal(x.calls[ack-1][1].latest.id,'r1');assert.equal(x.disk().pending,null);assert.equal(x.disk().latest.text,'A subapp is a small app.');
});
test('explicit reasoning uses advertised capability; unsupported effort rejected',async()=>{
 const x=setup();await x.session.refresh();await x.session.generate('second','max');assert.equal(x.calls.length,0);await x.session.generate('second','high');assert.equal(x.calls[0][1].reasoningEffort,'high');
});
test('save failure retains response and retries without another inference',async()=>{
 let results=0; const y=setup({result:async id=>{results++;y.fail(true);return {id,done:true,value:'saved later'};}});await y.session.refresh();await y.session.generate('second','');assert.equal(y.session.state.retry,true);assert.equal(y.calls.filter(c=>c[0]==='ack').length,0);
 await y.session.generate('first','');y.fail(false);await y.session.retrySaving();assert.equal(y.disk().latest.text,'saved later');assert.equal(results,1);assert.equal(y.calls.filter(c=>c[0]==='start').length,1);assert.equal(y.calls.filter(c=>c[0]==='ack').length,1);
});
test('recreated page retrieves persisted ID; missing request is interrupted, never resubmitted',async()=>{
 const pending={id:'old',model:'second'};const x=setup({}, {pending,latest:null});await x.session.refresh();assert.equal(x.disk().latest.id,'old');assert.equal(x.calls.some(c=>c[0]==='start'),false);
 const err=Object.assign(Error('Request not found'),{code:'not_found'});const y=setup({result:async()=>{throw err;},acknowledge:async()=>{throw err;}},{pending,latest:null});await y.session.refresh();assert.equal(y.session.state.pending,null);assert.match(y.session.state.message,/interrupted/);
});
test('cached and rejected terminal failures are acknowledged',async()=>{
 for(const result of [async id=>({id,done:true,error:{code:'usage_limit',message:'Limit reached'}}),async()=>{throw Object.assign(Error('Limit reached'),{code:'usage_limit'});}]){
  const x=setup({result});await x.session.refresh();await x.session.generate('second','');assert.match(x.session.state.message,/Limit reached/);assert.equal(x.session.state.pending,null);assert.equal(x.calls.filter(c=>c[0]==='ack').length,1);
 }
});
test('pending blocks duplicate requests and cancellation ignores late completion',async()=>{
 let finish;const x=setup({result:()=>new Promise(resolve=>{finish=resolve;})});await x.session.refresh();const generation=x.session.generate('second','');await new Promise(resolve=>setImmediate(resolve));await x.session.generate('first','');assert.equal(x.calls.filter(c=>c[0]==='start').length,1);await x.session.cancel();finish({id:'r1',done:true,value:'late'});await generation;assert.equal(x.disk().latest,null);assert.match(x.session.state.message,/cancelled/);
});
test('saved completion recovers acknowledgement without polling or generation',async()=>{
 const x=setup({result:()=>{throw Error('unexpected');}},{pending:{id:'old'},latest:{id:'old',text:'already saved'}});await x.session.refresh();assert.equal(x.disk().pending,null);assert.equal(x.disk().latest.text,'already saved');assert.equal(x.calls[1][0],'ack');
});
test('UI preserves model order, defaults reasoning, locks pending controls and renders text safely',async()=>{
 const {initAiDemo}=await import(`data:text/javascript;base64,${Buffer.from(source).toString('base64')}`);
 const elements=new Map();const events=new Map();
 globalThis.Option=class{constructor(text,value){this.text=text;this.value=value;}};
 globalThis.document={hidden:false,getElementById(id){if(!elements.has(id))elements.set(id,{value:'',hidden:false,disabled:false,textContent:'',options:[],replaceChildren(...options){this.options=options;this.value=options[0]?.value||'';},add(option){this.options.push(option);}});return elements.get(id);},addEventListener:(name,fn)=>events.set(name,fn)};
 globalThis.addEventListener=(name,fn)=>events.set(name,fn);
 let finish;
 const session=initAiDemo({ai:{available:true,providers:async()=>({providers:[{id:'chatgpt',connected:true}]}),models:async()=>({models}),start:async()=> 'ui-id',result:()=>new Promise(resolve=>{finish=resolve;}),acknowledge:async()=>true}}, {loadAiDemo:()=>({}),saveAiDemo:()=>{}});
 await new Promise(resolve=>setImmediate(resolve));
 assert.deepEqual(elements.get('ai-model').options.map(o=>o.value),['second','first']);assert.equal(elements.get('ai-reasoning').value,'');assert.equal(elements.get('ai-reasoning-field').hidden,false);
 elements.get('ai-model').value='first';elements.get('ai-model').onchange();assert.equal(elements.get('ai-reasoning-field').hidden,true);
 const generation=elements.get('ai-generate').onclick();await new Promise(resolve=>setImmediate(resolve));assert.equal(elements.get('ai-model').disabled,true);assert.equal(elements.get('ai-generate').disabled,true);assert.equal(elements.get('ai-cancel').hidden,false);
 finish({id:'ui-id',done:true,value:'<script>plain text</script>'});await generation;assert.equal(elements.get('ai-response').textContent,'<script>plain text</script>');assert.equal(elements.get('ai-cancel').hidden,true);assert.equal(elements.get('ai-generate').disabled,false);
 delete globalThis.document;delete globalThis.Option;delete globalThis.addEventListener;
});
