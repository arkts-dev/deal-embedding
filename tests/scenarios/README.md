# Original v0.8 acceptance scenarios

`requests.json` is the tracked input set for Budget, Departure, Food and Equipment.
It preserves the original `name`, `intent` and context JSON string exactly. The
context contains disclosed task facts, supporting fact IDs and capability
references. It contains no provider replies, credentials, saved state or answers
for production code to consume. Capability references are not permissions.

These are normative acceptance cases, not the exhaustive scope of v0.8. The
framework specification remains authoritative. Never rewrite the requests, relax
constraints or add scenario-specific production behavior to obtain a pass.
The dates remain fixed; any test clock must be explicit and isolated, never
silently rewrite task dates or change the device clock.

## Expected behavior

- **Budget:** initially show committed minus approved = 1750 EUR cents, over
  budget. Both amounts are editable. With approved 18000, committed 17000,
  18000 and 19750 give -1000 (under), 0 (equal) and 1750 (over). Approved 20000
  with committed 19750 gives -250 (under). Invalid drafts withdraw dependent
  results; valid edits recover them. No external reads.
- **Departure:** preserve venue, date, Europe/Berlin interval, 12 km and the
  15-minute unloading / 10-minute pre-departure inspection buffers. Explicitly
  load an authorized actual Vehicle travel estimate. Departure is collection
  start minus unloading minus returned travel duration; inspection is departure
  minus inspection buffer. Editable start and buffers recompute without another
  travel read; changed distance invalidates the estimate until explicit reload.
  Invalid inputs withdraw results. Preserve instants and rollover. No booking.
- **Food:** explicitly load actual Pizza menu/delivery data and construct a
  complete allocation of six 30 cm pizzas, at least two vegan, within EUR80
  including delivery to the disclosed entrance before 18:30 on the task date.
  Quantities and budget remain editable; recompute all constraints. Explain
  validated unavailability or missing data, never fabricate feasibility.
  No ordering.
- **Equipment:** use actual Rental catalogue and whole-basket quotes to compare
  package versus separate hire for the disclosed period and location. Require an
  adjustable boom stand with clip adapter and a balanced XLR female-to-male cable
  of at least 10 m. Account for package contents and shared stock. Apply editable
  EUR20 hire budget; show refundable deposit separately. Recompute after edits;
  invalidate changed quote arguments or expired evidence. No booking.

Across all four, prove fresh generation, edits and cold reopening through public
framework APIs. Preserve authoritative drafts/choices; recompute derived values,
restore pending work as stale, and require explicit refresh for stale provider
data. Never replay external mutations. Preserve existing domain/authority data,
permissions, signing keys and failed evidence; no uninstall or data reset.

## Execution and evidence

The opt-in Android runner in `tests/android/CutoverInstrumentation.kt` consumes
this JSON array through its `scenarios` instrumentation argument. That argument
names a file staged in the instrumentation target's private files directory;
it is not a host pathname. Use a test-owned filename and inspect the runner's
actual assertions/results: successful initialization alone is not acceptance.

Do not add paid inference or shared-device execution to default build gates.
Obtain explicit inference opt-in, serialize device use and preserve signing
continuity. Fixture provider passes are distinct from installed-provider journeys;
Vehicle's disclosed simulation is not live traffic evidence. Human visual
acceptance requires actual human review, not screenshots or model assertions.
Keep run outputs and failed evidence in ignored `build/`, not in these inputs.

## Provenance

Recovered once from the retained original request set and cross-checked against
all four archived request envelopes. No runtime or verification command reads
those archives. Full historical catalogues and private operation state are not
copied. Live acceptance refreshes current contracts and checks authority.
Original input file SHA-256: `6bbfb3a85900da95ece4ff72a75d7ff80f1306bc6714eaf5e5416aa558e3cc96`.
