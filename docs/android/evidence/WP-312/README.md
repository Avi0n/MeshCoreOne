# WP-312 MapLibre/OpenFreeMap candidate evidence

**Status:** MapLibre/OpenFreeMap implementation candidate complete; hosted
exact-head checks remain the authoritative merge gate.

## Revisions and scope

- Repository: `cbattlegear/MeshCoreOne-Android`
- Work package / owner: `WP-312` / `nodes-map-ui-engineer`
- Integrated base: `b8c15cc547be9608c6c9cd0e203771ca63a458e6`
- Frozen Swift source: `db14559b39d32322b06477c6ae676112f583db50`
- Manifest: 52 owned inputs; dependencies WP-218/WP-303/WP-304 are merged.
- No new Android runtime permission, account, key, analytics, updater or Google
  service was added.

## Provider and dependency result

- Engine: `org.maplibre.gl:android-sdk:13.6.1`
- AAR SHA-256:
  `3863ef506991b590270e1418129a44b98d73da31251c011dc440ea6121fb01aa`
- Base style: `https://tiles.openfreemap.org/styles/liberty`
- Visible attribution: OpenFreeMap, © OpenMapTiles, © OpenStreetMap
  contributors, each linked from the map.
- Satellite: unavailable; OpenFreeMap supplies no satellite imagery.
- Topography: unavailable; no approved OpenFreeMap topo style is configured.

Selected additions in the strict `core-maps` graph are MapLibre Native 13.6.1,
MapLibre GeoJSON/Turf 6.0.1, MapLibre Gestures 0.0.4, Gson 2.10.1, OkHttp
4.12.0, Timber 5.0.1, AndroidX Fragment 1.8.9, CustomView/ViewPager 1.0.0 and
their already-admitted Kotlin/AndroidX support modules. The exact graph is in
`android/gradle/dependency-locks/core-maps.lockfile`; artifact checksums are in
`android/gradle/verification-metadata.xml`.

MapLibre Native and MapLibre Gestures are BSD-2-Clause. MapLibre GeoJSON/Turf,
Gson, OkHttp, Timber and AndroidX are Apache-2.0. The APK retains the existing
Apache notice and adds exact upstream BSD notices:

| Notice | SHA-256 |
| --- | --- |
| `MapLibre-Native-BSD-2-Clause.txt` | `554cff8b6f6d2e77629e8c6eab769844ab86adc610ca2d60401ba2e0dce3c229` |
| `MapLibre-Gestures-BSD-2-Clause.txt` | `e82169bcba4d61ddb2d838aa154d153517e227efb07a60668efbcc472b18773f` |

OpenFreeMap code is MIT; the remotely served Liberty/OpenMapTiles/OSM resources
are not redistributed in the APK. Their required credits are visible on the
map. OpenFreeMap's official documentation states that its public instance has
no request limits and separately publishes weekly MBTiles/PMTiles/Btrfs
snapshots for self-hosting.

## Implemented behavior

- Lifecycle-correct Compose/MapView adapter using the Liberty base map.
- Real radio-partitioned contact/discovered-node loading from the process store.
- Stable GeoJSON point/line overlays, filters, labels, clustering/expansion,
  selection, refresh and an accessible node-list alternative.
- Persisted camera, cluster, label and filter preferences.
- Deep-link/message-location marker and camera focus.
- MapSnapshotter renderer with attribution and truthful offline unavailability.
- OfflineManager create/progress/pause/resume/delete lifecycle for the visible
  OpenFreeMap base-map region, with typed low-storage/network failures,
  cancellation cleanup, persisted metadata and destructive confirmation.
- Honest offline scope: MapLibre-managed base regions are supported; satellite,
  topographic and arbitrary local-MBTiles mounting are not claimed.

## Actual local verification

Using the repository launcher, checksum-pinned Temurin 21.0.12.1, SDK 37.2,
build-tools 37.0.0, explicit private caches and strict dependency verification:

