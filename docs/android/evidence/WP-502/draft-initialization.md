# Mounted draft initialization: bounded native adaptation

**Implemented and executed; primary WP-307 equivalence review is pending.**
This does not mark four original cases, notification integration, WP-308,
WP-407/#238 or full WP-502 accepted.

WP-502 / [PR244](https://github.com/cbattlegear/MeshCoreOne-Android/pull/244) /
existing canonical reservation-attempt
`3bc8c1b8-20ec-4647-88ae-db3775268489`, evolved to revision20.
Integrated base: `9c430cb0f1666faacc443794899b68c01128a87a`.
Code and execution: `13de7b9336b9c26996efc8631c51d8190a10d992`.
Frozen source: `db14559b39d32322b06477c6ae676112f583db50`.
Manifest/policy lineage is unchanged from the [WP evidence](README.md).

## Original assertions and proposed witnesses

The four originals are the actual bodies in
`MC1Tests/Views/Chats/ChatViewModelDraftRestoreTests.swift`, not four filename
or header matches. The primary source/consumer oracle is WP-307; WP-501's
[four-case audit](https://github.com/cbattlegear/MeshCoreOne-Android/blob/5144190e6dc866cca287ed2f6b6107fcda13dce4/docs/android/evidence/WP-501/draft-restore-four-cases.md)
and [precise handoff](https://github.com/cbattlegear/MeshCoreOne-Android/pull/165#issuecomment-6296316597)
identify the two previously missing populated mounted witnesses. No clobber
defect was presumed or found in these executed boundaries.

| Frozen original | Exact inputs/outcome | Existing or added native witness; acceptance limit |
| --- | --- | --- |
| `loadDraft restores the disk draft when the composer is empty` | Empty field, saved `"saved"`; `loadDraft` sets `"saved"`. | Reuse `ComposerStateHolderTest::drafts persist and restore per conversation` (constructor restores `"saved @al"`) and `DraftStoreTest`'s `draftToApply returns the saved draft when the field is empty`. Both new mounted methods also see `"saved"` at actual conversation entry as their lifetime control, not a new nominal empty-policy method. |
| `loadDraft leaves an already-populated composer untouched` | Independent disk `"disk"` and field `"in-progress"`; `loadDraft` preserves field. | New `inProgressFieldSurvivesRealFailureReloadAndAdaptiveRecompositionWithIndependentSavedDraft` observes the actual production single-initialization adaptation described below. Source-equivalence review pending; it does not call a fabricated repeat-restore method. |
| `restoreComposerDraft keeps disk draft from clobbering an already-populated composer` | Independent disk `"disk"` and directly populated field `"quick-reply"`; `restoreComposerDraft` preserves field. | New `prepopulatedQuickReplyFieldSurvivesRealFailureReloadAndAdaptiveRecompositionWithIndependentSavedDraft` uses that exact string and the same actual native lifetime. The original sets the field directly; neither it nor this witness delivers a notification. Source-equivalence review pending. |
| `restoreComposerDraft applies the disk draft when the composer is empty` | Empty field, saved `"saved"`; `restoreComposerDraft` sets `"saved"`. | Reuse the same existing empty-policy/constructor witnesses and mounted entry control, without duplicating a fourth nominal test. No pending notification source is installed by the original case. |

Existing owner witnesses are referenced, not re-executed or reassigned here.
The store's separate populated-field `draftToApply` unit case is supporting
policy evidence, not a substitute for the new mounted production consumers.

## Actual consumer and independent inputs

The new class is
`com.meshcoreone.android.app.platform.DraftInitializationComposeTest`,
in the existing owned App unit-test source registration.
It mounts the real `NativeNavigationShell` and `AppChatsContent` over the
actual AppContainer, RoomPersistenceStore/in-memory Room, session and durable
send queue. It does not mount a test-written policy/remember composable.

`AppChatsContent` keys the conversation by selected target, remembers the
actual `ComposerStateHolder` by feature/target/sender, and remembers the
timeline independently. `ComposerStateHolder.initialState()` reads the real
App draft adapter at construction. `ComposerRoute` owns the actual editable
field and synchronizes changed holder state. The Android adaptation restores
on initialization rather than invoking Swift's `loadDraft` repeatedly on an
existing ViewModel. Absence of a method alone is not equivalence evidence.

Each method proves these observable consequences:

1. Independently seed the real process `DraftStore` with `"saved"` and mount
   the selected actual conversation. Its visible composer contains `"saved"`.
2. Send it through the actual composer/service/Room queue. The protocol-speaking
   firmware harness holds opcode2, without returning a success receipt.
   Type `"in-progress"` or `"quick-reply"` through the actual field.
3. Only **after** typing, independently set the same conversation's actual saved
   draft to `"disk"` through `DraftStore.setDraft`. Assert that the App draft
   adapter reads `"disk"` while the mounted field still contains the other
   value. `onDraftChanged` is not used to prepare both inputs, and there is no
   unused wrapper or replacement persistence policy.
4. Require the mounted timeline to display the held message as Sending and
   require its one real durable envelope and PENDING/SENDING row. Release
   independent frozen direct terminal ERR2 (`Bytes.of(1, 2)`). The actual queue
   persists FAILED, removes the envelope, emits its terminal event, and the
   actual timeline refetch visibly changes that same original message to
   Failed. No synthetic timeline event or pre-mapped acceptance is injected.
5. Through that reload and real list refresh/recomposition at
   360/599/600/779/780/834/360dp, require the populated focused field, unchanged
   field semantics identity, selected navigation entry and session generation.
   The actual shell crosses its rail and tiled list-detail thresholds at 200%
   font. The independent saved value remains `"disk"` throughout.
6. Require other-contact/same-radio and same-contact/other-radio saved inputs
   to remain isolated. Navigate to the other actual contact as a new-lifetime
   control: a different field identity restores its own `"other-contact"`.
   Original `"disk"` and `"other-radio"` remain intact; exactly one connection,
   one direct wire command, no channel command and no retained envelope exist.

This proves the observable field identity and state/store divergence across
the tested actual consumer boundaries. The private holder reference is not
exposed; its ownership is traced to the real production `remember` keys, not
asserted through a test-only initialization counter.

## Exact execution

The existing manifest-declared App/scaffold lane and already registered task
are used, not a new module/task. Installed serialized Git-bundle/ext4 readiness:

```text
python tools\android-port\local\check.py --distribution Ubuntu-22.04 --commit 13de7b9336b9c26996efc8631c51d8190a10d992 --stages preflight
```

**PASS**, 0.63s. Actual strict selector:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.app.platform.DraftInitializationComposeTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

**PASS: 2 discovered/run/passed, zero failures/errors/skips, 34s build.**
Positive counts come from the actual two named JUnit XML cases under
`android/app/build/test-results/testDebugUnitTest/`, checked by the existing
trusted suite-count parser, not a function-name count.
The initial source835446312 also passed2/2 in43s; the final13de source adds
the explicit visible Sending-before-Failed and durable-envelope assertions.
No failure is hidden or a previous result transferred.

`python tools\android-port\portmap.py`,
`python tools\android-port\controller\validate.py` and `git diff --check`
**PASS** after the source/evidence additions. Exact Git comparisons also pass:
the existing Rooms/adaptive files are unchanged from code160, and inherited
`tools`, `.github`, manifest and WP-003 evidence bytes match integrated main9c.

No Rooms/adaptive cohort or native-device run is replayed. Rooms4/57 remain
code160/base0183; the earlier adaptive4/41 remain codebe. There is no claimed
fresh combined47-case execution, new screenshot capture or approved golden.
Exact hosted results/review bind to their own candidate, not this local result;
no duplicate CI-result bundle is committed.

## Limits and review prerequisite

The harness injects the actual process `DraftStore` backed by
`MemoryDraftDefaults`, not the physical SharedPreferences adapter. `"disk"`
names the independently saved-store input from the original; this is not a
physical-file/process-restart durability claim.

These are existing-holder failure/event reload and adaptive recomposition
boundaries plus a new-target initialization control. They do not establish
reconnect-driven restoration, arbitrary teardown/re-entry retention, process
recreation, notification pending-draft priority/consume-once behavior, or every
radio/channel family. The frozen production `restoreComposerDraft` first
consumes a notification draft and then applies empty-only store restoration;
that full notification integration remains separately unfinished.

Primary WP-307 must review whether these observed native single-initialization
consequences are an acceptable equivalent for the two populated originals and
the reused empty witnesses. Until then the four-case acceptance remains open,
not blocked on an archived WP-308 producer or presumed product bug. No foreign
composer/timeline production or owner tests are edited.
