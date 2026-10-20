# WP-502 software verification inventory

This is a bounded command-to-behavior mapping for the existing WP-003 producer's
verification-config amendment, not approval, a CI-result bundle, original-case
parity accounting or whole-WP acceptance. Source remains
`db14559b39d32322b06477c6ae676112f583db50`; integrated main is
`9c430cb0f1666faacc443794899b68c01128a87a`. The same producer/reservation owns
the adaptive, Rooms, mounted draft and onboarding WiFi regressions.
Historical native observations remain untouched.

## Existing task and source registration

`android/app/build.gradle.kts` registers `android/ui-tests/src/shared/kotlin`,
`android/ui-tests/src/test/kotlin` and `android/screenshot-tests/src/shared/kotlin`
in the App unit-test source set. Their device counterparts are in App androidTest.
There are no standalone `:ui-tests` or `:screenshot-tests` Gradle modules.

Merged WP-003 PR246 deploys the exact-inverse software-verification amendment
with existing controller `preflight`, `run --stage scaffold` and `inspect`
commands. Full `configured:false` intentionally preserves unfinished acceptance;
it is not an infrastructure-absence or software-execution blocker.
Same-owner reservation19 integrates the authoritative amendment without
changing Android bytes. Local code160 results below remain code160/base0183
executions, not new current-base runs or transferred hosted/review verdicts.

The existing task, executed through the installed serialized Git-bundle/ext4
snapshot lane, is:

```text
:app:testDebugUnitTest
--tests com.meshcoreone.android.uitests.ConversationComposeTest
--tests com.meshcoreone.android.app.platform.ConnectedConversationComposeTest
--tests com.meshcoreone.android.app.container.QueuedComposerIntegrationTest
--tests com.meshcoreone.android.app.container.ShortcutQueueIntegrationTest
--tests com.meshcoreone.android.app.container.DurableRetryIntegrationTest
--console=plain --dependency-verification strict --build-cache
--no-parallel --max-workers=4 -PscaffoldTestHeap=512m
```

Expected discovery is41:9+4+6+16+6. Selecting only the fully qualified
ConnectedConversationComposeTest class requires4. Positive execution uses
`android/app/build/test-results/testDebugUnitTest/TEST-*.xml`; absent, malformed,
zero, skipped, failed or errored cases cannot pass. Method names below identify
runner cases; descriptions record their actual assertion/parameter families.
Function-name counts alone are not execution evidence.

The bounded Rooms successor uses the same existing task with just
`--tests com.meshcoreone.android.uitests.RoomsComposeTest` and the identical
strict options above. It requires **4** non-skipped executions, separately from
the incumbent41. Its four methods actually pass at
`160ba7d3a4018278081c001319b8a6235859c689`; the two cohorts have not been
combined/re-executed as a claimed45-case result in this resumption.

### com.meshcoreone.android.uitests.RoomsComposeTest

| Method | Actual family |
| --- | --- |
| `loadingEmptyGuestMemberAdminAndDisconnectedUseTheProductionRoute` | Held initial load never flashes empty; real holder read/failed-seen/notification/badge/change callbacks in order; guest/member/admin/disconnected controls; 200% CJK/RTL focused draft across360/744/834dp; no post/join/retry from recomposition or permission changes. |
| `failedPostRetainsDraftAndAccessibleRetryKeepsTheOriginalMessageIdentity` | Held post disables duplicate action, rejected typed permission error retains draft/no row and reaches localized error/diagnostics; accepted fixture post clears draft once; actual message-change/reload and accessible Retry preserve original session/message/text with pending status. Not actual service/database delivery. |
| `reconnectLookupAndRejectedJoinKeepTheSheetAndExactRememberChoiceUntilSuccess` | Held contact lookup versus missing-contact recovery/cancel; default remember=true toggled false; held/rejected join preserves disconnected state/sheet/password/remember and exact typed failure; successful fixture join alone dismisses sheet and exposes composer. |
| `roomFramesCoverEffectiveSchemesWindowsFontsLocalesAndPermissionSurfaces` | Production content in57 existing theme/window/font rows: ten themes/19 effective schemes at all three widths and both fonts/directions per frame,12 sampled resource locales, all nine width/disconnected/guest/member pairs. Original CJK/RTL body, FAILED label, actual layout/glyph fit, two equal settled nonblank native draws/capture. |

