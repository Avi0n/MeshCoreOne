# Contact URI fallback-name original predicate

## Binding and selected repair

Repository `cbattlegear/MeshCoreOne-Android`, WP-501 / existing PR #165 / #134.
This owned test increment starts at
`6aa823cf72de2acd867d625ba27bb706c42bbf2e`, based on
`aae3b10dd78f3d3314f51291687932f42f7d0ac9`.
Frozen specification remains `db14559b39d32322b06477c6ae676112f583db50`.

Read all eight contact bodies and the shared `validHex`/`roundTrip` inputs in
`MC1Tests/Utilities/MeshCoreURLParserTests.swift`, lines10-89, blob
`773437f03abdb3dd4a1010109fca492d93c06dcf`. Read the actual frozen
`MC1/Utilities/MeshCoreURLParser.swift` contact parser and the current
`MeshCoreUriParser.parseContact`, existing native bodies and type enum.
The native enum's entries are exactly CHAT/REPEATER/ROOM, raw1/2/3.

The original out-of-range-type case requires both preserved name `Node` and
fallback CHAT after a required successful parse. The combined native
`contactTypesPreserveRawPolicy` checked only the fallback type. Losing the
name specifically on that fallback branch could satisfy that assertion.
One new real-parser method restores this missing predicate and uses the
exact original nonuniform32-byte `validHex`, not a generated key.
No production discrepancy was observed.

## Per-original comparison and existing witnesses

Existing methods are in
`com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest`.
The new method belongs to
`com.meshcoreone.android.parity.ContactUriOriginalPredicateTest`.

| Frozen original | Actual native input/assertion witness | Disposition |
| --- | --- | --- |
| `parseContactURL falls back to .chat for an out-of-range type without trapping` | Existing `contactTypesPreserveRawPolicy` requires parse/typeCHAT for type300 but omits name. New `originalOutOfRangeContactTypeKeepsTheNameAndFallsBackToChat` uses exact original URI fields/key and requires non-null, `Node` and CHAT together. | Missing name predicate repaired; no test-written fallback algorithm. |
| `parseContactURL maps type=2 to .repeater` | Existing `contactTypesPreserveRawPolicy` requires parse/typeREPEATER for `name=Node&type=2`. | Existing assertion retained; alternate valid key fixture disclosed below. |
| `A name carrying an injected public_key/type query does not override the declared key or type` | Existing `exportedContactNamesRoundTripWithoutQueryInjection` uses the exact declared nonuniform key and `Alice&public_key=` plus32BC bytes and `&type=3`; asserts declared key, CHAT and entire name after real export/parse. | Existing complete source predicates; not duplicated. |
| `Names containing query-significant characters round-trip intact` | Same existing method includes all five original names: `a & b`, `a=b`, `key & value = pair`, `100% sure?`, `a#b`; asserts each exact returned name, additionally key/type. | Existing complete parameter family; not five new tests. |
| `A literal plus in a name is preserved, not turned into a space` | Same existing method includes exact `C++ dev` and returned-name equality through actual export/parse. | Existing complete predicate; not duplicated. |
| `parseContactURL decodes a stock MeshCore plus-encoded space in the name` | Existing `contactFormPlusIsSpace` uses exact `Example+Repeater`, type2 and requires `Example Repeater`/REPEATER. | Existing predicates retained; alternate valid key fixture disclosed below. |
| `Spaces, colons, and unicode round-trip intact` | Existing exported-name method includes all four original names, including the source accented-Latin and CJK literals, with exact returned-name equality and the original declared key. | Existing complete parameter family; not copied or replaced with ASCII. |
| `Every contact type round-trips through export and parse` | Existing `contactTypesPreserveRawPolicy` exports/parses `Node` for all three actual enum entries and compares the returned type with each input. | Existing complete three-type family; alternate valid key fixture disclosed below. |

Existing direct-type/enum-loop inputs use32 repeatedA1 bytes, and the
stock-plus input uses32 repeatedAB bytes, rather than the original
nonuniform `validHex`. These are explicit differing valid-key fixtures,
not claimed literal-byte parity. The type/name predicates and three-type
family are present; independent comparison must retain this input
distinction rather than infer full input equivalence from annotations.
The repaired fallback witness now uses the exact source key.

This table is per-case accounting, not eight newly approved original
receipts. Only one missing predicate method is added; the other seven
behavioral witnesses are mapped rather than nominally duplicated.

## Actual strict execution

Declared isolated readiness passed with Java21.0.12.1, compile37.2,
build-tools37.0.0 and explicit private Gradle/Android caches:

```powershell
.\android\scaffold\invoke-gradle.ps1 -ConstrainedMemory `
  -BuildHeap 1024m -BuildMetaspace 768m -BuildCodeCache 192m `
  -TestHeap 384m -TestMetaspace 384m -GradleArguments @(
    ':app:testDebugUnitTest', '--rerun',
    '--tests', 'com.meshcoreone.android.parity.ContactUriOriginalPredicateTest',
    '--tests', 'com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest.contactTypesPreserveRawPolicy',
    '--tests', 'com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest.exportedContactNamesRoundTripWithoutQueryInjection',
    '--tests', 'com.meshcoreone.android.app.deeplinks.MeshCoreUriParserTest.contactFormPlusIsSpace',
    '--dependency-verification=strict', '--quiet'
  )
```

**PASS4/4**, one new plus three existing methods, failures0/errors0/skips0.
Fresh native XML contains exactly the named new identity once,
timestamp `2026-10-20T19:44:42.085Z`, and exactly the three existing
identities, timestamp `2026-10-20T19:44:41.130Z`. Reports are under
`android/app/build/test-results/testDebugUnitTest/`,
named `TEST-<fully-qualified-class>.xml`.

Executed new test-source blob:
`0c4065cc98761612f36c0985b015b817d5c04904`.
Unchanged existing test-source blob:
`e6927f38aaad3632c7d93068ff2c9d11edeb8ece`.
Generated prerequisites are not added to the4 native methods. This working
source execution is not a post-documentation rerun or an invented combined
channel/map/reaction/draft result.

## Consumer and acceptance limits

The real `AppDeepLinkRouter.routeContact` uses the parsed public key for
self/existing-contact routing, otherwise stages the parsed result through
`ContainerDeepLinkEnvironment` into `ContactLinkRequest(name,key,type)`.
These tests preserve the parser/encoder contract. They do not claim new
mounted confirmation, contact persistence, scan/QR, notification, radio,
physical-device or native navigation execution.

No foreign test/harness, production parser, build/dependency, source pin,
policy, archived accounting, generated review binding or acceptance gate
change is needed. Earlier channel/map/clock work remains intact and retains
its own execution/source-review bindings. Current successor hosted
verification, independent comparison (including the disclosed existing key
fixtures), persistent merge and whole-WP acceptance remain separate.
No second CI-result bundle is retained.
