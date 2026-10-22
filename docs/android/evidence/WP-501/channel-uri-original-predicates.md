# Channel URI original predicate restoration

## Binding and scope

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / existing PR #165 / #134.
This test-only increment is rooted at
`7db8a812ff293212210eda15fc86f01c4f4189ee`, containing main
`9c430cb0f1666faacc443794899b68c01128a87a`. The frozen specification is
`MC1Tests/Utilities/MeshCoreURLParserTests.swift` at
`db14559b39d32322b06477c6ae676112f583db50`, blob
`773437f03abdb3dd4a1010109fca492d93c06dcf`. That source and its production
parser have no diff from the pin.

Compare actual original inputs/assertions with
`android/app/src/test/kotlin/com/meshcoreone/android/app/deeplinks/MeshCoreUriParserTest.kt`,
not its `SourceCases` annotations. Existing combined cases covered broad
branches, but dropped some original literal inputs, output equality and wire
omission predicates. The nine new methods in
`android/parity-tests/src/test/kotlin/com/meshcoreone/android/parity/ChannelUriOriginalPredicateTest.kt`
restore the eleven selected originals below. Executed test-source Git blob:
`0ab76091469f021fcefba9fad02f64791f7e6ab1`.

They call the real `MeshCoreUriParser.parseChannel`,
`ChannelService.exportChannelURI` and `ChannelJoinFloodScopeApplier.applyIfNeeded`.
Expected key bytes are literal frozen vectors, not candidate-generated expected
packets. Existing hash comparisons remain in addition to independent literals.
No production, foreign test, build wiring, dependency, source pin, policy,
archived accounting or generated review-binding change is needed.

## Per-original comparison

All native method names below belong to `ChannelUriOriginalPredicateTest`.
This is eleven original identities mapped to nine new JUnit methods, not eleven
new passing tests or an instrumented assertion-call total.

| Frozen original case | Previously missing predicate/input in the combined native case | Restored actual method and assertions |
| --- | --- | --- |
| `parseChannelURL accepts a private channel with explicit secret` | `explicitChannelSecretsAlwaysWin` used `Ops+Base` for another original and did not check the original `Ops`/nil-region tuple. | `originalPrivateAndHashtagExplicitSecretsRetainAllDeclaredFields`: exact `name=Ops`, original `00112233445566778899AABBCCDDEEFF`, all three name/secret/null-region predicates. |
| `parseChannelURL keeps explicit secret when name is hashtag-shaped` | The combined test asserted mismatch and inequality with the name hash, not the returned name and exact declared secret. Many wrong secrets satisfy inequality. | Same new method: exact `%23test`, returned `#test`, exact private bytes and inequality with the hashtag hash. |
| `Public channel golden secret parses as explicit fixed key not name-hash` | Only inequality with `hashSecret("Public")` was asserted. | `originalPublicChannelRetainsItsFixedKeyAndName`: exact `Public` name and fixed `8b3387e9c5cdea6ac9e5edbaa115cd72` bytes, plus original inequality. |
| `parseChannelURL rejects secretless bare names` | `invalidChannelInputsNeverFallBack` included `Ops` but omitted original bare `test`. | `originalBareNamesNeverDeriveHashtagSecrets`: both original bare names must return null. |
| `parseChannelURL normalizes hashtag case when deriving secret` | The combined normalization fixture added `secret=`, merging this original with the next one. | `originalHashtagNormalizationDistinguishesAbsentFromEmptySecret`: exact absent-secret `name=%23Test`, name `#test`, independent golden `9cd8fcf22a47333b591d96a2b848b73f` and hash equality. |
| `parseChannelURL treats empty secret as missing for hashtag derivation` | The combined fixture also changed this original's lowercase `%23test` to mixed case. | Same new method: exact `name=%23test&secret=`, preserved lowercase name and correct golden/hash equality. These are two distinct source input shapes, not a new Cartesian parameter product. |
| `parseChannelURL reads optional region_scope` | The region-only combined test used an explicit private key, not the source secretless hashtag with its independent golden. | `originalHashtagRegionKeepsTheIndependentGoldenSecret`: exact `%23avion-testing2&region_scope=testregion`, region equality and `3976fbac9120f147576900ac90d41dd2` bytes. |
| `parseChannelURL trims and caps region_scope length` | Existing short-name trimming and unpadded long-name capping do not prove trim-before-cap on the same input. | `originalRegionPaddingIsTrimmedBeforeItsByteCap`: original explicit private key and `%20` + 40 `a`s + `%20`, returns exactly 30 `a`s. A source-limit guard asserts the actual 30-byte constant. |
| `export omits region_scope for inherit and allRegions` | Parsing an export back to null does not prove the wire query was omitted; an empty query can parse to null too. | `originalExportOmitsRegionQueryForInheritAndAllRegions`: both actual source scope arguments, URI-wide absence of `region_scope`, and parsed null region. |
| `export includes secret for hashtag channels so older clients still work` | The combined test exported with default inherit and checked only `secret=` presence. It did not preserve the original explicit region and full parsed tuple. | `originalHashtagExportRoundTripsItsSecretAndRegion`: real `#avion-testing2` hash is first checked against the independent source golden; export with `Region("testregion")`, require secret query, then exact name/secret/region equality. |
| `applyIfNeeded does not write when regionScope is nil` | Existing cumulative write count covered non-writing, but discarded the returned channel for the nil-scope call. | `originalNilRegionDoesNotWriteAndKeepsInherit`: original Ops/index1/AB-filled-secret/inherit input, null region, independent zero writes and returned `Inherit`. |

