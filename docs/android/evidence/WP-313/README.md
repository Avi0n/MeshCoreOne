# WP-313 remote-node management evidence

## Current takeover scope

Owner: `nodes-map-ui-engineer`. Existing issue: #121. Dedicated project session:
`91e69a6a-c119-44bc-8545-1a6ded93d968`; Copilot session:
`8e2508f9-176f-487c-ace1-d35f40f611cb`. Starting/current integrated base:
`ffb03a44d20a8ce4f34d402f814e4180c27f0727`.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest SHA-256: `58f7ebd7f46bbe0636c71005f20776efe139a287279e4f4708b25ce6bfa3f892`.
Policy revision: `1f5f2b54f17595b3bab93edc4f607ccb9e3b2fdd409943e32fd394ec19c3bfd4`.
Typed reservation revision 10 admits feature, app composition/tests, directly
resolved dependency locks/checksums, the necessary traceability-validator regression
and disjoint WP-311 contact-action callback integration.
Protected-path main-merge approval remains the coordinator/maintainer's gate.

The #70 implementation is preserved. Native settings, repeater regions, room
access, authentication, status/telemetry/neighbours, precise charts, history,
SNR/location maps and coordinate selection now consume the existing holders.
`AppRemoteNodesFeature` adapts actual services and process persistence.
MainActivity and the native navigation shell make management reachable from
Nodes without an extra tab or feature-to-feature dependency. Contact Detail's
Management, Telemetry, Saved History and Join Room actions now have app-mediated
destinations. Chat-node telemetry uses the original login-free binary public-key
port, while repeater/room access authenticates and room join returns the actual
session to the existing app conversation consumer. Identity-bearing destinations
are not restored from saved-state tokens and are cleared on radio replacement.

## Current validation and source discovery

Local diagnostic execution used the existing checksum-pinned Linux toolchain:
Python 3.12.4, JDK 21.0.12.1, Gradle 9.8.0, AGP 9.4.1, Kotlin 2.3.20 and
SDK 37.2. The exact branch was transported to a task-specific detached Linux
verification checkout because the Windows worktree's Git indirection and CRLF
wrapper cannot run directly under WSL.

Actual targeted Gradle task selection (strict dependency verification):

```text
:app:testDebugUnitTest
  --tests com.meshcoreone.android.app.remotenodes.*
  --tests com.meshcoreone.android.app.container.AppRemoteNodesIntegrationTest
  --tests com.meshcoreone.android.app.container.SessionLifecycleTest
  --tests com.meshcoreone.android.app.navigation.NavigationComposeTest
:feature:remotenodes:testDebugUnitTest validateModuleGraph --write-locks
```

The earlier targeted diagnostic run discovered/passed 331 feature cases in 38 suites
and 44 native/app cases in 4 suites, with no failures, errors or skips.
The native remote suite now selects 18 flows on both simulated SDK31 and SDK37.
It checks real focused input/Backspace/color/height, confirmation and exact
edited Apply, 200% font and resize, guest/admin/room roles, permission revocation,
cancelled login, offline/error history, chart values, report/full-map selection,
attribution, saved/missing-location camera selection, CLI keyboard/accessibility and
actual Nodes-tab and Contact Detail navigation, binary chat-node telemetry,
authenticated telemetry-only access, room join, private route restoration and
history snapshot lifetime across catalog updates. Six locally rendered PNGs are generated under the
existing app test artifact directory; they are not committed or claimed as
physical-device or real MapLibre tile evidence.

CLI resource changes also preserve the entered draft and use the new locale for
subsequent terminal messages; the diagnostics adapter stays stable between
configuration changes rather than causing repeated composition.

Picker camera regressions emit unequal, non-default latitude/longitude spans
and assert the next rendered camera retains the complete report while the
crosshair follows its center. Both saved and missing fixes receive two successive
pan/zoom reports; the missing-fix first report retains its wide view rather than
jumping to `.05`. Cancelling and reopening initializes from the unchanged saved
location, while committing a missing-fix selection still requires confirmation
before remote writes. The declared targeted selection is
`:app:testDebugUnitTest --tests "*RemoteNodesComposeTest.*LocationPicker*"`;
the complete native suite and affected app/remote-nodes lint remain the broader
regression selection. Exact-head hosted results, not this description, establish
the final outcome.

