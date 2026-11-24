#!/usr/bin/env node
'use strict';
// Runtime item for the issued entry aggregates over the pair the composer emitted and the production
// compiler admitted: a figure names count_<need> or total_<need>, the aggregate operand reads the
// entries part's own derived field, and the figure follows the records the user actually adds. An
// entries need without a numeric non-time field leaves its total and the figure over it unready with
// the located reason instead of a placeholder.
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
function textFields(){return all(tree(),node=>{if(node.component.split('.').pop()!=='TextField')return undefined;const values=props(node);return {label:values.label.stringValue,slot:values.onChange.actionSlot};});}
function textField(label){const found=textFields().find(item=>item.label===label);assert(found!==undefined,'missing TextField: '+label);return found;}
function buttons(label){return all(tree(),node=>{if(node.component.split('.').pop()!=='Button')return undefined;const values=props(node);if(values.text.stringValue!==label)return undefined;return {slot:values.onClick.actionSlot,enabled:values.enabled.booleanValue};});}
function numberTexts(){return all(tree(),node=>{if(node.component.split('.').pop()!=='NumberText')return undefined;const values=props(node);return {value:values.value.numberValue,suffix:values.suffix?values.suffix.stringValue:'',decimals:values.decimals?values.decimals.numberValue:-1};});}
function texts(){return all(tree(),node=>{if(node.component.split('.').pop()!=='Text')return undefined;return props(node).text.stringValue;});}
function add(){const controls=buttons('Add');assert(controls.length===2,'expected one add control per entries part');return controls[0];}

// Initialization: nothing is recorded yet, so the count and the total are their ready zeros and the
// figure over the total follows them. The entries need with only a time field publishes an unavailable
// total instead of zero history, and the figure over it is unready with a located reason.
let current=state();
assert.strictEqual(current.f_parts_count_spend,0,'no record exists before an add');
assert.strictEqual(current.f_parts_count_spend_ready,true);
assert.strictEqual(current.f_parts_total_spend,0,'the total of no records is zero');
assert.strictEqual(current.f_parts_total_spend_ready,true);
assert.strictEqual(current.f_parts_total,0,'the figure over the entries total reads the emitted aggregate');
assert.strictEqual(current.f_parts_total_ready,true,'the figure over a ready aggregate is ready');
assert.strictEqual(current.f_parts_records,0,'the figure over the entries count reads the emitted aggregate');
assert.strictEqual(current.f_parts_records_ready,true,'the figure over the count aggregate is ready');
assert.strictEqual(current.f_parts_total_logged_ready,false,'a time-only entries need publishes no total value');
assert(current.f_parts_total_logged_reason.includes('No numeric non-time field exists'),'the unavailable total keeps its located reason: '+current.f_parts_total_logged_reason);
assert.strictEqual(current.f_parts_span_ready,false,'the figure over an unavailable aggregate is never a placeholder');
assert(texts().includes(current.f_parts_span_reason),'the unready figure renders its located reason');
assert(numberTexts().some(item=>item.value===0&&item.suffix==='€'&&item.decimals===2),'the figure renders the prepared aggregate with its issued unit and decimals');

// The figure follows the records the user actually adds: two adds sum to two records, the aggregate
// counts and totals them, and the figure publishes that total.
dispatch(textField('Note').slot,'coffee');
dispatch(textField('Amount').slot,'4.5');
dispatch(add().slot,'0');
current=state();
assert.strictEqual(current.f_parts_count_spend,1,'one tap adds one record');
assert.strictEqual(current.f_parts_total_spend,4.5,'the total sums the added numeric field');
assert.strictEqual(current.f_parts_total,4.5,'the figure reads the entries total the user built');
assert.strictEqual(current.f_parts_total_ready,true);
assert.strictEqual(current.f_parts_records,1,'the figure over the count follows the first record');
dispatch(textField('Amount').slot,'2.5');
dispatch(add().slot,'0');
current=state();
assert.strictEqual(current.f_parts_count_spend,2);
assert.strictEqual(current.f_parts_total_spend,7,'the aggregate follows the second record');
assert.strictEqual(current.f_parts_total,7,'the figure follows the aggregate, not a stored copy');
assert.strictEqual(current.f_parts_records,2,'the figure over the count follows the second record');
assert(numberTexts().some(item=>item.value===7&&item.suffix==='€'&&item.decimals===2),'the figure renders the updated aggregate');
// The count aggregate is the entries need's own count field, so the figure over it and a reader of the
// need's publication observe one value rather than two prepared copies.
assert.strictEqual(current.f_parts_records,current.f_parts_count_spend,'the count figure and the entries count disagree');
console.log('entry aggregate: a figure over the issued entries count and total reads the emitted aggregate, follows added records and stays unready with its reason where no total exists passed');