Already complete LRU, DTO-path, CLI rewriter and node-settings parser witnesses
were not duplicated. For the DTO path helper, `pathNodesHex` directly maps
`pathHops.hex`; the existing alias assertions exercise that same current result.
This focused comparison does not count those files as new coverage.

## Actual execution

Declared isolated readiness passed with Java21.0.12.1, compile37.2 and
build-tools37.0.0. Using the session's private Gradle/Android caches:

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.ChannelUriOriginalPredicateTest',
    '--tests', 'com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS:** 9/9 new methods and 14/14 existing parser methods, 23/23 total,
0 failures/errors/skips. Inspected fresh native JUnit XML at
`android/app/build/test-results/testDebugUnitTest/TEST-com.meshcoreone.android.parity.ChannelUriOriginalPredicateTest.xml`
and the corresponding
`TEST-com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest.xml`.
The new report timestamp is `2026-10-20T18:02:35.821Z`; every one of the nine
expected method identities is present exactly once, and the existing report is
also positive with zero failures/errors/skips. `--rerun` forced the selected
test task. The test-source blob above binds this working-candidate execution;
subsequent evidence prose changes do not create another execution.

Read-only `python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\portmap.py`,
`python android\parity-tests\audit.py --check`,
`python android\parity-tests\semantic.py --check` and `git diff --check`
also **passed**. These are validators, not extra native test passes.

The declared task's generated-asset/localization prerequisites ran normally;
their output is not added to the 23-method total. No device, camera, QR, network,
physical radio, protected workflow or hardware action was executed. Hosted
candidate results remain exact-commit bound; old `7db8a812` CI is not relabeled
as execution of this new test. No second CI-result bundle is retained.

## Consumer and acceptance limits

The actual app router parses a `MeshCoreDeepLink.Channel`, then
`ContainerDeepLinkEnvironment.stageChannelConfirmation` carries its
name/secret/region into `ChannelLinkRequest`. The mounted pending-channel and
QR paths pass those three fields into the existing `joinLink` consumer.
These tests preserve the parsed/exported contract; they do not claim a newly
executed mounted join, persisted radio configuration or QR flow.
The nil-scope original is a direct helper contract, not proof that all mounted
joins call that helper.

No production discrepancy was observed in these selected predicates: the gap
was missing exact original assertions. Independent review and persistent merge
are still required. The separate four-case draft assessment/peer execution is
recorded in `draft-restore-four-cases.md`; notification recovery remains with
its assigned owner. Neither this increment nor a green selected task establishes
whole-WP acceptance or closes the seven earlier predicate issues.
