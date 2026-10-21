# WP-502: caret coverage and strict native-ink containment

**LOCAL / UNPUBLISHED / UNVERIFIED, UNCOMPILED, UNRUN.** One direct source
correction after `c2836c1f85328461a5a176341180f5879e397fcc`, not another
diagnostic-only candidate. This changes the AndroidOnly oracle contract
explicitly: per-UTF16 caret intervals retain logical/line/caret validation;
unclipped native paint, not caret interval X edges, establishes ink
containment. No geometry threshold, product or fixture changes. The new
contract and its new negative assertions still require execution/review.

## Exact inputs, ownership and supplied failure

Repository `cbattlegear/MeshCoreOne-Android`, issue135, existing DRAFT249.
Direct parent/public head `c2836c1f85328461a5a176341180f5879e397fcc`.
Main/base input `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
frozen Swift reference `db14559b39d32322b06477c6ae676112f583db50`.
Manifest semantic hash
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`,
blob `582edce11e1cb7761bc1b8f2de7e5c1caaeae27a`;
policy revision
`e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`,
blob `b77c6dc59ec2c626ea560484063808785233e948`.

Fresh actor cbattlegear, remote mainDBB, OPEN/DRAFT249-atc283/baseDBB, clean
exactc283, immutable source, trusted tool/policy/manifest/App lock and same
canonical owner41 guards passed before edits. Same attempt/session
`3bc8c1b8-20ec-4647-88ae-db3775268489`, runtime alias
`fb112832-0a71-4f6d-abc0-f07f80eaeb8b`. Actual trusted `wp-owned`/`modify`
evolution41 ->42 admitted the four owned paths BEFORE editing. No new actor,
reservation recreation, historical authorization/bootstrap rewrite or
protected/native operation. Unpaused/off/activationfalse policy unchanged.

The coordinator supplied authoritative IN-MEMORY official exactc283
automatic39590202907 observations. At that read the overall run was still
IN_PROGRESS; Linux119189723086 was completed FAILURE,848 tests completed/
2 failed and complete mandatory selected results absent.
SwiftData119194781651 was upstreamSKIPPED, NOT scope-excluded or passed;
SwiftCodec119189722881 was still in progress. These are historical supplied
statuses, not a later aggregate result. This worker did not poll CI or
download/save/hash/upload another result bundle.

Both named failures reach native/derived exact paragraph/line/EVERY-UTF16
agreement and real mounted raster observations, THEN reject the old box:

| Control observation | Supplied actual geometry and pixels |
| --- | --- |
| fixed-right-bidi inside mixedHebrewLatinLtrPaintMatchesActualCallbacksForContentCenterRightAndWrappedText | UTF16=8 box251.8..260.8 versus260 allocation; native ink447/outside0/clipChanged0/retained447/extents191,4..259,18; actual mounted text447; raw and clipped allocation/halo mismatches ALL0, classification both; actual mounted origin0,96. |
| after real scroll inside mixedBidiRightAlignedCopyStillRequiresRealTargetViewportContainmentAndScrolling | Corrected unmerged lookup and native comparison reached. UTF16=8 box87.8..96.8 versus96 allocation; same447/0/0/447 native ink, extents27,4..95,18; actual mounted447; raw/clipped allocation and halo mismatches ALL0, both; actual mounted origin24,0. |

These positively establish ONLY the reached comparisons and numeric pixels
at those fixtures. both does NOT distinguish actual clipping/clip-chain.
They do not establish methodPASS, a benign universal0.8px rule, named passes
for the other20 identities (including the genuine clipping negative), glyph
attribution, physical/API37/font/native acceptance or whole-WP completion.
Failedc283/779/630/47/090/781 histories and diagnostic-only source findings
5598800641 remain; no previous outcome is transferred to this candidate.

## Pinned native source contract, not a rectangle-name inference

Exact Compose1.10.6 first-party source entries were read in memory from
`https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.10.6/ui-text-android-1.10.6-sources.jar`.
Source-entry SHA256 binds inputs, not duplicate hosted-result artifacts:

