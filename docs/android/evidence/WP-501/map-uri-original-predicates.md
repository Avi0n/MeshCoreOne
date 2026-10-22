# Map URI original predicate restoration

## Binding and actual comparison

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / existing PR #165 / #134.
This owned test-only increment starts at
`e5102a7113e3b1d6186e238fc5070839c5cb0fe5`, containing main
`9c430cb0f1666faacc443794899b68c01128a87a`. Frozen source remains
`db14559b39d32322b06477c6ae676112f583db50`.

Read all five actual map-test bodies, lines367-402 of
`MC1Tests/Utilities/MeshCoreURLParserTests.swift`, blob
`773437f03abdb3dd4a1010109fca492d93c06dcf`, and the actual frozen
`MC1/Utilities/MeshCoreURLParser.swift` map/decimal parser, lines134-163.
Compared the real current `MeshCoreUriParser.parseMap` and
`MeshCoreUriParserTest.mapCoordinatesAreStrictDecimalDegrees`, not its
five `SourceCases` annotations.

The existing combined native method omitted the original southern
out-of-range and channel-host inputs. It also used delta-inclusive
`assertEquals(..., 0.000001)` instead of the original strict
`abs(actual - expected) < 0.000001`. Three owned
`MapUriOriginalPredicateTest` methods restore only these missing literal
inputs/predicates. Complete existing input families are reused, not copied.

## Per-original disposition

Existing method below means
`com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest.mapCoordinatesAreStrictDecimalDegrees`.
New methods belong to `com.meshcoreone.android.parity.MapUriOriginalPredicateTest`.

| Frozen original case | Existing actual assertions | Missing predicate/input and new witness |
| --- | --- | --- |
| `parseMapURL parses a valid map link into the correct coordinate` | Requires a non-null parse of exact `meshcore://map?lat=37.334900&lon=-122.009020`; checks both expected coordinates with inclusive delta1e-6. | `originalValidCoordinateUsesStrictSourceTolerance` requires non-null and both original strict `< 0.000001` absolute-error predicates, with the exact source URI/expected values. |
| `parseMapURL returns nil when lat or lon is missing` | All three exact source inputs return null: `meshcore://map?lat=37.0`, `meshcore://map?lon=-122.0`, `meshcore://map`. | Already complete in the existing method; no duplicate method. |
| `parseMapURL rejects out-of-range coordinates` | Exact source `lat=91.0&lon=0.0` and `lat=0.0&lon=181.0` return null. | `originalSouthernLatitudeBeyondTheBoundIsRejected` restores omitted exact `meshcore://map?lat=-90.001&lon=0.0` and requires null. All three original inputs now have assertions. |
| `parseMapURL rejects a non-map host` | Exact `meshcore://contact?lat=10.0&lon=10.0` returns null. | `originalChannelRouteIsNotAMapEvenWithCoordinates` restores the other original `meshcore://channel/add?lat=10.0&lon=10.0`, requiring null from the real map parser. |
| `parseMapURL rejects non-finite and hex-float values that Double would otherwise accept` | All three exact source shapes return null: latitude `nan`, longitude `inf`, latitude `0x1p4`, with their other coordinate0.0. | Already complete in the existing method; no duplicate method or broader invented malformed-number family. |

These are five frozen originals mapped many-to-one to one existing native
method plus three predicate-strengthening methods. They are not five new
tests, newly approved original receipts or an assertion-call count.
No defect in the actual selected production parser branches was observed.

## Actual strict execution

With explicit private Gradle/Android caches and declared isolated readiness
(Java21.0.12.1, compile37.2, build-tools37.0.0):

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.MapUriOriginalPredicateTest',
    '--tests', 'com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest.mapCoordinatesAreStrictDecimalDegrees',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS4/4:** three new plus the one existing map method,
failures0/errors0/skips0. Fresh native JUnit XML contains exactly the
three named new identities once each, timestamp
`2026-10-20T18:51:36.951Z`; the existing-method XML has exactly
`mapCoordinatesAreStrictDecimalDegrees`, timestamp
`2026-10-20T18:51:35.906Z`, also positive.
Reports are under `android/app/build/test-results/testDebugUnitTest/`,
named `TEST-<fully-qualified-class>.xml`.

Executed source blobs:

- New owned test: `a4b7f5c838d6c08d04b30aab1c106372fe941c14`.
- Unchanged existing parser test: `e6927f38aaad3632c7d93068ff2c9d11edeb8ece`.

`--rerun` forced this selected task; generated prerequisites are not added to
the4 native methods. This is a working-candidate source binding, not a second
execution after committing documentation, or a combined earlier23/10/peer47
run. Current hosted results remain exact-head bound; no duplicate CI-result
bundle is retained.

## Consumer and acceptance limits

The actual `AppDeepLinkRouter.route` navigates only after the parser returns a
`MeshCoreDeepLink.Map`, passing its coordinates to
`ContainerDeepLinkEnvironment.navigateToMap` and the real navigation
coordinator. These tests exercise the actual parser contract, not a
test-written validator. They do not claim a newly executed mounted route,
external intent/tab restoration, map provider, camera, location permission,
physical device or native map-rendering flow.

No foreign production/test, build wiring, dependency, frozen input, policy,
generated review binding, archived accounting or acceptance gate changes.
The channel predicate increment, four-case draft accounting, owned reaction
clock repair and seven earlier repairs remain intact. Independent comparison,
current successor hosted evidence and persistent reviewed merge remain
separate. This five-case map subset is not complete-file URL-parser parity
or whole-WP acceptance.
