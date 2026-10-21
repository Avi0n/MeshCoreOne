# WP-502: observed annotated foreground repair

**LOCAL / UNCOMPILED / UNRUN; exact source review required before publication.**
Direct parent is failed `c4302902c0c895d1e067636f106220d8ae19a5c8`.
Existing DRAFT249 remains on that parent, base/main
`dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`; immutable source remains
`db14559b39d32322b06477c6ae676112f583db50`.
Canonical owner/attempt `3bc8c1b8-20ec-4647-88ae-db3775268489` evolves
actual48 -> returned49 through trusted `wp-owned`/`modify` for precisely
the two owned adapter/control files, this receipt and the deviation.
Manifest977b/raw582e and policye8/rawb77c remain unchanged.

## Actual failure and source-backed cause

Coordinator supplied exactc430 own39634963018/Linux119343688579 terminal
FAILURE: App852 started/851PASS/1FAIL, aggregateFAIL/SwiftData upstreamSKIPPED.
Mandatory reports were incomplete. The new bidi method's FIRST retained-UI
measurement reaches `MountedNativePaintObservations.kt:24` and fails
`observed explicit native color required` for `@Alice\u202e`, seven UTF16
units. No completed raw-text/key/navigation verdict or later ink/transition
pass is inferred. The suppressed looper exception is secondary, not a
demonstrated cause. No CI-result bundle or log is copied here.

Actual production MessageBody blob `175192ba3ed6096bc23853a98f7cd74ef8f60c5d`
adds an explicit resolved foreground SpanStyle over 0..text.length FIRST,
then actual resolved link styles. Its BasicText uses typography.bodyLarge
without supplying a paragraph foreground. Pinned BasicText/TextLinkScope
preserve the original annotations and add resolved link SpanStyles to the
actual text passed to TextAnnotatedStringNode. Its semantics preserve that
annotated text and can leave paragraph style.color Unspecified.

The original adapter looked ONLY at paragraph style.color, so it rejected
this supported production representation before native paint. This is not
an observed ink-overflow finding or evidence that the raw control was lost.

## Narrow adapter correction

The explicit paragraph-color path still uses exactly that observed color.
For an unspecified paragraph color, require the FIRST observed SpanStyle to
cover EXACTLY 0..the complete original text.length with an explicit color.
Require a solid paragraph path and no shader-bearing span in either branch.
Missing span, partial coverage and unsupported brushes fail explicitly.
No search for a favorable later span, guessed color, renderer-default Black,
rewritten span order, sanitized text or synthesized paragraph is used.

Use that actual full-range foreground as the base argument to paint the
SAME native MultiParagraph. Android applies the preserved foreground spans
to native text runs; later observed link colors remain native spans and
override the full-range base just as in the real consumer. Since the first
span explicitly covers ALL original units, this adapter never relies on
an unspecified/default color for an uncovered run. It is not a claim that
each unit has separate glyph ink. Actual layoutInput/text/style/paragraph,
all later spans, geometry, shadows/decorations/draw style remain untouched.

The same resolved observed color is used for unconditional full native ink
containment AND both existing raw/clipped mounted composites. All original
line/viewport/ellipsis/style/inset predicates, literal fontScale2f, every
UTF16 logical/finite/exact-vertical/raw-caret coverage, nonblank/maxlines/
probe-edge checks, outside ink EXACT0 and real bitmap cleanup remain.
Raw/clipped models remain descriptive; neither-match still fails and
clipping proof still requires actual lost ink and clipped-only agreement.

## Directly coupled regression definition, not original-family credit

`MountedTextAssertionsTest#observedFullTextForegroundRetainsLinkPaintAndRejectsUnobservedOrClippedRuns`
is ONE new AndroidOnly adapter control definition, NOT executed:

- Real BasicText `@Alice\u202e` retains seven units, unspecified paragraph
  color, first blue full-text span and actual red Clickable link styling.
  Native callback/semantics agreement, unchanged strict text/ink checks and
  actual mounted software-view pixel agreement are required.
- Real BasicText without an observed color, with partial 0..6 coverage, or
  with a shader span must fail the specific unsupported representation guard.
- A real span-colored, unwrapped64dp text with a longer suffix must retain
  clipped-only mounted agreement/actual lost ink and the EXACT native ink
  rejection. Its wider white backdrop supplies real comparison background;
  the text allocation remains64dp. No existing window/frame is enlarged.

Original11 control bodies and all39 Rooms bodies/configuration/fixture are
unchanged, including the production bidi method and exact532 host correction.
No new frozen family/OriginalCase annotation or product consumer is added.
Whitespace-only original work is separately parked in owned stash
`c4d9d8f82142e9f8d8667984a8ad328eded707f1`, not mixed, committed or published.

## Pinned Compose1.10.6 source contracts

