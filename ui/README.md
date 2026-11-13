# UI assets

[spec-v0.8](../docs/spec-v0.8.md) is the sole embedding authority. The pack, schemas, guidance and examples are implementation artifacts, not competing specifications. They require alignment; existing component names/defaults are not requirements.

- `platform.dealui-pack`: compiler/renderer component metadata.
- `fragments/`: authored DEAL behavior, views and support code.
- `generation-guidance.md`, `composition.md`, schemas and examples: subordinate authoring material.

`build.sh` packages the pack at `embedding/platform.dealui-pack`; authored imports use `./platform.dealui-pack`. Generate projections rather than maintaining Android copies. Test checked transport and production rendering separately from generation quality; fixtures are not proof of v0.8 compliance.
