# Durable conversation recovery acceptance cohort

## Scope and evidence levels

Campaign `accept-conversation-recovery`, WP-501, existing issue134. Research
intake was merged `fb9639bfd28ee77443b2a79b316d673ac21a5e1c`. Resumed
implementation uses actual merged `ae349b875e8d00b93eeaf66687649f997ba17dc4`;
the historical avatar-gap handoff `06acdbd7457a085197e4c1ed4ccf240888f34044`
and the two previously held drafts are preserved through a normal merge.
PR312 and PR315 are merged, not outstanding dependencies. PR315's avatar
configuration, contact table, photo and clustering bytes are unchanged.

The released local bundle is visible-thread failed-send recovery, not all
conversation-window repairs. It fixes the App event adapter's missing seen
write, reuses local-enqueue bookkeeping, and supplies 20 original candidates
in 25 declared methods (16 list, four thread originals, five additional
thread/error/isolation boundaries). F17's real production-primer-to-open
contract remains unprovided. No fake primer, primary timeline edit or
complete 21/48/whole-WP acceptance is claimed. See the
[bounded implementation and verification record](../evidence/WP-501/failed-send-recovery-group.md).

All 27 `ChatReconnectPopulateTests` and 21 `ChatViewModelFailedSendTests`
declarations, complete bodies and private helpers were read, along with
the actual store, App event adapter, queue/retry, list and timeline paths.
Both frozen files have no parameterized declaration: each row below is one
original, not a filename-based claim. The eight negative message-event
inputs and three always-refresh inputs remain independent assertions within
their original declarations.

The read-only **current in-memory** inventory at intake is 5,133 historical
declarations, 3,993 with native proposals, 1,498 candidate semantic
comparisons in 199 proposal groups. These levels are not interchangeable:

| Level | Reconnect / failed-send at intake | Meaning |
| --- | --- | --- |
| Original declarations | 27 / 21 | Frozen input inventory, not passing cases |
| Automatic native proposals | 1 / 2 | Join candidates only; inspect actual assertions |
| Registered semantic comparisons | 0 / 0 | No reviewed comparison group yet for these suites |
| Exact new candidate execution | UNVERIFIED | No current Kotlin/compiler/JUnit evidence locally |
| Independently accepted originals | Not established | Neither counts nor a worker's source comparison grants acceptance |

The lone reconnect join exercises 60 loaded rows plus one arrival, not the
original 100-of-112 loaded window with no arrival. The two failed-send joins
exercise retry single-flight/status and retry error, **not** the named
list-event policy or local-enqueue `failureSeen` contract. Keep their tests
intact; do not use those labels as parity credit.

## Shared causes and build-out groups

| Group | Classification and producer paths | Exit criteria / checks |
| --- | --- | --- |
| A. Loaded-window continuity and live reconnect | Source-proven product gaps, primary WP-307. `feature/chats/.../timeline/ChatViewModel.kt`, `TimelineContracts.kt`; App timeline adapter/registration. Current refresh reads newest `totalFetchedCount + 50`, not the loaded sort-date anchor. App registration opens once and has no reconnect-to-refresh subscription. | Real interactive App timeline retains exact loaded IDs/counters and scroll anchor; catches up newer rows without admitting unseen older rows; DM/channel parity; first-open unread/divider and explicit replacement distinct; actual reconnect reaches the same live holder. |
| B. Populate/reset atomicity, filtering and indexing | Mixed existing core implementation and missing live consumer evidence, WP-307 with WP-213/core rendering and WP-202/store dependencies. Core `ChatCoordinator` and Room `fetchMessageWindow` already implement anchor/probe/reset/filter contracts. Interactive feature holder bypasses that coordinator and fetches unfiltered pages; refresh has no reaction-service reindex hook. | Real window lane serializes loadOlder/refresh/reset; cancellation commits no fetched replacement; hidden reaction rows excluded while raw offsets count them; fresh per-connection reaction service indexes retained messages. Existing core tests are reused as evidence, not credited as interactive coverage. |
| C. Failed-send list refresh and races | Existing implementation, not a new badge defect. `ChatListStateHolder` + actual `AppChatsFeature.listFor` + Room failed-key query. Its 16 candidate originals support the same D product bundle; no standalone mapping/test project. | All 16 exact method identities discovered in App's declared software lane, zero failures/errors/skips; real contact/channel/room union, empty/no-radio clearing, full event policy, stale/cancelled/cleared fetches and logged errors. |
| D. In-thread seen/local enqueue bookkeeping | Source-proven omission repaired in this bounded candidate through capability-admitted App composition. `AppChatsFeature.timeline.events` now uses the same visible-thread seen helper as `failPending` before emitting MessageChanged, then signals list refresh. Both writes check selected tab, conversation and radio; persistence errors are logged without dropping the timeline event. | Current thread's real failure event marks only matching persisted outgoing failure seen and refreshes list; foreign thread/radio untouched; channel open marks existing failures seen; local enqueue failure retains FAILED, failureSeen and actual native localized error. F17 prime/interactive continuity remains explicitly unprovided. |
| E. Operational acceptance | Separate protected/native/human gates, not software defects. | Exact-head hosted compiler + complete selected inventories; then separately authorized API31/37, required platform checks, process death/actual reconnect/radio/offline behavior, TalkBack and physical evidence. No execution inferred from Robolectric declarations or old CI. |

