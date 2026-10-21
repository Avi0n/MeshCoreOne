# WP-502: native caret geometry diagnostic boundary

**LOCAL / UNPUBLISHED / UNVERIFIED, UNCOMPILED, UNRUN.** This bounded
successor repairs diagnostic ordering and truncated observations. It does
NOT repair or waive the observed0.8px overrun, establish clipped/unclipped
glyph ink, or claim passing controls/flows/product/font/device/WP acceptance.
The geometric interpretation remains blocked on actual same-run observations.

## Exact authority, failure and unchanged scope

Repository `cbattlegear/MeshCoreOne-Android`, issue135, existing draft249.
Direct parent `630d802224bd6f3d2af0ab76290626fda3bdca6b` remains public;
no amend/reorder/rebase/reset or successor publication.
Main input `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`;
frozen reference `db14559b39d32322b06477c6ae676112f583db50`;
manifest `977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
policy `e8afe0670176107dec414025eeb06f9dc9070c027ad6fb244802cd1f3a0cb9f3`.

Fresh actor cbattlegear/mainDBB/open-DRAFT249-at630/clean630/source/policy
guards passed. Same canonical owner/attempt
`3bc8c1b8-20ec-4647-88ae-db3775268489` evolved trusted `wp-owned`/`modify`,
expected39 -> actual40 BEFORE editing these three owned paths. Historical
authorization context/bootstrap binding was not rewritten or recreated.
Retained native owner2be/acquisition was read only; no native lease operation.
Unpaused/off/activationfalse policy, installed hook/config and App lock remain.

The coordinator independently supplied official exact630
automatic39578322947 terminalFAILURE:
Linux119150169410 FAILURE, failed-command App848 discovered/846 passed/
2 failed/0 errors/skips; mandatory downstream reports INCOMPLETE;
aggregate119157861102 FAILURE; SwiftData119157864721 upstreamSKIPPED,
not scope-excluded or passed. This worker did not poll/dispatch/rerun CI or
save/hash/upload a duplicate CI-result bundle.

The two failed identities are NEW AndroidOnly controls:

| Method | Supplied actual observation / limitation |
| --- | --- |
| mixedHebrewLatinLtrPaintMatchesActualCallbacksForContentCenterRightAndWrappedText | Stops at fixed-right-bidi: actual260x24 node/paragraph260, line189.2..260. Available main diagnostic truncated near2000 characters; exact final box and primary rejected offset are NOT established. |
| mixedBidiRightAlignedCopyStillRequiresRealTargetViewportContainmentAndScrolling | Stops AFTER real performScrollTo at check224. Exact full text `\u05e9\u05dc\u05d5\u05dd Beta`; actual text node(24,0)..(120,24), measured96x24/paragraph96, line25.2..96; derived size96x24. Last UTF16=8 box(87.8,0)..(96.8,24) fails strict containment. |

Neither0.8px, line-right96 nor the other failure's truncated output proves
floating-point epsilon, harmless bearing, actual glyph clipping, wrong
derivation or backend bug. Suppressed queued runnables are not primary cause.
Failed-only evidence establishes no named passing verdict for the other20
identities. Failed630,47,090 and781 histories remain failed and unmodified.

The native-callback comparison in630 was AFTER the failing positive `check`,
so no native-versus-derived equality verdict was reached at that failure.
The scrolled fixture previously had no native callback twin. Those two
observation gaps, not the geometric thresholds, are corrected here.

## Pinned Android caret, line and drawing contracts

Existing Compose ui-text/foundation1.10.6 sources were read in memory from:

```text
https://dl.google.com/dl/android/maven2/androidx/compose/ui/ui-text-android/1.10.6/ui-text-android-1.10.6-sources.jar
https://dl.google.com/dl/android/maven2/androidx/compose/foundation/foundation-android/1.10.6/foundation-android-1.10.6-sources.jar
```

These are first-party source reads, not dependency restoration, execution or
copies of upstream code. SHA256 binds source entries, NOT CI-result bundles.

| Exact entry | Source-supported contract / source-entry SHA256 |
| --- | --- |
| androidMain/androidx/compose/ui/text/AndroidParagraph.android.kt388-426/429-448/456-507/521-590 | Bounding boxes delegate to TextLayout; getPathForRange is Android selection path, not glyph outline. Public primary/secondary positions are caret APIs. Paragraph painting delegates native canvas to layout.paint, with max-line clipping when applicable. `0a619c712f2d424fda1838547a3fe8ddd952a6d745002152411c2dccddd53a2c` |
| androidMain/androidx/compose/ui/text/android/TextLayout.android.kt406-430/525-583/765-826 | Character rectangles combine bidi-dependent downstream offset/upstream offset+1 caret horizontals and whole line top/bottom. Line-right delegates a DISTINCT Android line metric. Native paint calls layout.draw through TextAndroidCanvas; bounds are not derived from these per-character rectangles. `7cb6dc98106c65eda570c3d890977a237e8de72fe9cf9f7f685904d1fec91493` |
| commonMain/androidx/compose/ui/text/Paragraph.kt248-278 | Per-character layout boxes can have zero-width grapheme continuations. This is not an ink guarantee. `7d32206f015f72c0a30e7428a29ab18ec91061716f5ba3f6d6a37d1a1826c1d4` |
| commonMain/androidx/compose/ui/text/TextLayoutResult.kt385-408/449-503 | Public line baseline, primary/secondary position, paragraph/run direction and character-box signatures used by new observations. The public caret call does not expose the internal upstream selector. `5d6fc0f764765d2cb2361140169e8957807f91bd873c8c78a0861a641d1877c9` |
| commonMain/androidx/compose/foundation/text/modifiers/TextAnnotatedStringNode.kt522-569 | Actual draw optionally clips measured bounds on visual overflow, then paints its real cached MultiParagraph. `aec6edcdefd3f2506140c2b5e6b08f54d27047b60f8ff0305b272f93e268f944` |
| commonMain/androidx/compose/foundation/text/modifiers/TextStringSimpleNode.kt440-474 | Actual Paragraph paints with the selected style/color; clip restoration is separate from per-offset observations. `17eb39f90cff510ba13784b699516f464f79c49633bcfc586815186a40f74e14` |

This trace distinguishes caret/line/draw contracts but cannot determine
actual ink, shaping, bearing or a native-backend error from the supplied
numbers alone. No substitute selection-path/whole-text rectangle is used to
certify individual glyphs. The630 measured-minimum derivation remains EXACT,
not adjusted to hide the last offset.

## Diagnostic-only implementation, all geometry predicates retained

The entire `mountedPaintLayout` and the shared helper prefix through original
logical-line/ellipsis/height checks remain byte-identical. Only diagnostics
around existing line/UTF16 containment differ:

- Print compact WP502_LINE_REJECT or WP502_CARET_REJECT at an actually
  rejected bound, including actual offset/code unit/line/directions/allocation.
- Put the actual rejected offset/bounds/allocation and control context FIRST
  in the assertion message, before the existing long style/layout dump.
- Keep EXACT `contains(size, paintLines[line])` and
  `contains(size, bounds)` predicates, EVERY UTF16 offset, complete suffix/
  logical line starts/ends, no ellipsis and no vertical overflow. Zero tolerance.

No ceil/clamp/epsilon, direction/surrogate exclusion, coordinate translation,
larger frame/allocation, font/padding change, alternate fake renderer or
unconditional fallback. Actual target/text/root/ALL scroll ancestors/owning
Android window/visible display frame/insets/48dp checks are unchanged.
Insets still are not subtracted twice.

Controls now read the real simple-string semantics and actual onTextLayout
callback BEFORE either twin's positive full-containment check. They report
semantics/derived/native-callback inputs, all line ranges/edges/baselines/
ellipsis, EVERY UTF16 box/direction and primary/secondary caret positions
including the end offset. No offset is filtered. Then the original complete
native-versus-derived assertions run BEFORE the shared strict checks.
Actual mismatch fails there; no later agreement marker is fabricated.

Only after all original native agreement assertions succeed is the real
native size/paragraph/line/every-UTF16 observation retained in the exact
control-tag context. Thus the potentially truncated failure prefix still
contains native values and an actual reached agreement marker, rather than
depending only on standard output. Controls without such observations say
`native-callback=not-observed`; this is diagnostic absence, NOT accepted
geometry or fake native success.

This placement matters because the trusted failed-command log printer shows
bounded near-failure context/lines (including a2000-character slice) and tail;
it does not promise to expose every standard-output line or passing test.
No reporter/build/policy support edit is made. Native assertion diagnostic
labels are compact; expected/actual values remain normal JUnit assertions.

The post-scroll fixture adds a separately tagged, real96dp native callback
twin as a Column sibling AFTER the ORIGINAL scroll Row. Original120dp
viewport/112dp spacer/96x64dp target and original Text width/font/copy/
alignment/overflow are unchanged. Only a test tag is added to that original
Text to obtain its exact layout. The sibling does not replace/copy the
asserted renderer or enlarge the original viewport/target/window. It uses
the same literal text/style/font/alignment, captures real callback output,
and additionally must pass the original shared strict text checks.

Original partial48dp rejection, real performScrollTo and post-scroll positive
check remain. Both potential equality and the still-strict bounds failure
are observable without manufacturing a passing negative/positive control.
The same source could still fail at the same overrun; this is intentional
until the actual geometry interpretation is supported, not a claimed repair.

## Whole-source preservation and byte binding

| Owned test path under android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/ | Parent630 blob | Current blob |
| --- | --- | --- |
| MountedTextAssertions.kt (187 lines) | `ead4e5efa8809213d99ab907f1fe782795ce58fc` | `3f2ebe3b94eb0669df33c050dfd493250fc1c214` |
| MountedTextAssertionsTest.kt (359 lines) | `c909edd23c4c93e9d039745ecaebafd2d87fd2e2` | `d1c775d863b05b01ed4cd3ef0ee3e6e2e04bce4c` |

The third path is this new owned
`docs/android/evidence/WP-502/caret-native-diagnostics.md`.
Reversing only helper diagnostic blocks/messages restores ENTIRE630 helper;
reversing only observations/retained diagnostic context/call ordering/tag/
sibling restores ENTIRE630 control file. Not merely function-name equality.
The complete six-old-control tail is exact, all five newer bidi/center/right/
wrapped/viewport families remain, and all original assertion predicates remain.
There are STILL eleven control definitions, NOT discovered tests.

Entire NewChat`f858c733eec71bc64a7eaf8d9e83b0cae71458ed`,
Emoji`b6ddc2eeae41cac40d4fb664525fa21a8db17a54`,
Reaction`ff3add425a6026eaa48d92d08704be60fc3ccaca`,
ContactMatch`ca8a9d0b7915f01d4628acb03d06d1997f8052ee`,
Rooms`276317f4fe7521fe4c016bc55357372c85e09278` and ALL incumbent
WP502 evidence/deviation files remain byte-identical. All11 original full
flow bodies/independent vectors/forbidden ports/read counts/MRU/draft/
selection/caret/cancellation/locale/theme/font and090 real scrolling/ordering/
48dp predicates are untouched.

All34 independent input bindings PASS fresh comparisons:16 frozen inputs
equal db145/630/current and18 native equal mainDBB/630/current, as inventoried
in unchanged software-verification.md. Manifest/policy/App lock are unchanged;
no source advancement, OriginalCase recount or product acceptance.

## Actual source-safe checks and precise blocker

```text
python tools\android-port\portmap.py              PASS1736 mappings, unchanged
python tools\android-port\controller\validate.py  PASS1866 pinned/1786 owned/
                                                80 excluded/64 WPs/178 edges
