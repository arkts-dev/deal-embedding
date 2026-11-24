#!/usr/bin/env node
'use strict';
// Runtime items for the entries composer part over the pair the composer emitted and the production
// compiler admitted: admitted seeds, the closed EntryRecord {key, timestamp, values}, typed drafts with
// located reasons, one timestamp per add, stable keys, key-addressed remove and undo at index, count
// and a total from the first numeric non-time field. Only disclosed seeds exist before the first add.
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);const context=vm.createContext({sources});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));globalThis.dealFactories=factories;const {entry,rt,checkpointSchema}=dealLoad(factories,'experience_entry.js');const session=createDealUiSession(entry,rt,null,()=>{throw Error('No effect expected');},checkpointSchema);`,context);
const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(session.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",context);
const tree=()=>JSON.parse(vm.runInContext('session.snapshot()',context)).tree;
function props(node){return Object.fromEntries(node.props.map(prop=>[prop.name,prop]));}
function find(node,match){const found=match(node);if(found!==undefined)return found;for(const child of node.children||[]){const nested=find(child,match);if(nested!==undefined)return nested;}return undefined;}
function all(node,match){const found=[];const visit=n=>{const hit=match(n);if(hit!==undefined)found.push(hit);for(const child of n.children||[])visit(child);};visit(node);return found;}
function dispatch(slot,payload){vm.runInContext(`session.dispatch(${slot},${JSON.stringify(payload)})`,context);}
function textField(label){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='TextField')return undefined;const all=props(node);if(all.label===undefined||all.label.stringValue!==label)return undefined;return {slot:all.onChange.actionSlot,value:all.value.stringValue,enabled:all.enabled?all.enabled.booleanValue:true};});assert(found!==undefined,'missing TextField: '+label);return found;}
function button(label){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='Button')return undefined;const all=props(node);if(all.text===undefined||all.text.stringValue!==label)return undefined;return {slot:all.onClick.actionSlot,enabled:all.enabled.booleanValue};});assert(found!==undefined,'missing Button: '+label);return found;}
function rows(){return all(tree(),node=>{if(node.component.split('.').pop()!=='ListItem')return undefined;const all=props(node);return {title:all.title.stringValue,value:all.eventValue.numberValue,slot:all.onTap.actionSlot};});}
function numberTexts(){return all(tree(),node=>{if(node.component.split('.').pop()!=='NumberText')return undefined;const all=props(node);return {value:all.value.numberValue,suffix:all.suffix?all.suffix.stringValue:'',prefix:all.prefix?all.prefix.stringValue:''};});}
function textValues(){return all(tree(),node=>{if(node.component.split('.').pop()!=='Text')return undefined;return props(node).text.stringValue;});}

// Initialization: one admitted seed becomes one record of the closed EntryRecord shape with its
// evidenced values and timestamp, and no `text` or flattened `v_` field exists.
let current=state();
assert.strictEqual(current.f_parts_records_spend.length,1,'the admitted seed creates one record');
assert.strictEqual(current.f_parts_records_spend[0].key,'k1','the seed gets a stable key');
assert.strictEqual(current.f_parts_records_spend[0].timestamp,1700000000000,'the disclosed timestamp is preserved');
assert.strictEqual(current.f_parts_records_spend[0].values.note,'coffee','the record carries its values record');
assert.strictEqual(current.f_parts_records_spend[0].values.amount,4);
assert.strictEqual(current.f_parts_records_spend[0].text,undefined,'the record carries no display text');
assert.strictEqual(current.f_parts_records_spend[0].v_note,undefined,'the record carries no flattened field');
assert.strictEqual(current.f_parts_nextKey_spend,2,'the counter starts after issued seed keys');
assert.strictEqual(current.f_parts_removed_spend,null,'no pending undo exists before a removal');
assert.strictEqual(current.f_parts_count_spend,1);
assert.strictEqual(current.f_parts_count_spend_ready,true);
assert.strictEqual(current.f_parts_total_spend,4,'the total sums the first numeric non-time field');
assert.strictEqual(current.f_parts_total_spend_ready,true);
assert.deepEqual(numberTexts().map(item=>item.value),[1,4]);
assert.strictEqual(rows().length,1);
assert.strictEqual(rows()[0].title,'Note: coffee; Amount: 4','the prepared row text is separate from the record');
assert.strictEqual(rows()[0].value,1,'the prepared row carries the issued numeric event value');
assert.strictEqual(button('Undo').enabled,false,'Undo is disabled with nothing removed');

// A typed draft becomes the record's typed value in its closed values record; a text draft is cleared
// and the number draft kept.
dispatch(textField('Note').slot,'tea');
dispatch(textField('Amount').slot,'3.5');
current=state();
assert.strictEqual(current.f_parts_draft_spend_note,'tea');
assert.strictEqual(current.f_parts_formValid_spend,true,'a valid decimal draft is accepted');
dispatch(button('Add').slot,'0');
current=state();
assert.strictEqual(current.f_parts_records_spend.length,2,'one tap adds one record');
assert.strictEqual(current.f_parts_nextKey_spend,3);
assert.strictEqual(current.f_parts_records_spend[1].key,'k2','the key is the next issued counter');
assert.strictEqual(current.f_parts_records_spend[1].values.note,'tea');
assert.strictEqual(current.f_parts_records_spend[1].values.amount,3.5);
assert(Number.isInteger(current.f_parts_records_spend[1].timestamp)&&current.f_parts_records_spend[1].timestamp>0,'the add captured one wall-clock timestamp');
assert.strictEqual(current.f_parts_draft_spend_note,'','text drafts clear after an add');
assert.strictEqual(current.f_parts_draft_spend_amount,'3.5','numeric drafts are preserved');
assert.strictEqual(current.f_parts_count_spend,2);
assert.strictEqual(current.f_parts_total_spend,7.5);

// An invalid draft stays visible, uncommitted and located: the field and its domain render beside it,
// the Add control is disabled, and a forced dispatch commits nothing.
dispatch(textField('Amount').slot,'abc');
current=state();
assert.strictEqual(current.f_parts_draft_spend_amount,'abc','an invalid draft stays visible');
assert.strictEqual(current.f_parts_formValid_spend,false);
assert.strictEqual(button('Add').enabled,false,'Add is disabled while a draft is invalid');
assert.strictEqual(current.f_parts_fieldValid_spend_amount,false,'the invalid field carries its own prepared validity');
assert.strictEqual(current.f_parts_fieldReason_spend_amount,'Amount: enter a finite decimal number','the invalid field publishes its own located reason');
assert(textValues().includes('Amount: enter a finite decimal number'),'the located reason renders beside the draft');
dispatch(button('Add').slot,'0');
current=state();
assert.strictEqual(current.f_parts_records_spend.length,2,'an invalid draft adds no record');
assert.strictEqual(current.f_parts_notice_spend,'Enter a valid value in every field before adding');

// A long fractional part is still a finite number: the checked parse must not turn it into NaN.
const longFraction='0.'+'1'.repeat(310);
dispatch(textField('Amount').slot,longFraction);
assert.strictEqual(state().f_parts_formValid_spend,true,'a finite long fractional draft is valid');

// A grammatically valid decimal that overflows binary64 is not a number: it stays visible and an add
// commits no record instead of entering Infinity into authoritative history.
const overflowing='1'+'0'.repeat(400);
dispatch(textField('Amount').slot,overflowing);
current=state();
assert.strictEqual(current.f_parts_draft_spend_amount,overflowing,'an overflowing numeric draft stays visible');
assert.strictEqual(current.f_parts_formValid_spend,false,'an overflowing numeric draft is not a valid value');
dispatch(button('Add').slot,'0');
assert.strictEqual(state().f_parts_records_spend.length,2,'an overflowing numeric draft adds no record');
assert.strictEqual(state().f_parts_notice_spend,'Enter a valid value in every field before adding');
dispatch(textField('Amount').slot,'3.5');
assert.strictEqual(state().f_parts_formValid_spend,true,'a finite decimal draft is valid again');

// Removal is addressed by the record key: the row binds its key and its numeric event value. An unknown
// key rejects with a located reason naming the key and changes no record.
const captured=rows()[1];
assert.strictEqual(captured.value,2,'the row carries the numeric identity issued with its key');
dispatch(captured.slot,String(captured.value));
current=state();
assert.strictEqual(current.f_parts_records_spend.length,1,'remove drops exactly one record');
assert.strictEqual(current.f_parts_records_spend[0].key,'k1','removal preserves surviving keys');
assert.strictEqual(current.f_parts_removed_spend.index,1,'the pending undo records the removed index');
assert.strictEqual(current.f_parts_removed_spend.record.key,'k2','the pending undo records the removed record');
assert.strictEqual(current.f_parts_count_spend,1);
assert.deepEqual(rows().map(row=>row.value),[1],'the prepared rows drop exactly the removed record');
assert.strictEqual(button('Undo').enabled,true);
// An unknown key rejects with a located reason naming the key and changes no record. The rendered
// row action of a removed row is no longer visible, so this item dispatches the issued nominal
// action against the same compiled owner reducer the view binds.
const unknown=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;const initial=app.initialState.$f();const action=app.f_parts_Remove_spend$new({key:'zzz',at:1},'t',1,1);const next=app.f_parts_remove_spend.$f(initial,action);return {notice:next.f_parts_notice_spend,count:next.f_parts_records_spend.length};})()`,context);
assert.strictEqual(unknown.notice,'Unknown record key: zzz','an unknown key rejects with a located reason');
assert.strictEqual(unknown.count,1,'an unknown key changes no record');
// A valid key with a finite but wrong numeric payload is not the issued row identity: the removal
// rejects with a located reason and changes no record and no pending undo.
const mismatched=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;const initial=app.initialState.$f();const action=app.f_parts_Remove_spend$new({key:'k1',at:9},'t',1,1);const next=app.f_parts_remove_spend.$f(initial,action);return {notice:next.f_parts_notice_spend,count:next.f_parts_records_spend.length,removed:next.f_parts_removed_spend};})()`,context);
assert(mismatched.notice.startsWith('Entry row identity does not match its prepared row'),'a mismatched row payload was admitted: '+mismatched.notice);
assert.strictEqual(mismatched.count,1,'a mismatched row payload changed a record');
assert.strictEqual(mismatched.removed,null,'a mismatched row payload recorded a pending undo');
dispatch(button('Undo').slot,'0');
current=state();
assert.strictEqual(current.f_parts_records_spend.length,2,'undo restores the removed record');
assert.strictEqual(current.f_parts_records_spend[1].key,'k2','undo restores the original key');
assert.strictEqual(current.f_parts_records_spend[1].values.amount,3.5);
assert.deepEqual(rows().map(row=>row.value),[1,2],'undo restores the removed row at its index');
assert.strictEqual(rows()[1].title,'Note: tea; Amount: 3.5','the restored row keeps its prepared text');
assert.strictEqual(current.f_parts_removed_spend,null,'undo clears the pending undo');
const reopened=vm.runInContext(`(()=>{const checkpoint=JSON.parse(session.checkpoint());const restored=createDealUiSession(entry,rt,checkpoint,()=>{throw Error('No effect expected');},checkpointSchema);const snapshot=JSON.parse(restored.snapshot()).tree;const found=[];const visit=n=>{if(n.component.split('.').pop()==='ListItem')found.push(n.props.find(prop=>prop.name==='eventValue').numberValue);for(const child of n.children||[])visit(child);};visit(snapshot);restored.dispose();return found;})()`,context);
assert.deepEqual(reopened,[1,2],'a restored Session renders the prepared rows of its records');

// A second undo, and an undo with nothing pending, change nothing.
dispatch(button('Undo').slot,'0');
current=state();
assert.strictEqual(current.f_parts_records_spend.length,2,'an undo without a pending removal changes nothing');
assert.strictEqual(current.f_parts_notice_spend,'Nothing to undo');
// A removal after an undo still validates the payload against the current prepared rows and stores
// its own pending undo, so the issued row identity survives a remove/undo cycle.
const again=rows()[1];
assert.strictEqual(again.value,2,'the restored row carries the identity issued with its key');
dispatch(again.slot,String(again.value));
current=state();
assert.strictEqual(current.f_parts_records_spend.length,1,'a removal after undo drops exactly one record');
assert.strictEqual(current.f_parts_removed_spend.index,1,'a removal after undo records its index');
assert.strictEqual(current.f_parts_removed_spend.record.key,'k2','a removal after undo records the removed record');
dispatch(button('Undo').slot,'0');
assert.strictEqual(state().f_parts_records_spend.length,2,'the second undo restores the record');
assert.deepEqual(rows().map(row=>row.value),[1,2],'the second undo restores the prepared rows');

// The count and total are derived from records only, never from a draft or a display.
assert.strictEqual(state().f_parts_count_spend,state().f_parts_records_spend.length);

// A valid decimal draft commits the value the decimal denotes, and a long fractional draft is finite
// rather than NaN or an overflowing sum. Both assert the stored typed value, not just the draft flag.
const beforeDecimal=state().f_parts_records_spend.length;
dispatch(textField('Amount').slot,'0.12');
dispatch(button('Add').slot,'0');
current=state();
assert.strictEqual(current.f_parts_records_spend.length,beforeDecimal+1,'a valid decimal draft adds one record');
assert.strictEqual(current.f_parts_records_spend[beforeDecimal].values.amount,0.12,'a decimal draft commits the value it denotes');
dispatch(textField('Amount').slot,'0.012');
dispatch(button('Add').slot,'0');
current=state();
assert.strictEqual(current.f_parts_records_spend[beforeDecimal+1].values.amount,0.012,'a leading-zero decimal draft commits the value it denotes');
const longValue='0.'+'1'.repeat(310);
dispatch(textField('Amount').slot,longValue);
dispatch(button('Add').slot,'0');
current=state();
assert.strictEqual(current.f_parts_records_spend.length,beforeDecimal+3,'a finite long fractional draft adds one record');
const parsedLong=current.f_parts_records_spend[beforeDecimal+2].values.amount;
assert(Number.isFinite(parsedLong)&&parsedLong>0.1&&parsedLong<0.12,'a long fractional draft commits its finite value: '+parsedLong);
assert(Number.isFinite(current.f_parts_total_spend)&&current.f_parts_total_spend_ready,'the total over a long fractional value stays finite');
// The transient action notice is runtime state, not authoritative business state: a live Session still
// shows the reason of the action it just rejected, while the saved record does not carry it and a
// restart republishes no stale reason for an action that no longer exists.
dispatch(button('Undo').slot,'0');
assert.strictEqual(state().f_parts_notice_spend,'Nothing to undo','the live notice does not show its action reason');
const restart=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;const record=app.bridgePersistedState.$f(app.initialState.$f());return {keys:record instanceof Map?[...record.keys()]:Object.keys(record),notice:app.bridgeRestoredState.$f(record).f_parts_notice_spend};})()`,context);
assert(!restart.keys.includes('f_parts_notice_spend'),'the transient notice is written into the saved record');
assert.strictEqual(restart.notice,'','a saved notice restores empty');
// A record that carries the old authoritative notice is refused atomically rather than restoring a stale
// reason: the field is no longer part of the persisted projection, so the saved source is reported
// incompatible and kept.
const stale=vm.runInContext(`(()=>{const app=dealLoad(dealFactories,'experience.js').entry;const record=app.bridgePersistedState.$f(app.initialState.$f());record.set('f_parts_notice_spend','Nothing to undo');try{app.bridgeRestoredState.$f(record);return '';}catch(error){return error.message;}})()`,context);
assert(stale.includes('Unsupported persisted field'),'a record carrying the transient notice was admitted as authoritative state: '+stale);
console.log('entries part: admitted seed, closed values record, typed drafts with located reasons, one timestamp per add, stable keys, key-addressed remove and undo at index, count, total and a runtime-only notice passed');