The real-container tests use native Room with the existing deterministic radio
harness. They exercise catalog/OCV/snapshot persistence, offline history, stale
captured-port rejection and exact typed firmware/session fault classification.
No physical radio communication is used.

All 175 original cases have bindings in `source-cases.json`; its inherited
`junit` values are historical #70 observations, not a new result bundle. The
new focus binding and supplemental native input bindings contain no CI result.
Hosted exact-head job results/logs are authoritative for final automated
verification; this document does not approve human, legal, radio or signing gates.

The shared map provider, explicit unsupported layers and static native alignment
provenance are documented in [`../WP-312/README.md`](../WP-312/README.md).
Current native adaptations are in
[`../../deviations/WP-313.md`](../../deviations/WP-313.md).

The declared full local checks exposed an inherited duplicate
`androidx.tracing:tracing-android:1.3.0` POM verification entry. The support fix
removes only the repeated identical entry; its publisher-bound SHA-256 and
strict metadata verification are unchanged. It is admitted through the
dependency-resolution capability, not a checksum exception. Full graph resolution
also found that the initial lock generation had not consumed the release compile
configuration. The actual feature `dependencies --write-locks`, release compilation
and resolver tasks now generate complete debug/release locks, without speculative
versions or manual dependency-lock editing.

The persisted candidate's full local execution discovered/passed 583 app cases
in 43 suites (including all 32 native remote flow variants) and 331 feature cases
in 38 suites, with zero failures, errors or skips. Every one of the 175 original
bindings matched an actual passing JUnit method. The initial full run then
identified a configuration-unsafe resource read; native resources/formatting now
observe `LocalResources`/`LocalConfiguration`, rather than suppressing lint.

The separate initially empty user/project-cache dependency audit passed with
strict metadata and no build-cache reuse:

```text
:feature:remotenodes:dependencies --configuration debugRuntimeClasspath
:feature:remotenodes:compileDebugKotlin
--dependency-verification strict --no-build-cache --max-workers=1
--project-cache-dir <initially absent private cache>
```

Final full verification uses
`python .\tools\android-port\local\check.py --commit HEAD --stages all --distribution Ubuntu-22.04`.
Its diagnostic status is not an independent CI acceptance bundle; the required
exact-head hosted job and log remain authoritative.

## Historical #70 logic-layer observations

The following original contribution records are retained for provenance. Their
no-lease, deferred-UI and unavailable-prerequisite statements describe that
contribution, not the current takeover. Lenient historical macOS runs are not
used as current strict-verification evidence.

External contribution, no lease. Local macOS runs only, not CI or acceptance receipts.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`. Scope: the non-UI code of
`android/feature/remotenodes/`; deviations and type placement are in
[`../../deviations/WP-313.md`](../../deviations/WP-313.md).

## Commands

JDK 21 (`/opt/homebrew/opt/openjdk@21`), the Android command-line SDK, private Gradle/Android
user homes, `--dependency-verification lenient` (the macOS aapt2 artifacts are not in the
verification metadata). Run from `android/`, three times, with the feature's tests cleaned first:

```
./gradlew :feature:remotenodes:cleanTestDebugUnitTest :feature:remotenodes:testDebugUnitTest \
  :feature:remotenodes:lintDebug validateModuleGraph --dependency-verification lenient --console=plain
