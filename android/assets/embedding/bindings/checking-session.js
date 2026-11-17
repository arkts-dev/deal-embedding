"use strict";
// Isolate mechanics only. Compiled DEAL chooses checks and admission. No live Session,
// provider broker, effect scheduling, persistence, mounting, or platform drivers exist here.
globalThis.openChecking = function(factories, entryId, restoredState = null) {
  const NativeDate = Date;
  globalThis.Date = class extends NativeDate { constructor(...args) { super(...(args.length ? args : [0])); } static now() { return 0; } };
  let providerCalls = 0;
  globalThis.dealCapabilities = Object.freeze({...dealCapabilities,request: () => { providerCalls++; throw Error('Provider access during isolated checking'); }});
  let entry, rt;
  try { ({entry, rt} = dealLoad(factories, entryId)); }
  catch(error) {
    globalThis.checkingStep=()=>JSON.stringify({ok:false,diagnostics:'CHECK_STARTUP: '+(error.message||String(error)),tree:'',handlers:0,providerCalls});
    return '';
  }
  const call = (name,...args) => entry[name].$f(...args);
  let state, current, store;
  function plain(value) {
    if (value instanceof Map) return Object.fromEntries([...value].map(([k,v])=>[k,plain(v)]));
    if (Array.isArray(value)) return value.map(plain);
    if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).filter(([k])=>!k.startsWith('$')).map(([k,v])=>[k,plain(v)]));
    return value;
  }
  function response(ok, diagnostic = '') { return JSON.stringify({ok,diagnostics:diagnostic,tree:current ? JSON.stringify(plain(current.tree)) : '',handlers:call('checkHandlerCount'),providerCalls}); }
  function render(candidate,action) {
    const next = action ? call('transitionFromCandidate',candidate,current.tree,store,action) : call('initial',candidate,store);
    state=candidate;current=next;store=next.store;
  }
  // Snapshot existing carriers in place rather than JSON-cloning them. Restoring
  // descriptors and edges preserves nominal identities, typed-array metadata,
  // optional absence, aliases, cycles and special binary64 values.
  function checkpoint(root) {
    const seen = new Set(), saved = [];
    function visit(value) {
      if (!value || typeof value !== 'object' || seen.has(value)) return;
      seen.add(value);
      if (value instanceof Map) {
        const entries = [...value]; saved.push(() => { value.clear(); for (const [key,member] of entries) value.set(key,member); });
        for (const [key,member] of entries) { visit(key); visit(member); }
      } else {
        const descriptors = Object.getOwnPropertyDescriptors(value);
        saved.push(() => { for (const key of Reflect.ownKeys(value)) if (!Object.hasOwn(descriptors,key)) delete value[key]; Object.defineProperties(value,descriptors); });
        for (const descriptor of Object.values(descriptors)) if (Object.hasOwn(descriptor,'value')) visit(descriptor.value);
      }
    }
    visit(root);
    return () => { for (const restore of saved) restore(); };
  }
  globalThis.checkingStep = function(operation,index,payload) {
    try {
      if(operation==='start') {
        store=call('initialStore');
        let initial=call('bridgeInitialState');
        if(restoredState !== null) initial=rt.decodeGraph(restoredState,call('bridgeSchemaDigest'),entry.bridgeInitialState.$sig.substring(4));
        render(initial,null);
      }
      else {
        let action;
        if(operation==='handler') action=call('checkAction_'+index);
        else if(operation==='event') {
          let found;
          function visit(node) {for(const prop of node.props)if(prop.kind==='action'&&prop.actionSlot===index)found=prop;for(const child of node.children)visit(child);}
          visit(current.tree);if(!found) return response(false,'CHECK_EVENT: missing current event');
          const type=call('payloadType_'+index);
          let value=payload;
          if(type==='json')value=call('decodePayload_'+index,JSON.stringify(payload));
          action=type==='none'?call('action_'+index,found.actionBindings):call('action_'+index,found.actionBindings,value);
        } else throw Error('Unknown checking operation');
        let candidate;
        const rollback=checkpoint(state);
        try {candidate=call('nextState',state,action);}
        catch(error) {rollback();return response(true,'SYNTHETIC_INPUT_TRAP: state restored');}
        // Rendering is deliberately outside the synthetic-input trap boundary.
        render(candidate,action);
      }
      return response(true);
    } catch(error) {return response(false,'CHECK_EXECUTION: '+(rt.reifyError(error).message||String(error)));}
  };
  return '';
};