A/B remain a consolidated primary-producer dependency, not separate tiny
fixes or permission to edit shared timeline product paths. The released D
App-adapter wiring is admitted by `app-build-launcher` with operation
`update-launcher-or-composition`; its C regressions use WP-501-owned paths.
No trusted WP-501 rule admits arbitrary timeline/list product changes.
The accessibility-only timeline capability is not repurposed.

### Concrete source counterexamples (not executed failures)

Page size is 50. Source helper with `extraOlderCount = 62` stores 112 rows
and loads the latest 100. Source refresh with no new rows must retain exactly
those 100, counter100, hasMoretrue and the oldest loaded ID. Native refresh
requests151, receives112, and publishes all112/counter112/hasMorefalse.
Likewise the source parked-loadOlder case starts with 100 of162, fetches50
older, then refreshes after one arrival: expected151 rows, native newest201
request admits163, including12 unrequested older rows. A large catch-up
exceeding50 rows can conversely eject the oldest loaded ID.

Two stored sent outgoing `"\uD83D\uDC4D\nABCDEFGH"` reaction rows must not
become bubbles during source refresh. Interactive native open/refresh has no
outgoing-reaction filter, although the existing core coordinator does.
Source refresh indexes retained rows into a fresh ReactionService after
population; the interactive TimelineDataSource/dependencies expose no such
indexing operation. Room's independent stored-target lookup is not the
original fresh-service cache assertion.

Source MessageFailed handling enqueues reload **and** marks the current
thread's failures seen. Before this candidate the App DM/channel event
adapter only emitted MessageChanged. The candidate invokes the shared
visible-thread helper before emission and refreshes list observers after
the write. The separate Room holder repair remains unchanged.

## Full 27-original reconnect map

Names below are exact frozen backtick method names without trailing `()`.
`M` means missing complete original assertion/evidence, not automatically a
bug; `B` means concrete source-proven product mismatch; `E` identifies an
existing stronger or partial equivalent that must be preserved.

