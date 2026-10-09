"use strict";
globalThis.initializeLogger = factories => {
  const {entry} = dealLoad(factories, 'logging.js');
  globalThis.normalizeLog = input => entry.normalize.$f(entry['LogEvent$new'](input));
  return '';
};
