#!/usr/bin/env node
'use strict';
// Runtime items for the issued numeric presentation rule at the entry-field site over the pair the
// composer emitted and the production compiler admitted. A prepared row text and the NumberText total
// over the same stored value apply the one rule of the wiki's numeric presentation contract: automatic
// decimals render the shortest round-tripping decimal in plain notation, and a fixed count rounds that
// decimal half away from zero to exactly that many places - never the binary product of the double.
const fs=require('fs'),vm=require('vm'),path=require('path'),assert=require('assert');
const base=path.resolve(process.argv[2]),sources={};
function walk(dir){for(const name of fs.readdirSync(dir)){const file=path.join(dir,name);if(fs.statSync(file).isDirectory())walk(file);else if(name.endsWith('.js'))sources[path.relative(base,file)]=fs.readFileSync(file,'utf8');}}
walk(base);const context=vm.createContext({sources});
vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/sandbox.js','utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/checkpoint.js'),'utf8'),context);
vm.runInContext(fs.readFileSync(path.join(process.env.DEAL_UI_ROOT||'dependencies/deal-ui','runtime-js/session.js'),'utf8'),context);
vm.runInContext(`const factories=Object.fromEntries(Object.entries(sources).map(([id,source])=>[id,new Function('require','module','exports','process','console',source)]));globalThis.dealFactories=factories;const {entry,rt,checkpointSchema}=dealLoad(factories,'experience_entry.js');globalThis.appModule=dealLoad(factories,'experience.js').entry;const session=createDealUiSession(entry,rt,null,()=>{throw Error('No effect expected');},checkpointSchema);`,context);
const state=()=>vm.runInContext("createDealUiCheckpoint(rt,checkpointSchema,entry.bridgeSchemaDigest.$f()).decode(JSON.parse(session.checkpoint()),entry.bridgeInitialState.$sig.substring(4))",context);
const tree=()=>JSON.parse(vm.runInContext('session.snapshot()',context)).tree;
function props(node){return Object.fromEntries(node.props.map(prop=>[prop.name,prop]));}
function find(node,match){const found=match(node);if(found!==undefined)return found;for(const child of node.children||[]){const nested=find(child,match);if(nested!==undefined)return nested;}return undefined;}
function all(node,match){const found=[];const visit=n=>{const hit=match(n);if(hit!==undefined)found.push(hit);for(const child of n.children||[])visit(child);};visit(node);return found;}
function dispatch(slot,payload){vm.runInContext(`session.dispatch(${slot},${JSON.stringify(payload)})`,context);}
function rows(node,match){return all(node,n=>{if(n.component.split('.').pop()!=='ListItem')return undefined;const values=props(n);if(values.title===undefined)return undefined;if(!values.title.stringValue.startsWith(match))return undefined;return {title:values.title.stringValue,value:values.eventValue.numberValue,slot:values.onTap.actionSlot};});}
function textField(label){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='TextField')return undefined;const values=props(node);if(values.label===undefined||values.label.stringValue!==label)return undefined;return {slot:values.onChange.actionSlot,value:values.value.stringValue};});assert(found!==undefined,'missing TextField: '+label);return found;}
function button(label){const found=find(tree(),node=>{if(node.component.split('.').pop()!=='Button')return undefined;const values=props(node);if(values.text===undefined||values.text.stringValue!==label)return undefined;return {slot:values.onClick.actionSlot,enabled:values.enabled.booleanValue};});assert(found!==undefined,'missing Button: '+label);return found;}
function totals(){return all(tree(),node=>{if(node.component.split('.').pop()!=='NumberText')return undefined;const values=props(node);if(values.prefix===undefined||!values.prefix.stringValue.endsWith('total: '))return undefined;return {prefix:values.prefix.stringValue,value:values.value.numberValue,suffix:values.suffix?values.suffix.stringValue:'',decimals:values.decimals.numberValue};});}
const plain=(v)=>vm.runInContext('appModule.f_parts_numberText.$f('+JSON.stringify(v)+')',context);
const fixed=(v,d)=>vm.runInContext('appModule.f_parts_decimalText.$f('+JSON.stringify(v)+','+d+')',context);

// The formatter branches the rows exercise less often, checked against the rule itself: a plain
// shortest decimal, a full carry, a rounded zero that keeps no sign, an integral rounding and a
// trailing zero of a fixed count. Each expectation states the rule, not the emitted text.
assert.strictEqual(plain(1e21),'1000000000000000000000','a positive exponent was not expanded to plain notation: '+plain(1e21));
assert.strictEqual(plain(1e-7),'0.0000001','a negative exponent was not expanded to plain notation: '+plain(1e-7));
assert.strictEqual(plain(100),'100','a whole value gained a fraction: '+plain(100));
assert.strictEqual(vm.runInContext('appModule.f_parts_numberText.$f(-0)',context),'0','negative zero printed a sign');
assert.strictEqual(fixed(999.995,2),'1000.00','a fixed carry into a new digit was lost: '+fixed(999.995,2));
assert.strictEqual(fixed(9.995,2),'10.00','a fixed carry was lost: '+fixed(9.995,2));
assert.strictEqual(fixed(-0.001,2),'0.00','a value rounded to zero kept a negative sign: '+fixed(-0.001,2));
assert.strictEqual(fixed(1.5,0),'2','an integral rounding did not round half away from zero: '+fixed(1.5,0));
assert.strictEqual(fixed(17.5,2),'17.50','a fixed count dropped its trailing zero: '+fixed(17.5,2));
assert.strictEqual(fixed(0,2),'0.00','zero did not render with its fixed count: '+fixed(0,2));
assert.strictEqual(fixed(5e-324,2),'0.00','the smallest value did not round to zero: '+fixed(5e-324,2));

