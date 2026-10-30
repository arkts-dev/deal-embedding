# Android/JavaScript protocol

Public signatures: embedding.d.deal and app-linked catalogue-policy.d.deal. DEAL v1.5 async externals use Promise<R> without async. ProtocolController bootstraps trusted compiled consumers; Embedding supplies Android mechanisms; ApplicationProtocol links lifecycle/render inputs. Compiler-derived bindings preserve nominal identity. Generated UI cannot import this administration inventory.

Core owns generation/exact repair/fallback; app-linked compiled policy owns offers/lowering. Preparation cannot invoke app providers. Native code validates generic boundaries, checks/executes, authenticates transport, persists and observes; it never duplicates domain policy.

## Context and ownership

snapshot copies/validates schemas/values; accessors return copies. Six wire kinds: number/string/boolean/null/record/array. Records are closed, optional absence stays absent; array element metadata is required. Strings count UTF-16 units. Limits: length/count 0–8192, depth 8, record fields 32, encoded context 8192 UTF-8 bytes. Reject duplicates, cycles, unknown kinds, invalid metadata/bounds and trailing JSON, including absent optional branches. Empty records are valid; no implicit domain schema.

Only same-instance issued/reconstituted IDs confer resources; copied metadata confers nothing. Single-use requests freeze facts/contracts and retain traces until shutdown. Cancellation checks before inference, after host operations and atomically before acceptance; it stops waiting/acceptance, not remote cost. Context/request resources last until shutdown.

## Lifecycle and updates

generate/load check without mounting/saving. run mounts/persists before returning one live session per Experience; repeated live runs reuse it. Mount/save failure retains preparation for retry/release. close retains source; cold run rechecks current contracts without inference. saved reissues stored identity/metadata; release frees unused preparation; forget closes/deletes and reports persistence failures. Invalid/closed handles fail.

Embedding progresses effects/replies/deadlines independently of observation. Only next returns snapshots: empty cursor reads current, issued session cursors await later change/fault; coalescing preserves atomic read/register. Invalid/foreign cursors raise Error. dispatch returns null after action processing, not effect completion. close/shutdown reject waits. published synchronously acknowledges renderer acceptance, not pixels, and never gates progress. Run blocking async mechanisms off UI thread; cancellation/observation cannot queue behind inference.

## Authority and policy

Synchronous trusted-controller grant/revoke is authoritative at native admission and reply acceptance. Pending calls retain epochs; regrant never revives revoked replies. Provider authentication, consent, validation/deadlines remain independent; completed work cannot be undone.

Generic choice validation checks unique issued questions/options, complete answers and finite probability/confidence coverage/sum, not correctness. App policy validates domain relations. Current generation directly lowers fully specified zero-question offers; inferred offers enter composition without another selector. Unsupported or rejected deterministic source falls through to generation. No attempt/repetition ceiling: recover until acceptance/cancellation. Only eligible source diagnostics or explicit trusted runtime-test feedback enter repair; transport/operational failures retain classification.

Materialize canonical source once. Thereafter host-revision-bound exact edits must uniquely match disjoint original regions; no rewrite/regeneration fallback. Invalid initial envelopes retry initial admission without executing/creating a patch base; failed edits retain the valid base. Trusted candidate tests run real sessions, never expose test authority to generated UI.

## Observation and bootstrap

events uses empty trace=all, empty cursor=new snapshot, limit 1–200. Instance-local single-use continuations are bounded to 16 and invalidated by clear; appends preserve paging. Events are chronological; decimal millisecond time/elapsed, nonnegative duration, separate experience/session and produced/published identities. Artifacts are captured/truncated/unavailable with stored-byte digests. Capture is explicit/private/bounded/nonretroactive. Incomplete health/eviction is not success; observation failures preserve app results; clear removes observation only.

public-protocol.js owns synchronous context/request/controller state, never injected answers; native lanes independently validate resources and remain cancellable while computing. Bootstrap restores saved metadata before exposing saved. Production apps use compiled consumers; assertion consumers are fixtures. Apps own placement/lifecycle, embedding driving/render wiring. Android/JS support does not establish other-backend portability.