Rooms production route/content/state-holder ownership remains WP-310; room
accessibility source/test ownership remains WP-307. The controlled dependencies
reuse those real contracts and existing fixture pattern, not AppContainer,
RoomServerService, Room database, server sync or radio. Rendering adds57 Rooms
inputs to the existing127 conversation/local-content definitions; they are
not184 approved goldens or a newly executed combined capture cohort. Resource
locales are sampled, not the full locale x state x theme product or date-format
parity. Grouping/order, notification lifecycle, authentication expiry, full
message-action and store/wire families still need their primary-owner evidence.

### com.meshcoreone.android.app.platform.DraftInitializationComposeTest

The same existing strict App task with
`--tests com.meshcoreone.android.app.platform.DraftInitializationComposeTest`
requires **2** non-skipped executions. At code
`13de7b9336b9c26996efc8631c51d8190a10d992`, exact snapshot preflight passes;
actual XML proves2 discovered/run/passed, zero failure/error/skip,34s build.
This is a separately executed selector, not a newly combined47-case result.

| Method | Actual family |
| --- | --- |
| `inProgressFieldSurvivesRealFailureReloadAndAdaptiveRecompositionWithIndependentSavedDraft` | Real mounted production shell/remembered composer, actual Room/App/service queue; independently saved `"disk"` versus populated `"in-progress"`. Visible pending row and real durable envelope precede actual terminal ERR2/refetch to FAILED. Same focused field semantics identity, entry/generation and divergent saved input across360/599/600/779/780/834/360dp at200% font; contact/radio isolation and new-contact constructor control; exactly one wire command/connection. |
| `prepopulatedQuickReplyFieldSurvivesRealFailureReloadAndAdaptiveRecompositionWithIndependentSavedDraft` | Same actual native single-initialization boundaries with the original's directly populated `"quick-reply"` string. No notification source/priority/consume-once claim. |

