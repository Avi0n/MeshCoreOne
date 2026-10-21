# WP-502 contact-match theme probe correction

**LOCAL UNPUBLISHED / UNVERIFIED; corrective Kotlin NOT RUN.**
This named-path correction is a direct successor of published
`8af5053c65b0400e9867f01fc40fd24a4a44d480` on existing draft PR249.
Actual integrated main remains `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
reference `db14559b39d32322b06477c6ae676112f583db50`, manifest977b and
semantic policye8 are unchanged.

Same canonical actor/attempt `3bc8c1b8-20ec-4647-88ae-db3775268489` evolved
actual reservation32 to returned33 through `wp-owned`/`modify` for only
ContactMatchRowComposeTest.kt and this new owned evidence path. No replacement,
authorization-context rewrite or policy/product/support admission. Existing
dirty NewChatSheetComposeTest and its three owned docs are preserved outside
this two-file correction commit, not discarded, rewritten, staged or reordered.

## Actual predecessor failure

Exact8af automatic Android candidate CI39553583719 completed **FAILURE**.
Official Linux job119068601317 reports App826 tests completed/2 failed:

- `localizedKindsAndLocationMetadataConsumeOnlyTheCallerSuppliedDistance`
- `toggleModesExposeLocalizedCheckboxSelectionWithoutImplicitlyChangingItOnClick`

Both assert `expected:<DARK> but was:<LIGHT>` at the original
`assertRowBoundsAndAction` line134, from callers208/245. This is an assertion
failure after compilation, not a provider/compiler/device failure. Aggregate
fails; SwiftData is SKIPPED due upstream failure, not passed. Classifier,
controller and codec succeed. The authoritative failed log was read in memory;
no second CI-result bundle is retained, hashed or revalidated.

The failed dark iterations stop at the theme guard: these results do NOT prove
the later dark200%/Chinese/RTL geometry, glyph, localized metadata, location or
selection predicates pass. Suppressed unexecuted-looper messages are not
independent cause proof or a reason to restart a device.

## Surgical correction and source comparison

The loop changes `frame` within `compose.runOnIdle`, but the helper previously
read ordinary JUnit probe fields before its first semantics synchronization.
`SideEffect` writes `effectiveScheme` during the next recomposition, so that
unsynchronized read can still observe the preceding LIGHT frame. Inspected real
MeshCoreTheme already remembers its frame on theme/scheme/highContrast and
preserves Ember's forced-dark behavior; no product correction is made.

The only Kotlin change wraps the SAME exact assertion in standard Compose
idle/UI-thread synchronization:

```kotlin
compose.runOnIdle { assertEquals(frame.value.expectedScheme, effectiveScheme) }
```

There is no sleep, broad retry, cached substitute, expected-LIGHT waiver,
forced-dark oracle/density/theme reduction, screenshot/golden or product edit.
All three complete component methods, source Relay/key/defaults,18 metadata
input definitions, both Toggle states, independent localized strings, geometry/
glyph/48dp/Tap/location/distance assertions, mounting/teardown and two sampled
frames remain byte-identical outside this single assertion line. Rooms keeps
entire blob `276317f4fe7521fe4c016bc55357372c85e09278`.

Source comparison must reverse ONLY that one line to reproduce entire
predecessor ContactMatchRow blob `c6380085dca2bd146f1f42e5b1bcb869606c5a12`.
That exact whole-file reverse comparison **PASS**; corrected blob is
`ca8a9d0b7915f01d4628acb03d06d1997f8052ee`. All four pre-existing dirty
NewChat test/docs file blobs and entire Rooms preservation **PASS**.

Actual source-only commands on this worktree WITH the preserved uncommitted
NewChat file present:

```text
python tools\android-port\portmap.py                    PASS (1732 mappings)
python tools\android-port\controller\validate.py        PASS (manifest977b)
git diff --check                                       PASS
```

The1732 mapping count includes the dirty new AndroidOnly definition; it is not
a claim that the named-path correction commit contains NewChat or executes any
test. No source-only comparison is Kotlin compilation/discovery/execution
evidence.

## Declared future verification

Existing App source registration needs no build change. After permitted
execution/publication and comparison guards, the existing selector is:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.uitests.ContactMatchRowComposeTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

Positive evidence requires all THREE existing method identities, nonempty
well-formed JUnit XML and0 failures/errors/skips, including the previously
unreached dark assertions. Current corrective Kotlin/compiler/Gradle/native/
preflight/advisory are **NOT RUN**. No CI dispatch/rerun, local host/restart/
lease/main/policy/signing action or publication. Predecessor826/2failed stays
separate; no original-case recount, full-row/golden/device/full-WP acceptance.
