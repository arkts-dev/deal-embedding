# Compiler

Use the current DEAL 1.4 and Deal UI compilers and matched runtime/stdlib assets. Manifest versions must match the compiler; do not retain an old compiler lane for saved generated source. Instrument each invocation separately through the unified DEAL logger, including outcome and bounded diagnostic artifacts when capture is enabled; no independent compiler log sink. Limit generated application externals to published application declarations.

Stage only the authoritative checked `ui/values` helper module from the matched distribution; do not inject app-specific helpers or copy generated source into a library. Check against the supplied renderer pack. Reject pack/renderer disagreement and unsupported effect features.

Compile in candidate staging. Remove failed staging and return candidate diagnostics with local paths removed. Include diagnostic details in rejection exceptions so no-model checking and replay preserve the cause. Attach cleanup failures to the exception actually thrown after diagnostic translation, not to a discarded diagnostic.

A nonzero compiler status alone is not candidate rejection. Repair only structured source diagnostics attributed to the generated application; stop on missing/malformed reports, project configuration, framework/declaration diagnostics, backend or publication failures. Do not expose native paths in operational errors.

Collect compiler phase receipts through the invocation-scoped compiler observer and unified logger; preserve failure-path timings, isolate concurrent invocations and never change acceptance based on telemetry. Parent codegen totals overlap lowering/emission children; do not add them as independent time.

Keep checking distinct from native authorization and activation. Preserve existing compiler and Deal UI semantics when changing the embedding path.
