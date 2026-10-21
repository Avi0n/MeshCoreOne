# WP-502: mounted reaction viewport assertion correction

**LOCAL / UNPUBLISHED / UNVERIFIED SOFTWARE.** This is a bounded source
correction, not Kotlin compilation, discovered/executed tests, platform/glyph
approval, a NewChat repair, or full-family/full-WP acceptance.

## Binding and serialization

Repository `cbattlegear/MeshCoreOne-Android`, issue135, existing draft249.
Direct parent: `c1dc5f63d8fbf22d71811a7c7c919142684c403a`.
Local ancestry remains c1 -> d73a5af5ff902b6206f90a77e41d8c6b3009ecd5 ->
reviewed1be8668edf5469582fa038b8ebdd51b33541306d ->
published781fec26cbf4721577cd43a7726c471826872db7.
No reset/reorder/amend/cherry-pick or replacement of the earlier objects.

Frozen source remains `db14559b39d32322b06477c6ae676112f583db50`;
main input is `dbb37206db787cb5ec1dcef1b2ee3ceb34434e0c`.
Same actor/attempt `3bc8c1b8-20ec-4647-88ae-db3775268489` evolves trusted
`wp-owned`/`modify` actual36 to returned37 BEFORE edits for the existing owned
reaction test and this new owned receipt. No new actor, reservation recreation,
product/support admission, protected lease or runtime action.

The coordinator accepted c1 as a coherent NewChat DIAGNOSTIC boundary and
explicitly resumed ONLY the queued reaction source correction without waiting
for failed781 to succeed. This supersedes the earlier reaction pause only;
it does not establish a NewChat cause/repair or replace publication gates.
The combined candidate stays local for complete exact source comparison.
Any NEW corrective diagnostic publication needs separate explicit guards
after that comparison; no existing conditional1be publication is invoked.

Exact781 automatic39559686777 remains FAILURE: official Linux119088339862
App829 completed/discovered,826 passed,3 failed,0 errors/skips, all three
NewChat methods at didOverflowWidth. Aggregate119094781147 FAILED and
SwiftData119094782723 upstreamSKIPPED, not passed. No predecessor pass,
failed-run rerun, future-head verdict or source-clearance transfer.

## Defect in the previous assertion, not a product scrolling defect

Real `MessageActionsUi.kt` ReactionDetailsDialog, lines554-596 at the pinned
input below, renders tabs in `Row(horizontalScroll(rememberScrollState()))`
and sender names in a separate `Column(verticalScroll(...))`.
ReactionBadges is horizontally scrollable inside the route's vertical scroll.
The real dialog exposes localized title/empty copy and a real Done TextButton.
Shared touch targets use actual sizeIn minimum width/height48dp.

Previous d73 assertions required positive clipped bounds/assertIsDisplayed,
intrinsic TextLayoutResult no-overflow/no-ellipsis and48dp dimensions.
Partial visibility satisfies assertIsDisplayed. An unconstrained text layout
can fit its own allocation while a parent scroll viewport clips it; therefore
those assertions did not prove the full copy or target was visible.
Conversely, valid native scrolling must not be reported as product clipping.

## Real geometry and real actions in the correction

Only `ReactionDetailsComposeTest.kt` and this receipt change. The test changes
from398-line blob`d5dabbe48b6b038a8ff15a2e5986d27edc9aee77` to456-line
blob`1f356ec2feddcb417ce14030ac72d57a45290402`.

`fullBounds` reads the REAL SemanticsNode.positionInRoot and measured size;
it does not derive bounds from text, expected counts or a copied renderer.
For every asserted copy/target, a synchronized UI-idle observation compares
that full rectangle against actual clipped boundsInRoot, the real semantics
root bounds and EVERY actual horizontal/vertical scroll-ancestor viewport.
All four edges must fit with zero tolerance; full/visible positive dimensions
remain required. Failure messages include frame/full/visible/viewport geometry.
No arbitrary non-scroll parent is treated as a clipping viewport.

`assertCopyLayout` retains exact displayed text/fontScale/layoutDirection,
unconditional didOverflowWidth/didOverflowHeight and every no-ellipsis check.
Those intrinsic checks are ADDITIONAL to full viewport containment, not a
replacement. Layout failures include measured size, constraints and paragraph
extents. No widths, font sizes, padding, thresholds or effective DARK oracle
are lowered; NewChat's unresolved intrinsic-width diagnosis is not guessed.

Each real tab is brought into view using the standard performScrollTo action,
then checked for full containment, click action, actual minimum48dp dimensions,
Role.Tab/Selected and absence of ToggleableState. A selected-tab change uses
that same scroll/check/click path. Sender rows use their actual vertical scroll
before full copy/layout checks. Badges and actual+1 overflow use their real
nested scroll containers; badge copy is checked as well as its target.
Done requires full copy/target visibility and48dp dimensions before clicking.
No synthetic pointer position, direct selection/dismiss callback or product API.

