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
  return dealUi.snapshot();
};
