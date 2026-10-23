# WP-311 evidence

Repository: `cbattlegear/MeshCoreOne-Android`
Issue: #119
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`
Merged logic predecessor: PR #69 / `7e9584751ca79b874ced996e7056926bc3fc7ae8`
Completion base: `9b60b6965ce009e58517e8451f16f94502ae41f5` (`origin/main`, including WP-316)

This increment completes the native contacts/nodes presentation and production integration:
contacts, blocked/favorite/search/sort states, discovered nodes, add and QR scan/share, contact
details, advertisements, ping, shared MapLibre location, path discovery/editing and explicit
direct/flood confirmations. Camera use is optional and manual URI paste remains available.

## Verification environment

Windows, JDK 21, portable Android SDK 37, private Gradle/Android homes, strict dependency
verification, one Gradle worker and in-process Kotlin compilation. No emulator, physical Android
device, camera hardware or radio hardware was used.

## Commands and observed results

| Command | Result |
| --- | --- |
| `gradlew.bat :feature:nodes:testDebugUnitTest --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed. JUnit XML: **16 suites, 175 tests, 0 failures, 0 errors, 0 skipped**. |
| `gradlew.bat :app:testDebugUnitTest --tests com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest.contactShareContentUsesRealEncoderAndParser --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed. One selected test; all three contact types round-trip through the real encoder/parser. |
| `gradlew.bat :feature:nodes:lintDebug --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed; HTML and SARIF reports generated under `android/feature/nodes/build/reports/`. |
| `gradlew.bat :app:assembleDebug --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed after integrating current `origin/main`; debug APK assembled. |
| `gradlew.bat :core:designsystem:verifyThemePackaging --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed with the exact debug APK permission set including the legitimate optional `CAMERA` permission; package `com.meshcoreone.android.debug`, min/target 31/37, notices and test-fixture exclusions verified. |
| `gradlew.bat validateModuleGraph --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed; the feature retains no `core:services` edge. |
| `python tools\android-port\portmap.py` | Passed, exit 0. |

### Post-merge runtime inventory repair

Repair base: `9114207e93c3d9d4f6ea618b69c88f2eca51a0c4` (merged PR #158).
Resulting-main run `37995219362` failed closed because the strict inventory had no
verification entry for `core-remoteviews-1.1.0.pom`.

| Verified POM | SHA-256 |
| --- | --- |
| `androidx.core:core-remoteviews:1.1.0` | `3272accaf7e3ad43da3e14f3ee921e43089fe65de501480cec47c8280996e850` |
| `androidx.glance:glance:1.2.0` | `4cda4b69bcc4d4d4c9fdd9fb0cc2af02a4a6b655034477df9d27a9e04126c29c` |
| `androidx.glance:glance-appwidget:1.2.0` | `88f36b073e53458980438905ab3c36905146f49c73d273264a98265d548269e9` |
| `androidx.glance:glance-appwidget-external-protobuf:1.2.0` | `cb812781a9ef84421d706e83ac99ade9acc1793abfb4b8749086c99f999592f2` |
| `androidx.glance:glance-appwidget-proto:1.2.0` | `dc705d28c0ac12716294b8add1543a14d7c768b3d21693a2a22d87a55dfad76a` |
| `androidx.lifecycle:lifecycle-service:2.10.0` | `2de9428f9e1a3f71fa209658213d2d85f75877761cc1fd20a8c9b2b54aa7078a` |
| `androidx.work:work-runtime:2.7.1` | `fbfdb979e4c3eb6d886ab4f93905165df642206f7e4d24b58e305d9d759947a7` |
| `androidx.work:work-runtime-ktx:2.7.1` | `7beed974f2f62b5634c32acf02ff11d61fefb6caa7bb1217637d45f35f678c93` |

| Command | Result |
| --- | --- |
| `gradlew runtimeDependencyInventory --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed. **196 provenance rows, 195 runtime components, 0 duplicate provenance rows**. |
| `gradlew resolveScaffoldDependencies runtimeDependencyInventory --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed after exact-head run `37997811133` exposed and the repair restored the canonical `platform-widgets` release configuration lock memberships. |
| `gradlew -p build-logic/convention test --dependency-verification=strict --no-daemon --quiet --max-workers=1 -Pkotlin.compiler.execution.strategy=in-process` | Passed. JUnit XML: **6 suites, 38 tests, 0 failures, 0 errors, 0 skipped**. |
| `$env:PYTHONPATH='tools\android-port'; python -m unittest discover -s tools\android-port\tests -p test_ci_evidence.py` | Passed: **7 tests, 0 failures or errors**. |
| `controller.ci_evidence.validate_graph_runtime(Path('android/build/reports/scaffold'))` | Passed against the generated module graph and runtime provenance reports. |
| `python tools\android-port\controller\validate.py` | Passed, exit 0. |
| `python tools\android-port\portmap.py` | Passed, exit 0. |

The strict inventory first identified the missing
`androidx.core:core-remoteviews:1.1.0` POM. Continuing the same already-declared runtime graph
identified seven immediately subsequent POMs: Glance, Glance AppWidget, both AppWidget protobuf
artifacts, Lifecycle Service, Work Runtime and Work Runtime KTX. Only their canonical SHA-256
verification entries were admitted; no dependency, product behavior or permission changed.
The exact-head candidate then found the merged `platform-widgets` lock lacked release configuration
memberships. Canonical lock generation produced the same support-file bytes already present on open
WP-314 PR #157; this repair carries only that shared lock state and none of WP-314's product changes.

### Native screen-level follow-up regressions

Command (from `android/` in PowerShell):
`.\gradlew.bat resolveScaffoldDependencies :feature:nodes:testDebugUnitTest --tests '*DiscoveryNativeTest' --tests '*DiscoveryViewModelTest' --tests '*ContactDetailNativeTest' --tests '*PathManagementNativeTest' --tests '*ContactPathActionsApi31ComposeTest' --tests '*ContactPathActionsApi37ComposeTest' --tests '*DiscoveryUiApi31ComposeTest' --tests '*DiscoveryUiApi37ComposeTest' --dependency-verification=strict --no-daemon --quiet --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"`

