# Four original composer draft-restore cases

## Scope and binding

Read-only comparison on 2026-10-20 of candidate
`965452059d5361162838477199c5a2a51f2f0adc`, containing base
`0183a2b206b9ba655d7fea5c281b8bfbfecf8847`, in
`cbattlegear/MeshCoreOne-Android`, WP-501 / PR #165 / existing issue #134.
The specification is
`MC1Tests/Views/Chats/ChatViewModelDraftRestoreTests.swift` at frozen
`db14559b39d32322b06477c6ae676112f583db50`, blob
`8d1cebf12188b4c065aec2683f31c0b0983f4e07`. Its manifest primary owner is
WP-307, with WP-501/WP-502 cross-references and no exclusion.

Read all four original bodies, their actual `ChatViewModel+Messages.swift`
implementations and `ChatConversationView.swift` call sites. These selected
checkout files have no diff from the frozen commit. This note does not refresh
archived groups, accounting, proposals or review bindings, and does not audit
the concurrently owned Rooms changes.

## Original contract and native consumer

Swift `MC1/Views/Chats/ViewModel/ChatViewModel+Messages.swift::loadDraft`
assigns `store.draftToApply(over: composingText, for: id)` only
when non-null. `restoreComposerDraft` calls `loadDraftIfExists` first and then
`loadDraft`. The former consumes a pending notification draft and assigns it
unconditionally when available. Conversation loading calls `restoreComposerDraft`
after loading messages for both direct and channel conversations.

The four tests below do **not** configure a notification service or enqueue a
notification draft. The third directly sets `composingText = "quick-reply"`;
it tests preservation of a pre-populated field, not notification delivery,
consume-once behavior or cross-radio recovery.

The visible Android field is owned by
`feature/chats/.../composer/ComposerStateHolder.kt`, not the timeline's separate
`ChatTimelineState.draft`:

- `ComposerStateHolder.initialState()` reads
  `drafts.get(target.conversationId).orEmpty()` at construction.
  `onDraftChanged` changes the field and persists it.
- `app/.../container/AppChatsFeature.kt::drafts` adapts `get/set` to the real
  process `DraftStore.draft/setDraft/clearDraft`.
- `app/.../navigation/AppChatsContent.kt::AppChatConversation` remembers the
  composer by `feature, target, sender`. Connection changes activate the
  notification conversation but are not a composer restore call.
  `AppChatConversationScreen` opens the timeline; `ComposerRoute` displays the
  composer's state and forwards typing to `onDraftChanged`.
- `core/services/.../rendering/DraftStore.kt::draftToApply` implements the
  correct empty-only decision, but the mounted app/composer does not call it.
  Neither holder exposes a `loadDraft` / `restoreComposerDraft` equivalent
  that can be invoked on an already-populated composer.

## Per-case disposition

Paths below are relative to `android/`. Test class names and complete method or
dynamic-case names are the binding, not a source header or a file count.

Exact test paths:

- `feature/chats/src/test/kotlin/com/meshcoreone/android/feature/chats/composer/ComposerStateHolderTest.kt`
- `feature/chats/src/test/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModelTest.kt`
- `core/services/src/test/kotlin/com/meshcoreone/android/core/services/rendering/DraftStoreTest.kt`
- `core/services/src/test/kotlin/com/meshcoreone/android/core/services/notifications/NotificationPolicyNativeTests.kt`

Exact corresponding consumer paths:

- `feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/composer/ComposerStateHolder.kt`
- `feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/timeline/ChatViewModel.kt`
- `feature/chats/src/main/kotlin/com/meshcoreone/android/feature/chats/composer/ui/ComposerRoute.kt`
- `core/services/src/main/kotlin/com/meshcoreone/android/core/services/rendering/DraftStore.kt`
- `core/services/src/main/kotlin/com/meshcoreone/android/core/services/notifications/NotificationService.kt`
- `core/services/src/main/kotlin/com/meshcoreone/android/core/services/notifications/NotificationActionHandler.kt`
- `app/src/main/kotlin/com/meshcoreone/android/app/container/AppChatsFeature.kt`
- `app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatsContent.kt`
- `app/src/main/kotlin/com/meshcoreone/android/app/navigation/AppChatConversationScreen.kt`

