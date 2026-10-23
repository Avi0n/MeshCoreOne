# WP-502 Room failed-event consumer

Base: actual merged main `dd07233b54fbff93be0185b684fc3840bb62ab49`.
Frozen specification: `db14559b39d32322b06477c6ae676112f583db50`.
The PR and its own automatic job bind the exact candidate, not a self-referential
checksum or a second hosted-result bundle.

## Source and bounded repair

Frozen `MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift` lines201-250
distinguishes received, ordinary status and failed events. A failed ID already
in the displayed messages first schedules the pending first-50ms coalesced
reload, then awaits `markFailedSendsSeen` for the captured room session. Only a
successful write notifies conversationsChanged. Failure is logged without
removing the independently scheduled reload. Unknown IDs do nothing.

The actual App adapter already looks up the UUID in the captured radio's real
store and verifies the stored room-session ID; the raw failure event carries
only a UUID. The bug was collapsing both failure and ordinary status into
MessageChanged, so the holder could only mark failed sends seen inside a later
successful load. That loses the independent in-thread effect during a held or
failed read.

RoomConversationEvent now retains MessageFailed. The same real App adapter
emits it only after its unchanged persisted-radio/session checks. The holder
retains its current-session and loaded-message checks, schedules the unchanged
reload, and awaits the real failed-seen write immediately. Success alone
refreshes conversations. A real write exception reaches the existing App
diagnostic logger; collection continues and the reload remains scheduled.
Cancellation is rethrown. Normal load side effects, first50ms scheduling,
generation invalidation, snapshot ordering and the operation mutex are unchanged;
the immediate effect does not acquire that later-read mutex.

No new radio/token event architecture, retry/send policy, translation behavior,
deadline, expected outcome or original-family accounting is introduced.
Primary WP310 feature and App provenance stays intact.

## Deterministic real-consumer cases

`RoomFailedEventConsumerTest` is owned Android-only supplemental integration
coverage, compiled by the existing App unit-test source registration. It uses
the actual ContainerHarness/AppChatsFeature.room event stream and holder,
in-memory RoomPersistenceStore, and the existing test scheduler/5s harness
polling seam without advancing virtual time during asynchronous Room work.
No replacement event evaluator or successful fake persistence is used.

The existing Room data/dependency seams record operation order and can hold or
fail delivery of an actual fetched Room snapshot. All unmodified operations
delegate to the real App dependencies. Conversation callbacks call the actual
App refresh and verify its version. Diagnostics are observed at the actual
AppChatsFeature java.util.logging handler, not a substituted diagnostics sink.

| Supplemental case | Required observation |
| --- | --- |
| Known failure; later read held | Actual failureSeen and conversation refresh at0ms; no new read at49ms; at50ms held read cannot erase the already committed effect; ordinary load sequence follows release. |
| Failure during older held read | Immediate write at50ms invalidates the old snapshot; stale completion performs no read/notification effects; successor slot still fires at100ms. |
| Later read fails | Immediate durable effect succeeds before49ms; the50ms read error reaches the existing load diagnostic/UI error; next ordinary status recovers. |
| Ordinary/unknown/unloaded/foreign | Unknown ID, other session and other-radio-only IDs cannot reach this holder; a stored but not loaded ID is ignored by its known-message guard. Ordinary status schedules only the later read, with no immediate write/refresh before50ms. |
| Mixed burst/order | Failed, received, duplicate and status events at0/20/40ms share the FIRST50ms read, not50ms after the latest event. Equal-timestamp live arrival order and later store createdAt order are both checked without duplicate rows. |
| Actual write failure | A SQLite trigger aborts the real failureSeen UPDATE; the typed PersistenceStoreException reaches the App logger, with no false success callback. The already scheduled read still runs. Dropping the trigger and sending a fresh failed event proves collector survival and successful recovery. |
| Write cancellation | A suspended immediate write is cancelled at its actual collecting Job; cancellation propagates without diagnostics/success and does not own the separately scheduled reload. |
| Screen cancellation | Cancelling the screen before50ms stops its pending read and cannot revert the already committed immediate write or duplicate its callback. |

The frozen ordering suite has12 ordering/grouping cases and no failed-event
case. Those originals and the six existing Room accessibility originals receive
no new credit. Incumbent feature/App/Rooms/picker tests are not rewritten.
These eight cases are source-derived supplemental evidence, not Swift-original
test ports, native/device observations, approved goldens or whole-WP acceptance.

## Admission and execution boundary

Same canonical owner/attempt/session
`3bc8c1b8-20ec-4647-88ae-db3775268489`, same worktree/branch. Typed reservation
58 ->59 admits exactly RoomContracts and RoomConversationStateHolder via trusted
wp502-room-failed-event-consumer / repair-room-failed-event-consumer. Revision60
admits the directly necessary App adapter via app-build-launcher /
update-launcher-or-composition. Revision61 admits the owned test and WP502
evidence/deviation via wp-owned create/modify/record-evidence. Authorization prose,
source pin, primary ownership, policy and manifest are unchanged.

At authoring the new code has not compiled or executed locally. Declared
`python .\tools\android-port\controller\ci.py preflight` exits2:
`Explicit --state/ANDROID_CI_STATE is required; failed cloud setup is not readiness`.
Missing readiness is not permission for manual Gradle/native execution.
Source commands `python .\tools\android-port\portmap.py`,
`python .\tools\android-port\controller\validate.py`,
`python .\tools\android-port\controller\verification_config.py --check`,
and `git diff --check` passed/exit0. `git diff --cached --check` is also required
before commit. Exact candidate results belong in the PR together with its
genuine full immutable-main pre-push outcome and own
automatic hosted job. Missing local fast verification must remain UNVERIFIED;
neither merged292 nor landed303 outcomes transfer to this new implementation.
No runtime/hook installation, hard-lock action, native/guest/device/APK run,
dispatch/rerun or second CI-result bundle is authorized.
