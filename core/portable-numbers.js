"use strict";
// Binary64 conversion primitives. Lexical acceptance and presentation policy
// live in portable-presentation.deal; no task/domain decisions are made here.
exports.parseDecimal = value => Number(value);
exports.fixed = (value, places) => value.toFixed(places);
exports.numberText = value => String(value);
exports.remainder = (value, divisor) => value % divisor;
