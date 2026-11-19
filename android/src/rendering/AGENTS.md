# Rendering

[spec-v0.8](../../../docs/spec-v0.8.md) is the sole embedding authority; current renderer behavior, pack defaults and examples are subordinate.

Render checked trees and deliver typed input through compiled actions. Generate renderer metadata from the reconciled pack; preserve keys, nominal payloads, numeric values and Unicode. Do not infer domain conclusions or rewrite accepted text. Numeric presentation follows the shared `decimals`/`minimumDigits` field rule: automatic decimals use the shortest round-tripping decimal, fixed decimals round half away from zero, the integer part pads with leading zeros, and affixes are added unchanged.

Native rendering owns layout, drawing and presentation resources, not business state, admission, recovery or computational clocks. Separate rendering/publication from execution progress. Diagnostic hooks and observation failures must not change application outcomes.