git diff --check                                 PASS
```

In-memory full-file reversal/preservation/order checks, actual strict
predicate checks, all34 input pins and all incumbent evidence comparisons
PASS. These are source/data results, not Kotlin compilation/discovery or
native/callback/raster execution.

**BLOCKED interpretation:** same-run native callback versus derived boxes at
both exact failing cases remains UNOBSERVED. Even equality would establish
only backend-layout agreement, not prove ink containment. Actual native paint
observations plus genuine clipping negatives are required before replacing
any caret-box proxy with a different geometric interpretation. No safe source-
only conclusion selects harmless advance/bearing, derivation error, actual
clipped fixture or backend bug. Do NOT waive the strict current guard from
the supplied0.8px or line-right numbers.

Future evidence must use the existing App/scaffold runner, positively execute
exact3NewChat+4Emoji+4Reaction+11controls, and establish clean nonempty App/
complete mandatory selected reports/own all-six outcomes. Twenty-two
definitions are NOT22/22 passing execution; controls remain AndroidOnly.

**NOT RUN:** compiler/Kotlin/Gradle/WSL/native/guest/device execution, host/
advisory readiness probes or selectors. No publication, CI poll/watch/sleep/
dispatch/rerun, native lease/reentry/release/restart, hook/runtime/main/policy/
merge/settings/signing action, new worker or product family. Stop at this
one LOCAL diagnostic successor for complete source comparison. Actual
geometry/paint/flow/control acceptance and all prior product/WP gaps stay open.
