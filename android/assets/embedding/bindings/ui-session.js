"use strict";
// Module loading, Promise scheduling and the persistence bridge. Deal UI owns the store and tree;
// the persisted record is the compiled authoritative projection only. Restore runs the codec's
// digest/tag checks, the compiled nominal allocation and the compiled recompute before Deal UI
// publishes the first tree. No inference, provider call or effect runs for restore.
globalThis.mountDealUi = function(factories, entryId, persistedState = null, appModule = null, prepare = null) {
  const {entry, rt, checkpointSchema, app} = dealLoad(factories, entryId, appModule);
  const scheduleEffect = (invoke, complete, fail, exited) => {
    // Start immediately after commit; synchronous throws follow the same exit accounting.
    try { Promise.resolve(invoke()).then(complete).catch(fail).finally(exited); }
    catch (error) { try { fail(error); } finally { exited(); } }
  };
  const role = name => {
    const exported = app === null || app === undefined ? undefined : app[name];
    if (exported === undefined || exported.$kind !== 'function') throw Error('PERSISTENCE_ROLES: compiled source issues no authoritative state roles');
    return exported.$f;
  };
  const codec = createDealUiCheckpoint(rt, checkpointSchema, entry.bridgeSchemaDigest.$f());
  // A retaining host declares that this Experience's state must survive. The compiled app module
  // then owns the issued role facts: without them no authoritative record can be written or read,
  // so admission fails closed instead of faulting after a committed action.
  if (appModule !== null) { role('bridgeRestoredState'); role('bridgePersistedState'); }
  let state = null;
  // A saved record without issued roles is an incompatibility: reject it, keep the saved bytes.
  if (persistedState !== null) state = role('bridgeRestoredState')(codec.decode(persistedState, 'record'));
  else if (prepare !== null) state = prepare(entry.bridgeInitialState.$f(), rt, entry);
  // Mount input only: Deal UI decodes this typed state before it publishes any tree.
  const restored = state === null ? null : codec.encode(state);
  // Decorate only the tree-producing ABI calls; the pinned Session remains the sole store,
  // transition/rollback and effect authority. A fault throws before it assigns candidate state.
  const checkedEntry = {...entry};
  for (const name of ['initial', 'transitionFromCandidate']) {
    const original = entry[name];
    checkedEntry[name] = {...original, $f: (...args) => {
      const transition = original.$f(...args);
      validateRendererValues(transition.tree);
      return transition;
    }};
  }
  const session = createDealUiSession(checkedEntry, rt, restored, scheduleEffect, checkpointSchema);
  globalThis.dealUi = session;
  let projectedFrom = null, projectedValue = null;
  globalThis.persistDealUi = function() {
    // Deal UI refuses a checkpoint while an effect is in flight: a pending invocation is never saved.
    const current = session.checkpoint();
    if (current === null) return null;
    if (current === projectedFrom) return projectedValue;
    const live = codec.decode(JSON.parse(current), entry.bridgeInitialState.$sig.substring(4));
    const value = JSON.stringify(codec.encode(role('bridgePersistedState')(live)));
    // Cache only after the projection succeeded, so a refused state never yields an older record.
    projectedFrom = current; projectedValue = value;
    return value;
  };
  let clock=entry.bridgeFrameVisibility.$f(false);
  globalThis.setFrameVisibility = active => { clock=entry.bridgeFrameVisibility.$f(active);return ''; };
  globalThis.advanceFrames = now => {
    clock=entry.bridgeFrameSample.$f(clock,now);
    if (!clock.visible) return '';
    const delta=clock.delta;
    const slots=[];
    function visit(node){if(node.component.split('.').pop()==='Surface')for(const prop of node.props)if(prop.kind==='action'&&prop.name==='onFrame')slots.push(prop.actionSlot);for(const child of node.children)visit(child);}
    visit(JSON.parse(dealUi.snapshot()).tree);
    for(const slot of slots)dealUi.dispatch(slot,String(delta));
    return '';
  };
  return session.snapshot();
};