Result: exit 0; strict dependency resolution passed; JUnit XML contains **51 discovered, 51
passed, 0 failures, 0 errors, 0 skipped**. The 6 native Compose screen tests ran under Robolectric
native graphics at API31/API37, `w360dp-h900dp-mdpi`, RTL and 200% font scale. The disconnected
Discovery screen test seeds the known radio's offline store, verifies the cached node is visible
and no progress indicator or contact/radio action remains, and checks Add/Remove are visible with
at least 48dp targets. The Contact Detail tests scroll to Discover/Edit/Reset Path and verify
visible bounds and enabled 48dp targets while ready; while disconnected, all three actions remain
visible with >=48dp targets and are disabled. The other 45 tests cover discovery holder, contact
detail and path management behavior. `git diff --check` passed.

The Gradle tasks also ran the repository localization/theme/notice checks. Their observed
sub-suites were 74 localization converter tests, 22 theme converter tests and 4 theme-notice
tests, all passing. These are supporting checks, not additional WP-311 acceptance counts.

## Source-case accounting

All **98/98** WP-311 source IDs are bound and passing; see
[`source-cases.json`](source-cases.json). The previous logic contribution bound 96 cases. The
two formerly deferred `ContactShareContentTests` cases now run in
`MeshCoreUriParserTest.contactShareContentUsesRealEncoderAndParser` against
`ContactService.exportContactURI` and `MeshCoreUriParser.parseContact`, rather than a fake codec.

The 175 feature tests comprise the predecessor's 173 logic tests plus two QR output tests:

- encoded `meshcore://contact/add` text round-trips through ZXing without changing the URI;
- blank QR payloads are rejected.

## Native completion evidence

- `NodesEntry` dispatches list, discovery and detail destinations.
- `NativeNavigationShell` supplies adaptive list/detail routing and contact/chat/map callbacks.
- `AppNodesFeature` binds process storage and dynamically re-reads `sessions.current` for
  per-connection services.
- CameraX declares only optional camera capability; permission denial provides Settings recovery
  and never removes manual paste.
- QR rendering uses correction level M and fixed black-on-white modules.
- Contact location uses shared `core:maps` MapLibre/OpenFreeMap components.
- Contact/path destructive and routing mutations remain behind explicit confirmations.
- Ordered recent hops persist in process `SharedPreferences`.

## Dependency evidence

