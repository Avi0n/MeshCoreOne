# WP-501 live conversation-window continuity regression candidate

## Identity and scope

Repository: `cbattlegear/MeshCoreOne-Android`; existing issue:
[#134](https://github.com/cbattlegear/MeshCoreOne-Android/issues/134).
Base: `61fc863172e9248f3ba913acefeb6fd2354d2580`.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Current validated manifest:
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`;
current rendered policy revision:
`46a1aebeace81c8719901030e859569df4c2008e19cb841d80e887b5801077d8`.
Owner: `test-parity-engineer`; existing attempt/session:
`d6056690-3a5d-453e-b67e-36866c676ac5`.
The existing advisory receipt evolved from revision 145 to 146 for the new test,
then to 147 for this evidence path. Both actual admissions returned `wp-owned`
with operation `create`. The historical reservation binding was retained,
not released/recreated or represented as current-head acceptance.

This is one grouped regression candidate, not completion of WP-501. It was authored
test-only and unverified; the product repair that follows
([Product repair and local execution](#product-repair-and-local-execution)) is WP-307's
live-data-path continuation and is recorded here only to bind the observed results. It follows the coordinator's release of fixes
after the complete original-family investigation; it does not restart that ledger.
The original candidate changed only the WP-501 test/evidence/deviation roots; the
repair commit's production edits are listed below.

## Actual consumer and assertions

`ConversationWindowContinuityAcceptanceTest` constructs the real process
`AppContainer` through the existing `ContainerHarness`, real in-memory Room and
the actual `AppChatsFeature.timeline` adapter. It calls the production feature
`ChatViewModel.open`, `loadOlder`, and `refresh`; it does not substitute a fake
page source, copy the coordinator algorithm or directly mutate screen state.
Ordered expected IDs come from the independently seeded rows. A refresh error
cannot masquerade as successful preservation: every refresh requires a null
`passiveError`.

| Declared method | Required observable behavior |
| --- | --- |
| `directRefreshPreservesOneHundredLoadedRowsAndTwelveOlderRows` | With 112 stored and 100 loaded, refresh retains the exact 100 IDs, raw count 100 and `hasMore=true`; the next explicit page adds precisely the remaining 12. |
| `channelRefreshPreservesOneHundredLoadedRowsAndTwelveOlderRows` | Same actual channel-store path, without replacing it with the DM query. |
| `directRefreshDoesNotAdmitTheFiftyFirstProbeRow` | With 51 stored and 50 loaded, refresh keeps the oldest probe excluded and `hasMore=true`; explicit paging then admits that one row. |
| `channelRefreshDoesNotAdmitTheFiftyFirstProbeRow` | Same boundary through the actual channel adapter. |
| `directRefreshKeepsSentReactionRowsStoredButOutsideTheVisibleWindow` | With all 62 visible rows loaded, add the original two sent reaction-wire rows; refresh retains the original visible IDs without deleting either stored reaction. |
| `directRefreshCatchesUpNewRowsWithoutDiscardingFullyLoadedHistory` | Positive control: three new ordinary incoming rows are admitted, all 62 older rows remain, count becomes 65 and history stays exhausted. |
| `directExactPageRefreshDoesNotInventFurtherHistory` | Positive control: exactly 50 stored rows remain exactly 50, with no extra-history flag. |
| `channelExactPageRefreshDoesNotInventFurtherHistory` | Same exact-page control through the channel adapter. |

Each successful scenario also requires no radio session or transport creation.
There are eight declared methods, **not eight executed or accepted cases**.

## Pinned behavior and limits

The previously investigated
`MC1Tests/Views/Chats/ChatReconnectPopulateTests.swift` supplies the paged-window
and hidden-reaction requirements:

- `refreshWindow keeps the paged-in window` and
  `refreshWindow keeps the paged-in channel window and sender tables` use
  `pageSize=50`, 112 persisted rows and an explicit second page before refresh.
- `refreshWindow after hidden outgoing reactions retains the oldest row` uses
  62 ordinary rows and two newer outgoing SENT `thumbs-up + newline + ABCDEFGH`
  rows. The test preserves that exact wire text and status.
- `exactly floorLimit plus one drops the probe and reports hasMore` supplies the
  51/50 boundary. Repeating it across refresh, and the channel counterpart, are
  supplemental native consumer assertions, not extra original-family credit.
- `refreshWindow catch-up of newer store rows keeps the oldest loaded row` and
  `first open of exactly one page reports no further history` supply the positive
  controls.

Random source UUIDs remain fresh. The random source 32-byte contact key becomes
a deterministic 32-byte key; irrelevant ambient parent `lastMessageDate` becomes
`Instant.EPOCH`. The original 1000-based timestamps, message directions/status,
channel slot 3, empty channel secret, sender-name pattern and two-hop DM path
are retained. No real radio is required or configured.

These are supplemental integrated witnesses, **not complete replacements for
the 27 originals**. They do not assert coordinator render-item equality,
channel sender tables, actual reconnect subscription, primer/open behavior,
writer transfer, or cancellation races. No `OriginalCase` annotation,
review-proposal registration, archived binding refresh, ledger completion or
coverage-count increase is added. Merged PRs #312, #315 and #324 stay history.

## Local commands and exact outcomes

- `python .\android\parity-tests\audit.py --check`: PASS, frozen source/native
  provenance; archived accounting is not refreshed.
- `python .\android\parity-tests\semantic.py --check`: PASS, 58 groups, 5,133
  historical families, 4,027 automatic assertion proposals, 1,498 recomputed
  comparison candidates. These are not executed tests, investigation completion,
  semantic approval or the 1,479 committed archived binding triples.
- `python .\tools\android-port\controller\test_runner.py --tests android\parity-tests --quiet`:
  PASS, 108 discovered/run/passed, zero failures/errors/skips. Python fixtures only.
- `python .\tools\android-port\portmap.py`: EXIT 0; traceability listing only,
  not behavioral acceptance.
- `python .\tools\android-port\controller\validate.py`: PASS, 1,866 frozen
  reference paths, 64 WPs and 178 edges; no manifest change.
- `python .\tools\android-port\controller\ci.py preflight`: BLOCKED, exit 2,
  `Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.
- `python .\tools\android-port\controller\ci.py run --stage scaffold --scope scaffold`:
  BLOCKED for the same missing state; no Gradle or JUnit invocation occurred.
- `git diff --check`: PASS.

Kotlin compilation, positive discovery and execution of the eight methods remain
**UNVERIFIED**. No failed Kotlin test count or passing Kotlin result is inferred
from source inspection. No runtime repair, invented Gradle task, dependency
installation, CI rerun, device execution or second CI-result bundle is added.
The manifest's whole-WP verification remains explicitly unconfigured.

## Product-owner continuation

Current source predicts real counterexamples: feature `ChatViewModel.refresh`
requests `before.totalFetchedCount + PAGE_SIZE`, so the preserved 100-row window
can widen to all 112 and a 50-row window can admit the 51st probe. Its raw
normalization path does not apply the existing hidden-outgoing-reaction policy.
These are **source-derived expected failures, not observed executions of this
candidate**. Do not weaken the assertions to match those paths.

WP-307 owns the feature timeline; WP-303/407 owns App composition. The existing
WP-213 coordinator/window implementation and WP-202 store queries are prior art,
not permission for WP-501 to edit those files. The actionable blocker is the
current product producer's integrated live-window repair plus exact-candidate
execution of this regression group. Any actual producer overlap must be
serialized with that owner. This candidate does not resolve F17 or require
hardware to continue software work.

The exposed tool catalog has no `send_session_message`; direct delivery to
coordinator `fbbcfbcc-1115-4f05-94f9-9109c347734a` cannot be claimed. This concrete
test/evidence path is available for its in-session integration.

## Product repair and local execution

The repair continues the source-derived diagnosis above and changes no assertion of the
eight methods. Hosted run `40332259810` for candidate `b9e32319a` had failed five of the
eight (`directRefreshPreservesOneHundredLoadedRowsAndTwelveOlderRows`,
`channelRefreshPreservesOneHundredLoadedRowsAndTwelveOlderRows`,
`directRefreshDoesNotAdmitTheFiftyFirstProbeRow`,
`channelRefreshDoesNotAdmitTheFiftyFirstProbeRow`,
`directRefreshKeepsSentReactionRowsStoredButOutsideTheVisibleWindow`). A local run
of the merged candidate reproduced exactly those five failures before the repair.

Production changes (owner: WP-307 timeline, with the App adapter and benchmark target
delegations that implement the new contract member):

- `ChatViewModel.refresh()` anchors on the oldest loaded `sortDate` and uses
  `fetchMessageWindow(anchorSortDate, floorLimit)` instead of widening by `PAGE_SIZE`.
- Sent outgoing reaction rows are removed from the visible timeline in `open`,
  `loadOlder` and `refresh`, while `totalFetchedCount` and paging offsets count every
  stored row. The App adapter supplies the existing `isHiddenOutgoingReaction` policy.
- `TimelineDataSource.fetchMessageWindow` and `ChatTimelineDependencies.isHiddenOutgoingReaction`
  are added; see [WP-307 deviations](../../deviations/WP-307.md) for the adapted
  spinner-retirement scenario and the frozen-source mapping.

Local executions in the merged worktree (JDK/SDK from the local build cache, offline
Gradle, `--max-workers=4`):

- Before repair: `:app:testDebugUnitTest --tests '*ConversationWindowContinuityAcceptanceTest*'`
  ran 8, failed 5 (the five above), passed 3.
- After repair: the same selection ran 8, failed 0, skipped 0.
- After repair: `:feature:chats:testDebugUnitTest` ran 394 cases, failed 0, skipped 0
  (392 existing plus two new hidden-reaction cases).
- `:app:compileBenchmarkKotlin`: PASS (compiles the third `TimelineDataSource` adapter).
- After repair: `:app:testDebugUnitTest` restricted to the ten classes that consume the changed
  timeline/data-source paths (`ChannelAvatarConsumerTest`, `ChatOpenScopeAcceptanceTest`,
  `ChatPrefetchAdmissionAcceptanceTest`, `ChatRegistrationTest`,
  `ConversationWindowContinuityAcceptanceTest`, `DurableRetryIntegrationTest`,
  `FailedSendThreadRecoveryOriginalTest`, `FreshStoreBackupAcceptanceTest`,
  `MessageBackupAcceptanceTest`, `NotificationDraftRestorationTest`) ran 94, failed 0,
  skipped 0. The complete `:app:testDebugUnitTest` suite (one forked JVM per class) was **not**
  run locally; it remains **UNVERIFIED** here and is left to the hosted exact-commit job.
This is a local candidate result only. The exact-commit hosted job result and log remain
authoritative; no device, hardware, iOS, signing or license verification occurred.
