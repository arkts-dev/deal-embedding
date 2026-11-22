#!/usr/bin/env node
'use strict';
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);const context=vm.createContext({sources});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));const {entry,rt,checkpointSchema}=dealLoad(factories,'experience_entry.js');const session=createDealUiSession(entry,rt,null,()=>{throw Error('No effect expected');},checkpointSchema);`,context);
const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(session.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",context);
assert.equal(state().f_calculation_triple,18);assert.equal(state().f_calculation_double,6);assert.equal(state().f_calculation_double_ready,true);
const tree=JSON.parse(vm.runInContext('session.snapshot()',context)).tree;
function slot(node){for(const prop of node.props)if(prop.kind==='action')return prop.actionSlot;for(const child of node.children){const found=slot(child);if(found!==undefined)return found;}}
function actionSlot(node,names){for(const prop of node.props)if(prop.kind==='action'&&names.includes(prop.name))return prop.actionSlot;for(const child of node.children){const found=actionSlot(child,names);if(found!==undefined)return found;}}
function componentSlot(tree,component,names){function visit(node){if(node.component.split('.').pop()===component){const found=actionSlot(node,names);if(found!==undefined)return found;}for(const child of node.children){const found=visit(child);if(found!==undefined)return found;}}return visit(tree);}
const action=slot(tree);assert(Number.isInteger(action));context.action=action;
const toggleSlot=componentSlot(tree,'Toggle',['onChange']);assert(Number.isInteger(toggleSlot),'the compiled view binds one Toggle change');context.toggleSlot=toggleSlot;
// The compiled view binds the numeric text through its guarded branch.
function numberTextValue(node){if(node.component.split('.').pop()==='NumberText'){const prop=node.props.find(item=>item.name==='value');if(prop!==undefined)return prop.numberValue;}for(const child of node.children){const found=numberTextValue(child);if(found!==undefined)return found;}return undefined;}
assert.strictEqual(numberTextValue(tree),6,'the compiled NumberText shows the derived double');
// The compiled Button carries its declared number `value` as the payload of its click.
function buttonSlot(node){
  if(node.component.split('.').pop()==='Button'){const props=Object.fromEntries(node.props.map(item=>[item.name,item]));
    if(props.text!==undefined&&props.text.stringValue==='Load'){const action=props.onClick;return {slot:action.actionSlot,value:props.value.numberValue};}}
  for(const child of node.children){const found=buttonSlot(child);if(found!==undefined)return found;}return undefined;}
const load=buttonSlot(tree);assert(load!==undefined&&Number.isInteger(load.slot),'the compiled view binds a value-carrying Button');
assert.strictEqual(load.value,3,'the Button binds the current number value');
// The renderer reconstructs Button.onClick(number) from the bound value; the session delivers it.
vm.runInContext(`session.dispatch(${load.slot},'${load.value}')`,context);
assert.strictEqual(state().f_calculation_sequence,load.value,'the Button click delivers its declared number value unchanged');
// The migrated toggle binding delivers the declared boolean, never a string spelling of it.
vm.runInContext("session.dispatch(toggleSlot,'true')",context);assert.strictEqual(state().f_calculation_flag,true);assert.strictEqual(typeof state().f_calculation_flag,'boolean');
vm.runInContext("session.dispatch(toggleSlot,'false')",context);assert.strictEqual(state().f_calculation_flag,false);
assert.throws(()=>vm.runInContext("session.dispatch(toggleSlot,'maybe')",context),'a string spelling of a boolean dispatches nothing');
assert.strictEqual(state().f_calculation_flag,false);
vm.runInContext("session.dispatch(action,'9')",context);assert.equal(state().f_calculation_value,9);assert.equal(state().f_calculation_double,18);
assert.strictEqual(numberTextValue(JSON.parse(vm.runInContext('session.snapshot()',context)).tree),18,'the compiled NumberText follows the dispatched value');
vm.runInContext("session.dispatch(action,'-2')",context);assert.equal(state().f_calculation_value,-2);assert.equal(state().f_calculation_double,null);assert.equal(state().f_calculation_double_ready,false);assert.equal(state().f_calculation_double_reason,'Value must be nonnegative');assert.equal(state().f_calculation_triple,null);assert.equal(state().f_calculation_triple_ready,false);
assert.strictEqual(numberTextValue(JSON.parse(vm.runInContext('session.snapshot()',context)).tree),undefined,'the guarded NumberText renders nothing while its value is unavailable');
assert.equal(JSON.parse(vm.runInContext('session.snapshot()',context)).fault,'');
// The compiled persistence roles project the live state; restore rebuilds the typed state and
// recomputes derived values before the session publishes its tree (see test-persistence.js).
vm.runInContext(`const {app:compiled}=dealLoad(factories,'experience_entry.js','experience.js');
const codec=createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f());
const live=codec.decode(JSON.parse(session.checkpoint()),entry.bridgeInitialState.$sig.substring(4));
const projection=codec.encode(compiled.bridgePersistedState.$f(live));
if(Object.keys(projection).length!==2||projection.value[0]!=='record')throw Error('Projection is not the authoritative record');
if(projection.value[2].some(pair=>pair[0]==='f_calculation_double'))throw Error('Projection carried a derived value');
const restored=createDealUiSession(entry,rt,codec.encode(compiled.bridgeRestoredState.$f(codec.decode(projection,'record'))),()=>{throw Error('No effect expected');},checkpointSchema);
if(JSON.stringify(JSON.parse(restored.snapshot()).tree)!==JSON.stringify(JSON.parse(session.snapshot()).tree))throw Error('Restored display changed');
restored.dispose();`,context);
console.log('composed runtime: production compiler + FIFO store, owner updates, derived readiness, the boolean Toggle change and unavailable result passed');
