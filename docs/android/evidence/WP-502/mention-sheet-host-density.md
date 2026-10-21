# WP-502 Rooms sheet host density: LOCAL corrective source candidate

**LOCAL / UNPUBLISHED / UNCOMPILED / UNRUN**, direct successor to failed
`fb75fa519f6f5322f58b3df88eefeeefd7c37b04`. Existing
[DRAFT249](https://github.com/cbattlegear/MeshCoreOne-Android/pull/249),
issue135, one existing WP-502 branch/worker. This is a fixture source repair,
not a new family, product repair, execution receipt or CI-result bundle.

## Actual failed boundary, not inherited acceptance

Coordinator supplied authoritative exactfb75 run39622020604 /
Linux119298395237 TERMINAL FAILURE after reading the official log IN MEMORY:
App851 discovered/850 passed/1 failed/0 errors/skips.
`connectedUnmatchedCharlieShowsNotSavedWithoutSelectingAliceOrChangingTheRoom`
reached the actual status sheet and `MountedTextAssertions.kt:136` at
Rooms1362/1385 rejected expectedfontScale2.0, actual1.0 on FIRST title
`Mention: Charlie`. The literal matched; observed title allocation123x24,
style16sp, paragraph608x24. The font check precedes later strict text/line/
UTF16/ink checks. No later sheet-copy/native-ink/Done/recomposition/lifecycle
pass follows from this observation. No other named method pass is inferred
from failed-only output.

Supplied available mandatory total1790/1789pass/1fail is INCOMPLETE, not
full mandatory evidence. SwiftData was upstreamSKIPPED, not scope/pass;
classifier/controller/SwiftCodec SUCCESS do not repair Linux failure.
Source5601761902 remains source-only; exactc2ce's all-six success and prior
source/execution findings remain historical candidate-specific bindings.
No second hosted result was downloaded, copied, hashed or uploaded here.

## Fresh guards and same-owner admission

Canonical owner/attempt `3bc8c1b8-20ec-4647-88ae-db3775268489`;
runtime alias `fb112832-0a71-4f6d-abc0-f07f80eaeb8b`.
Fresh actor cbattlegear, clean exactfb75 local/remote, OPEN/DRAFT249,
main/base `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
Immutable db145 source, semantic manifest977b/raw582e and policye8af/rawb77c
unchanged; paused=false/dispatch=off/activation=false. Trusted
reserve/capabilities/ledger bytes match main.

Same actual reservation45 -> returned46 via trusted `wp-owned` / `modify`
BEFORE exactly these owned edits:

- `android/ui-tests/src/test/kotlin/com/meshcoreone/android/uitests/RoomsComposeTest.kt`
- `docs/android/deviations/WP-502.md`
- this evidence path.

No claim release/recreation/authorization rewrite, new producer or shared
product/build support. A first read-only guard compared the native lock to
its OLD comparator identity and rejected that stale assumption. Actual
read-only inspection found WP-503's active nodes operation for the SAME
WP-503 canonical owner. No lock was changed, entered, released or recovered;
that worker's native lifecycle is independent of these source-only edits.
Source/manifest/policy/WP-502 owner checks were valid; no actual policy or
source drift was excused.

## Complete source trace: why LocalDensity alone was insufficient

Read complete Rooms host/setup/mount and service Fixture, complete failed
Charlie body and real `MentionPickerSheet`, actual Room route/row/action
consumer, neighboring modal fixtures, and pinned upstream implementations.
Installed dependency lock: Material3 Android1.4.0, Compose UI/UI-unit1.10.6,
BOM2026.03.01. Source archives were fetched/read IN MEMORY from Google's
public Maven source repository; no dependency installation, native/compiler/
host-readiness probe or candidate execution.

Archive roots:
`https://dl.google.com/dl/android/maven2/androidx/compose/`.
Each table path is an exact source-jar entry, hash of its actual bytes:

| Artifact/version and entry | SHA256 / relevant contract |
| --- | --- |
| material3/material3-android/1.4.0, androidMain/androidx/compose/material3/ModalBottomSheet.android.kt | `bf776882030fd135640018d9e50e9b9e7a7af5354dcf059e5caa5a2b2acf987b`;364-414/420-463/475-488 |
| ui/ui-android/1.10.6, androidMain/androidx/compose/ui/platform/Wrapper.android.kt | `98ab329cedf7ee2533b1680f1400ca9fe1795f5c97806cea99f2e987f3b640b7`;137-143 |
| ui/ui-android/1.10.6, androidMain/androidx/compose/ui/platform/AndroidCompositionLocals.android.kt | `7c2cd0807e012b093562c246fca1110944a623531e2c9e0f7bb0ec4b314090fa`;96-138 |
| ui/ui-android/1.10.6, commonMain/androidx/compose/ui/platform/CompositionLocals.kt | `25f5d3a85b154cdd69bf11b4fe68be37ffe60a32b70b4e99ace5250afa6c6d2a`;212-240 |
| ui/ui-android/1.10.6, androidMain/androidx/compose/ui/platform/AndroidComposeView.android.kt | `93d6071fe39e79052f39ee42724df01e6ebc3f6f5399bae4cbd0e3a78a81f79b`;329/2923-2929 |
| ui/ui-unit-android/1.10.6, androidMain/androidx/compose/ui/unit/AndroidDensity.android.kt | `dc5364115bb62acba184798ceb9c83cacf6e39a0531c0fe015e51f7d3b4f7c24`;28-34 |

Material3 captures caller LocalView/LocalDensity/layout direction and parent
composition, but constructs its ComponentDialog with
`ContextThemeWrapper(composeView.context, EdgeToEdgeFloatingDialogWindowTheme)`.
Its `ModalBottomSheetDialogLayout` is a separate AbstractComposeView, with
that dialog context. `setContent(parentComposition, ...)` preserves a parent
composition, NOT a promise that Android owner locals are left unmodified.
The captured density is used for dialog elevation; it does not replace
the new owner's Android resource-derived density.

Wrapped Android content calls `ProvideAndroidCompositionLocals(owner, ...)`,
which supplies the new owner's configuration/context and delegates to
`ProvideCommonCompositionLocals`. That in turn explicitly provides
`LocalDensity provides owner.density`. `AndroidComposeView` initializes that
density with `Density(context)`. AndroidDensity obtains fontScale from
`context.resources.configuration.fontScale` and density from displayMetrics.
Therefore an outer test-only `LocalDensity(..., 2f)` can coexist with a
dialog's actual1f when actual host resources still contain1f. The observed
FIRST title mismatch is consistent with precisely this source-backed gap,
not evidence that the production sheet intentionally ignores a real200%
Android configuration or that the strict oracle should expect1f.

Before repair Rooms.createHost creates the actual activity, while
Rooms.mount only builds a localized context/CompositionLocalProvider with
requested LocalDensity. It does NOT set real host fontScale.
Existing owned EmojiComposerComposeTest.mount, NewChatSheetComposeTest.mount,
ReactionDetailsComposeTest.mount and onboarding setup already use actual
host resources.updateConfiguration BEFORE setContent. This is the existing
Robolectric test-fixture mechanism, not a product workaround/new API.

## Minimal correction and unchanged predicates

Only Rooms.mount is changed: a narrowly scoped DEPRECATION suppression for
the repository-standard test API, copy the current host Configuration,
set only fontScale to frame.value.fontScale, pass unchanged displayMetrics,
then require actual resources.configuration.fontScale equals requested
frame value at EXACT0 before the original setContent.
One comment explains separate dialog density. No width/height/DPI/locale/
direction/window/theme/text/font/style/inset/padding changes.

This applies the requested initial frame to actual host resources for ALL
Room consumers using the shared mount, rather than special-casing Charlie
or creating a pretend sheet. Every38 complete test body is unchanged.
Existing later frame-state changes remain existing composition-driven
tests; this repair adds no dynamic Android font-setting mechanism or claim
that those later changes were OS font-setting events.

Expected2f in the Charlie method remains literal2f, not actual-derived.
Unchanged360dp/200%/LTR content frame,1080x1200dp test-host profile and the
actual modal consumer/window remain. A correct200% modal may expose later
real clipping/viewport failures; none is waived or claimed passed.
All full-text/style/line/height/ellipsis/every-UTF16 logical/finite/raw-caret/
vertical/public-caret checks and unconditional actual native ink outside
EXACT0/nonblank/probe-edge/maxlines predicates remain byte-identical.
Real root/ALL-scroll/window/visible-frame/inset/48dp and genuine clipping
negative controls are untouched. No favorable raster model/tolerance,
clamp/sort/filter/offset normalization, fake layout/glyph/context or new family.

Frozen Charlie/[Alice]/Me/full DTO inputs, actual route/action/dispatcher/
evaluator/sheet/navigation-state consumer and retained Room/draft/effect/
recomposition/Done/cancellation assertions are unchanged from failedfb75.
The old unmatched-mention-status receipt remains EXACT historical source
accounting; later runtime evidence does not retroactively make it a pass.

## Preservation and source-only validation

Whole Rooms file reversal removes ONLY the seven added mount lines and
recovers failedfb75 Git blob `e8fe9dfde74017f85ed0b4d9a58ccdd7369b38a9`,
canonical-LF SHA256
`c48cdfed24c92398fec23fb63834608226a1777a3c22ec6b63b30c15edf3fed2`.
Candidate Rooms blob `257f5dc5428d320bd7ae70c8101d5c9673b1dfd6`,
canonical-LF SHA256
`15c956caca1c7310f71281b92e51e93437ae3a14ac39eea797e3adcecbe2cbb5`.
All38 complete original method bodies, fixture ports/state, Charlie inputs,
matrix cases/fonts/copy and assertions are thereby byte-identical; no
regex-only body equivalence. Removing ONLY the new deviation section
recovers the ENTIRE failedfb75 deviation file, canonical-LF SHA256
`80027f452aa221dc9592a2f234d4f6bcb4753230dd795e154a61a14f6d1a1dde`.

Strict source blobs unchanged from failedfb75:

| Owned source, same ui-tests directory | Actual unchanged Git blob |
| --- | --- |
| MountedTextAssertions.kt | `939c7260c3e0c4ff916292020a17116f5ef7efb7` |
| MountedNativePaintObservations.kt | `e771a88f2c8ae3172543aa0e25c77e15be41bdfd` |
| MountedTextAssertionsTest.kt, all11 original controls | `813108da3b124ae05613e9e355ba6bdcabadecf7` |
| NewChatSheetComposeTest.kt | `f858c733eec71bc64a7eaf8d9e83b0cae71458ed` |
| EmojiComposerComposeTest.kt | `b6ddc2eeae41cac40d4fb664525fa21a8db17a54` |
| ReactionDetailsComposeTest.kt | `ff3add425a6026eaa48d92d08704be60fc3ccaca` |
| ContactMatchRowComposeTest.kt | `ca8a9d0b7915f01d4628acb03d06d1997f8052ee` |

App dependency lock remains `c4d5b74acc0efef9f4162c95d843c4c2c1186ce7`.
Independently compared ALL34 declared frozen/native pins in
software-verification.md against actual frozen/parent/physical files, plus
ALL six frozen/twelve native unmatched-mention-status.md inputs, including
English source/generated/native strings. Each matches; no repinning or
policy/accounting/product/strict-helper edit. Only these THREE owned paths
change; all other tree entries/modes must equal the direct parent.

| Actual source-only command/check | Result |
| --- | --- |
| `python tools\android-port\portmap.py` | PASS,1737 actual mappings, nonempty output parsed. Headers do not prove behavioral parity. |
| `python tools\android-port\controller\validate.py` | PASS,1866 pinned/1786 owned/80 excluded,64 WPs/178 edges, semantic manifest977b. |
| `git diff --check` | PASS. |
| Whole-file reversal, exact incumbent/lock and34+18 source-input comparisons | PASS; source evidence only, no execution. |

No local Gradle/compiler/WSL/native/device/host probe, lease/recovery/runtime/
hook/main/policy/dispatch/rerun/merge/settings/protected action or publication.
No CI-result bundle. New successor must obtain its OWN named-method and
complete mandatory execution evidence; none transfers fromc2ce or failedfb75.

Reaction full-picker/deferred-dismissal product-support admission and other
product/loading/error/accessibility/removed-translation gaps remain open.
API31/37 device/targeted33/34/36, approved goldens/physical folding/spoken
TalkBack/radio/human/native/protected/full-WP acceptance remain unproven.
Stop at ONE clean LOCAL direct successor for exact source review.