| # | Frozen original | Actual comparison / disposition |
| --- | --- | --- |
| R01 | refreshWindow keeps the paged-in window | B/A: original112 stored/100 loaded; newest-count refresh admits12 unseen older rows. Existing60+1 test is not this input/predicate. |
| R02 | refreshWindow load still clears unread and returns success | M/A: source interactive load layers clearUnread on refreshWindow success; native refresh intentionally skips it, open replaces window. Need combined in-place interactive path, not substitute open. |
| R03 | replace mode replaces the paged-in window | E+M/A: native open replaces with50; existing open tests cover page shape, not original62-loaded/oldest removal after reopening. |
| R04 | refreshWindow clears a stuck isLoadingOlder | M/A: source explicitly clears spinner; native loadOlder already clears it on success/cancellation/failure. An absent refresh assignment does not demonstrate a reachable stuck-spinner bug. Preserve a real interrupted-load witness, not hand-mutated state. |
| R05 | first open of a primed coordinator runs populate and bakes the divider | M/A/B: original53 rows/unread3, speculative then interactive shared coordinator. Native StorePrimeTimeline is a test adapter; interactive holder has no shared-writer handoff. Existing warm tests do not execute this actual consumer chain. |
| R06 | never-paired conversation open is unavailable | M/A: source no registry/store, false/uninitialized; native process-owned store always exists. Actual navigation/first-pair gating must be evidenced before calling this a defect or approving an adaptation. |
| R07 | catch-up delivery admits once after refreshWindow | E+M/A: native start-idempotent duplicate-event test covers dedup; source performs refresh, store save, same event twice. Exact combined sequence still missing. |
| R08 | refreshWindow keeps the paged-in channel window and sender tables | B/A: same112/100 counterexample; source channel3 includes OldestSender and Sender(index%3) names/order. Native mention recency reads entire store, not source loaded-window table. |
| R09 | replace mode replaces the paged-in channel window | M/A: source62 loaded then50, OldestSender disappears from loaded sender table. Existing App recency reads all stored messages; exact scope/order needs producer reconciliation. |
| R10 | refreshWindow reconciles a silent store status rewrite | E+M/A: native refresh replaces DTOs, so persisted DELIVERED->FAILED should reconcile; exact silent write/readback original not asserted. |
| R11 | refreshWindow reindexes reactions on a fresh ReactionService | M/B: no live holder reindex hook; existing stored-target reaction matching is independent implementation evidence. Missing original fresh-service findDMTarget equality is not proof of user-visible reaction loss. |
| R12 | failed populate then refreshWindow retries with the intact window | E+M/A/B: native failure preserves messages/counters but uses passiveError and throws cancellation; original entry fault/clear-error/retry/oldest exact window still unproved. |
| R13 | populate catch keeps counters so refreshWindow retries the full window | E+M/A/B: source separate fetch fault with same62-loaded counters; native older/open error tests are not refresh-fetch retry. |
| R14 | cancelled populate keeps counters and does not set errorMessage | E+M/B: native cancellation propagates, unlike source false result; counters retained before commit. Must test cancellation adaptation explicitly with no error, no replacement. |
| R15 | refreshWindow after newer store rows reports hasMoreMessages | M/A: source100 loaded of112 plus50 newer, totalFetched100 before. Need original hasMore predicate; no inferred arbitrary catch-up bound. |
| R16 | loadOlder then refreshWindow serializes and keeps both pages | B/A/B: native Mutex serializes, but counterexample163 versus151 violates exact window; original gates after fetch and checks error/spinner retirement. |
| R17 | hardReset preserves the paged-in window so the next loadOlder abuts it | E+M/B: core reset uses anchor window and counters; live holder does not consume that coordinator. Source112 total/100 loaded then reset/loadOlder must end112, sorted, first1000. |
| R18 | refreshWindow after hidden outgoing reactions retains the oldest row | B/B: source62 visible plus2 hidden; native refresh renders unfiltered64. Exact sent/plain DM reaction fixture retained, failed reactions must stay visible. |
| R19 | fully paged conversation keeps hasMoreMessages false across refreshWindow | E+M/A: source62 fully loaded, counter62/false before-after. Native count refresh appears equivalent for this input; no exact executable original yet. |
| R20 | first open of exactly one page reports no further history | E+M/A: native limit+1 handles exact50 correctly; mislabeled short-initial-page test uses3 rows, not source50. Core/Room probe boundaries separately covered. |
| R21 | refreshWindow catch-up of newer store rows keeps the oldest loaded row | E+M/A: source62 loaded +3 new IDs all retained. Existing60+1 differs; source has no exact final-count assertion here, do not add one as an original predicate. |
| R22 | exactly floorLimit plus one drops the probe and reports hasMore | E+M/A/B: real Room PendingMessageWindow test covers4/floor3; source51/floor50, first1001 and counters50/true both replace and refresh. Native refresh admits51 and flipsfalse. |
| R23 | fully paged floorLimit plus one keeps every row and reports no more | E+M/A: source51 fully loaded then refresh retains51/false; no complete original consumer regression. |
| R24 | cancelled populate after a parked loadOlder commits nothing | E+M/B: Mutex wait cancellation is supported; source162 stored/100 loaded, older50 commits, cancelled refresh must not. Need real lane gates; no proxy flag test. |
| R25 | cancelled populate after the window fetch commits nothing | M/B: source explicit cancellation check after held fetch; native refresh has no explicit post-fetch ensureActive. Requires deterministic noncancellable-return boundary and exact before IDs/newer exclusion. |
| R26 | cancelled hardReset does not replaceAll or reschedule coalesced reload | E+M/B: core has post-fetch ensureActive/cancelInFlight; current core tests cover buffering/reschedule, not full original cancellation/pending-ID preservation. Interactive chain remains separate. |
| R27 | hardReset hides outgoing reaction rows and keeps the unfiltered fetch count | E+M/B: core BehaviorTest checks filtering/raw counter on4 rows; source50+1 hidden and oldest preserved; no live consumer execution credit. |

