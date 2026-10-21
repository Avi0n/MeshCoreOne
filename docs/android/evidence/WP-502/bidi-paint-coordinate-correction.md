# WP-502: reconstructed bidi workspace versus paint coordinates

**LOCAL / UNPUBLISHED / UNVERIFIED, UNCOMPILED, UNRUN.** One bounded
test-oracle successor, not a product repair, passing flow/control verdict,
font/raster/device approval, new original case or WP completion.

## Identity, exact failed parent and scope

Repository `cbattlegear/MeshCoreOne-Android`, issue135, existing draft249.
Direct parent `47ab453921172d1e4455dc5f79f517137b1292ad` remains published;
no reset/amend/rebase/reorder, descendant publication or passing-result transfer.
Main input `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
frozen source `db14559b39d32322b06477c6ae676112f583db50`;
manifest `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.

Fresh actor cbattlegear/mainDBB/open-DRAFT249-at47/clean47 guards passed.
The same canonical owner/attempt `3bc8c1b8-20ec-4647-88ae-db3775268489`
used the trusted `wp-owned`/`modify` evolution, expected38 -> actual39 BEFORE
edits, for only the existing helper/control files and this new owned receipt.
Authorization context and historical bootstrap binding were not rewritten.
The retained native-execution owner2be was read, not acquired/released/changed.
Policy remains unpaused, dispatch off, activation false; no policy installation.

The coordinator independently read exact47's official Linux119135290838 log
in memory: automatic39573939089 Linux FAILURE; failed-command App843
discovered,841 passed,2 failed,0 errors/skips. Mandatory downstream module
reports are INCOMPLETE. SwiftData119141537844 is upstreamSKIPPED, not passed;
aggregate119141535687 was still IN_PROGRESS at that supplied observation.
This worker did not poll for a later aggregate result, save a second result
bundle or treat the aggregate's old status as a current passing verdict.

Both actual NewChat methods fail the same parent oracle line125 on full
literal `\u05e9\u05dc\u05d5\u05dd Beta`:
`heldLoadPreservesOrderThenRealSearchSelectsExactlyTheOriginalDisplayNameContactWithoutReload`
and `ineligibleVersusEligibleListsKeepLocalizedCancelIndependentAtLargeRtlFont`.

| Observed input | Actual node / measured text | Rebuilt semantics paragraph / line |
| --- | --- | --- |
| English100% LTR | (68,980)..(143,1004),75x24 |276x24; left202.35625/right276 |
| Chinese200% RTL | (122,1083)..(252,1125),130x42 |236x42; left107.67343/right236 |

Full expected/actual text matches; no ellipsis/height overflow. Character
boxes share that wider right-aligned coordinate workspace. These numbers do
NOT establish painted contact-name clipping. Failed-only output establishes
no named passing verdict for the other15 methods or six oracle controls.
Earlier090837/829/8 and781 failures remain in their unchanged receipts.
Emoji's090 primary cause remains unresolved; its watcher is untouched.

The prior47 source conclusion missed this bidi coordinate-frame case.
Its strict bounds predicate mixed reconstructed workspace coordinates with
the smaller real paint allocation. This receipt corrects that conclusion;
historical source-feedback/failure records are retained, not overwritten.

## Exact first-party layout contract inspected

Existing pins are Compose ui/ui-text/foundation1.10.6 and Material3 1.4.0.
Only source archives were read in memory from Google's first-party Maven:

```text
https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.10.6/foundation-android-1.10.6-sources.jar
https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.10.6/ui-text-android-1.10.6-sources.jar
https://dl.google.com/dl/android/maven2/androidx/compose/material3/material3-android/1.4.0/material3-android-1.4.0-sources.jar
```

No dependency install/restore, Kotlin/Gradle/native execution or upstream
source copy was used. SHA256 values below bind source entries, not CI bundles.

