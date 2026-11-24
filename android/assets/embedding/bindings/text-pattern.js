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

// Called on candidate trees before initial publication or transition commit/effect dispatch.
// This is boundary validation only, not a second Session or a recovery policy.
globalThis.validateRendererValues = function(tree) {
  function visit(node) {
    const component = node.component.split('.').pop();
    const prop = name => node.props.find(value => value.name === name);
    if (component === 'TextField') {
      const pattern = prop('pattern');
      if (pattern && pattern.stringValue && !JSON.parse(textPattern(pattern.stringValue, '')).valid)
        throw Error('PROP_FAULT: TextField.pattern: invalid ECMAScript Unicode expression');
    }
    if (component === 'TimeField' || component === 'ClockText' || component === 'DateField') {
      const name = component === 'ClockText' ? 'minutes' : 'value';
      const value = prop(name)?.numberValue;
      if (!Number.isInteger(value) || (component !== 'DateField' && (value < 0 || value > 1439)))
        throw Error('PROP_FAULT: ' + component + '.' + name + ': invalid shared temporal domain');
    }
    node.children.forEach(visit);
  }
  visit(tree);
};