CameraX `1.6.2` and ZXing `3.5.4` were resolved through the existing repositories. Strict
verification metadata and only the affected `feature-nodes`/`app` lock states are committed.
The exact debug APK permission set includes the legitimate optional `CAMERA` permission. No Google services,
analytics, accounts or unrelated permissions were added.

## Limits

This evidence does not claim camera-device, radio, Android instrumentation, iOS, signing,
release or human-review verification. Avatar replacement has no admitted Android media-picker
contract in WP-311; existing avatar data/service behavior is preserved without fabricating one.

## Historical logic evidence

The predecessor mutation checks remain applicable and were not rerun in this completion increment:
sorting, delete masking/guarding, unresolvable hops, late discovery responses, grapheme-boundary
matching, hop de-duplication, scan claim ordering and ambiguous repeater resolution were each
demonstrated to fail at least one owned test when mutated. Frozen Foundation string oracles remain
under [`oracle/`](oracle/).

## Follow-up #226/#228

Base: `d8181418614a5acaa46f4acde54b741b492ac4b0`; initial implementation candidate:
`fe67497f4de3b7223aa94860d582fc7b5261394d`. Earlier hosted-check head:
`c8708313310b76df40be9e75a763fe90dac876e8`. The API31/37 viewport regression and its test
configuration were added at `d6aa3a63c` and verified locally as recorded below. Frozen source:
`db14559b39d32322b06477c6ae676112f583db50`. The controller-rendered WP-311
manifest SHA-256 is `39bd73ab7759d7f65f7ecc681cd472e4763d8c8d3e9f5973bc0c515323611dda`;
policy revision is `3fc0afa19ad31f0210a5d427f655ef90b50a8e64e818fe3420b188303b99fb20`.
The merged prerequisite PRs are WP-312 #140 and WP-311 #153.

Command:
`./gradlew :feature:nodes:testDebugUnitTest --tests '*DiscoveryNativeTest' --tests '*DiscoveryViewModelTest' --tests '*ContactDetailNativeTest' --tests '*PathManagementNativeTest'`

Result: BUILD SUCCESSFUL; 45 discovered, 45 passed, 0 failed, 0 errors, 0 skipped
(DiscoveryNativeTest 9; DiscoveryViewModelTest 10; ContactDetailNativeTest 13;
PathManagementNativeTest 13). The new discovery cases assert cached-radio offline reads,
settled empty state with explicit localized errors when data is unavailable, and no radio action.

The Contact Detail reflow also has local Compose semantics viewport regressions using Robolectric
native graphics at API 31 and API 37, `w360dp-h900dp-mdpi`, RTL and 200% font scale. They scroll
the real Contact Detail list and assert Discover, Edit and Reset Path have visible bounds and
48dp targets in both states: enabled while ready, disabled while disconnected.

Command:
`./gradlew :feature:nodes:testDebugUnitTest --tests '*ContactPathActionsApi31ComposeTest' --tests '*ContactPathActionsApi37ComposeTest'`

Result: BUILD SUCCESSFUL; 4 discovered, 4 passed, 0 failed, 0 errors, 0 skipped (two per API).

The supplied WP-503 Compose selectors, including
`offlineDiscoveryHasATextualCachedOutcomeInsteadOfAnUnownedIndefiniteSpinner` and its Reset Path
`assertIsDisplayed` test, were not present in this worktree and are not claimed as rerun. The
candidate adds its own native screen-level regressions at the required APIs and viewport; those
assert equivalent #226/#228 outcomes without claiming the absent selector identities. An earlier
exploratory version using virtual-looper-only waiting timed out at both APIs; the final harness
uses bounded polling and drains the main looper before/after each activity, and the final tests
pass. See the final combined result above. The manifest still declares no WP-311 verification
command or trusted check identity; local Gradle results are not represented as controller/CI gate
evidence. No emulator/device, hardware, iOS, signing or release evidence is claimed.

### Exact-head dependency-lock repair

Hosted run `38506813183` for head `c612bc3fa9fbb52f830107b9aee48f32c6d7a466` failed its Linux Android
build at `:resolveScaffoldDependencies`: the feature-nodes lock recorded `androidx.compose.ui:ui-test`
on `debugUnitTestLintChecksClasspath`, although the current native semantics test does not resolve
that dependency. Related stale, lint-only test artifacts were also present. Those unused lock
memberships were removed without weakening strict dependency verification; API31/API37 Robolectric
SDK and actual test runtime lock entries remain.