Order checks deliberately do NOT compare clipped centers saved before/after
different scroll actions. After the per-target checks, all real tab rectangles
are read together in ONE UI-idle observation; their nearest actual horizontal
scroll-parent IDs must be identical. Expected literal label order is compared
to the measured unclipped centers in that SAME viewport coordinate system,
using the original strict LTR/RTL direction. Real content may extend outside
that viewport: each target was individually scrolled fully into view, but
all tabs are NOT required to fit simultaneously.

Sender rectangles likewise use one synchronized observation and one identical
nearest vertical-scroll-parent ID for strict input-subsequence vertical order.
Their expected names/absence predicates are unchanged. No layout size, scroll
offset, center or successful assertion is manufactured. No sleep/retry/manual
looper, all-tabs-at-once constraint or clipped-target waiver.

## Complete method/input preservation

The whole original prefix (after removing only the two new imports), host,
frame selection, synchronized theme probe, resource strings and selectors
match d73/c1 exactly. The ENTIRE Fixture/DTO/port/exception/cancellation/
companion-object tail is byte-identical, not merely its method names.

All four complete methods and assertBoundary compare byte-for-byte to d73/c1
after reversing ONLY three real-click wrapper call sites:
thumb tab, heart tab and actual+1 overflow. No assertion/data/cancellation/
load-count branch in those bodies is deleted or changed.

| Complete existing AndroidOnly definition | Retained independent boundary |
| --- | --- |
| `preferredBadgeLoadsOrderedTabsAndTabSwitchUsesOriginalSenderRowsWithoutAnotherRead` | Heart preferred; original thumb3/heart2/laugh2/count/earliest ordering; Chinese/Hebrew sender subsequence/DTO identity; modifier recomposition/no read; real thumb change/Done; exact message400 once. |
| `staleBadgeAndRealOverflowResolveToFirstAndDoneReopenReloadsOnlyTheExactMessage` | Missing party falls back; real heart selection, Done, reopen actual+1/nil resets to thumb; same DTO availability; exact message400 twice; Chinese200%-RTL/effectiveDARK. |
| `successfulEmptyReturnedGroupsShowLocalizedEmptyCopyOnlyAfterTheReadCompletes` | Separate English/Chinese fresh fixtures complete successful empty read before empty assertions; no tabs/null selection/recomposition/no refetch/Done. No premature-empty loading acceptance. |
| `diagnosticFailureAndOwnerCancellationDoNotBecomeSuccessfulReactionDataOrInvokeActions` | Actual failed read/typed ReactionsLoadFailed versus rethrown owner CancellationException/no typed failure; exact counts/no success data/private-reason leak; boundaries before Done/cleanup. Diagnostic, not successful error/loading presentation. |

Still four definitions/six fresh fixture inputs, not executions or new
OriginalCase ownership/counts. Frozen four preferred-selection primitives
remain primarily covered by ReactionsAndStateTest. Actual public
MessageActionsRoute + real holder + private dialog remain the consumer.
Only reaction-list reads for the full literal message ID may succeed;
every unrelated data read/mutation and MessageAction callback records/throws.
No send/store/radio/App reaction consumer or deferred child dismissal is added.

Independent seven-row vectors were freshly rechecked: IDs410..416, raw emoji
and @[Target parent]/newline/0123abcd text, literal receipts1400/1300/1200/
800/1100/900/1000, count order3-2-2, earliest900-800-1000, thumb sender
subsequence Zulu/Beta/Alpha and heart Chinese/Hebrew. Every explicit
message400/radio401/default/path/time identity and original fixture remains
unchanged. Full vector/method/source inventory remains ind73's existing
software-verification.md; this correction does not rewrite that historical
398-line source record or its275 documentation-added lines.

## All16 input-pin comparisons and untouched incumbents

Fresh comparisons established all eight frozen/current/worktree equalities:

