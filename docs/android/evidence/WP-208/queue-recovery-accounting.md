# WP-208 queue recovery accounting

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `services-porter`.
Managed branch: `cbattlegear-friendly-garbanzo`.
App session: `9133a4cd-906c-4b8b-aa7c-ef39bbf78dd8`.
Runtime session: `0c0f91a9-098a-40b7-8220-71e582a39c8d`.
Base: `0a7a84e388393557e4b66ac7fd322248e28611fd`.
Necessary current-main integration:
`f81d2f56d897b022b6f6c4cf38551448691eae02` (the already approved WP-003
bounded CI budget update; no CI/tooling edits authored by this WP).
Subsequent trusted-main integration:
`4013368b7f47fdd51e291059bf690ced0dc4c434` (approved WP-003 historical
reservation ancestry repair; source pin, manifest and product scope unchanged).
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Trusted manifest SHA-256:
`977b9e91259cca64ffe10f17c96891e60608793827183350ccc81e2412a150d8`.
Trusted policy revision:
`70f65b1205f1581c0f1a86107ccdc20f794421a6cf9b172e17d8a3e544569a66`.
Initial real producer API commit: `139f9bb40f1355787c5eac3d2ffdc3ee109b35ef`.
The PR's exact head and hosted job/log, not a second retained CI bundle, determine
the candidate and its execution evidence.

## Production contract and remaining consumer prerequisites

Public type:
`com.meshcoreone.android.core.services.messaging.ChatSendQueueProgress()`.
Public injection:
`ChatSendQueueService(..., progress = processOwnedProgress)`.
The owner contains no coroutine scope, transport, service or close operation.
Its atomic generation-bound mutation methods are internal to messaging; App
only constructs and passes the owner, never manipulates failure counts.

WP-303 must construct one owner in the process graph, carry that same reference
through SessionEnvironment and RadioSessionContainerFactory.withLifecycle, and
inject it into every per-connection queue. The source-compatible constructor
default is the old isolated-queue lifetime, not a replacement for that wiring.
This producer PR alone does not fix the running App.

Counts are scoped by radio/message UUID, with pending-row UUID guarding a
manual retry's new envelope incarnation. A newer connection generation retires
older leases. Queue shutdown retains pending progress. Query success and
successful envelope completion clear it; failed terminal deletion does not.
Successful hydration prunes externally removed/replaced rows on that radio.
There is no schema migration or disk persistence of this process-only state.

WP-207 separately routes real CorrelationUncertain into physical recovery.
Ignored ERROR2, a fenced send refusal, cancellation and a stale ownership check
are never manufactured second query failures or null channel results. Existing
send-attempt persistence, disambiguation threshold three, query-failure cap
sixteen, direct/flood retry policy and protocol correlation fences are unchanged.
WP-501's existing real App edge regression must run after actual producer and
consumer adoption. No whole-bug completion claim is made here.

### Real-query accounting completion before recovery

The queue now exposes the concrete public method
`suspend fun awaitChannelQueryAccounting(): Unit`. It atomically seals new send
and query admissions, then observes the actual currently in-flight
disambiguation query of this queue generation. The flight
is registered with its message UUID and preflight pending-row UUID before
calling the real query. No active query means there is nothing to await; a
retired/not-current queue is rejected, not reported as completed. An empty
snapshot still seals admission. Sealing is monotonic for this generation,
including if the recovery observer is cancelled. Both drains stop after their
current envelope without cancelling it; durable enqueues remain accepted for
the successor. Checks before/after preflight prevent a suspended old preflight
from later submitting a new send. Query registration and preparation share the
same lock, so an admitted query is either observed or refused before invocation.
Terminal completion cannot admit the next envelope before recovery resumes.

An observer waits through the query's actual return/throw and either pending
status remapping plus recorded park, or the terminal persistence/deletion
attempt at exhaustion. It does not await the parked drain or erase retained
storage failures. Failed deletion still retains the exhausted budget.
Cancelling an observer leaves the query running. Manual shutdown, replacement
and query cancellation invalidate the flight without adding failure credit.
Deferred completion runs outside the queue lock.

