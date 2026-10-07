"use strict";
// Host transport for compiler-issued functions. No application transitions live here.
globalThis.mountDealUi = function(factories, entryId, restoredState = null) {
  const {entry, rt} = dealLoad(factories, entryId);
  const call = (name, ...args) => {
    const fn = entry[name];
    if (!fn || fn.$kind !== 'function') throw Error('Missing checked UI export: ' + name);
    return fn.$f(...args);
  };
  let state = call('bridgeInitialState');
  if (restoredState !== null) state = rt.checkType(entry.bridgeInitialState.$sig.substring(4), Object.assign(state, restoredState));
  let store = call('initialStore');
  let current = call('initial', state, store);
  store = current.store;
  let disposed = false, draining = false, inFlight = 0;
  function visibleSlot(node, slot) {
    return node.props.some(prop => prop.kind === 'action' && prop.actionSlot === slot) || node.children.some(child => visibleSlot(child, slot));
  }
  let fault = '';
  let version = 1;
  function enqueue(action, completion = false) {
    if (disposed) return;
    const queued = call(completion ? 'complete' : 'enqueue', store, action);
    store = queued.store;
    if (queued.accepted && !draining) drain();
  }
  function drain() {
    draining = true;
    try {
      while (!disposed) {
        const next = call('dequeue', store);
        store = next.store;
        if (!next.present) break;
        try {
          const candidate = call('nextState', state, next.action);
          const transition = call('transitionFromCandidate', candidate, current.tree, store, next.action);
          state = candidate; current = transition; store = transition.store; version++;
          if (transition.effect.effectId >= 0) {
            const snapshot = state, action = next.action;
            inFlight++;
            Promise.resolve(call('bridgeEffect' + transition.effect.effectId, snapshot, action))
              .then(result => enqueue(result, true))
              .catch(error => { if (!disposed) { fault = rt.reifyError(error).message || String(error); version++; } })
              .finally(() => { inFlight--; });
          }
        } catch (error) { store = call('reject', store); fault = String(error); }
      }
      store = call('finish', store);
    } finally { draining = false; }
  }
  globalThis.dealUi = Object.freeze({
    snapshot: () => JSON.stringify({tree: current.tree, version, fault}),
    state: () => { if (inFlight) throw Error('Wait for current work before replacing the experience'); return JSON.stringify(state); },
    dispatch: (slot, payload = null) => {
      if (!Number.isInteger(slot) || slot < 0 || !visibleSlot(current.tree, slot)) throw Error('Invalid or stale action slot');
      // Payload type comes from the checked action signature, never from the caller.
      const declared = call('payloadType_' + slot);
      let value = payload;
      if (declared === 'int') { if (!/^-?\d+$/.test(String(payload))) throw Error('Expected an integer payload'); value = parseInt(payload, 10); }
      else if (declared === 'boolean') { if (payload !== 'true' && payload !== 'false') throw Error('Expected a boolean payload'); value = payload === 'true'; }
      else if (declared === 'string') { if (typeof payload !== 'string') throw Error('Expected a string payload'); }
      else if (payload !== null) throw Error('This action takes no payload');
      enqueue(payload === null && declared === 'none' ? call('action_' + slot) : call('action_' + slot, value));
      return '';
    },
    dispose: () => { disposed = true; store = call('dispose', store); return ''; }
  });
  return dealUi.snapshot();
};