Read public source jars IN MEMORY, not installed dependencies or native
execution. Base URL:
`https://dl.google.com/dl/android/maven2/androidx/compose/`.
Archives:
`foundation/foundation-android/1.10.6/foundation-android-1.10.6-sources.jar`
and `ui/ui-text-android/1.10.6/ui-text-android-1.10.6-sources.jar`.
Entry paths below are relative to their common/android source roots.

| Entry | SHA256 | Source contract |
| --- | --- | --- |
| commonMain/androidx/compose/foundation/text/BasicText.kt | `92df0f194316e605dca53913ee2fd05d274441648550715ac966893513e0895c` | 649-709: styled original annotated text reaches native modifier/callback. |
| commonMain/androidx/compose/foundation/text/TextLinkScope.kt | `e42991b88f1004796244f718f1f89201383f1295d5f04caa541b808a66d94b7e` | 71-92/249-277/301-311: preserve original annotation order, resolved link spans. |
| commonMain/androidx/compose/foundation/text/modifiers/TextAnnotatedStringNode.kt | `aec6edcdefd3f2506140c2b5e6b08f54d27047b60f8ff0305b272f93e268f944` | 344-370/522-578: observed semantics and actual paragraph paint; renderer has a default, NOT used as this adapter's invented base. |
| commonMain/androidx/compose/ui/text/MultiParagraph.kt | `793ba1ec967b66dd0b94f9baaa9c7a7b04b684f68c9f66c3812ecee40592edb4` | 490-504: forward color/style to same native paragraphs. |
| androidMain/androidx/compose/ui/text/AndroidParagraph.android.kt | `0a619c712f2d424fda1838547a3fe8ddd952a6d745002152411c2dccddd53a2c` | 536-555/581-590: paint actual layout, not reconstructed glyphs. |
| androidMain/androidx/compose/ui/text/platform/AndroidTextPaint.android.kt | `4b8dbe8971c1ff49a53bfebf568db85fe2485b057fd2c0cdcf94c2ca1cd1c2c5` | 109-145: specified color changes base paint; native spans retained. |
| androidMain/androidx/compose/ui/text/platform/AndroidParagraphIntrinsics.android.kt | `724eefbc0e74bce3a69912ed4169a7d9458d04600c20baac37d27e36cd2b3f95` | 104-142: actual style and annotations create native text. |
| androidMain/androidx/compose/ui/text/platform/AndroidParagraphHelper.android.kt | `9ce6dfab90123cf4c24fef91ac09a3139eebd45a773ec05f78cd5aacfa9b41df` | 49-58/119-134: set observed annotations on native CharSequence. |
| androidMain/androidx/compose/ui/text/platform/extensions/SpannableExtensions.android.kt | `6d2cb0d008ec6025098a35a8eb4bba39e6572a2934f156d48a847bc544f2ced2` | 252-314/566-587: actual span color -> Android ForegroundColorSpan; shader path distinct. |

## Source-only preservation and validation

Whole helper reversal restores ENTIRE parent observer blob
`e771a88f2c8ae3172543aa0e25c77e15be41bdfd`, canonical-LF SHA256
`970f878adad228dc1787adf0064880e5c658ceea3771a1a184b887b6f3955086`.
Whole control reversal removes only the70-line new block and nine imports,
restoring ENTIRE parent controls blob
`813108da3b124ae05613e9e355ba6bdcabadecf7`, canonical-LF SHA256
`f21d839f579e919174f9089536aae6c484d8e7b8694de1c4ea3438c639a3367d`.
Strict helper939c7260, ENTIRE Roomsf31f8d66 and all other incumbent/product/
lock/historical-receipt bytes and modes remain unchanged.
Corrected observer blob `a4b910c803998aec90c4ca1514bc31407ae77220`;
extended controls blob `d5320f536417c6c6cad1a6ee889b628aca472586`.

| Source-only check | Result |
| --- | --- |
| `python tools\android-port\portmap.py` | PASS,1737 nonempty mappings parsed; headers are not parity. |
| `python tools\android-port\controller\validate.py` | PASS,1866 pinned/1786 owned/80 excluded,64 WPs/178 edges, manifest977b. |
| `git diff --check` | PASS. |
| Entire observer/11-control/deviation reversal; complete39-body Rooms/strict oracle preservation | PASS; original bodies/configuration are unchanged, not newly executed. |
| ALL34 historical +25 selected frozen/native pins | PASS, actual frozen/main/parent/physical bytes compared; existing admitted Room renderer remains parent-bound. |
| Changed paths/modes and separate parked whitespace | PASS, precisely four owned paths/no existing mode change; parked work intact and absent from this candidate. |

No compilation/discovery/new-control/native ink/pixel/navigation verdict
has been established for this successor. Exactc430 remains failed and
exact532 success stays historical. DRAFT249 is not updated here. No local
Gradle/WSL/native/device/readiness/lease/recovery/publication/dispatch/rerun/
gate/merge action. WP-503/native66bd ownership is untouched. Reaction
product-support/native/TalkBack/human/protected/full-WP gates remain open.
