# Remaining channel URI and flood-helper originals

## Binding and selected gaps

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / PR #165 / issue #134.
This increment starts at published
`ec221b686d262c8d50a578fbb27d47add08b9f20`, containing actual main
`680246f7914a5b4e5bf5167a916b620f83a79dee`.
The frozen specification remains
`db14559b39d32322b06477c6ae676112f583db50`.

Read all 23 channel/parser/export/helper bodies in
`MC1Tests/Utilities/MeshCoreURLParserTests.swift`, blob
`773437f03abdb3dd4a1010109fca492d93c06dcf`, not just their annotations.
The [earlier comparison](channel-uri-original-predicates.md) accounts for
11 selected originals. This note accounts for the other 12 without
repeating those nine owned methods or the existing parser suite.
Contact/map originals retain their separate comparisons; this is not
whole-file or whole-WP acceptance.

Two concrete original inputs were absent from the existing helper witnesses:

- `preferredFloodScopeOnlyUsesNonblankRegions` covers nil, empty,
  whitespace-only and padded `testregion`, but omits plain `testregion`.
- `floodScopeApplyIsBestEffortButCancellationPropagates` uses
  `error("store")`, an `IllegalStateException`, not the original typed
  `PersistenceStoreError.channelNotFound`. The actual native
  `ChannelRepository.setChannelFloodScope` throws
  `PersistenceStoreException(PersistenceStoreError.ChannelNotFound)` for
  a missing row.

`ChannelJoinOriginalPredicateTest` adds only the omitted plain input and
actual typed-error family, calling the real production helper. The typed
case preserves the source Ops/index1/AB-secret/enabled/inherit fixture,
requires returned Inherit and the original channel ID, and additionally
proves exactly one write attempt with the intended ID/scope. Thus a no-op
that merely returns the original channel cannot pass that test.
No product discrepancy is asserted.

## Per-original comparison

Existing short method names below belong to
`com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest`.
The two new names belong to
`com.meshcoreone.android.parity.ChannelJoinOriginalPredicateTest`.

| Frozen original | Actual native inputs/assertions and disposition |
| --- | --- |
| `parseChannelURL decodes a plus-encoded space in the name` | `explicitChannelSecretsAlwaysWin` requires successful parse of exact `Ops+Base` with the original explicit private secret and asserts `Ops Base`; it additionally checks secret equality. Existing complete predicate, not duplicated. |
| `parseChannelURL derives secret for secretless hashtag names` | `secretlessHashtagsUseGoldenHashes` parses both original `#test` / `#avion-testing2` inputs, requires each exact name and independent `9cd8...` / `3976...` literal. Earlier owned normalization/export methods also compare the actual hash function with those same independent vectors. This many-to-many witness retains the original returned-secret/hash equality contract; it is not another discovery or candidate-generated expected vector. |
| `parseChannelURL rejects empty secret with bare non-hashtag name` | `invalidChannelInputsNeverFallBack` includes exact `name=Ops&secret=` and requires null. Existing complete predicate. |
| `parseChannelURL rejects secretless invalid hashtag bodies` | Same method requires null for all four source bodies: empty hashtag, `-bad`, `has_underscore`, `has%20space`. Complete parameter family retained, not one nominal negative. |
| `parseChannelURL rejects present but invalid secrets without falling back to name-hash` | Same method keeps exact `%23test` plus all three source secrets `ZZ`, `AABB`, and `00112233445566778899AABBCCDDEE`, requiring null for each. Existing complete malformed/short-secret family. |
| `parseChannelURL ignores empty region_scope` | `regionScopeIsTrimmedAndUtf8Capped` requires successful parse and null region for explicit `region_scope=`. Its valid secret is repeated AB rather than the original nonuniform private secret; input difference disclosed, not literal-byte parity. |
| `parseChannelURL trims whitespace-only region_scope to nil` | Same method requires successful parse and null region for exact `%20%20`; same differing valid-secret fixture as the previous row. |
| `hasHashtagSecretMismatch is true only when secret is not the public hashtag hash` | `hashtagSecretMismatchOnlyAppliesToValidHashtags` asserts true for `#test`/private, false for `#test`/actual public hash, and false for `Ops`/private; additionally false for invalid `#bad_name`. The private bytes are AB-filled, not the original nonuniform key. It calls the real native mismatch function rather than directly constructing Swift's computed-property DTO; `explicitChannelSecretsAlwaysWin` additionally asserts the parsed hashtag's true mismatch field. These input/API differences remain explicit, not a claim that every native DTO-construction/property combination was executed. |
| `preferredFloodScope maps region names and ignores empty` | Existing `preferredFloodScopeOnlyUsesNonblankRegions` covers four of five exact inputs: nil, `""`, `"  "`, `"  testregion  "`. New `originalUnpaddedRegionNameSelectsItsExactScope` calls the real helper with exact `"testregion"` and requires `Region("testregion")`. The formerly omitted fifth source input is now asserted; no test-written normalization algorithm. |
| `applyIfNeeded persists region scope and uses with floodScope on success` | Existing `floodScopeApplyIsBestEffortButCancellationPropagates` requires one actual callback with original ID/Region("testregion") and returned Region("testregion"). It uses an Ops/default-zero-secret DTO instead of the source hashtag/hash DTO; the required callback/result predicates are present, but the fixture distinction is not hidden or independently approved here. No duplicate success test was added. |
| `applyIfNeeded keeps original channel when flood write fails` | Existing same method asserts whole returned-channel equality after a generic store exception. New `originalChannelNotFoundWriteKeepsInheritAndChannelIdentity` supplies the actual typed ChannelNotFound exception and original source fixture, checks one attempted ID/scope write, returned Inherit and unchanged ID. The original typed-error family is now exercised, not merely a different exception labeled equivalent. |
| `export and parse round-trip preserves name secret and region` | `exportedChannelsRoundTrip` uses exact `C++ & #1`, nonuniform private secret and Region("testregion"), requires real export/parse success, then name/secret/region equality. Existing complete tuple, not duplicated. |

