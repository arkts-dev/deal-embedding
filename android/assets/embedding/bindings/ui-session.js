"use strict";
// Module loading and Promise scheduling only. Lifecycle is owned by Deal UI.
globalThis.mountDealUi = function(factories, entryId, restoredState = null) {
  const {entry, rt} = dealLoad(factories, entryId);
  const scheduleEffect = (invoke, complete, fail, exited) => {
    // Start immediately after commit; synchronous throws follow the same exit accounting.
    try { Promise.resolve(invoke()).then(complete).catch(fail).finally(exited); }
    catch (error) { try { fail(error); } finally { exited(); } }
  };
  globalThis.dealUi = createDealUiSession(entry, rt, restoredState, scheduleEffect);
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
  return dealUi.snapshot();
};
