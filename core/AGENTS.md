# Core

Keep input construction and diagnostic repair in `generation.deal`. Backends implement the imported `host/*.d.deal` APIs. Rejected template source is a deterministic defect, not a source-generation repair opportunity; return its checker diagnostics without further model calls.

Treat those declarations as canonical signatures and `host/check-result.json` as the canonical checker-result wire schema. Keep native replies, core field reads and generated binding metadata aligned with them.

Restrict the core's host imports to trusted orchestration. Generation returns checked candidate identities; hosts validate disclosed context and catalog revision before activation.

Keep `generation-guidance.md` aligned with accepted DEAL syntax, Deal UI semantics and the renderer pack. Preserve intent-driven workflow generation and immutable state updates.