Random UUIDs in both platforms are channel/radio identity fixtures, not
cross-platform UUID literals. The table exposes all selected valid-secret,
DTO and API adaptations; it is not 12 newly approved original receipts.
The previously restored 11 originals, their literal vectors, and all
earlier seven repairs remain unchanged.

## Actual strict execution

Same-owner reservation62->63->64 admitted only the new owned test/evidence
paths; no release/recreate or authorization-prose change.
The first capability call used host separators and was rejected before any
edit or reservation change. Retrying with canonical Git identifiers
succeeded through the supported helper; no admission rule was bypassed.

Declared credential-stripped readiness passed Java21.0.12.1,
compile37.2/build-tools37.0.0 with explicit private Gradle/Android caches.
Only the two new methods were selected:

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.ChannelJoinOriginalPredicateTest',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS2/2**, failures0/errors0/skips0. Fresh native JUnit XML at
`android/app/build/test-results/testDebugUnitTest/TEST-com.meshcoreone.android.parity.ChannelJoinOriginalPredicateTest.xml`,
timestamp `2026-10-20T20:00:30.923Z`, contains exactly both named new
identities, once each. Executed new source blob:
`a024ae5f3518561cca16406b03aeeef4603b80ff`.
Generated localization/asset prerequisites are not added to this total.

Unchanged executed helper blob:
`b3a62b703ed0f2bf87395000e60e7cc1b0cca568`.
Current parser/native existing-test blobs remain
`f2ff509413f6a648a54d99184b24a044b88abc76` /
`e6927f38aaad3632c7d93068ff2c9d11edeb8ece`.
Frozen helper blob:
`7abf8ebd27427995cac4d3e64718e1e3b1afe744`.
Selected frozen source/test files have no diff from the pin.

Read-only `python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\portmap.py`,
`python android\parity-tests\audit.py --check`,
`python android\parity-tests\semantic.py --check` and `git diff --check`
also **passed**. These are validators, not additional native cases.

This is a two-method working-source execution, not a rerun of the existing
14-method parser suite or the previous channel/map/reaction/contact/draft
cohorts. Published predecessor ec221/run39487407056 started before these
new bytes and cannot verify their execution. Its exact outcome remains
separately bound. No second hosted result bundle is retained.

## Consumer and acceptance limits

These two originals directly target the helper; the new tests do likewise.
The actual mounted URL/QR consumers use
`ChannelSheetsStateHolder.joinLink`, not this App helper. Parsed
name/secret/region flow through `ContainerDeepLinkEnvironment` /
`ChannelLinkRequest` to that distinct consumer.
No new mounted join, actual Room missing-row transaction, radio write,
QR/camera, physical device or independent acceptance is claimed.
The typed exception is the real repository contract, injected at the
original helper's write-callback boundary, not a claim that these two
tests executed the repository.

The mounted `joinLink` consumer reports a write failure through
`dependencies.diagnostics.report("set joined channel region", failure)`
before returning the joined channel. Existing
`ChannelSheetsStateHolderTest::region failure keeps successful channel join`
asserts the returned Inherit and that exact single diagnostic operation
after its generic store failure; its neighboring
`link join applies declared region and returns updated channel` checks
the successful region result/write. These actual existing consumer
witnesses were read, not rerun or credited to the two-method result.

Native cancellation propagation remains exercised by the existing native
test and is not another frozen original here. The source helper logs a
write failure; this native helper has no logger and catches non-cancellation
exceptions without a diagnostic. The selected source assertions do not
verify logging, so neither the existing generic witness nor the new typed
witness alone establishes logging parity. The direct helper differs from
the mounted diagnostic path described above; it is not evidence that
the actual mounted consumer silently loses the failure. Wider
source/consumer behavior must not be accepted wholesale from this bounded
original-test comparison.

No foreign production/test, dependency/build graph, workflow, policy,
ledger, archived accounting, protected gate or source pin changes.
Current-successor hosted verification, independent assessment of disclosed
fixture/API differences, persistent merge and whole-WP acceptance remain
separate. Seven repaired issues stay open until the actual reviewed merge.
