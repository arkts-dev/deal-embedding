"use strict";
// Binary64 conversion primitives. Lexical acceptance and presentation policy
// live in portable-presentation.deal; no task/domain decisions are made here.
exports.parseDecimal = value => Number(value);
exports.fixed = (value, places) => value.toFixed(places);
exports.numberText = value => String(value);
exports.remainder = (value, divisor) => value % divisor;
exports.referenceLength = value => {
  if (typeof value === 'string' || Array.isArray(value)) return value.length;
  // DEAL JSON arrays use validated 1-based Map keys.
  if (value instanceof Map) {
    let length = 0;
    for (const key of value.keys()) { if (typeof key !== 'string' || !/^[1-9][0-9]*$/.test(key)) throw Error('Expected reference string/array'); length = Math.max(length, Number(key)); }
    if (length !== value.size) throw Error('Expected contiguous reference array');
    return length;
  }
  throw Error('Expected reference string/array');
};
exports.contains = (value, part) => value.includes(part);
