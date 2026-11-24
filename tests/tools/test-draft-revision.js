#!/usr/bin/env node
'use strict';
// Runtime item for the issued operation revision over the pair the composer emitted and the production
// compiler admitted. The revision an operation captures counts edits to its dependency set: a raw text
// draft is a read of the parsed value, so an equivalent spelling leaves every revision while a changed
// validated value or an invalid draft advances exactly the revisions that depend on it.
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
function textField(label){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='TextField')return undefined;const values=props(node);if(values.label===undefined||values.label.stringValue!==label)return undefined;return {slot:values.onChange.actionSlot,value:values.value.stringValue};});assert(found!==undefined,'missing TextField: '+label);return found;}
function revisions(current){return [current.revision_done,current.revision_record,current.revision_tap,current.revision_later];}

// Every operation that consumes the input observes the parsed value and its readiness, never the raw
// buffer, and starts at revision zero.
let current=state();
assert.strictEqual(current.f_parts_value,2,'the parsed input value is not the declared value');
assert.strictEqual(current.f_parts_value_draft,'2','the draft buffer is not the plain declared spelling');
assert.deepEqual(revisions(current),[0,0,0,0],'an operation revision did not start at zero');

// An equivalent spelling of the same value changes only the buffer: no revision advances.
dispatch(textField('Value').slot,'2.0');
current=state();
assert.strictEqual(current.f_parts_value,2,'the equivalent spelling changed the parsed value');
assert.strictEqual(current.f_parts_value_ready,true,'the equivalent spelling changed readiness');
assert.strictEqual(current.f_parts_value_draft,'2.0','the equivalent spelling did not stay in the buffer');
assert.deepEqual(revisions(current),[0,0,0,0],'an equivalent draft spelling advanced an operation revision');

// Another equivalent spelling, this time with trailing zeros, bumps nothing either.
dispatch(textField('Value').slot,'2.000');
assert.deepEqual(revisions(state()),[0,0,0,0],'a second equivalent draft spelling advanced an operation revision');

// A changed validated value advances exactly the revisions whose dependency set it is in.
dispatch(textField('Value').slot,'3');
current=state();
assert.strictEqual(current.f_parts_value,3,'the changed value did not commit');
assert.deepEqual(revisions(current),[1,1,1,1],'a changed argument value did not advance every dependent revision once');

// An invalid draft leaves the buffer visible and the value unready, and advances the same revisions:
// validity is part of the dependency, the draft spelling is not.
dispatch(textField('Value').slot,'not a number');
current=state();
assert.strictEqual(current.f_parts_value_draft,'not a number','an invalid draft was cleared');
assert.strictEqual(current.f_parts_value_ready,false,'an invalid draft left the parsed value ready');
assert.deepEqual(revisions(current),[2,2,2,2],'an invalid draft did not advance the dependent revisions');

// Returning to the same validated value is a value change against the unready state and bumps once.
dispatch(textField('Value').slot,'3.00');
current=state();
assert.strictEqual(current.f_parts_value,3,'the restored value did not commit');
assert.strictEqual(current.f_parts_value_ready,true,'the restored value is not ready');
assert.deepEqual(revisions(current),[3,3,3,3],'restoring a validated value after an invalid draft did not advance the revisions');

// The revisions are authoritative state and restore with the record.
const reopened=vm.runInContext(`(()=>{const checkpoint=JSON.parse(session.checkpoint());const restored=createDealUiSession(entry,rt,checkpoint,()=>{throw Error('No effect expected');},checkpointSchema);const decoded=createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(restored.checkpoint()),entry.bridgeInitialState.$sig.substring(4));restored.dispose();return {done:decoded.revision_done,later:decoded.revision_later,value:decoded.f_parts_value_draft};})()`,context);
assert.strictEqual(reopened.done,3,'a revision field did not restore with the record');
assert.strictEqual(reopened.later,3,'a revision field did not restore with the record');
assert.strictEqual(reopened.value,'3.00','the draft buffer did not restore with the record');
console.log('draft revision: equivalent buffer spellings invalidate nothing while value and validity changes advance the dependent revisions');