| Frozen input under the immutable source commit | Blob |
| --- | --- |
| MC1Tests/Views/Chats/Components/ReactionDetailsSelectionTests.swift | `cafcd068b3b78b30794538726476b4f8af660a11` |
| MC1/Views/Chats/Reactions/ReactionDetailsSheet.swift | `1df5bb805e5b08ee9792823ca9e9d33dc54f3b5e` |
| MC1/Views/Chats/Reactions/ReactionBadgesView.swift | `691f556edc4b999b797aee23e476679121cf80bd` |
| MC1/Views/Chats/Reactions/Sections/ActionsEmojiSection.swift | `c973508b61297cf9f5f308ed0420f6dac38d1ea7` |
| MC1Services/Sources/MC1Services/Models/Reaction.swift | `fed9026236e4cac1d37fd4491cb9f6c80303467c` |
| MC1/Views/Chats/Reactions/MessageActionsSheet.swift | `40a0e4033e3206018fe67f79a87acbd6b1a503c7` |
| MC1/Views/Chats/Reactions/Sections/ActionsButtonsSection.swift | `3f26628ec02a4fe173d915c64b5a8ae825c06be3` |
| MC1/Views/Chats/Reactions/String+EmojiAccessibilityName.swift | `355146cde98fe2a5f274d617c1b98ef554f0656c` |

All eight native inputs match mainactualdbb/1be/d73/c1/worktree. First four
are under feature/chats actions; full paths are in software-verification.md:

| Native input | Blob |
| --- | --- |
| MessageActionsUi.kt | `675960ac2359c88b593fff47a92ec985d91d62dc` |
| MessageActionsState.kt | `20a3b0d64dfef7e27791f306f266b4f78f3be810` |
| MessageActionModels.kt | `ba137531d4019ea71092116e4eee62e76c1537d1` |
| MessagePathModels.kt | `a79d9c65a17306f84b4fbf3f7c3fa195d8f5787a` |
| ReactionsAndStateTest.kt | `fa514c52875299ccd05fd37abedbcb8eb73fa211` |
| MessageDTO.kt | `73e9e78d5c6ac11560a84521e49e07f509e54099` |
| DiagnosticDTOs.kt | `439a39d0a49c3be28e26e4b829a419e82502d332` |
| AppMessageActions.kt | `e6dcbb07bff39c1912ec08c1c9bf9c2ab2263218` |

Entire incumbent files remain unchanged:

| Preserved source/evidence | Blob |
| --- | --- |
| NewChatSheetComposeTest.kt, including ALL c1 diagnostics/predicates/fixtures | `4d381adabff05182fe8107d547dfabb1d854ad6e` |
| new-chat-layout-diagnostics.md | `5a6d2a81ddfe944d560869948e69ba3814f118a8` |
| EmojiComposerComposeTest.kt | `61d52afa90d15b0a32340b955e8cf95f27517ca3` |
| ContactMatchRowComposeTest.kt | `ca8a9d0b7915f01d4628acb03d06d1997f8052ee` |
| RoomsComposeTest.kt | `276317f4fe7521fe4c016bc55357372c85e09278` |
| docs/android/deviations/WP-502.md | `e00611b1f3ef367a904755652bd00da4a4a260b2` |
| docs/android/evidence/WP-502/README.md | `a06b699ccf9f678c99c4714ee313ca75897c2d21` |
| docs/android/evidence/WP-502/software-verification.md | `ddaf374ea35b4a5ef53f5425007b3f00b471db54` |

## Source-safe results and explicit unresolved evidence

```text
python tools\android-port\portmap.py              PASS:1734 mappings
python tools\android-port\controller\validate.py  PASS:1866 pinned/1786 owned/
                                                80 excluded/64 WPs/178 edges
git diff --check                                 PASS
```

Manifest revision remains
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Independent source checks PASS for all16 pins, seven-row literal vectors,
whole prefix/fixture-tail and complete four-method/boundary preservation,
single checked-click path, strict full/visible/root/scroll viewport guards
and all eight incumbent source/docs blobs. These are SOURCE/DATA results.

Existing future App task/selector, **NOT RUN**:
`:app:testDebugUnitTest --tests com.meshcoreone.android.uitests.ReactionDetailsComposeTest`.
Full command flags and existing declared App/scaffold commands remain in
software-verification.md. Require genuine positive discovery of the four
exact identities and clean outcomes within actual App execution, not a
fabricated focused4/4, source grep or predecessor aggregate.

No Kotlin/compiler/Gradle/WSL/native/host/guest/advisory execution, runtime/
hook/main/policy/protected action, CI dispatch/rerun/poll/watch or publication.
No secondary CI bundle/hash/upload. Only exact hosted results can establish
software outcomes. Full WP verification remains unconfigured/unaccepted.

Product gaps remain owner-routed, not fixed/waived: reaction loading/typed
error presentation, relative sender times/original empty title and badge
accessibility (WP-309); ACTIVE Translate affordance reaching App's unregistered
handler (WP-309/WP-407). NewChat photo/avatar/error recovery and its unresolved
horizontal flag remain open. Real App reaction sending/deferred dismissal,
OS/IME/focus/radio/store/font/asset/TalkBack/device/golden/full-family/full-WP
acceptance is not claimed. Stop after this source correction for complete
exact comparison; there is no new family or automatic publication permission.
