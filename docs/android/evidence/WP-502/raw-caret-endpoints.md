# WP-502: retain raw line-break caret endpoints

**LOCAL / UNPUBLISHED / UNVERIFIED, UNCOMPILED, UNRUN.** One direct source
correction after `8bb02a432613039e9147341009446e641bbc8f9b`.
Only the horizontal caret-order premise is removed. Actual values are NOT
sorted, clamped, offset, rounded, normalized or rewritten. The unconditional
strict native-ink assertion and every other retained obligation remain.

## Exact inputs, owner and failed parent

Repository `cbattlegear/MeshCoreOne-Android`, issue135, existing DRAFT249.
Main/base `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
frozen source `db14559b39d32322b06477c6ae676112f583db50`;
manifest semantic hash
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`,
blob `582edce11e1cb7761bc1b8f2de7e5c1caaeae27a`;
policy revision
`e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`,
blob `b77c6dc59ec2c626ea560484063808785233e948`.

Fresh actor cbattlegear/clean exact8bb/public OPEN-DRAFT249-at8bb/base-mainDBB/
frozen source/trusted policy+tool/App lock/canonical owner42 guards passed
before edits. Same session/attempt
`3bc8c1b8-20ec-4647-88ae-db3775268489`, runtime alias
`fb112832-0a71-4f6d-abc0-f07f80eaeb8b`.
Actual trusted `wp-owned`/`modify` evolution42 ->43 admitted the three exact
owned paths BEFORE editing. No replacement reservation/actor, authorization
rewrite, source/policy advancement or native/protected operation.

Coordinator-supplied authoritative official IN-MEMORY exact8bb run39593878856
is TERMINAL FAILURE: classifier119201290727, controller119201394470 and
SwiftCodec119202031795 SUCCESS; Linux119202031992 and aggregate119207072135
FAILURE; SwiftData119207073278 upstreamSKIPPED, not scope-excluded or passed.
Official Linux log reports848 tests completed/ONE failed, incomplete
mandatory selected reports. No bundle/log download/hash/upload or CI poll.

Only shown failing method:
`mixedHebrewLatinLtrPaintMatchesActualCallbacksForContentCenterRightAndWrappedText`.
It reaches wrapped-center-bidi: actual native180x48, line0 17..163,
line1 62..118; nativeDerivedAgreement=passed for EVERY UTF16.
The new8bb `invalid native caret rectangle` fails at offset17/unit32 SPACE:
actual native AND derived Rect(left163.7,top0,right163.0,bottom24).
This is NOT observed ink overflow: strict native ink assertion was not
reached at this wrapped sample. No named passing verdict is inferred for
the other21 flow/control identities, including genuine negatives.
Failed8bb/c283/779 and prior histories remain failed and receipts unchanged.

This correction NARROWS source finding5599115018 only on horizontal-order
clearance:8bb's new left<=right premise was unsupported. The separately
source-backed ink-versus-caret adaptation and all other findings remain;
there is no acceptance transfer, fixed-font claim or universal epsilon.

## Pinned line-break and trailing-space contract

Read exact first-party Compose ui-text-android1.10.6 sources IN MEMORY:
`https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.10.6/ui-text-android-1.10.6-sources.jar`.

| Entry / lines | Actual contract / source-entry SHA256 |
| --- | --- |
| androidMain/androidx/compose/ui/text/android/TextLayout.android.kt519-598/765-799 | Native per-UTF16 getBoundingBox combines bidi-selected primary/secondary DOWNSTREAM(offset) and UPSTREAM(offset+1), with exact line top/bottom. Native code returns these endpoints directly as RectF, without sorting or an ordered-X assertion. Public caret queries default downstream; upstream explicitly selects the preceding side of a line break. `7cb6dc98106c65eda570c3d890977a237e8de72fe9cf9f7f685904d1fec91493` |
| androidMain/androidx/compose/ui/text/android/LayoutHelper.android.kt196-350/355-397 | Downstream and upstream insertion endpoints differ at line breaks. Upstream selects preceding line and uses lineVisibleEnd for bidi analysis: invisible trailing whitespace is excluded; line-end offset adjustment follows that visible end. lineEndToVisibleEnd strips native line-end spaces. The two endpoints are therefore not an ordered glyph-ink rectangle contract. `782550405c2507b7dedbab53885f23046025c42ff82e7d99c15328f1722b27c7` |

The exact8bb source already traces AndroidParagraph delegation, selection
path versus caret intervals, Paragraph/MultiParagraph translation, native
paint and TextMeasurer sizing in caret-versus-ink-oracle.md. Those source
inputs and that historical receipt remain unchanged. These native APIs
measure INSERTION endpoints, not glyph ink. Line-break/whitespace/bidi
logic can make the names left/right an unsuitable monotonic-X invariant.
The supplied actual native AND derived reversal is a concrete counterexample
to the added8bb assumption, not proof of a derivation/backend bug or clipping.

The implementation does NOT imitate upstream whitespace adjustment, discard
space units, synthesize an upstream query using downstream public APIs,
sort endpoints, use abs, add tolerance or normalize origins. It retains
the values returned by the pinned native API exactly for EVERY UTF16 unit.
No exception based on whitespace, direction, surrogate, ZWJ or sample tag.

