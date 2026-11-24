#!/usr/bin/env node
'use strict';
// Real pinned Session + shipped mount adapter/mechanism; only the compiled ABI/codec are fixtures.
// No compiler, Android, provider or paid request. Tests boundary ordering, not compiled-app acceptance.
const fs = require('fs'), vm = require('vm'), assert = require('node:assert/strict');
const context = vm.createContext({assert});
vm.runInContext(fs.readFileSync('dependencies/deal-ui/runtime-js/session.js', 'utf8'), context);
for (const name of ['text-pattern.js', 'ui-session.js', 'checking-session.js'])
  vm.runInContext(fs.readFileSync('android/assets/embedding/bindings/' + name, 'utf8'), context);
vm.runInContext(`
  const field = (component, name, value) => ({component, props:[
    {name, kind:typeof value === 'string' ? 'string' : 'number', stringValue:value, numberValue:value},
    {name:'onChange', kind:'action', actionSlot:1}
  ], children:[]});
  let initialTree = field('TextField','pattern','(a)\\\\1');
  let nextTree = initialTree, effects = 0;
  const fn = f => ({$kind:'function', $f:f, $sig:'(): fixture'});
  const entry = {
    bridgeInitialState:fn(()=>({count:0})), bridgeSchemaDigest:fn(()=>''),
    initialStore:fn(()=>({lifecycle:{disposed:false},queue:[]})),
    initial:fn((state,store)=>({tree:initialTree,store,effect:{effectId:-1}})),
    bridgeSessionStatus:fn(()=>({version:0,fault:''})),
    enqueue:fn((store,action)=>({store:{...store,queue:[...store.queue,action]},accepted:true,startDrain:true})),
    dequeue:fn(store=>({store:{...store,queue:store.queue.slice(1)},present:store.queue.length>0,action:store.queue[0]})),
    nextState:fn(state=>({count:state.count+1})),
    transitionFromCandidate:fn((candidate,old,store)=>({tree:nextTree,store,effect:{effectId:0}})),
    bridgeSessionCommitted:fn(status=>({version:status.version+1,fault:''})),
    bridgeSessionFailed:fn((status,fault)=>({...status,fault})),
    reject:fn(store=>store), finish:fn(store=>store),
    bridgeSessionStarted:fn(status=>status), bridgeSessionExited:fn(status=>status),
    bridgeReplacementReady:fn(()=>true), bridgeEffect0:fn(()=>{effects++;return null;}),
    payloadType_1:fn(()=>'none'), action_1:fn(()=>({})),
    bridgeFrameVisibility:fn(()=>({visible:false})),
    checkHandlerCount:fn(()=>0),
  };
  globalThis.createDealUiCheckpoint = ()=>({encode:value=>value,decode:value=>value});
  globalThis.dealLoad = ()=>({entry,rt:{reifyError:error=>error,runtimeDiagnostic:error=>String(error)},checkpointSchema:{}});
  globalThis.dealCapabilities = {request:()=>{throw Error('no provider');}};
  mountDealUi({},'fixture');
  const baseline = dealUi.snapshot(), state = dealUi.state();
  for (const tree of [field('TextField','pattern','['), field('TimeField','value',1440),
    field('ClockText','minutes',0.5), field('DateField','value',0.5)]) {
    nextTree = tree;
    dealUi.dispatch(1);
    const snapshot = JSON.parse(dealUi.snapshot());
    assert(snapshot.fault.includes('PROP_FAULT') && snapshot.version === 0 && effects === 0 && dealUi.state() === state && JSON.stringify(snapshot.tree) === JSON.stringify(JSON.parse(baseline).tree), 'rejected candidate committed');
  }
  nextTree = field('TextField','pattern','(?<year>\\\\d{4})');
  dealUi.dispatch(1);
  assert(JSON.parse(dealUi.snapshot()).version === 1 && effects === 1, 'valid sibling did not commit');
  for (const component of ['TimeField','ClockText']) for (const value of [-1,1440,0.5,NaN,Infinity]) {
    let rejected = false;
    try { validateRendererValues(field(component,component==='ClockText'?'minutes':'value',value)); }
    catch { rejected = true; }
    if (!rejected) throw Error('temporal value admitted');
  }
  for (const value of [0,1439]) validateRendererValues(field('TimeField','value',value));
  validateRendererValues(field('DateField','value',-1));
  initialTree = field('TextField','pattern','[');
  let refused = false;
  try { mountDealUi({},'fixture'); } catch { refused = true; }
  if (!refused) throw Error('invalid initial tree published');
  openChecking({},'fixture');
  if (JSON.parse(checkingStep('start')).ok) throw Error('checker admitted invalid pattern');
`, context);
assert.match(fs.readFileSync('android/src/bindings/DealSessionBindings.kt','utf8'), /"text-pattern.js", "ui-session.js"/);
assert.match(fs.readFileSync('android/src/bindings/CandidateChecks.kt','utf8'), /"text-pattern.js","checking-session.js"/);
console.log('renderer admission: real Session rejects before state/tree/version/effects; valid sibling commits; initial/checking faults retained');
