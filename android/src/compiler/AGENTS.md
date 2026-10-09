# Compiler

Use the DEAL and Deal UI compilers. Instrument each invocation separately through the unified DEAL logger, including outcome and bounded diagnostic artifacts when capture is enabled; no independent compiler log sink. Limit generated application externals to published application declarations.

Check against the supplied renderer pack. Reject pack/renderer disagreement and unsupported effect features.

Compile in candidate staging. Remove failed staging and return candidate diagnostics with local paths removed. Include diagnostic details in rejection exceptions so no-model checking and replay preserve the cause.

A nonzero compiler status alone is not candidate rejection. Repair only structured source diagnostics attributed to the generated application; stop on missing/malformed reports, project configuration, framework/declaration diagnostics, backend or publication failures. Do not expose native paths in operational errors.

Keep checking distinct from native authorization and activation. Preserve existing compiler and Deal UI semantics when changing the embedding path.
