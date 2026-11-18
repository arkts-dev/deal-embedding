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
const action=slot(tree);assert(Number.isInteger(action));context.action=action;
vm.runInContext("session.dispatch(action,'9')",context);assert.equal(state().f_calculation_value,9);assert.equal(state().f_calculation_double,18);
vm.runInContext("session.dispatch(action,'-2')",context);assert.equal(state().f_calculation_value,-2);assert.equal(state().f_calculation_double,null);assert.equal(state().f_calculation_double_ready,false);assert.equal(state().f_calculation_double_reason,'Value must be nonnegative');assert.equal(state().f_calculation_triple,null);assert.equal(state().f_calculation_triple_ready,false);
assert.equal(JSON.parse(vm.runInContext('session.snapshot()',context)).fault,'');
// Checkpoint round-trip uses actual compiled nominal and array identities.
vm.runInContext(`const checkpoint=JSON.parse(session.checkpoint());const restored=createDealUiSession(entry,rt,checkpoint,()=>{throw Error('No effect expected');},checkpointSchema);if(JSON.stringify(JSON.parse(restored.snapshot()).tree)!==JSON.stringify(JSON.parse(session.snapshot()).tree))throw Error('Restored display changed');restored.dispose();`,context);
console.log('composed runtime: production compiler + FIFO store, owner updates, derived readiness and unavailable result passed');
