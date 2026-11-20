# Live discovery → generated UI

Separate Android application: **Live discovery** (`dev.deal.apps.livedemo`).
A synthetic picnic note is the only task data. No supplied DEAL/UI source, fixture
inference, external-app access, purchases or bookings.

1. Open the app and enable live AI (separate discovery and generation consent).
2. DeepSeek Flash reads the disclosed note and returns a suggestion.
3. Tap **Open**. The configured general model performs real planning/source
   generation. DEAL-issued selection/coverage questions go to Jev by default.
   Both use the public embedding protocol.
4. Edit the generated amounts and observe the result. Saved tools reopen without
   making new inference calls.

## Run

From the repository root, with host-side `~/jev.key` and
`DEXTER_DEEPSEEK_API_KEY` configured (DeepSeek Flash planning/writing trial):

```sh
python3 demos/tools/generation-gateway.py --backend deepseek-flash \
  --discovery-backend deepseek-flash --port 8790 \
  --counts build/live-demo/gateway-counts.json
```

In another terminal:

```sh
JAVA_OPTS=-Xmx768m DEAL_GATEWAY_PORT=8790 \
  DEAL_GENERATION_BACKEND=deepseek-flash DEAL_DISCOVERY_BACKEND=deepseek-flash \
  bash demos/build.sh livedemo
adb -s PHONE_SERIAL reverse tcp:8790 tcp:8790
adb -s PHONE_SERIAL install --user 0 -r build/livedemo.apk
adb -s PHONE_SERIAL shell am start --user 0 \
  -n dev.deal.apps.livedemo/.LiveDemoActivity
```

Jev reads `~/jev.key` host-side (override with `--jev-key-file`) and is the
selector default even when `--selector-backend` is omitted. There is no general-model
fallback if Jev is unavailable. The adapter sends the full stage prompt as Jev state,
plus separately issued questions; DEAL retains probability/threshold validation.
To use Cerebras for planning/writing, select `--backend cerebras` in the gateway
and `DEAL_GENERATION_BACKEND=cerebras` in the APK build, with `~/cerebras.key`
configured. APK generation still defaults to Cerebras; the DeepSeek trial is explicit.

For a fresh measured run without deleting any existing tool or state, launch with
`--es run jev-01` (letters/digits/hyphens, maximum 40 characters). Reuse that value
for cold reopen; use another value for another fresh run. Each has separate storage
and saved/discovery links. Inspect archives retain requests and failures.

The USB reverse and host gateway must remain available for fresh discovery or
generation. Credentials remain host-side. Existing demo apps/data are untouched.
Do not uninstall or clear app data to test reopen.

**Inspect** exposes captured model requests/responses and failures. Capture is
explicitly enabled for this synthetic-note demo, not for other apps. Settings
contains the separate AI opt-ins and an explicit destructive action to delete
this app's generated experiences. Use that action only when you want a fresh
journey; returning from Inspect or cold reopening keeps the saved tool.

## Step 1: Jev remeasurement (unchanged prompt contents)

Three fresh isolated phone runs used real DeepSeek Flash discovery, Cerebras
planning/writing and Jev (`jev-1.14.0`) for all dispatched bounded questions.
**One produced a usable tool; two failed.** No failed run was reset or hidden.

| Measurement | Earlier Cerebras-only success | Jev successful run |
|---|---:|---:|
| Discovery | 1.27 s | 3.00 s |
| Generate request → first published UI | 19.04 s | 16.89 s |
| Open → first published UI | 19.20 s | 17.07 s |
| Discovery + Open processing, excluding human wait | ~20.5 s | 20.07 s |
| Generation provider request time, summed | 12.65 s | 10.46 s |
| Generation input / output tokens | 66,573 / 11,688 | 71,602 / 3,818 |

The successful Jev run dispatched four bounded calls (two selection, two coverage),
0.43–0.48 s each, plus Cerebras planning and one genuine custom status section.
Initial 120/84 → 36/Under; edits exercised over/equal/under, both amounts, blank-input
result withdrawal, and cold reopen of 120/130 → -10/Over with zero new inference.
The original saved store stayed byte-identical; existing preference entries stayed
unchanged. Separate run namespaces preserve all new attempts.

Failures: one writer emitted stale constant results; Jev correctly rejected coverage,
then its exact-edit repair failed to produce an admitted tool. Another writer used
unissued fields, then emitted comparisons of nullable numbers rejected with E3007.
Neither failure permits a speed/reliability claim across the cohort. The earlier
baseline was one success, not a comparable three-attempt reliability cohort.
Monetary cost remains unknown without billing rates. Human visual acceptance is pending.

