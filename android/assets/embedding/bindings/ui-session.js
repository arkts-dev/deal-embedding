"use strict";
// Module loading, Promise scheduling and the persistence bridge. Deal UI owns the store and tree;
// the persisted record is the compiled authoritative projection only. Restore runs the codec's
// digest/tag checks, the compiled nominal allocation and the compiled recompute before Deal UI
// publishes the first tree. No inference, provider call or effect runs for restore.
globalThis.mountDealUi = function(factories, entryId, persistedState = null, appModule = null, prepare = null, clockDrivers = null, readingCarriers = null, triggerRoutesIn = null, activationRoutesIn = null, expiryRouteIn = null) {
  const {entry, rt, checkpointSchema, app} = dealLoad(factories, entryId, appModule);
  const scheduleEffect = (invoke, complete, fail, exited) => {
    // Start immediately after commit; synchronous throws follow the same exit accounting. The host
    // publishes its millisecond wall reading for the completion before the completion is enqueued, so
    // the compiled policy stamps the answer with the instant the call really returned instead of a
    // reading taken a second earlier.
    try { Promise.resolve(invoke()).then(result => { publishReading(); complete(result); }).catch(fail).finally(exited); }
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
  // The emitted hook is compiler-owned policy; this binding only applies it before mount. A
  // composition may carry several clock owners, so every distinct issued restore identity runs once:
  // one owner's normalization never leaves another owner's clock running.
  const restoreNames = drivers.map(driver => driver.restore).filter(name => typeof name === 'string' && name !== '');
  if (state !== null) {
    const applied = Object.create(null);
    for (const restoreName of restoreNames) {
      if (applied[restoreName]) continue;
      applied[restoreName] = true;
      // A restored state always loads the compiled app module (a saved record without its issued roles
      // is refused above and a prepared state carries no driver), so the hook comes from that one
      // module cache and nominal identity is never duplicated. A missing hook fails closed.
      const hook = app === null || app === undefined ? undefined : app[restoreName];
      if (!hook || hook.$kind !== 'function') throw Error('Missing issued clock restore identity: ' + restoreName);
      state = hook.$f(state);
    }
  }
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
  // The private reading carriers this composition issued: a Session publishes one host millisecond wall
  // reading into the compiled field they bind. The carriers are resolved from the rendered tree by their
  // issued identities; a missing or duplicated one is never dispatched, so a model-authored node cannot
  // stand in for the reading. `publishReading` runs for the press the host is about to admit and for the
  // completion it is about to deliver, and never from a renderer event or on an interval.
  const readingIds = Array.isArray(readingCarriers) ? readingCarriers : [];
  const publishReading = () => {
    if (readingIds.length === 0) return;
    const wall = Date.now();
    if (typeof wall !== 'number' || !Number.isFinite(wall) || wall < 0) return;
    const slots = actionSlots();
    for (const identity of readingIds) {
      const slot = slots[identity];
      if (!Number.isInteger(slot) || slot < 0) continue;
      // A reading is bookkeeping about the action that follows it, so a refused publish never blocks
      // the action or loses a produced answer.
      try { session.dispatch(slot, String(wall)); } catch (_) {}
    }
  };
  // A press admits with the host reading taken for it; an edit changes a value and reads no reading.
  const pressEvents = ['onClick', 'onTap', 'onAction', 'onBack'];
  // The host-facing session publishes the reading for the press it is about to admit. The binding's own
  // driver dispatches use the inner session, so a host-driven action never publishes a reading of its
  // own.
  globalThis.dealUi = Object.freeze({...session, dispatch: (slot, payload = null) => {
    if (readingIds.length > 0 && pressEvents.includes(actionEvents()[slot])) publishReading();
    return session.dispatch(slot, payload);
  }});
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
  // The event each rendered action slot was bound to. A value edit (a text, number, slider or selection
  // change) carries no press: publishing a reading before it would commit a transition nobody reads,
  // while a press is the action whose compiled policy decides its windows from the instant it happened.
  const actionEvents = () => {
    const tree = JSON.parse(session.snapshot()).tree;
    const found = Object.create(null);
    (function visit(node) {
      for (const prop of node.props) {
        if (prop.kind !== 'action' || !Number.isInteger(prop.actionSlot)) continue;
        found[prop.actionSlot] = Object.hasOwn(found, prop.actionSlot) ? '' : prop.name;
      }
      for (const child of node.children) visit(child);
    })(tree);
    return found;
  };
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
  // state, and the exact text it read, so a running clock still advances during a provider call and a
  // press intent recorded in that window is admitted as soon as the state is readable again, at the
  // reading prepared at admission. The text is the business state's own identity: a driver uses it to
  // tell a transition that really changed the state from a lane tick that changed nothing.
  const clockText = () => { try { const text = session.state(); return typeof text === 'string' ? text : null; } catch (_) { return null; } };
  const parsed = text => { try { return JSON.parse(text); } catch (_) { return null; } };
  let cachedText = clockText();
  let cached = cachedText === null ? null : parsed(cachedText);
  // Always attempt a fresh read: state is unavailable while an effect is in flight, and a press
  // recorded in that window is admitted by the next pump that can read it. `checkPress` documents the
  // dispatch path (the forced pump) but never withholds a refresh.
  const stateNow = checkPress => {
    const fresh = clockText();
    if (fresh !== null && fresh !== cachedText) { cachedText = fresh; cached = parsed(fresh); }
    return cached;
  };
  // The issued trigger routes: one ordered queue per checked clock-end or entry-added slot, the issued
  // carrier whose bound action consumes its head, and the operation whose admission records the captured
  // event. The activation routes are the authorized initial reads, and the expiry route is the
  // independently scheduled deadline transition. All three are compiler metadata: a rendered node that
  // does not carry the issued identity is never dispatched.
  const triggerRoutes = Array.isArray(triggerRoutesIn) ? triggerRoutesIn : [];
  const activationRoutes = Array.isArray(activationRoutesIn) ? activationRoutesIn : [];
  const expiryRoute = expiryRouteIn !== null && typeof expiryRouteIn === 'object' ? expiryRouteIn : null;
  const carrierSlot = identity => {
    const slot = actionSlots()[identity];
    return Number.isInteger(slot) && slot >= 0 ? slot : -1;
  };
  // An authorized initial read is one admitted action: the compiled reducer clears the authoritative
  // activation-needed marker before its effect starts, so a queued duplicate dispatch is a guarded no-op
  // and no later tick repeats the read. A dispatch the state cannot accept leaves the marker for the next
  // driver tick instead of losing the read.
  globalThis.dealActivate = function(monotone) {
    if (activationRoutes.length === 0) return '';
    const state = stateNow(false);
    if (state === null) return '';
    for (const route of activationRoutes) {
      if (state[route.field] !== true) continue;
      const slot = carrierSlot(route.carrier);
      if (slot < 0) continue;
      try { session.dispatch(slot, String(monotone)); } catch (_) {}
    }
    return '';
  };
  // Trigger queues drain head-first. Each dispatch carries the captured event identity of the head and the
  // compiled admission consumes it only when it admits that event; a refused admission retains the event,
  // so the driver stops at the head it could not consume rather than skipping past it. A head the state
  // did not admit at its current business state is not dispatched again: nothing changed, so a second
  // dispatch would commit a revision that carries no business change. It waits for the state to change
  // again, which is exactly when an unready argument or a pending operation becomes admissible.
  const refusedHead = Object.create(null);
  globalThis.dealTriggers = function() {
    if (triggerRoutes.length === 0) return '';
    for (const route of triggerRoutes) {
      const slot = carrierSlot(route.carrier);
      for (let guard = 0; guard < 64; guard++) {
        const state = stateNow(false);
        if (state === null) break;
        const queue = state[route.queue];
        if (!Array.isArray(queue) || queue.length === 0) { delete refusedHead[route.queue]; break; }
        const head = queue[0];
        if (head === null || typeof head !== 'object' || typeof head.event !== 'number' || !Number.isFinite(head.event)) break;
        if (slot < 0) break;
        const refused = refusedHead[route.queue];
        if (refused !== undefined && refused.event === head.event && refused.state === cachedText) break;
        session.dispatch(slot, String(head.event));
        const after = stateNow(false);
        if (after === null) break;
        const remaining = after[route.queue];
        if (!Array.isArray(remaining) || remaining.length >= queue.length) { refusedHead[route.queue] = {event: head.event, state: cachedText}; break; }
        delete refusedHead[route.queue];
      }
    }
    return '';
  };
  // The expiry transition is scheduled by the Session's own lane and is dispatched only for a deadline
  // the reading that lane took has reached, so it progresses while detached and costs no revision, no
  // recompute and no render while every recorded deadline is still ahead. An undecidable reading is not
  // the absence of a deadline: the compiled transition expires an operation that has one.
  globalThis.dealExpire = function(monotone) {
    if (expiryRoute === null || !Array.isArray(expiryRoute.deadlines) || expiryRoute.deadlines.length === 0) return '';
    const state = stateNow(false);
    if (state === null) return '';
    let due = false;
    for (const pair of expiryRoute.deadlines) {
      const at = state[pair.expires];
      if (typeof at !== 'number' || at === 0 || state[pair.expired] !== false) continue;
      if (!Number.isFinite(monotone) || monotone >= at) due = true;
    }
    if (!due) return '';
    const slot = carrierSlot(expiryRoute.carrier);
    if (slot < 0) return '';
    session.dispatch(slot, String(monotone));
    return '';
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
        session.dispatch(slot,String(reading));
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
    visit(JSON.parse(session.snapshot()).tree);
    for(const slot of slots)session.dispatch(slot,String(delta));
    return '';
  };
  return session.snapshot();
};