WP-207 must await this real producer completion, via WP-303's actual graph
delegation, BEFORE withdrawing the token, publishing DISCONNECTED, cancelling
the connection scope, retiring the lease or tearing down services. Its wait
must run outside protocol/lifecycle locks, remain cancellable by manual stop,
replacement and shutdown, and recheck generation/intent after suspension.
Postponing physical close after token withdrawal is insufficient: terminal
persistence checks require the current token. No default-success graph hook,
arbitrary grace delay or non-cancellable whole query is an acceptable adapter.
The process-owned progress injection and its no-close lifetime are unchanged.

Nine additional ChannelQueryAccountingTest cases exercise the completion
contract. The early-recovery case uses the actual ChannelService over the real
MeshCoreSession and raw firmware ERROR2. Recovery starts during the real first
timeout retry sleep, BEFORE the queue catch, and is held by the producer
barrier, not by a test-only recovery gate. The original 1,999/2,000ms timeout
window is retained. The actual 400..600ms source retry delay is observed and
checked at its last millisecond; the retry then encounters the unchanged
ConnectionLost/UnresolvedReply(channel:0) fence. Two replacement generations
spend two genuine query outcomes, failing/deleting A while preserving B.
A blocked real terminal-deletion operation proves the token stays current
until cleanup completes. The same-channel B retains its exact original attempt
count of two even before recovery's continuation can retire the graph. Idle
preparation followed by competing channel/direct enqueues preserves both durable
rows without send/query admission; preparation during suspended preflight
allows its already-admitted persistence to finish but prevents a new wire send.
Other cases cover manual cancellation without credit,
observer-only cancellation, failed terminal storage with retained exhaustion,
a real unconfigured-channel result, non-cooperative late replacement and an
actually empty in-flight set. These cases are authored pending exact-candidate
execution; the authoritative PR check/log supplies results, not a second bundle.

### Combined App/runtime ordering still requires actual consumer adoption

The lower-level cases intentionally do not include ConnectionManager.
SessionCore emits CorrelationUncertain in the query's finally block, before its
caller necessarily observes the thrown outcome. WP-207 PR277 dispatches physical
recovery on Default, so teardown can potentially retire/cancel the queue before
its query catch records that genuine outcome. A diagnostic is also emitted for
written-query cancellation, so it must not be relabeled as a genuine timeout or
used to manufacture a failure count.

WP-501/WP-207 must establish the actual combined ordering with the real App
graph, fresh generations and the original A/B outcome after WP-303 injection.
The completion method above supplies the messaging side of that handoff;
retaining memory alone cannot supply a missing outcome. Runtime and App must
actually call it with the stated pre-withdrawal ordering. This producer does not relax cancellation or stale-result
guards, add scheduler sleeps, or claim its real-session service tests prove
that combined boundary. Coordinator source review identified this prerequisite;
it is not hidden by a lower-level test pass or a guessed App adaptation.

### Passive persistence receipts for the native-adapted App witness

The API published in `7c10498286507958dfc80796a0ffd5a4c770b746` is retained:
`com.meshcoreone.android.core.services.messaging.ChannelQueryAccountingEvent`
and the optional final queue constructor parameter
`onQueryAccounting: ((ChannelQueryAccountingEvent) -> Unit)? = null`.
The immutable event carries actual `token: SessionToken` (including radio),
`messageID: UUID`, `pendingID: UUID`, `failureCount: Long`,
`failureCap: Long`, `outcome: ChannelQueryAccountingOutcome`, and
`queryFailure: Throwable`. The outcomes are `PARKED` and `TERMINAL_DELETED`.
Counts come from the actual retained progress increment, the cap from this
queue's config and the cause from the actual thrown query, not a send failure
or recovery diagnostic. The observer is synchronous, passive, non-blocking and
non-throwing; it must not perform lifecycle work.

The consumer's intervening no-new-API decision produced local removal commit
`0aa9ed05eee49e3687504f2d724d128fdc6a3051`; that commit was never pushed.
The coordinator's subsequent independent WP-501 review superseded that decision:
existing Failure diagnostics plus retirement cannot prove successful accounting.
Park/terminal diagnostics occur before completion, remapping can fail, and
runtime retirement can follow a cancelled/failed accounting wait.
The API is therefore retained in this coherent successor, not replaced with a
new runtime success callback or an accounting-barrier restructure.

Exact emission order, unchanged from published7c:

- `PARKED`: actual query throw and counter increment, typed query diagnostic,
  successful PENDING status write and persistence-completion ownership check,
  recorded park diagnostic, receipt callback, then `finishQueryAccounting`.
