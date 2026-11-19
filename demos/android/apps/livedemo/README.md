# Live discovery → generated UI

Separate Android application: **Live discovery** (`dev.deal.apps.livedemo`).
A synthetic picnic note is the only task data. No supplied DEAL/UI source, fixture
inference, external-app access, purchases or bookings.

1. Open the app and enable live AI (separate discovery and generation consent).
2. DeepSeek Flash reads the disclosed note and returns a suggestion.
3. Tap **Open**. Cerebras performs real planning/source generation. With the
   optional Jev selector enabled, DEAL-issued selection/coverage questions go to
   Jev instead. Both use the public embedding protocol.
4. Edit the generated amounts and observe the result. Saved tools reopen without
   making new inference calls.

## Run

From the repository root, with the normal host-side Cerebras key and
`DEXTER_DEEPSEEK_API_KEY` configured:

```sh
python3 demos/tools/generation-gateway.py --backend cerebras \
  --discovery-backend deepseek-flash --selector-backend jev --port 8788 \
  --counts build/live-demo/gateway-counts.json
```

In another terminal:

```sh
JAVA_OPTS=-Xmx768m DEAL_GATEWAY_PORT=8788 \
  DEAL_DISCOVERY_BACKEND=deepseek-flash bash demos/build.sh livedemo
adb -s PHONE_SERIAL reverse tcp:8788 tcp:8788
adb -s PHONE_SERIAL install --user 0 -r build/livedemo.apk
adb -s PHONE_SERIAL shell am start --user 0 \
  -n dev.deal.apps.livedemo/.LiveDemoActivity
```

Jev reads `~/jev.key` host-side (override with `--jev-key-file`); omit
`--selector-backend jev` to keep all generation inference on Cerebras. There is
no provider fallback. The adapter sends the entire unchanged prompt as Jev state,
plus separately issued questions; DEAL retains probability/threshold validation.

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
