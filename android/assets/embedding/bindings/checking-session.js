"use strict";
// Isolate mechanics only. Compiled DEAL chooses checks and admission. No live Session,
// provider broker, effect scheduling, persistence, mounting, or platform drivers exist here.
globalThis.openChecking = function(factories, entryId, restoredState = null, appModule = null, copiedState = null) {
  const NativeDate = Date;
  globalThis.Date = class extends NativeDate { constructor(...args) { super(...(args.length ? args : [0])); } static now() { return 0; } };
  let providerCalls = 0;
  globalThis.dealCapabilities = Object.freeze({...dealCapabilities,request: () => { providerCalls++; throw Error('Provider access during isolated checking'); }});
  // A public probe is a read of a live source: isolated checking admits no transport at all, so a
  // candidate cannot observe a remote document while it is only being checked.
  globalThis.dealProbe = Object.freeze({request: () => { providerCalls++; throw Error('Probe access during isolated checking'); }});
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
  // The compiled state class the replacement's persistence roles declare: its per-field descriptors are
  // the resolved types a copied authoritative value must satisfy.
  function declaredState(app) {
    const declared = /@[^\s,()\[\]]+/.exec(String(app.bridgePersistedState.$sig || ''));
    if (declared === null) return null;
    return Object.hasOwn(checkpointSchema.classes,declared[0]) ? checkpointSchema.classes[declared[0]] : null;
  }
  // A copied authoritative value arrives as JSON and carries no DEAL type. It is rebuilt under the
  // descriptor the replacement's compiled declaration resolves for the copied field and re-enters
  // through the checkpoint codec, so a list keeps its element descriptor, a class its nominal identity
  // and a record its key type; the compiled restore then admits the value it was handed instead of
  // refusing a bare carrier. A value the declaration does not admit stays a located rejection.
  function typedCopied(codec,digest,descriptor,value) {
    let identity = 0;
    function wire(type,member) {
      if (type.charAt(0) === '?') { return member === null ? ['null'] : wire(type.slice(1),member); }
      if (member === null) {
        if (type === 'unknown') return ['null'];
        throw Error('null is not a '+type);
      }
      if (type.charAt(0) === '[' && type.charAt(type.length-1) === ']') {
        const element = type.slice(1,-1);
        if (!Array.isArray(member)) throw Error('a list is expected for '+type);
        return ['array',identity++,element,member.map(item => wire(element,item))];
      }
      if (type.charAt(0) === '@') {
        const plan = Object.hasOwn(checkpointSchema.classes,type) ? checkpointSchema.classes[type] : null;
        if (plan === null) throw Error('no compiled declaration for '+type);
        if (typeof member !== 'object' || Array.isArray(member)) throw Error('an instance of '+type+' is expected');
        // A composite identity is claimed before its members, the order the codec reads the wire in.
        const id = identity++, entries = [];
        for (const field of plan) { if (Object.hasOwn(member,field.name)) entries[entries.length] = [field.name,wire(field.descriptor,member[field.name])]; }
        for (const name of Object.keys(member)) {
          let issued = false;
          for (const field of plan) { if (field.name === name) issued = true; }
          if (!issued) throw Error('undeclared field '+name+' of '+type);
        }
        return ['class',id,type,entries];
      }
      if (type === 'record' || type === 'record[string]') return record(type,member);
      if (type === 'unknown') {
        if (Array.isArray(member)) return ['array',identity++,'unknown',member.map(item => wire('unknown',item))];
        if (typeof member === 'object') return record('record',member);
        return wire(typeof member,member);
      }
      if (type === 'number') {
        if (typeof member !== 'number') throw Error('a number is expected');
        if (Number.isNaN(member)) return ['nan'];
        if (member === Infinity) return ['positiveInfinity'];
        if (member === -Infinity) return ['negativeInfinity'];
        if (Object.is(member,-0)) return ['negativeZero'];
        return ['number',String(member)];
      }
      if (type === 'string' || type === 'boolean') {
        if (typeof member !== type) throw Error('a '+type+' is expected');
        return [type,member];
      }
      throw Error('unsupported declared type '+type);
    }
    function record(type,member) {
      if (typeof member !== 'object' || Array.isArray(member)) throw Error('a record is expected for '+type);
      const id = identity++, element = type === 'record[string]' ? 'string' : 'unknown', entries = [];
      for (const name of Object.keys(member)) entries[entries.length] = [name,wire(element,member[name])];
      // The codec reads a record in the issued key order, so the rebuilt entries keep it.
      const order = rt.objectKeys(new Map(entries.map(pair => [pair[0],null])));
      entries.sort((left,right) => order.indexOf(left[0])-order.indexOf(right[0]));
      return ['record',id,type,entries];
    }
    return codec.decode({schemaDigest:digest,value:wire(descriptor,value)},descriptor);
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
        if(copiedState !== null) {
          // A live refinement hands the copied authoritative record to the replacement's own restore:
          // it overlays the replacement's initial persisted projection, so an introduced field keeps
          // its compiled initializer and the candidate's first tree is validated from the state the
          // user will actually see instead of from fresh initialization.
          if(app === null || app === undefined) throw Error('PERSISTENCE_ROLES: a refined candidate needs the replacement\'s issued state roles');
          // The persisted projection is a nominal record the copy may not reach through JSON, so the
          // compiled role's own record is overlaid and the codec is asked for a value of the declared
          // type per copied field: a JSON round-trip of the record itself yields `{}`.
          const digest=call('bridgeSchemaDigest'), codec=createDealUiCheckpoint(rt,checkpointSchema,digest);
          // The compiled role projects the replacement's own authoritative record afresh on every call,
          // so the copy overlays that record directly.
          const projection=app.bridgePersistedState.$f(call('bridgeInitialState'));
          const declaredFields=declaredState(app);
          if(declaredFields === null) throw Error('PERSISTENCE_SCHEMA: the replacement issues no compiled state declaration');
          const copied=JSON.parse(copiedState);
          for(const key of Object.keys(copied)) {
            // The copy is by emitted record key and by the exact resolved type the replacement declares:
            // a field the replacement does not persist is a located incompatibility of the replacement,
            // never a silently dropped value, and a value that does not fit its declaration is the same
            // located rejection instead of a runtime fault in the compiled restore.
            if(!projection.has(key)) throw Error('PERSISTENCE_SCHEMA: copied state names a field the replacement does not persist: '+key);
            let field=null;
            for(const candidate of declaredFields) if(candidate.name === key) field=candidate;
            if(field === null) throw Error('PERSISTENCE_SCHEMA: copied state names a field the replacement declares no type for: '+key);
            try { projection.set(key,typedCopied(codec,digest,field.descriptor,copied[key])); }
            catch(error) { throw Error('PERSISTENCE_SCHEMA: copied state does not fit the replacement declaration of '+key+': '+(error.message||String(error))); }
          }
          initial=app.bridgeRestoredState.$f(projection);
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