Command (from `android/`):
`./gradlew resolveScaffoldDependencies :feature:nodes:testDebugUnitTest --tests '*DiscoveryNativeTest' --tests '*DiscoveryViewModelTest' --tests '*ContactDetailNativeTest' --tests '*PathManagementNativeTest' --tests '*ContactPathActionsApi31ComposeTest' --tests '*ContactPathActionsApi37ComposeTest' --dependency-verification=strict --no-daemon --quiet --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"`

Result: command exited 0; strict scaffold dependency resolution and all selected node tests completed
successfully. JUnit XML records 47 tests, 47 passed, 0 failed, 0 errors, 0 skipped. This is local
evidence only. Exact-head hosted run `38509337209` for
`86d1692f71f703a62a316d43e904afce981b3547` then passed the Linux Android build (including strict
scaffold dependency resolution), candidate classification, controller/workflow policy, required
checklist and `android-ci`; both hosted Swift oracle jobs also passed. GitHub's exact-head result and
log are authoritative; no duplicate CI bundle is retained.

## Follow-up #237: QR Copy reachability

Increment parent: `bbb92b6e0a6027e2c1890feaf13bce534bac45bf`; original follow-up base and frozen
source remain as above. The user-directed request relayed on 2026-10-18 adds #237 to this same
WP-311 workstream. Trusted `wp-owned` admission permits `modify` on
`android/feature/nodes/src/main/kotlin/com/meshcoreone/android/feature/nodes/ui/NodesListScreen.kt`.
The existing session reservation was evolved from revision 2 to 3 with the supported `reserve.py
evolve` operation, preserving identity, bindings and immutable authorization context. No other
reservation claims that QR dialog path; WP-313's detail/navigation paths are not changed.

The repair restores source-equivalent vertical scrolling in the QR dialog content and explicitly
preserves a 48dp minimum Copy height. It does not change labels, QR payload/size, clipboard bytes,
the two-second Copied state, chooser extras, radio behavior or dismissal.

Command (from `android/`, PowerShell with `ANDROID_HOME` pointing to the existing local SDK):

`.\gradlew.bat :feature:nodes:testDebugUnitTest --tests '*DiscoveryNativeTest' --tests '*DiscoveryViewModelTest' --tests '*ContactDetailNativeTest' --tests '*PathManagementNativeTest' --tests '*ContactPathActionsApi31ComposeTest' --tests '*ContactPathActionsApi37ComposeTest' --tests '*DiscoveryUiApi31ComposeTest' --tests '*DiscoveryUiApi37ComposeTest' --tests '*ContactShareApi31ComposeTest' --tests '*ContactShareApi37ComposeTest' --tests '*ContactShareContentTest' :feature:nodes:lintDebug --dependency-verification=strict --no-daemon --quiet --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"`

Result: exit 0; **58 discovered, 58 passed, 0 failures/errors/skips** across 11 suites; nodes
lint passed. The 51 earlier follow-up tests remain passing, five source-share tests pass, and
two new dialog tests pass at API31/API37 with native graphics, 360x900 mdpi, resource font scale
2.0 and RTL. The dialog tests assert scroll semantics, contained Copy bounds >=48dp in both
dimensions, complete text-line allocation, exact compact clipboard key, disabled Copied feedback
through one second and reset at two seconds, exact ACTION_CHOOSER/ACTION_SEND type/text/subject,
and Done. Reports are under `android/feature/nodes/build/test-results/testDebugUnitTest/` and
`build/reports/lint-results-debug.html`; no result bundle is committed.

`python tools\android-port\portmap.py`, `python tools\android-port\controller\validate.py` and
`git diff --check` passed. Declared `ci.py preflight` first required explicit state; after using
`ci.py local-inputs` with the installed JDK/SDK, it failed because the local SDK lacks
`build-tools\37.0.0\package.xml`. This is an unverified local readiness signal, not a reason to
weaken the check or a hosted readiness claim. The actual Gradle test/lint command above passed.

**Pending native acceptance:** the unchanged WP-503
`NativeNodesFlowAccessibilityTest.offlineQrShareKeepsNamedAlternativeCopyFeedbackAndExactNativeIntentReachable`
must run against this repair on actual API31/API37. The candidate-owned Robolectric tests are
supporting evidence, not a substitute for that gate. No WP-503 fixture is copied, edited, hidden
or relaxed, and no device/radio verification or independent approval is claimed here.

