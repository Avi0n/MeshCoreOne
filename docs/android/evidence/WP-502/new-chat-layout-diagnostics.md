# WP-502: failed New Chat layout guard, measurement-only successor

**BLOCKED: clipping cause unresolved; NOT a verified repair or acceptance.**
This receipt supersedes historical creation-time NOT RUN/pending status for
the published New Chat candidate only. Its actual hosted execution failed.
The local successor adds measurements without changing a pass predicate.

## Exact history and retained local work

Repository: `cbattlegear/MeshCoreOne-Android`, issue135, existing draft249.
Frozen reference: `db14559b39d32322b06477c6ae676112f583db50`.
Integrated main input: `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
Manifest semantic revision:
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Same canonical actor/attempt: `3bc8c1b8-20ec-4647-88ae-db3775268489`.
Trusted `wp-owned`/`modify` evolution returned actual36 from35 BEFORE edits,
admitting this receipt and the existing owned NewChat test. No reservation
release/recreation, foreign lease change, or product/support admission.

Local ancestry, without reset/reorder/amend/cherry-pick:

```text
measurement-only successor containing this receipt
  -> d73a5af5ff902b6206f90a77e41d8c6b3009ecd5  paused reaction candidate
  -> 1be8668edf5469582fa038b8ebdd51b33541306d  reviewed emoji source
  -> 781fec26cbf4721577cd43a7726c471826872db7  published NewChat / FAILED
  -> 5b97e24ef2ebcb3cc69e2e7ee7fe7906aefd644e  genuine successful predecessor
