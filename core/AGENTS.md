# Core

Keep input construction and diagnostic repair in `generation.deal`. Backends implement the imported `host/*.d.deal` APIs. Rejected template source is a deterministic defect, not a source-generation repair opportunity; return its checker diagnostics without further model calls.

Keep catalogue eligibility, issued choices and answer validation in `choice-policy.deal`, and source synthesis/presentation/descriptor policy in `catalogue-source.deal`. Preserve the demonstrated catalogue behavior; do not add speculative scenarios. `source-literals.deal` owns runtime DEAL literal escaping. The policy-data import provides JSON shape conversion and Unicode lowercase, not policy. The observer import accepts bounded stage labels/codes, not raw user/provider content.

Keep envelope validation in `candidate-check.deal`; call the native checker with two validated source strings. Native candidate rejection supplies diagnostics; unexpected compiler/resource failures propagate without repair.

Keep composition and prompt formatting in `generation-guidance.deal`. Generated guidance contains asset text and pack AST facts only; build tooling must not select example arguments or composition rules.

Treat those declarations as canonical signatures and `host/check-result.json` as the canonical checker-result wire schema. Keep native replies, core field reads and generated binding metadata aligned with them.

Restrict the core's host imports to trusted orchestration. Generation returns checked candidate identities; hosts validate disclosed context and catalog revision before activation.

Keep `generation-guidance.md` aligned with accepted DEAL syntax, Deal UI semantics and the renderer pack. Preserve intent-driven workflow generation and immutable state updates.
