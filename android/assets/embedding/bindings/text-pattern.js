"use strict";
// Presentation/boundary mechanism. No Java-regex dialect or portable subset.
globalThis.textPattern = function(pattern, text) {
  try {
    new RegExp(pattern, "u"); // Validate before wrapping, so unmatched groups stay invalid.
    const expression = new RegExp("^(?:" + pattern + ")(?![\\s\\S])", "u");
    return JSON.stringify({valid:true, matches:expression.test(text)});
  } catch (error) {
    if (!(error instanceof SyntaxError)) throw error;
    return JSON.stringify({valid:false, matches:false});
  }
};