| Command/check | Result |
| --- | --- |
| `:app:compileDebugKotlin` | Passed |
| `:core:maps:testDebugUnitTest --rerun-tasks` | Passed: 20 discovered/run/passed in 3 suites; 0 failed/errors/skipped |
| `:app:testDebugUnitTest --rerun-tasks` with `-TestHeap 512m -TestMetaspace 768m` | Passed: 547 discovered/run/passed in 41 suites; 0 failed/errors/skipped |
| `:core:maps:lintDebug :feature:map:lintDebug :app:lintDebug` | Passed; no lint errors |
| `:app:assembleDebug` through `android/scaffold/invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 1536m` | Passed |
| `:core:datastore:verifyPreferencePackaging` | Passed against the rebuilt APK, including the established four-ABI DataStore provenance check |
| `:core:designsystem:verifyThemePackaging` | Passed; exact permission set retained, with MapLibre precise-location and Wi-Fi-state permissions removed |
| `:core:designsystem:verifyThemeConsumerLocks` | Passed: 8/8 verifier tests and all 10 WP-301 consumer-lock baselines retained alongside later WP additions |
| `runtimeDependencyInventory` under strict dependency verification | Passed: 151 linked runtime artifact inputs; all required MapLibre/transitive POMs and binaries checksum-pinned |
| `zipalign -c -P 16 4 app-debug.apk` | Passed |
| Repository `apk_alignment.inspect_alignment` | Passed for every packaged ELF: MapLibre is packaged only for arm64-v8a/x86_64, while existing 32-bit non-map libraries remain packaged; every packaged `PT_LOAD` segment uses `p_align=0x4000` |

Built debug APK SHA-256:
`021AFD5D8417AD54DFC36FF3E1A76B356773E3CECD67AE8BAE83090CA1826B9A`.
This is static packaging evidence, not execution on a 16 KiB device.

## Acceptance

| Acceptance | Status |
| --- | --- |
| `WP-312-behavior` | Implemented: base map, overlays, filtering, clustering, camera persistence/focus, snapshots, attribution and offline base-region lifecycle. Satellite/topographic absence is explicit. |
| `WP-312-boundaries` | Implemented/tested locally: no GPS permission dependency, cache/snapshot lifecycle, pause/resume/delete, network loss, cancellation, low disk and static 16 KiB packaging. Physical-device execution is not claimed. |
| `WP-312-source-test-parity` | Shared map algebra and snapshot/offline parameter families are covered by 20 nonzero JVM cases; feature-specific downstream map builders remain consumers of these WP-312 seams in their owning WPs. |

PR #140 may leave draft only after its exact-head hosted checks pass.

## Issue #224: compact RTL system-font200 controls

Follow-up base: `d8181418614a5acaa46f4acde54b741b492ac4b0`.
Owner remains WP-312 / `nodes-map-ui-engineer`; production changes are confined
to `MapControls` in the map feature.

The fixed Refresh/Offline Maps/List row exhausted its width before measuring
List on actual API31 and API37. Actions now wrap using the available width,
and every map-control chip reserves at least 48dp in both dimensions rather
than relying on a touch region expanded outside its measured bounds. Existing
copy, theme, filter state, map renderer and callbacks are unchanged.

The regression is the unchanged WP-503 fixture at
`8eb2d610b7a954dedfefcba6a973c497c41ce8f1`:
`NativeMapRuntimeAccessibilityTest#populatedNativeMapListSelectionKeepsCoordinatesAndFavoriteFilterReachableAtFont200`.
Acceptance requires its actual API31/37 visibility, unclipped-font, contained
48dp target, selection, coordinate and favorite-filter assertions. Local
compile/lint/domain results alone do not establish that acceptance.
Exact-candidate native re-execution is pending with the existing WP-503 owner.

Local environment readiness passed with the pinned JDK 21/platform37.2 tools.
The declared launcher command
`.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory -BuildHeap 1536m -BuildMetaspace 768m -GradleArguments ':feature:map:compileDebugKotlin',':feature:map:lintDebug',':core:maps:testDebugUnitTest'`
passed: Kotlin compilation and map-feature lint succeeded; core-map JUnit XML
reported 3 suites, 23 discovered/run/passed tests, 0 failures/errors/skips.
`git diff --check` also passed. No iOS, native-device or screenshot acceptance
is inferred from these local checks.