## Full 21-original failed-send map

The sixteen standalone methods in `FailedSendBadgeRecoveryOriginalTest`
constitute the complete **list refresh/race subgroup**, not the entire21-case
source file. Source `applyFailedSendKeys` is private in the split native
holder: the first three originals use real Room keys through the actual
refresh entry point, not a fake union evaluator or reflection.

| # | Frozen original | Native assertion / remaining disposition |
| --- | --- | --- |
| F01 | applyFailedSendKeys maps contact id onto the list set | New contactKeyReachesTheRealListMembershipSet: exact singleton ID, true membership and random-ID false. |
| F02 | applyFailedSendKeys unions channel ids | New channelKeyUsesTheStoredChannelIdRatherThanItsSlot: channel4 versus7, exact singleton channel UUID. |
| F03 | applyFailedSendKeys maps room session id | New roomKeyReachesTheRealListMembershipSet: exact room ID and membership. |
| F04 | clearConversations empties the failed-send set | New clearingConversationsClearsExistingFailureMembership: nonempty before, empty/false after actual clear. |
| F05 | refresh with empty keys clears a previously populated set | New emptyStoredKeysReplaceRatherThanUnionWithPreviousBadges: real failure first, mark seen yields empty query, refresh removes prior ID. |
| F06 | refresh loads failed DM channel and room keys from the store | New realRoomQueryUnionsAllThreeConversationKinds: source channel2/self-failed room and outgoing DM, all three memberships and exact union. |
| F07 | conversation reload loads failed-send indicators | New ordinaryConversationReloadAlsoLoadsFailureMembership: actual reload job and contact row, no direct refresh substitute. |
| F08 | refresh with no radio id clears the set | New missingCurrentRadioClearsBadgesWithoutAnotherStoreRead: null current radio, empty result and no new query. Native process store remains present by design. |
| F09 | messageFailed and peers always refresh | New threeFailureOrResendEventsAlwaysRequestIndicatorRefresh: actual App event mapping and all3 independent true predicates with no badge. Existing retry single-flight join is not equivalent. |
| F10 | messageStatusResolved refreshes only when a badge is showing | New directStatusResolutionRequiresAnExistingBadge: DELIVERED event false before/true after stored contact failure. |
| F11 | roomMessageStatusUpdated refreshes only when a badge is showing | New roomStatusResolutionRequiresAnExistingBadge: false before/true after stored self-room failure. |
| F12 | other message events do not refresh | New allEightOtherEventKindsLeaveIndicatorRefreshUnrequested: all8 original message inputs mapped by App and rejected despite existing badge. |
| F13 | stale empty refresh does not overwrite a newer apply | New staleEmptyRoomResultCannotEraseTheNewerFailureSet: fetch empty, gate before apply, persist failure, newer refresh applies, old release cannot erase. |
| F14 | cancelled in-flight refresh does not apply fetched keys | New cancelledReadCannotApplyItsFetchedFailureSet: real fetched contact keys parked before apply, cancellation leaves empty. Native propagates CancellationException; task cancelled explicitly asserted. |
| F15 | store throw leaves last-known badges | New failedReadRetainsLastKnownBadgesAndReportsTheSameError: real badge then injected original-style fetch fault; badge retained, exact error reaches diagnostics. |
| F16 | clearConversations drops an in-flight refresh apply | New clearingConversationsInvalidatesAnAlreadyFetchedFailureSet: clear after real query/before apply, release leaves empty while stored failed key still exists. |
| F17 | loadMessages marks failed sends seen; prime does not | E+M/D: native interactive open marks seen, but source prime versus interactive shared coordinator contract is not implemented by a real native primer. No fake adapter claim. |
| F18 | loadChannelMessages marks failed sends seen | Candidate `FailedSendThreadRecoveryOriginalTest.channelOpenClearsTheStoredFailureBadgeWithoutChangingFailedStatus`: actual channel3/open/Room/list query; loaded row, FAILED status, seen=true and removed badge. |
| F19 | handle messageFailed for the current contact marks seen | Candidate `serviceFailureForTheVisibleContactClearsItsPersistedBadge`: actual navigation, App event transform, Room write and list refresh. The production omission is repaired; no new execution is inferred. |
| F20 | handle messageFailed for another contact does not mark seen | Candidate `foreignContactFailureCannotBeMarkedSeenByTheVisibleThread`: two contacts, persisted failed other row, ordered current-thread sentinel proves the foreign event was processed before retained badge/seen=false assertions. |
| F21 | recordLocalEnqueueFailure marks the current contact seen | Candidate `offlineLocalEnqueueKeepsFailedSeenStorageAndAnActualLocalizedComposerError`: original pending row fixture, real offline App enqueue rejection, actual composer and App failure write; await completion of persistence before asserting FAILED/seen=true/no badge and mounted native localized error. Existing Android copy is specific NoDeviceConnected rather than the frozen generic UnableToSend; this adaptation still requires independent review. |