| Source entry under commonMain/androidx/compose/ | Relevant inspected contract / source-entry SHA256 |
| --- | --- |
| foundation/text/modifiers/ParagraphLayoutCache.kt157-195,266-310,317-345,366-402 | Painter uses resolved paragraph intrinsics/finalConstraints; changes in either minimum or maximum width force new layout. Semantics reconstructs MultiParagraph with copyMaxDimensions, losing minima, while retaining original layoutSize. `e7dcd7051122d18adc560db6518cdc8d4b4adaad0798e08319d9d12df0bd4db4` |
| foundation/text/modifiers/LayoutUtils.kt24-85 | finalMaxWidth chooses bounded maximum for wrapping/ellipsis, otherwise infinity, then clamps rounded intrinsic width to the minimum/maximum interval; unwrapped ellipsis uses one line. `3a865e3a830fa5a8dcb0e51f4eaa68c070bb8fdec25de1f60fa8c747708d14f6` |
| ui/text/TextMeasurer.kt82-92,140-188,260-349 | Public real Compose layout engine implements that same final-width/max-lines policy, resolveDefaults, original resolver/density and constrained measured size; cache can be disabled. `7709fa3764c97708777397a68002fcac1797ead641911dfcafaa645af5eaf037` |
| ui/text/TextLayoutResult.kt40-84,302-317 | Full observed input includes resolver/text/style/constraints; size and paragraph are distinct; geometry is paragraph-local. `5d6fc0f764765d2cb2361140169e8957807f91bd873c8c78a0861a641d1877c9` |
| foundation/text/modifiers/TextStringSimpleNode.kt291-303,349-388 | GetTextLayoutResult invokes reconstruction; actual measure uses ParagraphLayoutCache and places measured content at0,0. `17eb39f90cff510ba13784b699516f464f79c49633bcfc586815186a40f74e14` |
| foundation/text/modifiers/MultiParagraphLayoutCache.kt253-275,365-408 | Annotated actual measured result/finalConstraints and width-cache invalidation contract. `6d96a49dcebb8f4ac71cb559d317197b484d8041888b0b5f3af18a52dd25ec3d` |
| foundation/text/modifiers/TextAnnotatedStringNode.kt344-370,419-460 | Semantics preserves measured MultiParagraph; actual onTextLayout receives native cached layout; content placed at0,0. `aec6edcdefd3f2506140c2b5e6b08f54d27047b60f8ff0305b272f93e268f944` |
| material3/Text.kt102-146 | Actual Material Text merges the same full style and forwards real onTextLayout to BasicText. `f638817208f2ac7a6db976d085acba3cbdcee22c3eb2fb6f9cbf72ca5f570d65` |

The preceding receipt's SemanticsNode/ViewRootForTest, Material Surface/tag/
window/inset and unclipped48dp source comparisons remain applicable.
Existing UI/screenshot sources contained no reusable TextMeasurer helper;
the existing returned-character-extent prior art alone cannot recover this
lost painter coordinate origin.

## Derivation, not coordinate normalization or a second renderer

Let the actual allocated integer width be S, the original constraint interval
be [minimum,maximum], and the painter paragraph width be P. The pinned painter
first derives P from rounded maxIntrinsicWidth and that interval. When text
wraps/ellipsizes, P is bounded and measured S equals P. For unwrapped clipping,
P can exceed maximum; measured S is maximum but P remains the larger intrinsic
width. Width-cache reuse does not invalidate this inference: changed minimum
or maximum width always relayouts, and constraining its rounded intrinsic/
paragraph minimum yields the same allocated S.

Semantics loses minimum, but preserves maximum and measured S. Restoring the
minimum to S reproduces P: bounded text still lays out at S; unwrapped text
whose intrinsic width exceeds maximum still lays out wider than S. This does
NOT force clipping to fit. Restoring measured minimum height likewise leaves
paragraph maxHeight unchanged; equality with the actual allocation is required.
Fixed-width centered/right-aligned text retains its actual unused left space,
rather than translating its first line/glyph to zero.

`mountedPaintLayout` therefore passes the exact observed annotated text,
full TextStyle, placeholders, resolver, density/fontScale, layoutDirection,
softWrap, overflow and maxLines to public TextMeasurer with cache disabled.
Only the lost minima are recovered from actual measured size; maximum
constraints are preserved, never enlarged. The actual size first must satisfy
the observed interval. This is derived real Compose layout, not a custom
renderer, synthetic TextLayoutResult, copied product API or expected packet.

