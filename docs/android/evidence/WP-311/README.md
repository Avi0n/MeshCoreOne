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