## Bounded review repair: truthful offline Add and reserved QR actions

Increment parent: `34a20d96d68e0eb025ec8dff7bdb22ac1b30ac67`; original base, frozen source,
manifest and policy bindings are unchanged. The bounded PR233 review identified an enabled
offline Add whose missing-service return had no outcome. The same `wp-owned` reservation was
evolved with the supported CLI from revision 3 to 4 for DiscoveryStateHolder and to 5 for
DiscoveryScreen investigation; the exploratory screen layout change was discarded, so no
DiscoveryScreen production change remains. Original authorization context and identities are
unchanged. No other producer checkout or WP-503 fixture was modified.

Add now checks actual READY state and service availability at activation, showing the existing
localized not-connected dialog if either is unavailable. It does not use a retained service
while disconnected. This is the review's explicit recovery-policy option; no radio snapshot is
presented as a reliable reactive enabled state. Connected Add still uses the original frame
conversion, updates the store through the service and reloads Added state. Offline Remove/Clear
are unchanged and covered as persistence-only actions.

WP-503's published `2876ef2a0b3615ad23dae5d692da46ca58263bb0`, executed combined
`c24f9ae191f7fb0a0484dc7aa6cf473d339b8074`, integrated the exact parent producer. Its actual
API31 original Nodes7 run was 6 pass/1 fail (Copy 84x31dp), API37 7/7 pass, zero errors/skips.
The later process-ready integration `148886af2f27a0fb95e4e7aba8ffd1109da225b0` retained the same
API31 Copy failure. These owner-reported device results supersede earlier pending-only wording
and do not grant native acceptance to this revision. Authoritative source/device bindings:
https://github.com/cbattlegear/MeshCoreOne-Android/pull/233#issuecomment-6259966748 and
https://github.com/cbattlegear/MeshCoreOne-Android/pull/233#issuecomment-6262284771.

Copy is now outside the QR content scroller, in the dialog's reserved action area with Share.
Its final arrangement groups Copy and Done with FlowRow and retains Share as the confirm action,
so Copy does not force an additional fixed-height row at narrow/large-text widths. This final
arrangement supersedes the interim stacked-Copy/Share arrangement in `62d503decbd67cf5a06a1368beb77ec9c2e0af75`.
The QR/key retain scrolling; Copy retains >=48dp minimum height, exact clipboard bytes, disabled
Copied feedback/reset, chooser payload and dismissal. Owned QR tests require Copy to be visible
without a preceding scroll, have no scrolling ancestor, and retain contained >=48dp bounds and
fully allocated text/key.

The first mounted connected-action run found newly composed Added semantics with zero bounds on
API31 in this manual Robolectric harness. A speculative row-layout change did not fix it and was
discarded. Completing the existing root's pending measure/layout traversal at its unchanged bounds
made the connected-state assertion pass. This is harness-only; no viewport enlargement, relaxed
assertion or WP-503 fixture change is used.

Final combined command (from `android/`, existing SDK environment):

`.\gradlew.bat :feature:nodes:testDebugUnitTest --tests '*DiscoveryNativeTest' --tests '*DiscoveryViewModelTest' --tests '*ContactDetailNativeTest' --tests '*PathManagementNativeTest' --tests '*ContactPathActionsApi31ComposeTest' --tests '*ContactPathActionsApi37ComposeTest' --tests '*DiscoveryUiApi31ComposeTest' --tests '*DiscoveryUiApi37ComposeTest' --tests '*ContactShareApi31ComposeTest' --tests '*ContactShareApi37ComposeTest' --tests '*ContactShareContentTest' :feature:nodes:lintDebug --dependency-verification=strict --no-daemon --quiet --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"`

Result: exit 0; **65 discovered, 65 passed, 0 failures/errors/skips** across 11 suites;
nodes lint passed. DiscoveryNativeTest contributes 12 cases; each mounted Discovery API class
contributes 3; each QR API class contributes 1. The final assertions ran against the candidate's
production bytes, not the historical WP-503 fixture. `python tools\android-port\portmap.py`,
`python tools\android-port\controller\validate.py` and scoped `git diff --check` passed.
`ci.py preflight` with explicit session-local state/output remains blocked by missing local SDK
`build-tools\37.0.0\package.xml`; actual module tests/lint worked without changing dependencies.