The shared assertion retains raw semantics diagnostics/return value. Before
using derived coordinates it requires EXACT measured-size equality, full-text/
style equality, identical line count and every original logical line start/end.
Both original and derived layouts must have no vertical overflow or ellipsis.
Every line's four paint-coordinate edges and EVERY UTF16 returned box must fit
the ORIGINAL measured allocation with zero tolerance. Complete contiguous
logical coverage through the full suffix remains mandatory. No RTL, surrogate,
combining-mark, ZWJ, whitespace or zero-width continuation is dropped.
Raw versus derived paragraph/line/character coordinates are logged separately.

All actual displayed-target/text checks, exact unmerged descendant identity,
font/direction checks, measured size versus node, clipped/root/ALL scroll
ancestor containment, owning Android window/visible display frame and actual
system/cutout/IME-inset observations remain byte-identical. Insets are still
not double-subtracted. No viewport/profile/qualifier/font/padding/product
change, offset subtraction, per-line translation, min-line normalization,
larger allocation, bounds tolerance, retry/wait, broad catch or fake success.
Mismatch between derived and observed logical layout fails closed.

## Preserved six controls and five new mounted definitions

All SIX original control bodies/fixtures remain exact. Removing only two new
imports and the one added witness/method block restores their ENTIRE parent
test file. No previous negative is deleted or relaxed. Existing unconstrained/
wrapped/CJK/RTL/ZWJ positives and width/height/ellipsis/partial48dp negatives
must still execute positively through the existing runner in the future.

The two added positive methods mount five pairs of REAL Material Texts each.
One has production-style simple semantics; its twin has the SAME literal
copy/style/font/width/alignment and captures real onTextLayout output. Callback
geometry is independently observed, NOT generated by this oracle or derived
from its expected values. The test compares exact measured size, paragraph
dimensions, baselines, all line edges/starts/ends/ellipsis and every UTF16 box.
Both twins independently require the real full-window/ancestor text checks.

Literal copy includes Hebrew-first `\u05e9\u05dc\u05d5\u05dd Beta`,
Latin-first `Beta \u05e9\u05dc\u05d5\u05dd`, and the wrapped mixed
`\u05e9\u05dc\u05d5\u05dd Beta \u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd Beta \uD83D\uDC69\u200D\uD83D\uDCBB`.
Loose content/center, fixed260dp right alignment and wrapped180dp center/right
cover BOTH layout directions at100% LTR and200% RTL, using the same unchanged
native-host qualifier. These are tiny Android-only layout fixtures, not assets.

| Added complete method definition, NOT execution | Independent predicate |
| --- | --- |
| mixedHebrewLatinLtrPaintMatchesActualCallbacksForContentCenterRightAndWrappedText | Five LTR100% twin pairs; actual loose bidi/center semantics MUST exhibit workspace/shift mismatch, actual wrapped callback MUST have multiple lines, fixed-right native line MUST retain positive left free space. All derived coordinates must match native callbacks exactly. |
| mixedHebrewLatinRtlAtTwoHundredPercentMatchesActualPaintCallbacksAndAlignment | Same independently literal five-pair state family at RTL200%, including CJK/ZWJ wrapped text; the same native geometry and real-origin checks apply. |
| mixedBidiCenterAndRightClippedTextCannotPassByMovingItsCoordinateOrigin | REAL annotated Hebrew/Latin long suffix at64dp, softWrap=false/Clip, center/right. Raw native line must exceed allocation, with no height overflow/ellipsis; shared helper must specifically reject line bounds. Reconstructed intrinsic paragraph must remain wider, not shrunk to measured width. |
| mixedBidiRightAndCenterEllipsisCannotBecomeCompleteCopyThroughReconstruction | REAL annotated mixed literal at64dp/maxLines1, right/center. Actual native ellipsis is required independently; helper must specifically reject ellipsis, not silently recreate missing copy. |
| mixedBidiRightAlignedCopyStillRequiresRealTargetViewportContainmentAndScrolling | REAL120dp scroll viewport/112dp spacer/96x64dp clickable target with fixed-width mixed right-aligned text. Actual partial display and unclipped48dp minimum precede specific viewport rejection; standard real performScrollTo then must permit complete target/text containment. |

The new clipped/ellipsis controls require the exact primary AssertionError
marker, not an arbitrary exception. The positive native-callback witnesses
prevent an always-failing helper or arbitrary line translation from passing.
No six-old/five-new verdict is claimed without actual execution.