python3.13 tools/android-port/portmap.py
python3.13 tools/android-port/controller/validate.py
```

## Observed results

- `:feature:remotenodes:testDebugUnitTest`, three runs: 38 suites, 330 tests, 0 failures,
  0 errors, 0 skipped each time (no test task was up to date).
- `lintDebug`: "No issues found". `validateModuleGraph` passes (the module depends only on
  core:model, core:contracts, core:designsystem, core:ui, core:maps, core:l10n; the build adds
  no dependencies).
- `portmap.py` exits 0 and `validate.py` reports `"result": "valid"` on the committed tree.
- The module registers `verifyScaffoldTests` in its `build.gradle.kts` (same pattern as
  core:connectivity), so CI collects its JUnit reports.

## Source-case coverage

`docs/android/test-cases.json` lists 175 cases owned by WP-313 (15 Swift test files).
[`source-cases.json`](source-cases.json) binds each to its `@OriginalCase` method and the JUnit
result read from the XML.

| | Count |
| --- | --- |
| Owned source cases | 175 |
| Bound and passed in JUnit | 174 (153 source-behavior, 21 platform-adaptation) |
| Deferred | 1 |
| Missing | 0 |

The other 156 tests are native: Foundation reproductions, chart math, drawing-free map data,
login flow, retry runner, drift and route presentation, session matching, late-reply recovery.

Parameterized Swift families are one method each covering every argument (validation
boundaries, SNR buckets, key-width). `platform-adaptation` cases:

- the snapshot-store cases (RepeaterStatusViewModelTests, NodeLocationCaptureTests) run against a
  fake that reproduces `recordNodeStatusSnapshot`'s 15-minute enrich-or-insert window, because a
  feature cannot use the Room store or `core:services`;
- the two `RemoteNodeModelTests` DataStore round trips test only the DTO role/permission logic
  (core:data's `RoomSourceTest` covers the persisted role);
- three `NodeContactInfoSectionTests` cases check only the state-holder half (edits arrive as
  `setOwnerInfo`, Apply sends the edited value); the UITextView typing path is not exercised;
- locale-dependent assertions (`Locale.current.measurementSystem`) take an injected
  `MeasurementSystem` and test every system;
- `syncTime` uses the injected clock instead of a real before/after bracket.

### Deferred

- `NodeContactInfoSectionTests::contact info text color stays readable while the field is focused()`:
  a UIKit focus-color/hit-test assertion, to be redone when the Compose Contact Info field exists.

Not ported (no test id attached): all Compose screens and rows (repeater/room settings, status,
history, login sheet, add-region sheet, CLI tab, the tab picker and glass filter bar); chart
drawing, scrub gestures and haptics (math and models are ported); MapLibre rendering, camera
animation, pin sprites and attribution (pins, lines, regions and filters are ported); VoiceOver
announcement posting (the decision logic is ported); `RepeaterSettingsView`, `RoomSettingsView`,
`SharedNodeSettingsViews`, `NodeManagementTabPicker` are layout-only apart from the pieces named in
the code (clock-drift warning, load placeholder, owner-info counter). Room conversation files are
owned by another WP.

## Mutation checks

Each mutation was applied to a clean tree, the feature tests run, then the file restored
(`git checkout`). Results from the JUnit XML:

| Mutation | Failing tests |
| --- | --- |
| Name byte cap `>` to `>=` | `NodeSettingsValidationTest#name at byte cap is valid` |
| Late-reply duplicate guard removed | `NodeSettingsLateRecoveryTest#mesh duplicate of an answered reply is not recovered for another query` |
| Default-scope clear ignores the firmware gate | `RepeaterDefaultScopeTest#removeRegion of current default skips clear before repeater v1_15` |
| Region dump cap `>=` to `>` | `RepeaterRegionParseTest#parseRegionTree rejects a saturated newline-terminated dump` |
| SNR "good" threshold made non-strict | `MapPrimitivesTest#snrQualityBucketsUseStrictGreaterThresholds`, `NeighborSNRMapBuilderTest#snrBucketsMapToTheExpectedTraceLineStyle` |
| Empty prefix matches a session | Survived at first (no test); added `NodeStatusSessionMatchTest`, then `empty prefix never matches a session` fails |

## Swift oracles

`oracles/*.swift.txt` are the swiftc programs used to check Foundation-dependent output (rename to
`.swift` to run): number formatting and unit conversion (`numbers`, `numbers_default`),
`localizedStandardCompare` (`compare`), CLI argument text and device-time dates (`settings_text`),
region parser edge cases (`settings_regions`), abbreviated duration formatting (`drift`),
location report formatting and calendar arithmetic (`history_location`), road-usage distance text
(`map_road_usage`) and stable SHA-256 pin ids (`map_badge_ids`).

## Not claimed

Original-case counts are source-case parity for the logic layer only. They do not establish UI
parity, map rendering, real BLE/service integration (the ports are driven by fakes), or
CI/acceptance results.