| Source entry / lines | Contract / SHA256 |
| --- | --- |
| androidMain/androidx/compose/ui/text/AndroidParagraph.android.kt388-448/490-507/521-589 | getBoundingBox delegates native TextLayout; getPathForRange delegates Android SELECTION path, not glyph outline; primary/secondary caret and paragraph/run directions are distinct APIs. Native paint delegates layout.paint; its max-lines clip is separate. `0a619c712f2d424fda1838547a3fe8ddd952a6d745002152411c2dccddd53a2c` |
| androidMain/androidx/compose/ui/text/android/TextLayout.android.kt406-430/519-598/765-826 | Character rectangle combines bidi-selected primary/secondary insertion positions: downstream(offset), upstream(offset+1), plus WHOLE line top/bottom. Public downstream caret queries cannot be substituted for its upstream boundary. getLineRight is a separate Android metric. Paint calls actual Android layout.draw through TextAndroidCanvas, not these boxes. `7cb6dc98106c65eda570c3d890977a237e8de72fe9cf9f7f685904d1fec91493` |
| commonMain/androidx/compose/ui/text/Paragraph.kt248-278 | Every UTF16 position has native layout information; grapheme/ligature continuation boxes may have zero width. Neither positive width per unit nor distinct glyph-per-unit is required. `7d32206f015f72c0a30e7428a29ab18ec91061716f5ba3f6d6a37d1a1826c1d4` |
| commonMain/androidx/compose/ui/text/MultiParagraph.kt490-505/640-650 | Public paint delegates to its actual Android paragraphs with the actual style, preserving paragraph translations. getBoundingBox translates native per-paragraph caret rectangles to the multi-paragraph origin. `793ba1ec967b66dd0b94f9baaa9c7a7b04b684f68c9f66c3812ecee40592edb4` |
| commonMain/androidx/compose/ui/text/TextMeasurer.kt260-349 | Actual supplied text/style/resolver/density/direction/constraints determine native MultiParagraph; layout width is intrinsic clamped to the original min/max policy. No alternate font or text renderer is introduced. `7709fa3764c97708777397a68002fcac1797ead641911dfcafaa645af5eaf037` |
| commonMain/androidx/compose/ui/text/TextLayoutResult.kt302-321/394-408/449-503 | size is required paint allocation; didOverflowHeight includes didExceedMaxLines; didOverflowWidth compares paragraph workspace width. Public per-UTF16 bounds and caret methods are not ink masks. `5d6fc0f764765d2cb2361140169e8957807f91bd873c8c78a0861a641d1877c9` |

Previously pinned first-party Canvas/asImageBitmap/native Canvas, Material
Text color and conditional TextStringSimpleNode/TextAnnotatedStringNode
painting entries, plus unchanged repository owning-view draw prior art,
remain inventoried in native-paint-observations.md. That receipt is unchanged.
Simple text clips only on actual cache.didOverflow; annotated text clips on
hasVisualOverflow && overflow!=Visible; ancestors may add clipping.
No reconstructed overflow flag is taken to establish an actual clip chain.

The upstream implementation proves the old per-UTF16 X guard tested
insertion/selection-layout extents, NOT the claimed painted-content extent.
The supplied actual pixels independently expose a counterexample at BOTH
unchanged fixtures: a caret rectangle crosses the allocation while native
and mounted visible ink does not. No epsilon is needed or allowed. A
selection path, cursor/advance rectangle or normalized line origin would
still be the wrong observable for this ink requirement and is not used.

## Explicit replacement and retained obligations

The entire shared oracle prefix through complete logical line coverage and
all line/allocation checks remains unchanged, apart from its AndroidOnly
description. Original full literal/AnnotatedString/style/fontScale/layout-
direction identity, native layout-size reconstruction, line count/start/end,
no ellipsis, no height overflow, measured text/node, target/text/root/ALL
scroll ancestors/owning native window/visible display frame/insets and48dp
consumer guards remain. The measured-minimum/original-maximum layout policy
is EXACTc283 and unchanged; no coordinate-offset normalization.

The old `contains(size, getBoundingBox(offset))` assertion is EXPLICITLY
replaced, not silently discarded or renamed as glyph proof:

1. Still obtain EVERY UTF16 box via native paint layout, including spaces,
   combining marks, surrogate halves and ZWJ continuations. Full logical
   line ranges must cover offset0 through literal.length without a gap.
2. Each unit must belong to its actual logical line, have finite ordered
   native caret coordinates, and retain EXACT native line top/bottom.
   Those line rectangles still fit the original allocation, with zero
   tolerance. Every boundary at unit offset AND offset+1 must have finite
   primary and secondary public caret positions. No direction/surrogate
   waiver, filtering, skipping or claim of per-unit glyph ink.