Local requests, responses, failures, screenshots, test/build exits and measurement
receipt are retained under ignored `build/jev-step1/`. This step changes protocol
and explicit backend configuration only; unnecessary prompt removal is a later step.

## Step 2: stage-specific prompt trimming

Framework guidance now copies exact stage-relevant spec passages instead of whole
chapters: planning 15,087 → 8,775 characters; selection 36,000 → 602; writing
35,806 → 17,456. Original requests/facts, current plans/sources, issued questions,
ownership/readers and frozen provider declarations remain. Full language and
checked pack remain; duplicate writer prose and the incomplete example are removed.
No task/provider/scenario special cases were added to framework policy.

Three fresh isolated real-model runs: **one complete, two partial**. Both partials
show explicit missing-behavior notices and do not count as successful journeys.
Their request-to-published times were 44.68 s and 40.21 s, including repeated writer
repairs/truncations. Failures and source are retained, not reset.

| Complete-run step | Step 1: Jev, original prompts | Step 2: trimmed prompts |
|---|---:|---:|
| Planning (Cerebras) | 5.37 s | 2.91 s |
| Selection first (Jev) | 0.43 s | 0.33 s |
| Selection second (Jev) | 0.48 s | 0.35 s |
| Initial coverage (Jev) | 0.46 s | 0.36 s |
| Custom section (Cerebras) | 3.27 s, one ask | 6.64 s, two asks |
| Final coverage (Jev) | 0.44 s | 0.40 s |
| Compilation/framework/mount/rest | 6.42 s | 6.33 s |
| Request → first published UI | 16.89 s | 17.31 s |
| Discovery + Open processing, excluding human wait | 20.07 s | 18.89 s |
| Generation input / output tokens | 71,602 / 3,818 | 51,041 / 12,027 |

The complete run needed one syntax repair (`Expected ')'`). Input tokens fell
28.7%; planning/selection/coverage were faster in the measured successes, while
an extra writer ask offset those stage savings in this run. Outputs and retries
are stochastic: one success cannot establish typical total speedup or its absence.
End-to-end processing was lower on this success, including faster independent discovery.

Initial 120/84 → 36/Under; both amounts edited, under/equal/over checked, blank input
withdrew results, and cold reopen retained 120/130 → -10/Over without inference.
Original and step-1 saved stores remained byte-identical. Tests cover stage schemas,
authoritative payload rules, unchanged APIs, compiled construction and runtime.
An old vocabulary test asserted the removed example's exact spelling; it now checks
the contract and retains real numeric/boolean payload execution tests. The suite
passed through construction/runtime; the remaining vocabulary/renderer/cache/
dependency checks passed after that assertion update. APK build passed.

Local receipts and test/build results: ignored `build/prompt-step2/`; raw phone
archives/screenshots also remain under `build/jev-step1/lean-*`. Human visual
acceptance and monetary billing remain unmeasured.

## Step 3: default Jev selector, DeepSeek Flash planning/writing trial

The demo gateway now defaults to Jev for every DEAL-issued bounded question.
Omitting `--selector-backend` no longer puts selections on the general model;
a missing Jev credential/backend fails without fallback. General generation remains
explicit: this trial uses `deepseek-flash` in both the APK and gateway. The APK's
normal general-model default remains Cerebras. No vendor policy was added to DEAL.

Three fresh isolated runs on the same physical phone/task and trimmed prompts:
**0/3 complete, 3/3 partial**, all with editable amounts and remaining 36 but no
required status and explicit missing-behavior notices. These are failures of the
complete journey, not successful generated tools.

| Measurement | Cerebras + Jev, step 2 | DeepSeek Flash + Jev |
|---|---:|---:|
| Complete / attempts | 1/3 | 0/3 |
| Request → first published UI, each attempt | 44.68 / 40.21 / 17.31 s | 109.21 / 139.40 / 125.06 s |
| Median time to published UI (including partials) | 40.21 s | 125.06 s |
| Generation calls, each attempt | 12 / 12 / 7 | 11 / 11 / 11 |
| Generation input tokens, each attempt | 116,642 / 127,386 / 51,041 | 88,697 / 89,361 / 77,792 |
| Generation output tokens, each attempt | 30,840 / 22,020 / 12,027 | 16,803 / 27,064 / 22,262 |