[Four-original mapping and limits](draft-initialization.md) reuses existing
empty-policy/constructor cases. The store is the actual process DraftStore
with injected memory defaults, not physical-file durability. Primary WP-307
[assessment6297011991](https://github.com/cbattlegear/MeshCoreOne-Android/pull/165#issuecomment-6297011991)
supports the four specific no-notification predicates as a bounded native
adaptation, removing that source-case wait, not publishing a privileged gate.
Exact publishedccd's hosted39465426509 actually passes all six jobs; Linux
job118776483387 reports App766/766 with both new method identities, zero
failure/error/skip. This is not a separate combined47-case execution or a
later successor result. Notification/reconnect/process/physical preferences
and general empty-field external-store reload remain distinct limits.
No foreign composer/timeline code or WP-501 accounting is changed.

### com.meshcoreone.android.uitests.OnboardingWiFiComposeTest

Same declared strict App task with
`--tests com.meshcoreone.android.uitests.OnboardingWiFiComposeTest`
requires **3** non-skipped executions. Code
`2b10dd966e43a46a4e4dec28c857ca8d05d139eb` passes exact preflight0.45s and
actually discovers/runs/passes3, zero failure/error/skip,26s build.

| Method | Actual family |
| --- | --- |
| `frozenHostFamiliesAndPortGuardsReachTheRealFlowBeforeNormalizedKeyboardSubmit` | Actual Welcome/Permissions/Pair/WiFi host/coordinator; all nine frozen `classifies host` argument pairs drive real button availability, seven port boundaries, invalid70000 IME error/no port call, comma replacement/accessibility clear, address Next/port Done, normalized exact host/65535 input, held then explicitly accepted result reaches actual Region but not full completion. English/light360dp/200% font. |
| `heldFailureRetainsLocalizedFieldsAndRetryAloneAdvancesToRegion` | Actual modal Connecting disables duplicate Connect/toolbar Cancel; second real IME creates no extra attempt. Explicit CJK/RTL failure is a polite live region, retains host/port and no Region; Retry clears error and alone reaches Region on explicit success. Chinese/forced-dark Ember834dp/200%, RTL. |
| `benignCancelledAttemptDoesNotAdvanceAndReopenedSheetResetsOnlyItsOwnInputs` | Benign `Cancelled` result retains host/port without error or advancement; actual toolbar Cancel/reopen creates fresh empty host/default5000, no second port call; unrelated effects/completion absent. German/default dark360dp/100%. Not coroutine cancellation or system/swipe dismissal evidence. |

All three deny optional permission snapshots, assert no request and no
Bluetooth/demo/preset/location/settings effect, and assert actual modal
heading density/direction/no ellipsis/height overflow. Real production
`OnboardingFlow` owns all coordinators and sheet state; controlled WiFi/other
dependency ports are not actual sockets, AppContainer transport, OS permission
or radio evidence. Region is only the reached production destination; country
catalog/application and full onboarding/demo/pairing remain untested here.
No screenshot/approved golden, full Cartesian matrix or new combined50-case
execution is claimed. The broader primary-owner source-family audit is not
replaced by these three mounted integration methods.

### com.meshcoreone.android.uitests.ConversationComposeTest

| Method | Actual family |
| --- | --- |
| `draftFocusAndRetrySurviveResize` | Production timeline/composer content; retained focused draft and one Retry across360/744/834dp content constraints. Not a real service graph. |
| `disconnectedAndByteOverflowDisableTheRealComposer` | Offline send rejection;162-byte CJK rejected and160-byte boundary accepted; original text retained. |
| `timelineLoadingEmptyAndFailureHaveAccessibleRecovery` | Distinct loading/empty/failed states; textual error and one actionable retry. |
| `hardwareReturnUsesSendGuardsAndNormalMultilineFallback` |24 guard/key/modifier families: disconnected/overflow/blank/cooldown/sending/enabled, Enter/NumPadEnter, Shift/plain;200% font, exact send/newline outcomes. |
| `localImageRecoveryViewerAndPreviewSafetyUseNoNetwork` | Self-authored raster; decode failure/retry/viewer; manual/cached preview; malware has no open action. |
| `offlineLocalMapRecoveryDoesNotGateCoordinateActions` | Local schematic only; pending/null/retry/success snapshot; coordinate callback independent of offline rendering. |
| `allNineteenEffectiveThemeFramesRenderDeterministically` | Ten unlocked themes/19 effective frames,57 width/font rows, independent forced-dark oracle; identical settled draws/nonblank/visible-glyph checks. |
| `systemPreferenceAndForcedSchemeRequestsRenderDeterministically` |20 theme/SYSTEM-dark requests including forced-dark Ember; actual effective scheme asserted. |
| `localeWindowFontDirectionAndDeliveryStatePairsAreComplete` |48 cases cover all176 factor pairs over12 locales,4 states,3 widths,2 fonts,2 directions; expected pair sets are checked. |

Rendering totals127 inputs (57+20+48+2 local-content captures). Repeatable draws
are not approved goldens. Native host SDK31 is not actual API37 execution.

### com.meshcoreone.android.app.platform.ConnectedConversationComposeTest

| Method | Actual family |
| --- | --- |
| `rejectedNativeDirectSendAndMountedRetryExposeEachPersistedTerminalFailure` | Actual Room/AppContainer/session, independent direct ERR2; initial send and mounted Retry preserve one row/text, durable envelope and one terminal event per attempt. |
| `rejectedNativeChannelSendAndMountedRetryExposeEachPersistedTerminalFailure` | Same actual graph for channel with terminal ERR6 (not retryable ERR2); exact resend flag/captured timestamp/node name. |
| `directDraftAndTerminalFailureSurviveAdaptivePanesAndTabReturn` | Real direct flow above plus360/599/600/779/780/834/360 available-width transitions,200% actual text layout, focused CJK/RTL draft, unchanged message/draft/entry/generation, FAILED/Retry semantics and tab return; no extra wire/enqueue/event/connection. |
| `channelDraftAndTerminalFailureSurviveAdaptivePanesAndTabReturn` | Same adaptive flow for actual channel selection, persisted draft and envelope semantics. |

The adaptive cases complement, rather than duplicate as parity, the shell
owner's synthetic text-field resize test and WP-307's pixel-offset restoration
test. They do not simulate a physical hinge or assert spoken TalkBack output.

### com.meshcoreone.android.app.container.QueuedComposerIntegrationTest

| Method | Actual family |
| --- | --- |
| `directPendingRetryAndSendAgainHaveOneDurableOwnerAndTerminalEvent` | Actual direct initial/retry/Send Again;3 envelope modes, one service queue/event each, exact resend policy. |
| `channelPendingRetryAndSendAgainPreserveRealEnvelopeAndTerminalEvent` | Actual channel counterpart; resend and pre-enqueue message metadata preserved. |
| `missingQueueProviderSurfacesTypedFailureAndFailsSavedDirectAndChannelRows` | Both recipient kinds; missing provider is typed, pending row really becomes FAILED, no fake admission. |
| `realRoomEnqueueFailureSurfacesTypedCauseAndFailsSavedDirectAndChannelRows` | Actual rejected transaction for both recipients, typed persistence cause and saved-row semantics. |
| `failedSeenBookkeepingCannotSuppressDurableFailureEventOrRefresh` | Terminal event/refresh survives a later typed seen-store failure, which is explicitly logged. |
| `cancelledComposerAcceptanceRemainsDurableForOneReplacementGeneration` | Accepted cancellation retains original provenance and durable intent for replacement generation. |

### com.meshcoreone.android.app.container.ShortcutQueueIntegrationTest

| Method | Actual family |
| --- | --- |
| `realAdvertServiceSessionErrorsReachAdapterAndExecutorForZeroHopAndFlood` | Independent opcode7 ERR1/6 through actual AdvertisementService; same session payload/outer cause, localized executor failure then OK0 recovery, both reaches/confirmation/no message rows. |
| `realAdvertBoundaryPreservesTypedNotConnectedFallbackAndCancellationForBothReaches` | Both reaches; typed not-connected/SendFailed/InvalidResponse/generic/cancellation at actual transport-write seam, fresh generation per uncertain correlation, exact provenance. |
| `rawAdvertSessionPayloadAndExistingShortcutFailureRetainIdentity` | Explicit raw-conversion seam (service normally wraps); same payload/cause and existing ShortcutException identity, no wire effects. |
| `queuedResultRequiresOneRealDurableDirectAndChannelEnqueueWithoutWireReceipt` | Both real PendingSend envelopes persist before Queued; no delivery/firmware-ACK proxy for acceptance. |
| `realEnqueueTransactionFailureNeverReturnsQueuedAndMarksActualRowsFailed` | Rejected real pending-send INSERT; typed cause, original message FAILED, no admission/send. |
| `realPendingCreationFailureNeverReturnsQueuedOrPersistsOptimisticRows` | Rejected real message INSERT; no optimistic message or queue row. |
| `rejectedFailureStatusWriteCannotReplaceTheTypedEnqueueError` | Enqueue and cleanup UPDATE both reject; original typed enqueue cause/logging retained; row honestly remains PENDING, not invented FAILED. |
| `realAdapterRetainsTypedReadinessRecipientAndLengthErrorsWithoutCreatingRows` | Not connected, invalid recipient and too-long text categories; no rows. |
| `appSessionFailurePayloadAndOuterCauseSurviveLocalizedShortcutPresentation` |24 synthetic conversion/presentation combinations:6 MeshCore families x2 actual wrapper types x2 locales; exact payload/outer cause, no fake durable receipt. |
| `confirmationConnectionDropDoesNotReportQueuedOrCreateAStaleRadioMessage` | Actual disconnect during confirmation; NeedsForeground and no stale row/admission. |
| `confirmationRadioReplacementNeverQueuesOrCreatesTheOldRecipient` | Actual changed radio/key/generation during confirmation; no old/new radio send. |
| `declinedOrCancelledConfirmationNeverPersistsOrPublishesDirectOrChannelSends` | Both recipients x decline/cancel; no effects while held or afterward. |
| `cancellationAfterPendingPublicationPropagatesButAcceptedQueueSurvivesGenerationReplacement` | Direct accepted cancellation; exact original/recovery-cause provenance and legitimate retry across real generations. |
| `cancelledAcceptedChannelSendSurvivesGenerationReplacementWithoutDuplicateFailure` | Channel counterpart; one retained message and terminal queue result after reconnect. |
| `syncingShortcutQueueWaitsForRealSyncCompletionWhileAdvertRemainsReadyOnly` | Durable acceptance while SYNCING, actual sync/drain, READY-only adverts. |
| `acceptedDirectAndChannelQueueRowsSurviveActualFileStoreReopenAndDrainOnReconnect` | Real file-store close/reopen, fresh AppContainer reconnect/drain, one wire command/message, no duplicate rows. |

### com.meshcoreone.android.app.container.DurableRetryIntegrationTest

These six incumbent App-owner cases are reused, not relabeled as WP-502 ports.

| Method | Actual family |
| --- | --- |
| `offlineMountedRetryReplacesRowsBeforeQueueLookupAndRetainsPendingTimeline` | Atomic local retry replacement; typed offline error, other radio untouched, no transport. |
| `deliberateSendAgainPersistsResendTrueAndNeverDowngradesLateDelivery` | Deliberate resend differs from Retry; late DELIVERED cannot be downgraded. |
| `cancelledCallerAfterRealMountedCommitRetainsPendingAndTheSameCancellation` | Actual post-commit cancellation, pending survives, original exception retained. |
| `realTransactionFailureRestoresOldRowAndFailedTimelineWithoutQueueAdmission` | Actual rejected transaction rolls back old row/status, no queue admission. |
| `offlineIntentHydratesThroughActualNewGenerationWithoutDoublePersistence` | Actual new session hydrates/delivers one intent/command and cleans queue. |
| `joinedOldGenerationLeavesOneDurableIntentForReplacementHydration` | Old session joined/torn down, reader stopped; replacement drains retained intent without double persistence. |

## Existing device selectors: declared, not executed in this resumption

Existing `:app:assembleDebug :app:assembleDebugAndroidTest` compiles target/test
APKs. Actual device execution uses
`adb -s <explicit-owned-serial> shell am instrument -w -e class <selectors>`
with `com.meshcoreone.android.debug.test/androidx.test.runner.AndroidJUnitRunner`.
Readiness must verify actual OS/API and matching binaries. No new device command
was run for this software delta.

API31/37 selectors total12 existing methods:

| Fully qualified class | Methods |
| --- | --- |
| `com.meshcoreone.android.uitests.ConversationDeviceTest` | `offlineAndByteOverflowAreCapabilityIndependent`; `actualDeviceShowsDistinctTimelineLoadingEmptyAndFailure`; `actualDeviceHardwareReturnUsesSendGuardsAndMultilineFallback`; `actualDeviceLocalImageRecoveryAndPreviewSafety`; `actualDeviceOfflineLocalMapRecoveryAndCoordinateActions`; `retainedDraftAndRetryWorkOnTheCurrentDeviceWindow`; `actualDeviceRendersEveryEffectiveThemeFrame` |
| `com.meshcoreone.android.app.platform.NativeCapabilityTest` | `actualDeniedOrGrantedPermissionsStayCapabilityScopedOnTheSelectedApi`; `realOfflineConversationAndFullEmojiInsertionDoNotRequestOptionalPermissions`; `actualCameraDenialLeavesManualInputAndOneDecodeUsable` |
| `com.meshcoreone.android.app.platform.NativePlatformTest` | `productionApplicationBuildsOneProcessGraphOnSupportedApi` |
| `com.meshcoreone.android.app.navigation.NavigationKeyboardBackTest` | `realImeBackDismissesKeyboardBeforePoppingDetailAndInsetsAreAppliedOnce` |

API33/34/36 selector
`com.meshcoreone.android.uitests.PlatformApiBoundaryDeviceTest` has2 methods:
`actualNotificationDenialIsAdvisoryAndLanRemainsIndependentBeforeApi37` and
`actualConnectedDeviceRegistrationAndPublicBondRemovalBoundaryMatchTheApi`.
They query scoped permission/service/public-method facts; never remove a bond
or certify a real radio/background session.

Last actual native observations remain source458, with original APK identity,
logs and120 API31/37 PNGs in the preserved worker session storage. They are not
this candidate's device acceptance. Hosted scaffold does not execute androidTest.

## Source families and remaining acceptance

The frozen manifest has0 WP-502 primary inventory rows and126 test cross-reference
files owned by other WPs. These are not126 accepted cases; files include helpers,
and original assertions/parameter families still require their primary owners'
accounting. This mapping does not replace WP-501's audit or mark those families
ported from headers.

The new adaptive cases are native integration adaptations informed by
`MC1/Views/Chats/ChatConversationView.swift` draft save/teardown,
`MC1/State/NavigationCoordinator.swift` selected route/sidebar/per-tab ownership,
and the draft-restoration/status-label families in
`MC1Tests/Views/Chats/ChatViewModelDraftRestoreTests.swift` and
`MC1Tests/Views/Chats/Components/UnifiedMessageBubbleA11yLabelTests.swift`.
They check retained draft restoration, not all4 original draft-policy branches,
and FAILED text semantics, not every incoming sender/path/disclosure variant.
The additional mounted single-initialization witnesses above explicitly map
the four originals and their reused cases, but do not self-accept equivalence.
Primary WP-307 ownership is unchanged; notification integration remains open.

| Remaining row | Disposition |
| --- | --- |
| Full deterministic production-feature matrix | Open software work: existing127 conversation/local-content inputs have a bounded57-input Rooms slice/four methods, plus three actual onboarding WiFi methods with independent validation/held-failure/retry/cancel outcomes. Full Rooms sync/history/actions/store/radio, remaining onboarding/pairing/demo/region/preset, reactions/path, nodes/discovery, full map, remote admin, tools and settings remain unfinished. Reuse actual primary-owner fixtures/contracts, not decorative placeholders. |
|126 cross-reference test-file families | Unfinished behavioral inventory/equivalence reconciliation with primary owners/WP-501; no blanket pass/exclusion. |
| Formal verification entry | WP-003 PR246 is merged/deployed at main9c430cb0; its exact-inverse amendment declares actual controller/App/scaffold software commands. Full `configured:false` intentionally retains unfinished acceptance. No new WP-502 catalog edit, approval or dependency unlock is claimed. |
| Current API31/37 and targeted33/34/36 | Matching candidate/device observation still required for full acceptance; no device rerun authorized in this resumption. |
| Physical resize/folding, spoken TalkBack, radio/background | Human/device evidence unverified; does not block ordinary deterministic software implementation. |
| Approved visual goldens | No approval/baseline manufactured or updated. Identical settled draws do not prove visual parity. |
