# ISSUE-0003 takeover integration

## Scope and provenance

The fixup branch reconciles canonical `caf6140` with the complete committed WD-0069 candidate
`7b446dc5d7a96e568ca8c1782b6fd769bc6e7e4e`. The original candidate is retained as merge ancestry;
its working directory was not changed. Neither a canonical contribution nor publication is implied.
The embedding specification and original journey acceptance remain unchanged.

The import includes typed plans, clock/entry parts, issued definitions, ownership, bindings,
operations, publications and deterministic assembly. Reconciliation retains the landed full catalogue,
issued catalogue holes, typed reader eligibility and renderer precommit regex/temporal validation.

## Corrections beyond the imported candidate

- Composite reads recursively include local-helper reads, like writes. Dependency traversal rejects
  cycles longer than two arms without repeatedly enumerating every path. Regressions cover hidden
  helper cycles, conflicting writes, a three-arm cycle and a valid three-arm chain.
- One compiled disclosure predicate keeps request/context sources separate, checks complete text and
  numeric tokens, preserves structured field association and row identity, and accepts equivalent
  numeric spellings. Seed/catalogue checks reject larger-number substrings and fabricated row
  combinations. Scalar lookup is separate from a record field literally named `value`.
- Catalogue and typed-part holes retain their issued signatures and body ownership checks. Syntax,
  declaration injection, duplicate definitions and invalid wire kinds reject rather than falling back.
- Optional unconnected readers retain typed nonnullable assembly fallbacks; these do not establish a
  factual publisher. The previous candidate's nullable fallback broke the master-list helper.

## Bounded deterministic evidence

No device, inference, paid request, AAR rebuild or broad gate was used for this takeover.

- Construction contracts: 34 modules; latest run passed in 25.36 seconds.
- Catalogue contracts: 22 modules; passed in 20.15 seconds.
- Composer contracts: 39 modules, 1,015 syntax calls; passed in 66.82 seconds total across bounded
  stages. All 20 emitted-pair lanes subsequently passed individually, including production compiler
  and admission/runtime checks where each lane defines them. Individual lane totals ranged from
  0.27 to 29.53 seconds. Compile-only lanes are not runtime evidence.
- Catalogue runtime: aggregate, lists, issued bodies and all six local groups (timers, reading, quick,
  points, ideas, drafts) passed individually through the real composer, compiler and pinned Session.
- Renderer ECMAScript/admission and compiled numeric persistence checks passed after reconciliation.
- Harness controls passed: contended lock exits 75 before preparation; timeout kills descendants.
- Matrix validator passed three tests and 40 claim-mutation controls: 132 rows, 36 kinds, 45 families;
  76 covered, 33 partial, 13 missing, 3 device, 5 live, 1 human, 1 explicit. This validates claims,
  not product completion. Syntax and whitespace checks passed.

## Long-run pitfalls and fixes

The original runtime harness started one JVM per syntax request and called an obsolete compiler
signature. It now uses the same persistent, bounded syntax transport as the composer harness and
passes explicit metadata/freshness arguments. A combined 1,126-line local fixture exceeded the
short bound; six smaller compositions retain its assertions without compiling one oversized AppState.
The full default still runs every group. Group success output names only the executed group.

Four focused Python harnesses verify dependency pins, hold the nonblocking repository build lock,
clean staged generated outputs and bound subprocess trees. Timing out Node also terminates its JVM
children. Composer pair selection requires an exact source/output snapshot, never cached test results.
The old 1,000-turn composer pump was also an accidental ~1,000-call ceiling: new regressions crossed
it. A wall-time deadline replaces it, with completed-call diagnostics. Timeouts remain failures.

## Limits / next verification

Timed-out and failing attempts remain private build evidence; no timeout was counted as a pass.
Machine contention changed observed runtimes substantially; these measurements are not an isolated
benchmark or a general speedup claim. No broad/default combined command was run to completion.

Android/Kotlin/Compose, fresh packaged generation, standalone-boundary proof and destructive seam
controls remain unverified for this revision. Existing packaged discovery seam anchors do not match
this checkout's older packaged outputs; rebuild before attempting those controls, never waive them.
Real provider, device, cold-reopen journey and human acceptance remain separate obligations.
No issue closure or acceptance follows from this branch or its matrix result.
