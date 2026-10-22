# WP-208 queue recovery accounting

Repository: `cbattlegear/MeshCoreOne-Android`; owner: `services-porter`.
Managed branch: `cbattlegear-friendly-garbanzo`.
App session: `9133a4cd-906c-4b8b-aa7c-ef39bbf78dd8`.
Runtime session: `0c0f91a9-098a-40b7-8220-71e582a39c8d`.
Base: `0a7a84e388393557e4b66ac7fd322248e28611fd`.
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