```

Reaction work was committed at a coherent boundary: four paths,673 additions,
zero deletions,398-line class plus275 documentation additions. It remains
paused, local, unexecuted and unreviewed. Emoji1be remains local/unpublished.
This successor changes ONLY NewChat's measurement diagnostics and this receipt;
all four paths fromd73 remain byte-identical.

## Authoritative failure, not a transferred verdict

Exact781 automatic39559686777 is terminalFAILURE. Official Linux119088339862
reports829 tests completed,3 failed. The coordinator independently established
App829 discovered/run,826 passed,3 failed,0 errors/skips; that is not a focused
NewChat pass or a verdict for any local descendant.

| Exact NewChatSheetComposeTest method | Reported primary failure |
| --- | --- |
| `heldLoadPreservesOrderThenRealSearchSelectsExactlyTheOriginalDisplayNameContactWithoutReload` | `New Chat clipped horizontally` |
| `freshNoRadioAndSuccessfullyLoadedRawEmptyShowEmptyCopyWithoutSelectionOrServiceMutation` | `No Contacts clipped horizontally`, caller306 |
| `ineligibleVersusEligibleListsKeepLocalizedCancelIndependentAtLargeRtlFont` | Chinese New Chat title (U+65B0 U+804A U+5929) clipped horizontally, caller264 |

All stop at the original helper's `didOverflowWidth` assertion, line168.
Aggregate119094781147 FAILED; SwiftData119094782723 was upstreamSKIPPED,
not passed or scope-excluded. Classifier119087663887,controller119087731406
and Swift codec119088339691 succeeded. Suppressed queued-looper notes do not
establish a primary cause. No earlier/later flow predicates receive acceptance
credit from these failed methods.

The official Linux log was read in memory and its three identities/messages
and829/3 summary checked. Those messages contain no numeric node bounds,
measured widths, constraints, paragraph extents or character boxes. No second
CI-result bundle/log/hash/upload was created. A local Windows stdout encoding
error while displaying Chinese log text was corrected for the read; it was
not a CI/provider/compiler/native failure.

## Source trace and what is still unknown

Real `NewChatSheet` uses a ModalBottomSheet containing
`Column(fillMaxSize().padding(horizontal=16.dp))`. Its title and Cancel are
unweighted children of a fill-width/SpaceBetween Row. The title uses Material
`titleLarge`; raw-empty copy is inside a fill-width centered Column with24dp
padding and uses `titleMedium`/`bodyMedium`. NewChatRow also uses titleMedium.
`MeshTypography` overrides body styles/titleSmall/labelLarge, not
titleLarge/titleMedium. No exact inherited runtime font metrics are claimed.

The test already settles its mounted host, obtains a displayed real text node
and sheet bounds, and invokes the real GetTextLayoutResult semantics action.
Its existing order checks containment, exact text/fontScale/direction,
vertical overflow, horizontal overflow, then every line's ellipsis state.
This is not the earlier unsynchronized ContactMatch theme-probe defect.
No source proof establishes that another idle call fixes it.

Existing `ConversationScreenCases` distinguishes allocated paragraph width
from visible character bounds for shrink-to-fit RTL; ContactMatch and Rooms
also inspect returned character boxes. That is relevant prior art, NOT proof
that these English/Chinese NewChat failures are harmless allocation noise.
Both actual clipping and a layout/probe mismatch remain possible. Source
container padding is not an observed pixel width; no dimensions are invented.
The original didOverflowWidth predicate cannot safely be removed or weakened
without the missing measurements.

## Precisely bounded instrumentation

NewChat test changes from blob`20f60bbfa5e76273dc25d2f2a3a1ecf8772f91ad`
to385-line blob`4d381adabff05182fe8107d547dfabb1d854ad6e`.
The complete delta is one21-line diagnostic block and five failure-message
changes inside `assertReadable`; no import, other method or fixture changed.

`WP502_NEW_CHAT_LAYOUT` records real expected/actual text, full ScreenCase,
effective scheme, node/sheet bounds, layout size/input constraints, paragraph
width/height, density/fontScale/direction/style, softWrap/maxLines/overflow,
both overflow flags, line start/end/left/right/top/bottom/ellipsis, and every
returned UTF16 character box with exact out-of-measured-bounds entries.
Character boxes include whitespace/format characters; they are diagnostic
values, not a platform shaped-ink/glyph approval. They are indexed using the
ACTUAL layout text so a text mismatch does not generate an out-of-range
diagnostic before the existing exact-text assertion.

The record prints when either overflow flag or out-of-bounds list is nonempty
and is also included in clipping/ellipsis assertion messages. Containment
failures include frame/node/sheet rectangles. No exception is caught, no layout
is synthesized, and stdout is not substituted for assertions. The original
two containment expressions, font/direction/text expectations, unconditional
didOverflowHeight/didOverflowWidth assertions and every no-ellipsis assertion
remain exactly as before. No new tolerance, padding/font shrink, theme/font
reduction, sleep/retry/extra idle, expected-oracle waiver or product edit.

Reversing ONLY this diagnostic block and its five message changes restores
the ENTIRE original364-line NewChat file and its20f60 Git blob, not merely
matching method names. Thus all three complete methods, five fixture inputs,
six DTO identities/key/path/default vectors,14 localized literal vectors,
search/callback/Cancel boundaries and full modal harness are unchanged.
This source comparison is not evidence those predicates executed successfully.

## Exact preservation and source pins

All blobs below are whole-file bindings, not coverage:

| Preserved file (UI tests unless otherwise specified) | Blob |
| --- | --- |
| EmojiComposerComposeTest.kt | `61d52afa90d15b0a32340b955e8cf95f27517ca3` |
| ReactionDetailsComposeTest.kt | `d5dabbe48b6b038a8ff15a2e5986d27edc9aee77` |
| ContactMatchRowComposeTest.kt | `ca8a9d0b7915f01d4628acb03d06d1997f8052ee` |
| RoomsComposeTest.kt | `276317f4fe7521fe4c016bc55357372c85e09278` |
| docs/android/deviations/WP-502.md | `e00611b1f3ef367a904755652bd00da4a4a260b2` |
| docs/android/evidence/WP-502/README.md | `a06b699ccf9f678c99c4714ee313ca75897c2d21` |
| docs/android/evidence/WP-502/software-verification.md | `ddaf374ea35b4a5ef53f5425007b3f00b471db54` |

Fresh source comparisons established frozen/current equality for all three
NewChat inputs: NewChatView`adc82dcfb141f66640890b32c01dd741b768aef0`,
ChatsConversationSheets`d27da1e0181ab553b982c4d3d5bdf40107623158`,
and Contact`7b6ecd2e224c1b1651edeba8a0019426881e7db4`.

Mainactualdbb/published781/reviewed1be/parentd73/worktree are identical for
NewChatSheet`92aad8e98a5bd33ff7e74dc86a6567a76950e0ac`,
NewChatState`e04e89f7277096caa34f635ea0f42803e8ab80d1`,
ChatListDependencies`e7aa83e0d9d1fa75e6540b68a01965d42d0988ef`,
ChatTextMatching`552a87f9cc022add223827b0fa511e55e8d9daf4`,
and MeshCoreTheme`20568675e4222c6f3c40951d65559d0925e70306`.

Paused reaction source retains all four COMPLETE methods:
`preferredBadgeLoadsOrderedTabsAndTabSwitchUsesOriginalSenderRowsWithoutAnotherRead`,
`staleBadgeAndRealOverflowResolveToFirstAndDoneReopenReloadsOnlyTheExactMessage`,
`successfulEmptyReturnedGroupsShowLocalizedEmptyCopyOnlyAfterTheReadCompletes`,
`diagnosticFailureAndOwnerCancellationDoNotBecomeSuccessfulReactionDataOrInvokeActions`.
Its398 lines, six fresh inputs, seven literal reaction DTOs/count3-2-2/
earliest900-800-1000/sender-subsequence vectors, complete explicit message,
recorded-read/forbidden-call boundaries and275 documentation-added lines are
available intact ind73. It is AndroidOnly, not four new OriginalCases.

For complete combined local source comparison, read the entire NewChat file
in this commit, entire ReactionDetails file ind73 and full
`git diff 1be8668edf5469582fa038b8ebdd51b33541306d d73a5af5ff902b6206f90a77e41d8c6b3009ecd5`
(four paths). This receipt's containing commit has only the two named repair
paths; it neither rewrites the prior objects nor publishes their descendants.

## Source-only results and remaining gate

```text
python tools\android-port\portmap.py              PASS:1734 source mappings
python tools\android-port\controller\validate.py  PASS:1866 pinned/1786 owned/
                                                80 excluded/64 WPs/178 edges
