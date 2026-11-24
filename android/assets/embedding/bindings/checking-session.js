"use strict";
// Isolate mechanics only. Compiled DEAL chooses checks and admission. No live Session,
// provider broker, effect scheduling, persistence, mounting, or platform drivers exist here.
globalThis.openChecking = function(factories, entryId, restoredState = null, appModule = null) {
  const NativeDate = Date;
  globalThis.Date = class extends NativeDate { constructor(...args) { super(...(args.length ? args : [0])); } static now() { return 0; } };
  let providerCalls = 0;
  globalThis.dealCapabilities = Object.freeze({...dealCapabilities,request: () => { providerCalls++; throw Error('Provider access during isolated checking'); }});
  let entry, rt, checkpointSchema, app;
  try {
    ({entry, rt, checkpointSchema, app} = dealLoad(factories, entryId, appModule));
    // A retaining host requires the compiled role facts at admission, not after a committed action.
    if(appModule !== null && (app === undefined || app === null
        || app.bridgeRestoredState === undefined || app.bridgeRestoredState.$kind !== 'function'
        || app.bridgePersistedState === undefined || app.bridgePersistedState.$kind !== 'function'))
      throw Error('PERSISTENCE_ROLES: compiled source issues no authoritative state roles');
  }
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
  // A candidate tree the shared admission refuses is located checker feedback about the assembled
  // source, not an execution failure: the diagnostic names the component, the prop and the class, or
  // the card limit, so bounded repair can locate the writer's defect and no degraded tree activates.
  function admissionDiagnostic(fault) {
    const located = (prefix) => fault.component === '' ? prefix : prefix + ': ' + fault.component + (fault.prop ? '.' + fault.prop : '');
    if (fault.code === 'PROP_FAULT') {
      let text = located('CHECK_PROP');
      if (fault.propClass) text += ' ' + fault.propClass;
      if (fault.detail) text += ': ' + fault.detail;
      return text;
    }
    if (fault.code === 'CARD_FAULT') {
      let text = located('CHECK_CARD');
      if (fault.count !== undefined) text += ' (' + fault.count + ')';
      if (fault.detail) text += ': ' + fault.detail;
      return text;
    }
    return fault.code + (fault.detail ? ': ' + fault.detail : '');
  }
  function render(candidate,action) {
    const next = action ? call('transitionFromCandidate',candidate,current.tree,store,action) : call('initial',candidate,store);
    validateRendererValues(next.tree);
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
        if(restoredState !== null) {
          // Restore facts shared with the live session: digest/tag checks, nominal allocation, then
          // the compiled recompute before any tree is rendered.
          initial=app.bridgeRestoredState.$f(createDealUiCheckpoint(rt,checkpointSchema,call('bridgeSchemaDigest')).decode(restoredState,'record'));
        }
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
    } catch(error) {
      if(error && error.fault) return response(false,admissionDiagnostic(error.fault));
      return response(false,'CHECK_EXECUTION: '+rt.runtimeDiagnostic(rt.reifyError(error)));
    }
  };
  return '';
};
