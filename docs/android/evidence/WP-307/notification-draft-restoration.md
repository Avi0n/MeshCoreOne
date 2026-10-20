# Pending notification reply: mounted App consumer

Repository `cbattlegear/MeshCoreOne-Android`; WP-307 / chats-ui-engineer.
This is a same-owner continuation after merged PR #235, not a successor
session, native rerun, or amendment to its scroll evidence.

## Source and admission

Base: `9c430cb0f1666faacc443794899b68c01128a87a`.
Specification: `MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift` and
`MC1/Views/Chats/ChatConversationView.swift` at
`db14559b39d32322b06477c6ae676112f583db50`; both selected files and
`ChatViewModelDraftRestoreTests.swift` remain byte-identical to that pin.
The repository's two unrelated historical Swift test exceptions are documented
by ADR-003/004; neither is changed or used as this behavior's oracle.
WP-306 prerequisite is merged (PR #74 / `95a38aada`).

Manifest SHA-256:
`949598d9d070f14378dcda6150f67ead1bb70d29fc261c685cadf6649c4aa175`.
The supported existing WP-307 reservation evolved from revision12 to13 for
`AppChatsFeature.kt`, `AppChatsContent.kt`, and the App-owned mounted test under
`app-build-launcher` / `add-direct-support-wiring`, then to14 for this evidence
and the WP deviation under `record-evidence`. Historical identity, base,
authorization and attempt were preserved, not released/recreated:
coordinator `bcb17a74-5fa6-47d0-a4be-b6b595e20559`; executing existing session
`a5186fbf-7706-408e-9c21-354dfe60e3d8`.
The branch fast-forwarded to current main without changing another checkout.
No semantic overlap with PR #247's settings/language repair was found.

The frozen `restoreComposerDraft` consumes the notification source first,
then applies disk only when the resulting field is empty. The conversation
view schedules persistence when composer text changes and flushes on exit.
Kotlin `NotificationActionHandler.replyNotSent` already saves pending text
in the real session NotificationService (also preserving cancellation);
the App had no production `consumeDraft` caller. Its shared generic draft
getter served both timeline and composer, and must remain read-only.

## Negative and implementation

At `5a4c3d702ea71dd5dbdff7255a21e7757839f4c0`, the new mounted test is the
only change from main. The actual MainActivity / NativeNavigationShell /
AppContainer / Room / session graph received process saved text `disk` and
real NotificationService pending text `failed reply` for the same direct
conversation. Its actual editable field was **disk**, not **failed reply**.
The runner discovered/run1, failed1, errors0/skips0 at the unchanged visible
text assertion. This is an observed consumer failure, not an inferred fifth
case in the separate four no-notification originals.

Production fix: `AppChatsContent` runs a composer-keyed `LaunchedEffect`;
`AppChatsFeature.restoreNotificationDraft` consumes only a Direct target's
matching current-radio/contact EntityKey. It assigns through the existing real
composer edit/persistence path. A nonempty pending reply replaces old disk;
an empty pending reply falls back to disk, matching the source order.
No pending source means no assignment. Consumption/application are synchronous
inside the effect, with no suspension between them; cancellation before the
effect starts does not consume. No connection-ready gate is added.

The effect is not keyed to window width, list refresh, or connection state.
A retained composer keeps user text. A pending reply arriving later remains
available until a new composer entry rather than replacing typing on
recomposition. Recovered text is immediately persisted through the existing
Android per-edit policy, rather than a second Swift-style debounce, so the
consumed value survives later owner replacement through the process store.
The separate timeline does not consume or claim the visible field.

Final software-code candidate:
`c933f03080de482e7dc9872b400cef33e04b209a`.

| Path under `android/` | Git blob |
| --- | --- |
| `app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt` | `eb9153b9c73cfa3dfa6e693fccd5a202ef2f3ef8` |
| `app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt` | `dcc2f6db558cc349b0316eede420625ab8ae46b9` |
| `app/src/test/kotlin/com/meshcoreone/android/app/platform/NotificationDraftRestorationTest.kt` | `94b6e6c5e6913095362ce4b9bc36b7e450cd22b4` |

## Actual commands and outcomes

All Gradle executions used the installed serialized Linux/ext4 snapshot lane,
pinned Python/JDK/SDK, candidate credential-stripped environment, shared Gradle
cache and strict dependency verification. No native/device endpoint was used.
Commands below are the actual Gradle arguments after the managed wrapper's
`-p <snapshot>\android` project argument.

Readiness:

```text
python tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit 5a4c3d702ea71dd5dbdff7255a21e7757839f4c0 --stages preflight
python tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit db4152167988af41ac3c8b50ef6caf766752342b --stages preflight
python tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit c933f03080de482e7dc9872b400cef33e04b209a --stages preflight
```

All three **PASS**, actual scaffold environment checker, approximately0.5s
each. These are readiness results, not discovered feature tests.

Negative at `5a4c3d702`:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.app.platform.NotificationDraftRestorationTest.pendingNotificationReplyWinsSavedDraftOnceAndSurvivesAdaptiveRecomposition
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**Expected FAIL**, 1 discovered/run, 1 assertion failure, 0 errors/skips.
JUnit records exact expected `failed reply`, actual editable text `disk`.

First positive at `db4152167988af41ac3c8b50ef6caf766752342b`:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.app.platform.NotificationDraftRestorationTest
--tests com.meshcoreone.android.app.platform.ChatTimelineRestorationTest
--tests com.meshcoreone.android.app.navigation.*
:app:lintDebug
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**PASS**, 144 discovered/run/passed, 0 failures/errors/skips; App lint passed;
build1m43s. Three are the new notification tests; the remaining141 are existing
selected scroll/navigation tests, not new original-case credit.

Final expanded isolation assertions at `c933f03080de482e7dc9872b400cef33e04b209a`:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.app.platform.NotificationDraftRestorationTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**PASS**, 3 discovered/run/passed, 0 failures/errors/skips, build32s.
Production bytes are unchanged from the144-case/lint run; the test adds actual
channel entry and mismatched-current-radio direct entry controls. No earlier
result is relabeled as execution of these added assertions.
Counts/outcomes came from actual JUnit XML testcase nodes, not method names.

Exact final method identities in
`com.meshcoreone.android.app.platform.NotificationDraftRestorationTest`:

- `pendingNotificationReplyWinsSavedDraftOnceAndSurvivesAdaptiveRecomposition`
- `absentNotificationReplyRestoresOrdinarySavedDraft`
- `emptyNotificationReplyFallsBackToSavedDraft`

The positive shared mounted scenario asserts notification-first precedence,
consumption removal, persisted recovery, later pending text not consuming on
same-owner refresh/resize, focus and field identity through
360/599/600/779/780/834/360dp, new-entry recovery, different-contact and
same-contact/different-radio isolation, ordinary channel saved draft, no
consumption from the mismatched active radio service, one unchanged session,
zero direct/channel send commands and zero queued envelopes.

`python tools\android-port\portmap.py`,
`python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\verification_config.py --check`,
and `git diff --check` **PASS** on the implementation.

## Limits and handoff

This is a **seeded-real-service mounted consumer witness**, not a full
OS-notification RemoteInput / failed-send end-to-end test. Notification posting,
permission, platform delivery, cancellation-driven recovery from an actual OS
reply, process/Activity recreation, physical persistence, API37 and hardware
are not established by these SDK31 Robolectric tests. The process draft store
uses the existing MemoryDraftDefaults harness; Room/session/queue are real.

No foreign composer/core NotificationService/WP-502 UI-test/Rooms source was
edited. The four no-notification original-case assessment at
PR #165 comment6297011991 and prior scroll/native evidence remain unchanged.
This closes the demonstrated software wiring gap only, not issue238, a whole
WP, trusted review/merge, or a native/human gate. Exact published candidate CI
and its hosted logs remain authoritative; no second result bundle is retained.