git diff --check                                 PASS
```

In-memory source checks PASS: full-file diagnostic reversibility, coherent
ancestry, all incumbent blobs, every paused reaction-commit path unchanged,
three frozen equalities and five native main/ancestor/worktree equalities.
These are source/data checks, not Kotlin compilation or positive discovery.

Declared future verification remains the existing App/scaffold lane:
`python tools\android-port\controller\ci.py preflight`,
`python tools\android-port\controller\ci.py run --stage scaffold`,
`python tools\android-port\controller\ci.py inspect`. The existing
`:app:testDebugUnitTest --tests com.meshcoreone.android.uitests.NewChatSheetComposeTest`
selector is documented in software-verification.md; neither it nor any local
Gradle/compiler/WSL/native/host/guest/advisory check was run for this successor.
No CI dispatch/rerun/watch/poll, publication or protected/runtime/main action.

**Next necessary evidence is actual diagnostic geometry from an independently
reviewed, exact-candidate hosted execution, not a rerun of failed781.** Current
source-only constraints cannot distinguish the two causes. If real product
clipping is established, route exact dimensions/frame/consumer to WP-306
(NewChatView primary owner) and trusted native-path ownership/admission;
NewChatSheet/theme/App/core/resources/build/goldens are not admitted here.
If a probe defect is established, make a source-reviewed root correction
without weakening real readability predicates. No successful repair is claimed.

NewChat photo/avatar/error-recovery gaps remain. Paused reaction loading/
typed-error presentation, relative sender times, badge accessibility and
ACTIVE Translate-affordance removal remain owner-routed WP-309/WP-407 gaps,
not waived empty/error success. Real reaction sending/App dismissal, OS Back/
IME/focus/radio/store/assets/fonts/devices/goldens/full-family/full-WP evidence
remain unproven. Publication of1be/reaction/this descendant stays held.
