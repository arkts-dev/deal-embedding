# Compiler adapters

[spec-v0.8](../../../docs/spec-v0.8.md) is the sole embedding authority; existing compiler adapters, schemas and fixtures are subordinate.

Use matched DEAL/Deal UI compiler, runtime, standard library and manifests. Library inputs exclude application helpers. Transform parsed syntax through compiler APIs, not substring replacement; composition policy belongs in compiled DEAL.

Compile/check in staging. Preserve exact submitted bytes, structured diagnostics and cleanup failures; remove private local paths from public evidence. Distinguish source rejection from infrastructure/resource failures. Compilation alone does not establish required execution checks or semantic completion.