- `TERMINAL_DELETED`: actual query throw at the configured cap, terminal
  diagnostic, successful FAILED status write, current-token check, failure
  notification, pending-row deletion, current-token check and queue/progress
  cleanup, receipt callback, then `terminalChannel`'s finally completes accounting.

Each registered flight can report once. Failed remap/deletion, cancelled/retired
flights and real null/configured successful queries emit no success receipt.
This successor additionally respects the existing status-write Boolean: a
no-op caused by a missing/delivered message must not certify a committed
PENDING/FAILED transition. The original persistence operations, cancellation,
failure retention, send/query counts, thresholds and recovery wait are unchanged.
The returned Boolean is used only to qualify the receipt.

These are **post-persistence receipts, not notifications of an already-completed
barrier**. Emission remains before waking recovery to avoid racing token
withdrawal on Default. A manual cancellation can still follow a receipt.
The actual App witness must capture the live token at callback arrival, observe
A1/PARKED then A2/TERMINAL_DELETED and B1/PARKED with their seeded durable
identities, assert actual Room states, and await finished retirement. It must
explicitly reject both `session.channelQueryAccounting.cancelled` and
`session.channelQueryAccounting` failure fallbacks, remap/terminalPersistence
failures and teardown issues. Receipt arrival alone is not full acceptance.
No new test-driven await, gate, synthetic failure/outcome or retry override is
introduced. App wiring remains WP-303's owned work.

The original nine accounting plus sixteen retention cases are preserved.
Eight additional accounting cases (seventeen total) and strengthened assertions
exercise passive success without a recovery waiter, exact persistence/identity/
typed-cause observation before recovery, failed remap followed by later shutdown
remapping, failed terminal status/deletion, cancellation during non-cancellable remap
and terminal deletion, delivered-row no-op writes at caps one/two, real null/
configured success, stale replies and observer-only cancellation. These are
authored cases, not locally executed evidence; RecordingStore is not Room.

Trusted main `a5dd0fc6c521d09d59d28ca75169e4bdbd56a6cd` was adopted locally
as directed and is included with this coherent API-retaining successor, not published alone to
restart CI. It includes the reviewed host-test partition/suffix discovery and
lint Hint parsing. Its intervening reviewed App action/map changes are normal
main integration, not authored WP-208 edits. Source pin/manifest are unchanged.
This integrated candidate requires its own exact-head hosted result; prior
3fdc green or the superseded event-candidate job does not verify its full bytes.
Targeted local Gradle remains blocked by the confirmed legacy-runtime/protected
upgrade interlock. No repeated failed readiness or native action is performed.
The coordinator additionally holds all new pushes pending review and a supported
processor-neutral publication route from WP-003. The installed legacy hook lacks
`execution_runtime`/`run_owned` and its runner's shared admission requirement,
so after the host-test repair it can reach scaffold Gradle without the current
guard. No push, hook bypass/rewrite, installer or lock mutation is attempted.
Native stack282 remains ordered [278,281]; neither member is merged individually.

Successor source checks: `python tools\android-port\controller\validate.py --repo .`
passed (64 WPs, 178 edges, unchanged manifest SHA above);
`python tools\android-port\portmap.py --repo .` passed, exit zero;
`git diff --check` passed. An initial command used the nonexistent
`tools\android-port\controller\portmap.py` path and did not run validation; the
correct existing command above then passed. Git comparisons confirm unchanged
progress production/test and SendQueue bytes versus3fd, and all nine original
accounting case names remain. These are source/traceability checks, not execution
of the thirty-three authored Kotlin cases. Local Kotlin discovery/execution
remains zero for this unpublished successor.

## Authorship and coverage

Acceptance: `WP-208-behavior`, `WP-208-boundaries`, supplemental native evidence
for `WP-208-source-test-parity` (no new original-family credit).
Frozen ChatSendQueueService.swift, ChatSendQueueServiceTests.swift and
ChatSendQueueServiceAttemptCountTests.swift were read and compared against the
pin; the comparison reported no differences. Existing original tests/goldens
are untouched.