## Exact preservation and source bindings

Only three owned paths change from47:

| Path under android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/ | Parent47 blob | Current source blob |
| --- | --- | --- |
| MountedTextAssertions.kt (176 lines) | `e72a3f855bb462aed907fb386075275a6bc842b6` | `ead4e5efa8809213d99ab907f1fe782795ce58fc` |
| MountedTextAssertionsTest.kt (306 lines) | `5e43b3a304d735adb6edecac6122aee4956b57ed` | `c909edd23c4c93e9d039745ecaebafd2d87fd2e2` |

The third path is this new
`docs/android/evidence/WP-502/bidi-paint-coordinate-correction.md`.
The complete original helper is recovered by reversing only the new measurer
import/function and paint-coordinate tail/comment. Source checks separately
require all replacement strict geometry/logical-layout predicates; unchanged
profile/window/root/scroll/inset code is not merely matched by function names.

Entire NewChat`f858c733eec71bc64a7eaf8d9e83b0cae71458ed`,
Emoji`b6ddc2eeae41cac40d4fb664525fa21a8db17a54` and
Reaction`ff3add425a6026eaa48d92d08704be60fc3ccaca` files equal47.
Consequently all11 full flow methods, independent identity/read/search/MRU/
UTF16-selection/caret/cancellation/forbidden-port vectors, theme/font/locale
samples and real route callbacks are exact. All090 real scrolling/48dp/
same-observation tab/sender ordering predicates are untouched.
Entire ContactMatch`ca8a9d0b7915f01d4628acb03d06d1997f8052ee` and
Rooms`276317f4fe7521fe4c016bc55357372c85e09278`, ALL incumbent WP502
evidence/deviations and production/build/resources/goldens remain unchanged.

Fresh34 exact-object comparisons PASS using the unchanged independent tables
in software-verification.md:16 frozen pins equal db145/47/current and18 native
pins equal mainDBB/47/current. Those are Reaction8+8, Emoji5+6, NewChat3+4;
there is no source advancement, original-case accounting or parity verdict.

## Actual source checks and remaining gate

```text
python tools\android-port\portmap.py              PASS1736 mappings, unchanged
python tools\android-port\controller\validate.py  PASS1866 pinned/1786 owned/
                                                80 excluded/64 WPs/178 edges
git diff --check                                 PASS
```

In-memory source comparisons PASS complete11-flow preservation, entire6-old-
control-file restoration, helper reversal, strict actual-window/logical-
layout/all-UTF16/no-normalization predicates, five new independent native/
negative witness definitions,34 input pins and all incumbent evidence.
These are source/data checks, NOT Kotlin compilation or software execution.
An initial guard script used Windows-default decoding for policy JSON;
the trusted UTF8 loader confirmed exact main/current policy e8 before evolution.
An overbroad authoring sanity check matched the unchanged ancestor-selection
filter; the final no-coordinate/filter check applies to the actual new paint/
UTF16 code. Neither was a Kotlin test result or a lowered rendering predicate.

Future verification uses ONLY the existing declared App/scaffold runner and
`:app:testDebugUnitTest`. Require actual exact3 NewChat+4 Emoji+4 Reaction
and all11 MountedTextAssertionsTest identities (six retained/five new), clean
nonempty App/mandatory selected reports and all exact six-job outcomes.
Twenty-two definitions are NOT focused22/22 execution or new originals.
Missing/zero/failed/skipped reports remain failures, not acceptance.

**NOT RUN:** Kotlin compilation, Gradle/WSL/native/guest execution, host
readiness/advisory probes or any test selector. No publication, CI dispatch/
rerun/poll/watch/sleep, native lease/reentry/release, hook/runtime/main/policy/
merge/settings/signing/device operation or new worker/family. Official exact
hosted jobs/logs remain authoritative; no duplicate CI-result bundle.

Stop at this coherent LOCAL candidate for complete exact source comparison.
Derived-versus-native callback/negative-control and real flow outcomes remain
unobserved. Missing product reaction loading/error/times/empty-title/badge
accessibility/ACTIVE Translate removal and all previously recorded service/
radio/OS/font/assets/golden/device/full-family/full-WP gaps remain explicit.
