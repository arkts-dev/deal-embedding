#!/usr/bin/env node
'use strict';
// Runtime item for cross-fragment action binding over the pair the composer emitted and the production
// compiler admitted: a view of one placed owner binds another placed owner's exported nominal action,
// and dispatch reaches that owner's reducer instead of being rejected as undeclared.
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
function dispatch(slot,payload){vm.runInContext(`session.dispatch(${slot},${JSON.stringify(payload)})`,context);}
function toggle(label){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='Toggle')return undefined;const values=props(node);if(values.label===undefined||values.label.stringValue!==label)return undefined;return {slot:values.onChange.actionSlot,checked:values.checked.booleanValue};});assert(found!==undefined,'missing Toggle: '+label);return found;}

// The binding view belongs to owner a and the bound action class belongs to owner b. Owner a reads
// owner b's flag through an issued port connection, so the rendered control shows the value only
// owner b's reducer writes and its dispatch must reach owner b's reducer.
let current=state();
assert.strictEqual(current.f_a_flag,false,'owner a starts with its own flag clear');
assert.strictEqual(current.f_b_flag,false,'owner b starts with its flag clear');
const foreign=toggle('Owner b flag');
assert.strictEqual(foreign.checked,false,'the foreign-bound control does not render owner b flag');
dispatch(foreign.slot,'true');
current=state();
assert.strictEqual(current.f_b_flag,true,'the foreign-bound action did not reach owner b reducer');
assert.strictEqual(current.f_a_flag,false,'the foreign-bound action wrote the binding view owner instead');
assert.strictEqual(toggle('Owner b flag').checked,true,'the foreign-bound control does not render the target owner value it dispatched');

// The same control keeps binding the target owner's class across a second dispatch, and the target
// owner's own value is what the control follows.
dispatch(toggle('Owner b flag').slot,'false');
current=state();
assert.strictEqual(current.f_b_flag,false,'the foreign-bound action did not commit the cleared boolean');
assert.strictEqual(current.f_a_flag,false,'the cleared foreign-bound action wrote the binding view owner');
assert.strictEqual(toggle('Owner b flag').checked,false,'the foreign-bound control did not follow the cleared target value');

// The target owner's own controls are emitted from its own view.
for(const label of ['Load b','Link b','Change b']){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='Button')return undefined;const values=props(node);if(values.text===undefined||values.text.stringValue!==label)return undefined;return {slot:values.onClick.actionSlot};});assert(found!==undefined,'owner b emitted no control: '+label);}
console.log('foreign binding: a view of one owner binds another owner exported action and dispatch reaches that owner reducer');
