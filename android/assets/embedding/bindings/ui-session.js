"use strict";
// Module loading, Promise scheduling and the persistence bridge. Deal UI owns the store and tree;
// the persisted record is the compiled authoritative projection only. Restore runs the codec's
// digest/tag checks, the compiled nominal allocation and the compiled recompute before Deal UI
// publishes the first tree. No inference, provider call or effect runs for restore.
globalThis.mountDealUi = function(factories, entryId, persistedState = null, appModule = null, prepare = null, clockDrivers = null) {
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
  // Private clock driver. The host supplies paired adapter readings; this mechanism resolves the
  // compiler-issued ticker identities from the rendered tree and dispatches the prepared reading to
  // the compiled actions. It never computes elapsed, completion or trust, and a renderer event never
  // starts it.
  const drivers = Array.isArray(clockDrivers) ? clockDrivers : [];
  // Cold restore normalization: a restored clock resumes paused with the runtime sampling anchors,
  // prepared reading and press intent cleared, while authoritative elapsed/completed are retained.
  // The emitted hook is compiler-owned policy; this binding only applies it before mount.
  const restoreName = drivers.length > 0 ? drivers[0].restore : null;
  if (state !== null && typeof restoreName === 'string' && restoreName !== '') {
    // A restored state always loads the compiled app module (a saved record without its issued roles is
    // refused above and a prepared state carries no driver), so the hook comes from that one module
    // cache and nominal identity is never duplicated. A missing hook fails closed.
    const hook = app === null || app === undefined ? undefined : app[restoreName];
    if (!hook || hook.$kind !== 'function') throw Error('Missing issued clock restore identity: ' + restoreName);
    state = hook.$f(state);
  }
  const restored = state === null ? null : codec.encode(state);
  const session = createDealUiSession(entry, rt, restored, scheduleEffect, checkpointSchema);
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
  const lastTick = Object.create(null);
  const actionSlots = () => {
    const tree = JSON.parse(session.snapshot()).tree;
    const found = Object.create(null);
    (function visit(node) {
      let id = null, slot = null;
      for (const prop of node.props) {
        if (prop.name === 'id' && prop.kind === 'string') id = prop.stringValue;
        if (prop.name === 'onTick' && prop.kind === 'action') slot = prop.actionSlot;
      }
      // Only a Ticker carrier is a driver slot, and a duplicated issued identity is ambiguous: it is
      // never dispatched rather than letting a model-authored node hijack the private driver.
      if (id !== null && slot !== null && node.component.split('.').pop() === 'Ticker') {
        if (Object.hasOwn(found, id)) found[id] = -1; else found[id] = slot;
      }
      for (const child of node.children) visit(child);
    })(tree);
    return found;
  };
  // The session only exposes state while no effect is in flight. The driver keeps the last readable
  // state so a running clock still advances during a provider call; a press intent recorded in that
  // window is admitted as soon as the state is readable again, at the reading prepared at admission.
  const clockState = () => { try { return JSON.parse(session.state()); } catch (_) { return null; } };
  let cached = clockState();
  // Always attempt a fresh read: state is unavailable while an effect is in flight, and a press
  // recorded in that window is admitted by the next pump that can read it. `checkPress` documents the
  // dispatch path (the forced pump) but never withholds a refresh.
  const stateNow = checkPress => {
    const fresh = clockState();
    if (fresh !== null) cached = fresh;
    return cached;
  };
  globalThis.dealClockPump = function(monotone, wall, checkPress) {
    if (drivers.length === 0) return '';
    let state = stateNow(checkPress === true);
    if (state === null) return '';
    // A press records only its intent; admit the control with the paired reading taken for this press.
    // The prepared monotone sample is published first and the control then carries the wall reading of
    // that same pair, so compiled policy compares both readings instead of trusting the epoch the
    // suspended interval or a wall-clock step left behind. A missing carrier admits nothing: no stale
    // sample and no inferred wall delta ever stand in for the reading that was not taken.
    for (const driver of drivers) {
      const request = state[driver.request];
      if (typeof request !== 'number' || request === 0) continue;
      const key = request === 1 ? 'start' : request === 2 ? 'pause' : request === 3 ? 'reset' : null;
      if (key === null) continue;
      const slots = actionSlots();
      const slot = slots[driver[key]];
      const sampleSlot = slots[driver.sample];
      if (!Number.isInteger(slot) || slot < 0 || !Number.isInteger(sampleSlot) || sampleSlot < 0) continue;
      session.dispatch(sampleSlot, String(monotone));
      session.dispatch(slot, String(wall));
      state = stateNow(true) || state;
      // The admitted press reading is the anchor of the run it starts, so the next tick counts only
      // progress after it; a paused clock schedules nothing.
      lastTick[driver.need] = state[driver.running] === true ? monotone : -1;
    }
    // The host interval schedules sampling; the admitted tick carries the actual monotone progress,
    // so a delayed or coalesced callback reports the elapsed time that really passed.
    for (const driver of drivers) {
      if (state[driver.running] !== true) { lastTick[driver.need] = -1; continue; }
      const previous = lastTick[driver.need];
      if (previous === undefined || previous < 0) { lastTick[driver.need] = monotone; continue; }
      if (monotone - previous < driver.intervalMs) continue;
      const slots = actionSlots();
      const sampleSlot = slots[driver.sample], tickSlot = slots[driver.tick];
      if (!Number.isInteger(sampleSlot) || sampleSlot < 0 || !Number.isInteger(tickSlot) || tickSlot < 0) continue;
      session.dispatch(sampleSlot, String(monotone));
      session.dispatch(tickSlot, String(wall));
      lastTick[driver.need] = monotone;
      state = stateNow(false) || state;
    }
    return '';
  };
  let clock=entry.bridgeFrameVisibility.$f(false);
  let visible=false;
  // A Session resume (a hidden surface becoming visible again) is not a renderer event: the issued
  // resume carrier keeps the authoritative run, anchors every clock at the reading the host took for
  // the resume and advances the Session trust epoch, so evidence captured before the suspend is never
  // re-trusted and the suspended interval is never counted.
  globalThis.setFrameVisibility = (active, monotone) => {
    clock=entry.bridgeFrameVisibility.$f(active);
    const resumed=active===true&&!visible;
    if(resumed&&drivers.length>0){
      // A missing or nonfinite host reading is not a reading: the compiled policy then refuses the
      // anchor and publishes its located reason instead of accepting a placeholder.
      const reading=(typeof monotone==='number'&&Number.isFinite(monotone))?monotone:-1;
      const slots=actionSlots(),dispatched=Object.create(null);
      for(const driver of drivers)lastTick[driver.need]=-1;
      // One Session resume is one admitted action: every driver names the same carrier, so the reading
      // is dispatched once for the composition, never once per clock.
      for(const driver of drivers){
        const slot=slots[driver.resume];
        if(!Number.isInteger(slot)||slot<0||dispatched[slot])continue;
        dispatched[slot]=true;
        dealUi.dispatch(slot,String(reading));
      }
    }
    // The transition counts only once the resume it describes has been admitted, so a failed dispatch
    // is retried by the next visible transition instead of being recorded as done.
    visible=active===true;
    return '';
  };
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