3. Out-of-allocation caret extents remain printed, including unit/offset/
   actual bounds/allocation/line/run+paragraph direction, explicitly labeled
   meaning=caret-not-ink. The former failing0.8px is neither rounded nor
   clamped; its full native value remains observable.
4. AFTER every unit is validated, ALWAYS assert strict unclipped native ink
   containment for the WHOLE actual paint paragraph. This is unconditional,
   not invoked only when the old box fails and not a favorable raw/clipped
   acceptance fallback. Pixel containment is NOT inferred from line edges.

`assertNativeTextInkContained` paints the real Android MultiParagraph using
the observed solid color, shadow/decoration/drawStyle and source-standard
null defaults into a transparent native ARGB bitmap through the public
native Canvas adapter. Unsupported brushes/unspecified color fail explicitly.
No candidate fake renderer, raster mask from expected strings, selection
path, glyph rectangle surrogate, font replacement or expected-pixel vector.
The selected paint input is the unchanged native measured-minimum recovery
from the real mounted semantics, with the actual original resolver/style/
density/direction/wrap/maxLines/overflow/placeholders. Existing callback
controls still independently compare all native/derived inputs/paragraph/
baselines/lines/EVERY-UTF16 BEFORE positive checks; source policy and c283
reached observations support that equivalence, not an invented engine.

No allocation clip is applied to the native ink bitmap. didExceedMaxLines
is explicitly rejected before drawing, so its native internal paint clip
cannot hide truncated lines. Every nonzero-alpha pixel counts, including
antialiasing: the REQUIRED outside count is EXACTLY0 on all four allocation
edges. Nonempty patterned paint is required; a blank/transparent result
cannot pass. Native output touching a probe-buffer edge fails explicitly.
Bitmap margins/ceil are only unchanged diagnostic storage, with the exact
original allocation/origin printed. They never enlarge the node/window,
alter the font/style/copy or relax a caret/line/ink coordinate.
The assertion describes API31 native raster containment, not continuous
vector glyph bounds, font fidelity or pixels beyond the bounded probe.
The primary ink-rejection marker and actual allocation/counts/extents/probe
origin precede the potentially long callback/UTF16 context in failures;
no bounded official-log excerpt must infer them from a later suffix.

Native probe sizing/paint attributes are extracted from the existing mounted
observer into shared private helpers, preserving the exact c283 arithmetic,
style checks and native paint invocation. The mounted observer's raw/clipped
capture/composites/BOTH zero-tolerance comparisons/four descriptive matches/
neither failure remain unchanged. Source-supported expected ink containment
is DISTINCT from diagnostic mounted hypothesis matching. both still proves
neither clip chain; no selected hypothesis feeds the strict ink assertion.
The new one-bitmap strict operation and all five mounted buffers retain
finally cleanup; native Canvas restore remains finally-protected.

## Genuine negatives and original-versus-Android consequences

The EXISTING center/right64dp clipping family gets only a new direct check
of the new native-ink assertion, using the unchanged actual cached annotated
native layout, NOT copied rendering or manipulated inputs:

- Original actual offscreen ink-loss AND distinguishable clipped-only
  mounted witness still execute first; no named pass is inferred from c283.
- Directly call the NEW ink assertion and require a real AssertionError with
  primary marker "painted ink outside measured text". A probe/blank/style/
  callback failure cannot become this expected clipping success.
- Original line-bound rejection still follows. Both center and right
  fixtures retain the same literal/font/64dp/softWrap=false/Clip settings.

No new Test method/family, golden, expected image or OriginalCase accounting.
The original six entire control bodies are unchanged. Existing mixed-bidi
LTR/RTL200/content/center/fixed-right/wrapped and real post-scroll positive
families automatically exercise the new shared oracle after their retained
native callback and mounted observations. Eleven full flow bodies and
separate ContactMatch/Rooms incumbents are untouched.

