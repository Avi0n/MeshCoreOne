# WP-312 operational-map producer repair (#317)

This is a bounded follow-up, not whole-WP acceptance or hardware evidence.
Repository: `cbattlegear/MeshCoreOne-Android`. Owner: `nodes-map-ui-engineer`.
Base: `3611b47f1cb93c8bd89fa5b16333aedb2b05901f` (PR #315 verified merged).
Swift specification remains `db14559b39d32322b06477c6ae676112f583db50`.
Actual app session: `286dcf2f-db80-4924-86e8-c919b360f3ff`;
Copilot session: `3ca57f86-29b4-491f-ad68-aab5c244ea11`;
branch: `cbattlegear-wp-312-operational-shared-map`.
The exact candidate head and authoritative hosted job links belong to the PR.
No second CI-result bundle is committed, uploaded, hashed, or revalidated here.

## Admission and retained bookkeeping failure

The initial legacy `local/reserve.py claim` failed with
`Existing WP lease is bound to different source/base/manifest/backend`.
The incumbent advisory attempt/session is
`2dfddc03-a0eb-4fd8-978a-c1292a266e62`, revision 2, branch
`wp312-map-list-font200`, base `ebb6c8eeb470f551856f4fdd684ebdd3f716d62d`.
The coordinator recorded the authoritative terminal PR #229 intent separately;
this worker did not release/recreate, impersonate, transfer, install a runtime,
or edit SQLite. That receipt is not this worker's identity.

The user-directed coordinator resumed isolated source work, clarifying that
reservations are advisory file intents, not pre-edit locks or fleet activation.
Read-only trusted `reserve.py admit` resolves `android/core/maps/` + `modify`
to `wp-owned` and the exact `AppMapFeature.kt` + `add-direct-support-wiring`
to `app-build-launcher`. The latter also admits directly necessary App test
support. There was no open WP-312 producer at the refreshed-base inspection.
The source, process/connection lifetimes, optional-GMS boundary and
min/target/applicationId are unchanged. No feature-to-feature dependency or
WP-311/313/314/315 path is changed.

## Source mapping and bounded behavior

- `MapCalloutSelection.swift`, `MapView.swift` and `MapViewModel.swift`:
  contact/discovered DTOs survive marker selection; contact details and direct
  chat use the existing App navigation coordinator. Discovered details show the
  actual public key and Add calls the current radio session's real contact
  service. Wrong-radio actions fail explicitly.
- `MapViewModel.swift`: screen-owned first-50ms coalescing listens to actual App
  contacts/services/connection/session changes. Superseded read generations
  cannot replace the current data. Filter changes re-filter without fetching;
  source changes, hidden selections and deleted markers clear selection.
  The single pending message-location pin survives refresh.
- `MapPoint.swift`, `MC1MapView+Layers.swift`: separate native name, endpoint/hop,
  and badge text layers use the provider's Noto Sans glyph stack. Turning names
  off does not remove hop indices or trace badges. Untraced segments remain
  dashed/neutral, not indistinguishable from failed/weak red segments.
- Shared MapLibre surfaces render the approved OpenFreeMap/OpenMapTiles/OSM
  legal links. The main feature no longer has a separate attribution-only
  implementation. Existing snapshot attribution stays enabled.
- The live `MapProjectionHandle` consumes local physical MapView pixels and
  delegates both directions to MapLibre's native projection, not camera spans.
  Detaching returns `NotReady`; outside-viewport and invalid results are typed.
  Saved native zoom/bearing/tilt round-trip alongside backward-compatible
  four-field camera values. Antimeridian framing uses the shorter longitude arc.

These changes address bounded portions of `WP-312-behavior`,
`WP-312-boundaries`, and `WP-312-source-test-parity`. They do not reassign
original-case credit or claim the remaining original/native matrix passed.

## Verification boundary

The manifest's WP-312 verification remains `configured:false`; it is not
rewritten to fabricate acceptance. The current scaffold declares
`verifyScaffoldTests`, `:core:maps:testDebugUnitTest` and the App unit-test
runner. This repair registers actual feature map unit tests with
`verifyScaffoldTests` using the existing module convention. The existing hosted
Android candidate workflow supplies the admitted build/test commands.

Local `python tools\android-port\controller\ci.py preflight` is **BLOCKED**:
`Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.
No local Gradle/native resource was occupied. This is an **UNVERIFIED
CANDIDATE** until the exact-head hosted result/log demonstrates otherwise.
The PR records actual command outcomes, never invented discovery counts.

Added coverage includes owned map camera/marker/projection-handle contracts,
feature filter algebra, deterministic first-window refresh/stale-read/action
state, mounted MapEntry action/filter/live-store behavior, mounted shared legal
links, and real App adapters over Room plus the existing synthetic radio graph.
The mounted JVM tests replace only the native surface at its explicit seam;
they are not MapLibre JNI, native window, device, network or physical-radio
observations. Real native pan/zoom/rotation/tilt/resize/antimeridian fidelity
remains unverified until separately executed on the admitted native lane.

## Downstream and human follow-up

WP-315 must replace its span-derived LoS picking with this projection handle
after the producer merges. No LoS consumer is edited here. Existing optional
location services remain shared; this repair introduces no new location
producer or provider. Real-hardware checks still need to observe native marker
selection, legal-link visibility with expanded callouts/font200, camera
restoration, projection round-trips across the native gesture/window matrix,
offline behavior, and discovery Add against a real radio. No device, radio,
license, signing, release or whole-WP acceptance is claimed.
