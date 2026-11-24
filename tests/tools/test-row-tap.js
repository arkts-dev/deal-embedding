#!/usr/bin/env node
'use strict';
// Runtime items for the row_tap operation over the pair the composer emitted and the production
// compiler admitted: the operation carries a captured stable row identity, an unrelated row edit
// leaves its revision unchanged, and an edit or removal of the tapped row invalidates it. The same
// pair proves the entry field's issued decimals and unit on the prepared row and the stored total.
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);const context=vm.createContext({sources});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext('const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function("require","module","exports","process","console",source)]));globalThis.app=dealLoad(factories,"experience.js").entry;',context);
// Every call runs inside the sandbox: a DEAL function must be invoked with its own runtime bindings.
const evaluate=code=>vm.runInContext(code,context);
evaluate('globalThis.state=app.initialState.$f()');
const state=()=>evaluate('globalThis.state');
const step=(code)=>evaluate('globalThis.state='+code+';globalThis.state');
const capture=(key)=>step('app.update_f_parts_CaptureRow_tap.$f(globalThis.state,app.f_parts_CaptureRow_tap$new({key:"'+key+'"},1,1,1))');
const draft=(value)=>step('app.update_f_parts_Draft_spend_amount.$f(globalThis.state,app.f_parts_Draft_spend_amount$new({value:"'+value+'"},1,1,1))');
const add=()=>step('app.update_f_parts_Add_spend.$f(globalThis.state,app.f_parts_Add_spend$new({at:1},1,1,1))');
const remove=(key,at)=>step('app.update_f_parts_Remove_spend.$f(globalThis.state,app.f_parts_Remove_spend$new({key:"'+key+'",at:'+at+'},1,1,1))');
const undo=()=>step('app.update_f_parts_Undo_spend.$f(globalThis.state,app.f_parts_Undo_spend$new({at:1},1,1,1))');

let current=state();
assert.strictEqual(current.f_parts_records_spend.length,1,'the admitted seed creates one record');
assert.strictEqual(current.f_parts_records_spend[0].values.amount,4,'the stored amount stays numeric');
assert.strictEqual(current.f_parts_rows_spend[0].text,'Amount: 4.00\u20ac','the prepared row applies the issued decimals and unit: '+current.f_parts_rows_spend[0].text);
assert.strictEqual(current.f_parts_total_spend,4,'the total reads the stored numeric value');
assert.strictEqual(current.revision_tap,0,'the row tap revision starts at zero');
assert.strictEqual(current.f_parts_rowKey_tap,'','no row is captured before a tap');

current=capture('k1');
assert.strictEqual(current.f_parts_rowKey_tap,'k1','the tap captures the stable row key');
const captured=current.revision_tap;
assert(captured>0,'capturing a row does not advance the row tap revision');

current=draft('3.5');
current=add();
assert.strictEqual(current.f_parts_records_spend.length,2,'the unrelated row was added');
assert.strictEqual(current.f_parts_rows_spend[1].text,'Amount: 3.50\u20ac','the added row applies the issued decimals and unit: '+current.f_parts_rows_spend[1].text);
assert.strictEqual(current.f_parts_total_spend,7.5,'the total sums the stored numeric values');
assert.strictEqual(current.revision_tap,captured,'an unrelated row addition invalidated the row tap operation: '+current.revision_tap+' vs '+captured);

current=remove('k2',2);
assert.strictEqual(current.f_parts_records_spend.length,1,'the unrelated row was removed');
assert.strictEqual(current.revision_tap,captured,'removing an unrelated row invalidated the row tap operation');

current=remove('k1',1);
assert(current.revision_tap>captured,'removing the tapped row left the row tap operation current: '+current.revision_tap);
const removedRevision=current.revision_tap;
current=undo();
assert(current.revision_tap>removedRevision,'restoring the tapped row left the row tap operation current');
const guarded=capture('nope');
assert.strictEqual(guarded.f_parts_rowKey_tap,current.f_parts_rowKey_tap,'an unknown row key replaced the captured row identity');
console.log('row tap: captured stable row identity, row-scoped revision, unrelated-row immunity and issued entry decimals passed');