// One seeded amount is stored as its typed value and displayed by the one issued rule: fixed decimals
// round the decimal 1.005 half away from zero to 1.01, while rounding the binary product would show
// 1.00 because Math.round(1.005*100) is 100.
let current=state();
assert.strictEqual(current.f_parts_records_solo.length,1,'the admitted seed creates one record');
assert.strictEqual(current.f_parts_records_solo[0].values.amount,1.005,'the stored amount stays the typed value 1.005');
assert.strictEqual(current.f_parts_total_solo,1.005,'the total reads the stored typed value');
const fixedRows=rows(tree(),'Amount: ');
assert.deepEqual(fixedRows.map(row=>row.title),['Amount: 1.01€'],'the fixed-decimals row text does not round the decimal half away from zero: '+JSON.stringify(fixedRows.map(row=>row.title)));
const fixedTotals=totals().filter(item=>item.prefix==='Fixed total: ');
assert.strictEqual(fixedTotals.length,1,'the fixed-decimals total is missing');
assert.strictEqual(fixedTotals[0].value,1.005,'the total presents a different value than the row');
assert.strictEqual(fixedTotals[0].decimals,2,'the total lost its issued decimals');
assert.strictEqual(fixedTotals[0].suffix,'€','the total lost its issued unit');

// Automatic decimals render the shortest round-tripping decimal in plain notation, never the host
// exponent spelling, so the row and the total of 1e-7 both read 0.0000001.
assert.strictEqual(current.f_parts_records_tiny[0].values.rate,0.0000001,'the stored tiny rate is not the typed value');
const tinyRows=rows(tree(),'Rate: ');
assert.deepEqual(tinyRows.map(row=>row.title),['Rate: 0.0000001'],'the automatic-decimals row text is not plain shortest decimal: '+JSON.stringify(tinyRows.map(row=>row.title)));
const tinyTotals=totals().filter(item=>item.prefix==='Tiny total: ');
assert.strictEqual(tinyTotals.length,1,'the automatic-decimals total is missing');
assert.strictEqual(tinyTotals[0].value,0.0000001,'the tiny total presents a different value than the row');
assert.strictEqual(tinyTotals[0].decimals,-1,'the tiny total is not automatic');

// The added row goes through the same one formatter: 2.675 rounds half away from zero to 2.68, while
// the binary product Math.round(2.675*100) is 267 and would display 2.67.
dispatch(textField('Amount').slot,'2.675');
dispatch(button('Add').slot,'0');
current=state();
assert.strictEqual(current.f_parts_records_solo.length,2,'the draft add committed no record');
assert.strictEqual(current.f_parts_records_solo[1].values.amount,2.675,'the parsed draft is not the typed value');
assert.deepEqual(rows(tree(),'Amount: ').map(row=>row.title),['Amount: 1.01€','Amount: 2.68€'],'the added row text does not apply the issued rule: '+JSON.stringify(rows(tree(),'Amount: ').map(row=>row.title)));
assert(Math.abs(current.f_parts_total_solo-3.68) < 0.000000001,'the total no longer sums the typed values: '+current.f_parts_total_solo);

// A fixed count keeps exactly that many places, including trailing zeros and a rounded carry.
dispatch(textField('Amount').slot,'1.5');
dispatch(button('Add').slot,'0');
current=state();
assert.deepEqual(rows(tree(),'Amount: ').map(row=>row.title),['Amount: 1.01€','Amount: 2.68€','Amount: 1.50€'],'a fixed count dropped its trailing zero: '+JSON.stringify(rows(tree(),'Amount: ').map(row=>row.title)));

// The removed row and its restored counterpart keep the same prepared text.
const captured=rows(tree(),'Amount: ').filter(row=>row.title==='Amount: 1.50€')[0];assert(captured!==undefined,'the added row is not rendered');
dispatch(captured.slot,String(captured.value));
current=state();
assert.strictEqual(current.f_parts_records_solo.length,2,'the row removal dropped no record');
dispatch(button('Undo').slot,'0');
assert.deepEqual(rows(tree(),'Amount: ').map(row=>row.title),['Amount: 1.01€','Amount: 2.68€','Amount: 1.50€'],'undo restored a row with different prepared text');

// A time field prepares a plain integral row through the same projection and no fixed-count formatter,
// and its label is data: the quote and the newline reach the row text literally instead of as source.
const stampRows=rows(tree(),'A "quoted" time\nsecond line: ');
assert.deepEqual(stampRows.map(row=>row.title),['A "quoted" time\nsecond line: 90'],'a time-only row text is not the plain integral spelling of its label and value: '+JSON.stringify(stampRows.map(row=>row.title)));
assert.strictEqual(state().f_parts_records_stamp[0].values.minute,90,'the seeded minute is not the typed value');
// A time-only entries part has no numeric non-time field, so its total is unavailable with the reason
// the parts contract states, never a zero as fact.
const textNodes=all(tree(),node=>{if(node.component.split('.').pop()!=='Text')return undefined;const values=props(node);return values.text===undefined?undefined:values.text.stringValue;});
assert.ok(textNodes.includes('No numeric non-time field exists'),'a time-only entries part did not publish its unavailable total reason: '+JSON.stringify(textNodes));
// The unavailable total is absent, never a zero presented as a fact.
assert.deepEqual(totals().filter(item=>item.prefix.indexOf('Stamped')===0).map(item=>item.prefix),[],'a time-only entries part rendered a numeric total');
console.log('numeric text: one issued presentation rule at the entry-field row and total (fixed half-away rounding, plain automatic decimals) passed');
