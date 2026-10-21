# Portable UI

`platform.dealui-pack` is the authoritative component contract. `build.sh` packages these exact bytes at `embedding/platform.dealui-pack`; authored and saved views keep importing `./platform.dealui-pack`. Android assets must not maintain another pack.

`composition.md` and `generation-guidance.md` describe portable composition and accepted authoring syntax. Build tooling extracts component/property/default/event/typed-child facts from this pack into generation guidance. The native renderer implements capabilities; it does not infer domain conclusions or duplicate compiler composition checks. Legacy `renderer.compose.*` identities remain for compatibility; additions use platform-neutral identities.

## Enrichment

- `Row(minimumChildWidth: 240)` arranges equal-width cells and wraps to fewer columns at narrow widths or large font scales, preserving checked child identity. Existing Column remains a vertical reading sequence.
- Heading and Caption express hierarchy through backend typography. PrimaryAction and SecondaryAction use the existing enabled/no-payload action boundary.
- Money requires a label and integer minorUnits; currency defaults to EUR. Formatting follows currency decimal precision and device locale. Negative values remain negative.
- Metric requires a label and explicit value text; unit is separate.
- Comparison requires explicit status text and Money/Metric children. The existing Deal UI checker enforces its typed-child contract through views, conditions and keyed loops. DEAL owns arithmetic, validation and the supplied conclusion; the backend cannot infer winners or over/under status.

The source pairs are framework examples, not provider facts, app workflows or templates selected at runtime:

- `comparison`: editable amounts, explicit bounds, DEAL over/under/equal transitions, payloads and reset.
- `rows`: immutable keyed-row rebuilds, captured row IDs, reorder/removal and stale-event rejection.
- `values`: distinct hire alternatives/deposit and travel metrics, including keyed typed children.
- `generation-example`: example-only async capability; initial empty, explicit read, pending, success, failure and retry. `host/sample` is not a production capability.

## Verification

From the consuming workspace, build the library/test APK, then run `tools/verify-toolkit.py`. It uses `tools/ToolkitHarness.java`, the current UI/DEAL compiler, production JS loader/session and mocked provider replies. It checks exact packaged bytes, current source, required props, dynamic typed children, actions, bounds, state restoration and asynchronous states. Receipts include revisions and exact input hashes under ignored build output.

`ToolkitInstrumentation` exercises exact packaged examples through ApplicationProtocol and Android's real sandbox. `ToolkitActivity` is a test-APK-only gallery using ExperienceSurface, not a consumer-owned session driver. Native controls, device outcomes, saved-source migration, live generation and visual review are distinct checks. Host success is not native visual approval; an authored example is not fresh-model adherence.

No public protocol change, discovery change, provider read during checking, consumer Kotlin widget or task-specific backend calculation is required.