This is a deliberate AndroidOnly HARNESS correction, not a Swift product
adaptation or claim of new parity. Frozen views/tests remain immutable;
their literal selection/grouping/filter/service/cancellation contracts are
unchanged. Android now requires complete logical text and actual native
painted ink inside the allocation, while permitting native insertion/caret
intervals to extend beyond it. Actual painted overhang/clipping STILL fails
at outside>0, even if line/caret boxes fit. This may be MORE strict for
real ink overhang and LESS restrictive for invisible insertion geometry.
It grants no exception for RTL, fonts, emoji, surrogate units or0.8px.
Original labels/targets remain wholly visible in real current viewports.

## Complete source comparison and bounded result

Four owned paths: shared oracle, shared native paint support, controls and
THIS new receipt. All earlier receipts/deviation files remain byte-exact,
including failed candidates' supplied histories.

| Source under android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/ | Parentc283 blob | Current source blob |
| --- | --- | --- |
| MountedTextAssertions.kt,199 lines | `3f2ebe3b94eb0669df33c050dfd493250fc1c214` | `1bd1f2ac2e062c586c5447c676dd05b860f41794` |
| MountedNativePaintObservations.kt,234 lines | `e923c56e5b8eb564bd886ba76dd67fca7138dc63` | `e771a88f2c8ae3172543aa0e25c77e15be41bdfd` |
| MountedTextAssertionsTest.kt,375 lines | `3cd23065f5784ee51cb84cdabee660fe5bd2e88b` | `dd23a6e745d8df51221477f18f83c57196ba05a7` |

Whole canonical-source reversals PASS, not just function-name comparisons:

- Only the reviewed UTF16 block/new strict-ink call/header reverse restores
  ENTIREc283 oracle SHA256
  `173847d8bc03cf57349a0c3b1661a44d956ee9529e824dd3cf352fa0f143ae90`.
  Complete prefix/all old line/full-text/style/viewport predicates exact.
- Removing ONLY the new five-line direct-ink negative check restores
  ENTIREc283 controls SHA256
  `0cb074a64dbaf374bed43032ec28da1baece3786dfe71a70c2603fb4e2adb384`.
  Six-old-control complete tail, eleven method identities, actual unmerged
  finder/default preservation, fixtures, dimensions and five families exact.
- Removing ONLY new strict-ink/private extraction/header and inlining the
  EXACT original sizing/paint code restores ENTIREc283 native observer
  SHA256 `c2db7b4e67d6a9e7fd0b77c033c84cb151afc722ab709f649a22e51a69d6c61f`.
  Entire mounted capture/BOTH hypotheses/negative ink loss/clipped-only
  witness/classification/failure-cleanup body unchanged after extraction.

Entire NewChat`f858c733eec71bc64a7eaf8d9e83b0cae71458ed`,
Emoji`b6ddc2eeae41cac40d4fb664525fa21a8db17a54`,
Reaction`ff3add425a6026eaa48d92d08704be60fc3ccaca`,
ContactMatch`ca8a9d0b7915f01d4628acb03d06d1997f8052ee`,
Rooms`276317f4fe7521fe4c016bc55357372c85e09278` and all prior evidence/
deviation blobs remain exact. All34 real source comparisons PASS: actual16
frozen db145/c283/current and18 native mainDBB/c283/current inputs, using
the unchanged literal software-verification.md inventory. Manifest/policy
and App lock`c4d5b74acc0efef9f4162c95d843c4c2c1186ce7` unchanged.

Declared SOURCE checks actually run:

```text
python tools\android-port\portmap.py              PASS1737 mappings
python tools\android-port\controller\validate.py  PASS1866 pinned/1786 owned/
                                                80 excluded/64 WPs/178 edges
                                                manifest977b
git diff --check                                 PASS
```

These checks and in-memory source reversals/bindings are SOURCE results,
not Kotlin compilation, test discovery or native pixel/control execution.
No compiler/Kotlin/Gradle/WSL/native/device/host readiness, advisory, lease/
reentry/restart/runtime/hook/main/policy/dispatch/rerun/poll/merge/gate action.
No publication, amend/rebase, new worker/family or duplicate CI bundle.
Stop at ONE LOCAL direct successor for complete source review.

Actual new ink-guard/control/flow discovery and results remain UNOBSERVED.
Future own hosted verification must positively execute3NewChat+4Emoji+
4Reaction+11controls with clean nonempty App, complete mandatory selected
reports and own all-six outcomes. The22 source definitions are not named
passes. Product gaps, removed-translation boundaries, Emoji cause-unresolved,
all27/native and physical/API37/full-WP holds remain unchanged.