| Frozen original case | Exact source setup and predicate | Existing native assertions and actual branch | Disposition |
| --- | --- | --- | --- |
| `loadDraft restores the disk draft when the composer is empty` | Fresh model; same-ID disk `"saved"`; call `loadDraft`; field equals `"saved"`. | `core/services/.../rendering/DraftStoreTest.kt::draftStoreTests`, dynamic `DraftStoreTests::draftToApply returns the saved draft when the field is empty()` sets `"saved"` and asserts `draftToApply("", id) == "saved"`. `feature/chats/.../composer/ComposerStateHolderTest.kt::drafts persist and restore per conversation` constructs the real composer with `"saved @al"` and asserts that exact visible-holder draft. `timeline/ChatViewModelTest.kt::draft survives holder recreation and empty draft removes storage` writes `"do not lose me"`, recreates the timeline holder and checks equality. | **Existing empty-field policy and actual composer initialization are exercised.** Constructor restoration is the current native adaptation; the timeline assertion is supporting evidence, not proof of the visible field. No new duplicate initialization test is needed. The two separate Swift entry points are not separately implemented Android methods. |
| `loadDraft leaves an already-populated composer untouched` | Disk `"disk"`; field `"in-progress"`; call `loadDraft`; field remains `"in-progress"`. | `DraftStoreTest::draftStoreTests`, dynamic `DraftStoreTests::draftToApply returns nil when the field already has text()` sets saved `"saved"`, passes `"typing"` and asserts null. The mounted composer restores once and its subsequent `onDraftChanged` writes the new field; there is no exposed repeat-restore call on which to assert the original disk/field ordering. | **Store-policy coverage only; mounted repeat-restore equivalence is not demonstrated.** Neither replacing disk with the new field via `onDraftChanged` nor calling `draftToApply` in a test-written restoration wrapper would reproduce this original consumer action. Owner action below is required; no observed clobber is asserted. |
| `restoreComposerDraft keeps disk draft from clobbering an already-populated composer` | Disk `"disk"`; field `"quick-reply"`; call `restoreComposerDraft`; field remains `"quick-reply"`. | The same empty-only store decision applies, but no current composer test invokes a real restore boundary with these two independent inputs. `NotificationPolicyNativeTests` separately tests pending-draft consumption, not field precedence. No populated-field constructor parameter or repeat-restore operation exists on the visible holder. | **Missing consumer-level assertion / adaptation decision.** The original does not require a notification fixture. A notification-only test, an adaptive text-retention test or setting the field after disk restoration is not equivalent. Do not mark this original passed from those witnesses. |
| `restoreComposerDraft applies the disk draft when the composer is empty` | Fresh model; same-ID disk `"saved"`; call `restoreComposerDraft`; field equals `"saved"`. | Same actual `ComposerStateHolder.initialState` and existing `drafts persist and restore per conversation` assertion as the first case, with the store's exact `"saved"` empty-field witness. With no configured notification source, Swift delegates to the first policy. | **Existing no-notification empty-field behavior is exercised through the native constructor adaptation.** This is a many-to-one mapping, not an additional passing test or proof of notification precedence. The full restore-source ordering remains outside this witness. |

The two empty-field cases have a concrete implementation/assertion mapping, not
independent review approval of the constructor adaptation. The two populated-field
cases remain unaccepted at the mounted consumer boundary. No four-case total,
whole-file parity or whole-WP acceptance is claimed.

## Existing execution evidence, not a new run

The authoritative predecessor
[run 39441236560 / Linux job 118692199165](https://github.com/cbattlegear/MeshCoreOne-Android/actions/runs/39441236560/job/118692199165)
passed on exact `965452059d5361162838477199c5a2a51f2f0adc` / base
`0183a2b206b9ba655d7fea5c281b8bfbfecf8847`. Read its current method inventory
directly in memory and found the two complete holder method identities above,
the `DraftStoreTest` dynamic cases and the notification policy witnesses.
The affected chats/services module suites passed with positive discovery and
zero failures/errors/skips. A passing inventory does not repair the missing
consumer assertions. No Android/device test was rerun for this read-only audit,
and no second CI-result bundle was retained. Any documentation successor's
checks remain bound to that successor, not this predecessor execution.

For this documentation-only change, the declared read-only commands
`python tools\android-port\controller\validate.py`,
`python tools\android-port\controller\verification_config.py --check`,
`python tools\android-port\portmap.py`,
`python android\parity-tests\audit.py --check`,
`python android\parity-tests\semantic.py --check` and `git diff --check`
all **passed**. These are validators, not newly discovered JVM tests or measured
assertion-call counts. No test, generated binding, production, workflow or
frozen-source bytes changed.

## Bounded primary-owner handoff and stop

**WP-307 consumer/test contract requires resolution, with any App support
reconciled through its current producer.** Preserve independently saved disk
`"disk"` and pre-populated composer `"in-progress"` / `"quick-reply"` across
the actual conversation-entry/reload restoration boundary. Either expose/wire
the source-equivalent empty-only restoration operation on the real consumer
and test those exact inputs, or supply an independently reviewed native
single-initialization adaptation with mounted assertions proving that
re-entry/reload cannot replace that existing field. Reuse the working empty-only
store policy; do not implement its algorithm only inside a WP-501 test wrapper.

Related source observation for that owner, **not the third test's setup**:
`NotificationActionHandler.replyNotSent` calls
`NotificationService.saveDraft(EntityKey, text)` and posts a failure
notification; `consumeDraft` removes the per-radio/contact pending draft.
At the inspected candidate, all Kotlin calls to `consumeDraft` outside its
declaration are notification-service tests, not app/feature consumers.
`AppChatsFeature.activate` sets/clears the active conversation only.
Consequently the full source restore-source-ordering contract is not wired
through this entry path. The service's existing
`NotificationPolicyNativeTests` case
`WP-215::drafts are consumed once and keyed by radio plus contact` proves
consume-once/isolation in isolation, not recovery into the composer.

WP-501 does not edit WP-307/WP-308/App production or foreign tests to resolve
that decision. Adding four nominal tests around the store would conceal the
unimplemented consumer boundary and duplicate existing assertions. This slice
stops with the one actionable consumer-contract blocker above, rather than
claiming a product clobber without a reachable reproduction. The seven earlier
predicate repairs, active Rooms work, other evidence/human gates and issue
states are unchanged.