Actual unchanged WP-503 native API31/API37 acceptance is still required against the new published
producer. The unrelated Nodes search/favorite negative in the later owner report is not repaired
or attributed by this bounded Add/QR increment. PR233 stays draft; no independent approval,
current-base merge verification, device execution or merge is claimed.

## Local-held QR share subject correction (2026-10-23)

Original repair base: `155a2ce70baa341fc9559f111454e8529dd7e47e`.
Frozen source remains `db14559b39d32322b06477c6ae676112f583db50`.
Existing session/attempt `869123e3-46d4-474e-94aa-4bc2d026143b` and managed branch
`cbattlegear-verbose-telegram` were retained. Same-owner typed reservation evolved to
revision 7 for current-base integration and revision 8 for the bounded source, mounted
regression and WP-311 documentation operations; immutable authorization was not rewritten.
The repair commit `b4e02fadddcfdf9bb4931a2ddb46d22c7b9a9ffc` was integrated with current
main `b8c6c610d5d4f5fe729ecd86ea89117aaebb9393` (WP-310 merge) in candidate
`3ec966b4e2d178415d3994813a92dd441b6e0901`, tree
`3ca170d1cd3ff24500c05eec5744383016e3343b`. The merge parents are the repair commit and
`b8c6c610d5d4f5fe729ecd86ea89117aaebb9393`. Its four incoming WP-501 paths are disjoint
from the Nodes source/test changes. The 46 unrelated unstaged lock/license paths were
disjoint from incoming changes and were not staged or discarded. All 46 SHA-256 snapshots
matched after integration and verification.

Frozen `ContactQRShareSheet.swift:176-178` supplies
`L10n.Contacts.Contacts.Qr.shareSubject`. Android's actual `ContactShareDialog` instead
supplied the contact name to the pass-through holder. The dialog now resolves existing
`C.contactsQrShareSubject` with `stringResource` and passes that value. No resource,
holder, URI/key, chooser title, clipboard, feedback timing or layout change is needed.
A-7 documents only the MIME/text platform adaptation, not a subject substitution.

The actual mounted dialog regression now requires the localized subject to differ from
the fixture's contact name and checks the captured send subject, while also retaining
the contact-name chooser title and all existing clipboard/two-second feedback/URI/key/
geometry/dismissal assertions. These are owned Robolectric tests, not native-device
acceptance or WP-503 fixture changes.

Commands from `android/`, with `ANDROID_HOME` and `ANDROID_SDK_ROOT` set to the existing
`$env:LOCALAPPDATA\Android\Sdk`:

```powershell
.\gradlew.bat :feature:nodes:testDebugUnitTest --tests '*ContactShareApi31ComposeTest' --tests '*ContactShareApi37ComposeTest' --tests '*ContactShareContentTest' --dependency-verification=strict --no-daemon --quiet --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"
.\gradlew.bat :feature:nodes:lintDebug --dependency-verification=strict --no-daemon --quiet --max-workers=1 "-Pkotlin.compiler.execution.strategy=in-process"
```

On the original repair base, the pre-fix red run discovered 2 tests and failed both on
`expected MeshCore One Contact, actual Offline relay`, with 0 errors/skips, before the
caller fix. On integrated candidate `3ec966b4`, the test command discovered/passed 7 tests
across 3 suites (1 mounted case per API, 5 share-content cases), 0 failures/errors/skips;
the separate lint command passed. A combined forced rerun (`--rerun-tasks`) emitted passing
test results but exited with Gradle `Metaspace` failures; the isolated test and lint commands
above both completed successfully.
The declared `ci.py preflight --state <existing session environment.json> --output
<session qr-subject-preflight> --local` passed without provisioning. Declared
`python -B tools\android-port\portmap.py` and
`python -B tools\android-port\controller\validate.py` passed; scoped `git diff --check`
passed.

Publication is held for source review. No push, new PR, native replay, install, runtime
installation, hook action or gate publication occurred. WP-503's frozen PR #311 and
its shared native expectation/provenance remain untouched; its contact-name subject
expectation still needs the owner-coordinated source-correct update. Whole-port and
#237 native acceptance remain incomplete; prior native negatives are not cleared here.