DeepSeek planning took three asks in every attempt: the first two rejected with
`expected table`; the third used DEAL's existing thinking escalation. Summed planning
times were 47.50 / 34.16 / 34.97 s. Custom writing took four asks each (47.57 / 90.67 /
77.69 s), with repeated foreign-field writes, unissued AppState fields and one
unsupported view function call. No adapter retries or backend fallback were added.
Jev remained `jev-1.14.0`, 0.305–0.429 s per bounded call. Discovery remained DeepSeek
Flash, 1.42 / 1.26 / 1.62 s. Actual generation model was reported as `deepseek-flash`.
Reported generation reasoning tokens were 11,832 / 20,669 / 16,258; Jev reasoning
usage is unknown. Monetary cost remains unknown without billing.

For this small cohort DeepSeek was slower and yielded no complete tool. These are
observed attempt results, not a universal model ranking; the earlier cohorts were
not interleaved controls. No complete success exists here to claim edit/reopen
acceptance. Existing saved files remained byte-identical and all original preference
entries unchanged. Nothing was reset. An install-time Activity relaunch initially
reopened the prior tool with zero gateway calls; it was excluded before the three
fresh runs, and its automation log was retained.

Full deterministic `bash tests/run.sh` passed (exit 0), including the nine gateway
transport tests; public-AAR APK build passed. Regressions cover the default Jev
selector, explicit DeepSeek generation versus independent discovery, unchanged
retry messages/token/thinking settings, and missing-selector failure. Trial files,
all requests/responses/failures, preservation comparison and receipts are under
ignored `build/deepseek-step3/`. Human visual acceptance remains open.

## Generation mechanism optimization: captured-response phone replay

Replayed the successful step-2 response sequence on the same physical phone through
public-AAR demo APKs. Both variants used the same pinned compiler, checks and exact
responses; no fresh inference or paid model calls. Uninstrumented runs were grouped
optimized → control → optimized → control, in fresh isolated namespaces.

| Request → first published UI | Control | Optimized |
|---|---:|---:|
| Included runs, seconds | 5.925 / 5.628 / 5.952 / 6.802 / 5.886 / 5.618 | 5.374 / 5.190 / 5.070 / 5.199 / 5.002 |
| Median | 5.906 s | 5.190 s |
| Generation bridge evaluations per run | 309 | 147 |

Observed median saving: **0.716 s (12.1%)**. All twelve generated source pairs were
byte-identical, including one additional optimized run excluded from timing because
its request-start event had rotated out. Every run compiled, passed the production
candidate checks, and published the same editable 120/84 → 36 / Under budget screen.
The optimized APK also passed spent edits to 130/120/84 (over/equal/under), cold
saved reopen, and no new inference on reopen. Original saved preference entries
remained unchanged; no package reset or uninstall.

The reusable mechanisms batch each computation's six independent syntax operations,
then consume per-item results in the original DEAL validation order. Bounded caches
retain only exact syntax-input/output strings: one per loaded syntax host in an
isolate, another per native generation invocation. No mutable Session, authority,
admission result or compiler object is cached. Purity and composition decisions stay
in DEAL. Compiler, pins and candidate checks are unchanged.

Full deterministic suite and public-AAR APK builds passed. Tests cover exact cache
keys, binding changes, bounds, lifetime, uncached failures, non-syntax hosts, per-item
batch errors and preservation of policy-error ordering. Earlier polling-only batching
was rejected because fewer calls did not establish a latency improvement.

This is a small deterministic replay comparison, not fresh-model latency/reliability,
original-journey acceptance or human visual acceptance. Private receipts and the
excluded stale install-time Activity run are retained in ignored
`build/generation-perf/` and `build/perf-investigation/`.

## Earlier observed phone run

- Live suggestion: “Picnic budget reconciliation”.
- Generated screen: budget 120, spent 84 → remaining 36 EUR / Under budget.
- Native input edit: spent 130 → remaining -10 EUR / Over budget.
- Force-stop/reopen retained 130 and the correct result, with no new LLM calls.
- Successful generation: six real Cerebras calls; request to first published
  generated screen approximately 19 seconds. Discovery approximately 1.3 seconds.
- An earlier real discovery succeeded but suggestion presentation failed with
  `Schema mismatch`. Its evidence was retained. The framework fix uses a typed
  checkpoint and explicit numeric button values; it does not substitute inference.
- This is one successful generated journey, not a reliability cohort or human
  visual acceptance. Screenshots were captured; automated checks used UIAutomator.

Local receipts, screenshots and retained failures are in ignored
`build/live-demo/`; no paid inference runs during builds or deterministic tests.