ChatSendQueueProgressTest authors sixteen dynamic cases using actual production
queues, MessageService, MeshCoreSession, firmware bytes and virtual time:
replacement/fencing with A FAILED/deleted while same-channel B survives; default
sixteen real query timeouts across sixteen replacement graphs; genuine non-null
and null query success; successful send cleanup; query cancellation; terminal
deletion failure; preflight persistence failure; replacement pending identity;
failed hydration and genuine typed query storage failure; hydration pruning;
radio isolation; stale lease/attach refusal; late retired
query success and late retired query failure. RecordingStore is explicitly not
Room, hardware or process-death evidence. The two injected non-cooperative late
query roles exercise completion fencing, and a third injected query role throws
the exact typed storage error. Other query roles use real session exchanges,
where ERROR2 is ignored and the query must actually time out.

## Commands and results

- `python tools\android-port\controller\validate.py --repo .`: **passed**;
  64 WPs, 178 dependency edges, 1,866 frozen inputs, 1,786 owned, 80 excluded.
- `git diff --check`: **passed**.
- `git diff db14559b39d32322b06477c6ae676112f583db50 -- MC1Services/Sources/MC1Services/Services/ChatSendQueueService.swift MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceTests.swift MC1Services/Tests/MC1ServicesTests/Services/ChatSendQueueServiceAttemptCountTests.swift`:
  **passed**, no differences.
- `python .\tools\android-port\controller\ci.py preflight`: **blocked**, exit 2,
  missing explicit `--state/ANDROID_CI_STATE`. No Gradle or Kotlin test executed.
- `python tools\android-port\local\check.py --worktree --stages preflight`:
  **blocked**, exit 1, installed merge verifier does not support working-tree
  overlays. This is not a Kotlin/compiler/test result.

The declared relevant runner is `:core:services:test`, with selectors for
ChatSendQueueProgressTest, SourceChatQueueTest and SourceSendTest. It has not
run locally: WP-503 holds the actual disposable/native and gradle-execution
guards. No new emulator, native/hardware lane, tool installation, resource-lock
bypass, signing, privileged CI dispatch or merge was attempted. Publication
remains an explicitly unverified candidate until actual hosted execution.
ChannelQueryAccountingTest uses the same declared services runner. The
prior exact candidate's successful execution does not verify these new bytes.

Actual initial publication:
`git push --set-upstream origin cbattlegear-friendly-garbanzo` **succeeded** at
`9ce95617f242e2db4b72a98ed473aab545069893`. The installed unchanged hook
explicitly reported `UNVERIFIED CANDIDATE` because the shared local Gradle lane
was queued beyond ten seconds. No lock was bypassed.

Initial exact-head hosted run
[39812741210](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39812741210)
**failed** on that head. Readiness, source disposition/provenance and frozen-input
validation passed. Actual command
`python tools/android-port/controller/ci.py run --stage scaffold --scope scaffold`
selected `verifyScaffoldTests verifyRoomSchema` and stopped at
`:app:testDebugUnitTest`: 1,225 discovered, 1,224 passed, one failed, zero
errors/skips. The failure was the existing App-owned
`IncomingReactionSyncTest.meshCoreOpenV3UsesTheStoredChannelTargetWithoutSavingAnOrdinaryMessage`.
The `:core:services:test` task and new regression discovery were **not reached**;
no services-test execution/pass is claimed. The hosted job/log is authoritative;
no second log/XML bundle is retained here.

The successor also moves progress pruning after successful row validation, so
malformed/failed hydration cannot erase retained progress. That real source
refinement and current-main integration are not an unchanged CI rerun.

## Advisory reservation trace

The real initial `reserve.py claim --wp WP-208 --session-id
9133a4cd-906c-4b8b-aa7c-ef39bbf78dd8 --base
0a7a84e388393557e4b66ac7fd322248e28611fd ...` returned **blocked**:
`Existing WP lease is bound to different source/base/manifest/backend`.
The historical record still belongs to the coordinator, revision 2, at base
76ddc915; its PR187 terminal merge receipt already exists. It was not edited,
impersonated, released/recreated or represented as this session's receipt.

The coordinator clarified trusted-main claim/help and release policy: these are
advisory file intents, not pre-edit locks. The actual authorized primary WP-208
assignment plus trusted path/operation admission permits isolated owned edits
while the controller reconciles historical records. Actual `reserve.py admit`
returned `wp-owned` for each modified production/test/evidence/deviation path
with `modify` or `create` as appropriate. Invariants: one WP/branch/PR, pinned
source, declared verification and evidence provenance. No cross-owner primary
path is edited and no hard-resource/merge/publisher guard is bypassed.