## Fixtures, adaptations and remaining gates

Reconnect helper: fresh radio/contact IDs, CHAT, key32 random, outPath2/0102,
TestContact, contact unread supplied, incoming DELIVERED plain DM `message
<timestamp>`, timestamp1000+index, createdAt timestamp, unread/isReadfalse,
nil sender/ACK/SNR/reply/RTT, retries/repeats0. Channel3/General/emptysecret,
`channel <timestamp>`, OldestSender only at index0 then Sender(index%3).
Outgoing hidden fixture is SENT/plain/read, exact thumb + newline +ABCDEFGH.
No byte/key/default family is silently replaced with configuration/avatar
fixtures.

Failed-send helper: fresh IDs/radio, Test CHAT, key32 repeats low hash byte,
outPath0/empty, timestamps/coords/flags0, nil lastHeard/nickname, all
blocked/muted/favoritefalse, unread0. Channel defaults0 (specific cases4/7/2),
General/emptysecret/enabled/ALL/notfavorite, unread/mentions0. Room nameRoom,
ROOM_SERVER, connectedtrue/notfavorite. Message `"hello"`/1700000000,
outgoing FAILED/plain/unread, nil ACK/SNR/sender/reply/RTT, path0,
repeats/retries/max0. Room failure prefixABCDEF01, `"failed room send"`,
1700000000, selftrue/FAILED. Native fresh IDs retained; fixed Instant replaces
irrelevant ambient Date; deterministic UUID hash bytes replace Swift
process-randomized UUID.hashValue, used only as irrelevant contact key bytes.
Fields not specified by source retain source-equivalent native defaults.

One per-test real in-memory Room + AppContainer, no fake repository. The only
data decorator injects a fetch error or parks **after the real query, before
holder apply**, corresponding to the frozen DEBUG seams. First three
private-apply originals seed real failed rows; no production visibility API
is added. Event policy tests register before send on the real App event
stream and inspect the actual mapped event, not a copied mapping. No radio
connection/send/queue activation, network, native runner, manual Gradle,
allowance or physical observation is implied.

Proposals and source comparison remain candidates. Require exact new-head
App Kotlin compilation plus all25 distinct JUnit identities, complete passing
mandatory selected inventories and independent source review. Declared
preflight currently lacks explicit CI state; local software execution is
UNVERIFIED, not a fabricated green or a publication blocker. Whole48 and
whole-WP acceptance remain blocked on A/B, F17, actual execution/review and
separately authorized operational evidence.