## Minimal correction and preserved coverage

Shared oracle:

- Remove ONLY `bounds.left <= bounds.right`, the unsupported horizontal
  ordering premise. Retain ALL FOUR finite coordinate checks, ordered
  vertical top/bottom, exact native line top/bottom identity, logical unit
  membership in its real line and complete0..literal.length coverage.
- Keep both finite public primary/secondary caret queries at offset AND
  offset+1 for EVERY unit. Keep the raw caret extent diagnostics, full
  literal/style/fontScale/direction/line/ellipsis/height/size predicates.
- Keep unconditional unclipped native painted ink outsideEXACT0/nonempty/
  maxlines/probe-edge assertions and its bitmap/canvas failure cleanup.
  No new fallback, selected hypothesis or replacement geometry model.
- Real target/text/root/ALL scroll ancestors/owning native window/visible
  display frame/insets and48dp consumer predicates remain untouched.

Existing callback agreement helper additionally compares native versus
derived raw left and raw right endpoints SEPARATELY at EXACT0 tolerance for
EVERY UTF16, before strict positives. Its incumbent complete Rect equality
is retained. Thus existing wrapped-center/right LTR100/RTL200 families
explicitly preserve native endpoint identity even when endpoints reverse,
without imposing that a particular engine/font must exhibit inversion.
This strengthens evidence rather than accepting a normalized rectangle.
No new Test method, family, fixture text/font/dimension or rendered sibling.

All genuine clipping negatives are unchanged, including actual offscreen
ink loss plus distinguishable mounted clipped-only witness AND specific
new-ink primary-rejection check, then original line rejection. Full native
paint support is byte-exact8bb; both raw/clipped hypotheses remain descriptive,
both is not clip proof, neither explicitly fails. No observed method pass
or future native-ink result is inferred.

## Comparison and source-only outcome

Exactly three owned paths: shared oracle, controls and this new receipt.
All original six complete control bodies, eleven full flows, five existing
families, fixtures and34 real frozen/native bindings remain unchanged.
All incumbent source/docs, prior receipts/deviation and App lock preserved.

| Source under android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/ | Parent8bb blob | Corrective source blob |
| --- | --- | --- |
| MountedTextAssertions.kt,200 lines | `1bd1f2ac2e062c586c5447c676dd05b860f41794` | `939c7260c3e0c4ff916292020a17116f5ef7efb7` |
| MountedTextAssertionsTest.kt,379 lines | `dd23a6e745d8df51221477f18f83c57196ba05a7` | `813108da3b124ae05613e9e355ba6bdcabadecf7` |
| MountedNativePaintObservations.kt,234 lines | `e771a88f2c8ae3172543aa0e25c77e15be41bdfd` | ENTIRE FILE UNCHANGED; outside three-path delta |

Complete canonical-source reversals PASS:

- Restoring ONLY the horizontal premise/comment/diagnostic label recovers
  ENTIRE8bb oracle SHA256
  `c26333637de21153216b6346204626b4b2bf96bc586ecb96d1437d9009a074af`.
  Every other predicate/body byte is unchanged, not just method names.
- Removing ONLY the four explicit raw-endpoint comparison lines recovers
  ENTIRE8bb control file SHA256
  `7c721831ad75cde0e9def23c9b898880cf2aa51aa40cbb10551e98cc2609271c`.
  Entire six-old-control tail, native/derived Rect equality, existing genuine
  clipping/ink-negative bodies, method definitions and fixture copy exact.

Entire NewChat`f858c733eec71bc64a7eaf8d9e83b0cae71458ed`,
Emoji`b6ddc2eeae41cac40d4fb664525fa21a8db17a54`,
Reaction`ff3add425a6026eaa48d92d08704be60fc3ccaca`,
ContactMatch`ca8a9d0b7915f01d4628acb03d06d1997f8052ee`,
Rooms`276317f4fe7521fe4c016bc55357372c85e09278`, all other existing
UI test files and prior evidence/deviation blobs remain exact8bb.
All34 actual independent provenance bindings PASS:16 frozen db145/8bb/current
and18 native mainDBB/8bb/current, using unchanged software-verification.md.
Manifest/policy/App lock`c4d5b74acc0efef9f4162c95d843c4c2c1186ce7` exact.

Declared source-safe commands actually run:

```text
python tools\android-port\portmap.py              PASS1737 mappings
python tools\android-port\controller\validate.py  PASS1866 pinned/1786 owned/
                                                80 excluded/64 WPs/178 edges
                                                manifest977b
git diff --check                                 PASS
```

These and whole-file reversals/provenance checks are SOURCE results only,
not Kotlin compilation/discovery, endpoint/ink execution or any named pass.
No local compiler/Gradle/WSL/native/device/host readiness probe, lease/runtime/
hook/main/policy/dispatch/rerun/poll/merge/gate action. No publication,
amend/rebase, new worker/family or duplicate CI-result bundle.
Stop at ONE LOCAL direct successor for complete source review. Actual own
selected22 identities, clean nonempty App/complete mandatory selected reports/
own all-six outcomes remain required. Geometry/ink/physical/API37/native/
protected/full-WP acceptance and all original product gaps remain unproven.
