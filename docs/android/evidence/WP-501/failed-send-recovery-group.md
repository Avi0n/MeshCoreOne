# WP-501: visible-thread failed-send recovery

## Scope and source

Existing issue134; same owner/attempt
`d6056690-3a5d-453e-b67e-36866c676ac5`, same managed branch/worktree.
Frozen source remains `db14559b39d32322b06477c6ae676112f583db50`.
Implementation base is actual merged
`ae349b875e8d00b93eeaf66687649f997ba17dc4`; PR312 and PR315 are merged.
The historical local `06acdbd7457a085197e4c1ed4ccf240888f34044` avatar
handoff and previously held drafts were preserved through a normal merge.

The current implementation is a single App-adapter product correction and
its complete relevant failed-send regression group, not a standalone badge
test project or completion of the 48-original recovery campaign. No
primary-owned timeline/list implementation, avatar renderer, runner,
dependency, policy, frozen input, native allowance or resource is changed.

## Actual defect and correction

Frozen `MC1/Views/Chats/ViewModel/ChatViewModel+EventStream.swift` handles
`messageFailed` by reloading the row and marking the currently open thread's
failures seen, then notifying conversation observers. The existing Android
`AppChatsFeature.timeline(...).events` read the row and emitted
`TimelineEvent.MessageChanged` only. Unlike opening a conversation or a local
enqueue failure, an ordinary service failure could therefore leave an
unseen failed-send list badge after the user had already seen the failure.

The App event adapter now calls the same visibility-gated persistence helper
used by `failPending`, then refreshes conversation observers before emitting
the unchanged timeline event. Visibility is the actual selected Chats tab
and top navigation destination, with matching radio plus contact ID or
channel index. A retained collector for another route/tab cannot mark it
seen. Existing Room writes restrict the update to failed outgoing unseen
rows. Persistence failures retain the standard warning, do not manufacture
a seen result and do not drop the message-change event. Cancellation is not
caught. No read-status/retry/wire/queue semantics change.

The local enqueue path retains its delivered-status guard and existing two
conversation refresh notifications. The helper extraction does not add an
extra refresh to that path.

## Complete frozen-family disposition

The [full cohort map](../../parity/recovery-cohort.md) retains all 27
reconnect and 21 failed-send originals, helpers, event inputs and remaining
producer dependencies. The candidate contains:

| Originals | Actual native candidate |
| --- | --- |
| F01-F16: contact/channel/room membership, clear/empty/no-radio, reload, event policy, stale/cancelled reads and fetch failure | All 16 methods in `FailedSendBadgeRecoveryOriginalTest`; real App list adapter, actual in-memory Room query and real holder. The decorator only injects the frozen-style failure or suspends after a real query before applying its keys. |
| F18: channel load marks failures seen | `FailedSendThreadRecoveryOriginalTest.channelOpenClearsTheStoredFailureBadgeWithoutChangingFailedStatus`; channel3, actual open, stored FAILED/seen and actual refreshed list. |
| F19: current-contact event marks seen | `serviceFailureForTheVisibleContactClearsItsPersistedBadge`; actual navigation/event stream, App transform, persisted flag, retained FAILED status, list badge and observer version. |
| F20: foreign-contact event does not mark seen | `foreignContactFailureCannotBeMarkedSeenByTheVisibleThread`; actual ordered event processing followed by a matching sentinel, then unchanged other-contact flag/badge. |
| F21: local enqueue failure marks current contact seen and reports error | `offlineLocalEnqueueKeepsFailedSeenStorageAndAnActualLocalizedComposerError`; already-persisted source pending-row seam, real offline App enqueue, real composer, actual failure write and mounted localized native error. |
| F17: prime does not mark seen, interactive open does | **UNPROVIDED.** Production `ChatPrimerFactory` defaults to null, and `StorePrimeTimeline` is test-only. No fake primer or direct Room read is registered as the missing production consumer. |

Thus there are **20 original candidates, not 21 accepted originals**.
Five additional methods preserve the surrounding production contracts:
visible channel radio/slot isolation; inactive-tab retention; changed-route
retention; real SQLite seen-write failure and recovery; and late local
failure/repeated events against delivered storage.

