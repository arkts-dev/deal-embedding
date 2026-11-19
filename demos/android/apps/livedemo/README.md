# Live discovery → generated UI

Separate Android application: **Live discovery** (`dev.deal.apps.livedemo`).
A synthetic picnic note is the only task data. No supplied DEAL/UI source, fixture
inference, external-app access, purchases or bookings.

1. Open the app and enable live AI (separate discovery and generation consent).
2. DeepSeek Flash reads the disclosed note and returns a suggestion.
3. Tap **Open**. Cerebras performs real planning, selection, coverage and source
   generation through the public embedding protocol.
4. Edit the generated amounts and observe the result. Saved tools reopen without
   making new inference calls.

## Run

From the repository root, with the normal host-side Cerebras key and
`DEXTER_DEEPSEEK_API_KEY` configured:

```sh
python3 demos/tools/generation-gateway.py --backend cerebras \
  --discovery-backend deepseek-flash --port 8788 \
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

The USB reverse and host gateway must remain available for fresh discovery or
generation. Credentials remain host-side. Existing demo apps/data are untouched.
Do not uninstall or clear app data to test reopen.

**Inspect** exposes captured model requests/responses and failures. Capture is
explicitly enabled for this synthetic-note demo, not for other apps. Settings
contains the separate AI opt-ins and an explicit destructive action to delete
this app's generated experiences. Use that action only when you want a fresh
journey; returning from Inspect or cold reopening keeps the saved tool.

## Observed phone run

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
