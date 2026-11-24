#!/usr/bin/env node
'use strict';
// The issued data sources of the production Bind stage are authoritative state: a plan constant, a
// disclosed fact, the frozen request literal, a parsed input and a part publication each seed their
// declared value, so an operation constructed from one carries real data rather than a placeholder.
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);const context=vm.createContext({sources});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));const {entry,rt,checkpointSchema}=dealLoad(factories,'experience_entry.js');const session=createDealUiSession(entry,rt,null,()=>{throw Error('No effect expected');},checkpointSchema);`,context);
const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(session.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",context);
const initial=state();
assert.strictEqual(initial.f_parts_rate,5,'the plan constant is not seeded as its issued source value');
assert.strictEqual(initial.f_parts_currency,'EUR','the disclosed fact is not seeded as its issued source value');
assert.strictEqual(initial.f_parts_request,'Record the value of 5 in EUR','the frozen request literal is not seeded as its issued source value');
assert.strictEqual(initial.f_parts_pick,1,'the choice input is not seeded as its issued index');
assert.strictEqual(initial.f_parts_span_draft,'30','the numeric input is not seeded as its declared text draft');
// The countdown publication is derived from the seeded seconds source: 30 s of checked milliseconds.
assert.strictEqual(initial.f_parts_duration_focus,30000,'the countdown duration is not the checked conversion of its source');
assert.strictEqual(initial.f_parts_remaining_focus,30000,'the countdown publication is not derived from its issued duration');
assert.strictEqual(initial.f_parts_remaining_focus_ready,true,'the countdown publication is not ready');
assert.strictEqual(initial.f_parts_completed_focus,false,'the countdown starts completed');
assert.strictEqual(initial.f_parts_running_focus,false,'the countdown starts running');
console.log('issued data sources: plan constant, disclosed fact, request literal, parsed input and countdown publication seed their declared values');