Declared runner identities: 16 methods in
`com.meshcoreone.android.parity.FailedSendBadgeRecoveryOriginalTest` plus
9 in `com.meshcoreone.android.parity.FailedSendThreadRecoveryOriginalTest`.
These **25 declarations are not execution/discovery evidence**. Exact-head
App compilation and their complete passing JUnit identities are required.

## Fixture and native adaptations

The full source helpers/defaults are documented in the cohort map. New
thread fixtures retain `"hello"`, timestamp1700000000, outgoing/plain,
source default failure/status and seen fields, channel3/General/empty
secret and Test/CHAT contacts. Explicit fixed dates replace ambient Date.
Sequential UUIDs for the new contact fixtures give distinct repeated
hash-byte public keys without accidental low-byte collisions; source UUID
hash bytes are not a wire oracle. Radios and message identities remain
isolated per test.

F01-F03 consume actual saved/queryable failures instead of exposing the
source's private apply method. F05 first saves and marks a real failure seen
before querying empty keys, preserving the original replacement predicate.
F08 keeps the process store alive while the current-radio input becomes
null, as required by Android process lifetime.

F21 preserves the original already-saved pending row and NotConnected
failure boundary. The creation seam returns that exact row; it does not
fake successful delivery. A READY composer input only enables Send, while
the actual offline App queue rejects enqueue. Completion is awaited at the
real `recordLocalEnqueueFailure` return, not at the earlier UI error
transition, so persistence cannot race the assertions. No radio connects.

The existing Android composer displays localized `NoDeviceConnected` for
typed NOT_CONNECTED instead of the frozen generic `UnableToSend.message`.
The test asserts the actual mounted native copy independently from the
localization resource and records this difference; it does not modify or
weaken the frozen equality. Independent acceptance of this native
adaptation remains pending. No iOS execution is claimed.

## Reservation and verification

Same-owner r142 evolved to r143 for all 39 incoming main paths using
`merge-disjoint-semantic-edits`, then r144/r145 for the App composition and
owned tests/evidence. `app-build-launcher` admits
`AppChatsFeature.kt` with `update-launcher-or-composition`; `wp-owned`
admits the WP-501 test/document paths. Attempt, identity, historical binding,
authorization prose, branch/worktree and the complete protected hard-lock
record were compared and preserved. No claim recreation, direct ledger
edit or dispatch/settings change occurred.

Actual local commands:

| Command | Result |
| --- | --- |
| `git diff --check` | PASS; no whitespace errors. |
| `python tools\android-port\controller\validate.py` | PASS; 1,866 reference inputs, 64 WPs/178 edges, frozen manifest/source ownership valid. |
| `python tools\android-port\portmap.py` | PASS; provenance/ownership check, not behavioral parity. |
| `python android\parity-tests\audit.py --check` | PASS; frozen source/native provenance, not acceptance. |
| `python android\parity-tests\semantic.py --check` | PASS; 58 groups/5,133 historical declarations, 4,023 with candidate assertion proposals, 1,498 recorded candidate comparison bindings. None is a new accepted count. |
| `python tools\android-port\controller\test_runner.py --tests android\parity-tests --quiet` | PASS; 108 discovered/run/passed, zero failures/errors/skips. Python accounting fixtures only. |
| `python tools\android-port\controller\verification_config.py --check` | PASS; full feature verification remains `false`. |
| `python tools\android-port\controller\ci.py preflight` | BLOCKED, exit2: explicit `--state/ANDROID_CI_STATE` absent. |
| `python tools\android-port\local\check.py --commit HEAD --stages python,preflight,scaffold,inspect` | BLOCKED, exit1: malformed installed reservation runtime manifest, before Android execution. At that invocation HEAD was the normal main-integration commit, not a claim of final-candidate verification. |

No manual Gradle, guard bypass, runner repair or dependency installation was
used. Kotlin/compiler/JUnit execution is **UNVERIFIED locally**. Ordinary
feature-branch publication may still use its existing automatic hosted
checks; only the exact new-head job/log can establish those outcomes. No
second CI-result bundle is retained.

F17, the broader retained-window/reconnect/hidden-row/unread-boundary/mention
work, mounted rich footer/inline reaction consumers, independent source
review, exact-candidate execution and separately authorized native/human
gates remain outstanding. Issue261's bounded historical acceptance is not
reopened; whole issue134/WP-501 remains incomplete.
