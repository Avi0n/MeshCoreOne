package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.meshcore.shared.remotenode.NodeSettingsController
import one.meshcore.shared.remotenode.NodeSettingsValidation
import one.meshcore.shared.remotenode.contactInfoSettingsFields
import one.meshcore.shared.remotenode.identitySettingsFields
import one.meshcore.shared.remotenode.radioSettingsFields
import one.meshcore.shared.remotenode.ui.ApplyBanner
import one.meshcore.shared.remotenode.ui.RemoteBehaviorSectionState
import one.meshcore.shared.remotenode.ui.RemoteContactInfoSectionState
import one.meshcore.shared.remotenode.ui.RemoteDeviceActionsSectionState
import one.meshcore.shared.remotenode.ui.RemoteDeviceInfoSectionState
import one.meshcore.shared.remotenode.ui.RemoteIdentitySectionState
import one.meshcore.shared.remotenode.ui.RemoteRadioSectionState
import one.meshcore.shared.remotenode.ui.RemoteSectionRequest
import one.meshcore.shared.remotenode.ui.RemoteSecuritySectionState
import one.meshcore.shared.remotenode.ui.SettingsApplyFeedback
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.room.RoomSettingsController
import one.meshcore.shared.room.behaviorIntervalFields

/**
 * Observable state for the room management surface. Every section owns its loading, loaded, and error
 * flags so a failure in one leaves the others readable, matching the repeater [ApplyBanner] surface and
 * the iOS section-scoped error convention.
 */
data class RoomSettingsUiState(
    override val applyBanner: ApplyBanner? = null,
    val roomAccessExpanded: Boolean = false,
    val isLoadingRoomAccess: Boolean = false,
    val roomAccessLoaded: Boolean = false,
    val roomAccessSectionError: Boolean = false,
    val guestPassword: String? = null,
    val allowReadOnly: Boolean? = null,
    val roomAccessModified: Boolean = false,
    val isApplyingRoomAccess: Boolean = false,
    val roomAccessApplySuccess: Boolean = false,
    override val radioExpanded: Boolean = false,
    override val isLoadingRadio: Boolean = false,
    override val radioLoaded: Boolean = false,
    override val radioSectionError: Boolean = false,
    override val frequencyText: String = "",
    override val bandwidthText: String = "",
    override val spreadingFactorText: String = "",
    override val codingRateText: String = "",
    override val radioModified: Boolean = false,
    override val isApplyingRadio: Boolean = false,
    override val behaviorExpanded: Boolean = false,
    override val isLoadingBehavior: Boolean = false,
    override val behaviorLoaded: Boolean = false,
    override val behaviorSectionError: Boolean = false,
    override val advertIntervalText: String = "",
    override val floodAdvertIntervalText: String = "",
    override val floodMaxHopsText: String = "",
    override val behaviorModified: Boolean = false,
    override val isApplyingBehavior: Boolean = false,
    override val behaviorApplySuccess: Boolean = false,
    override val behaviorValidation: NodeSettingsValidation.BehaviorValidation? = null,
    override val identityExpanded: Boolean = false,
    override val isLoadingIdentity: Boolean = false,
    override val identityLoaded: Boolean = false,
    override val identitySectionError: Boolean = false,
    override val name: String = "",
    override val latitudeText: String = "",
    override val longitudeText: String = "",
    override val identityModified: Boolean = false,
    override val identityValidation: NodeSettingsValidation.IdentityValidation? = null,
    override val isApplyingIdentity: Boolean = false,
    override val identityApplySuccess: Boolean = false,
    override val contactInfoExpanded: Boolean = false,
    override val isLoadingContactInfo: Boolean = false,
    override val contactInfoLoaded: Boolean = false,
    override val contactInfoSectionError: Boolean = false,
    override val ownerInfo: String = "",
    override val ownerInfoTooLong: Boolean = false,
    override val contactInfoModified: Boolean = false,
    override val isApplyingContactInfo: Boolean = false,
    override val contactInfoApplySuccess: Boolean = false,
    override val securityExpanded: Boolean = false,
    override val newPassword: String = "",
    override val confirmPassword: String = "",
    override val isChangingPassword: Boolean = false,
    override val changePasswordSuccess: Boolean = false,
    override val deviceInfoExpanded: Boolean = false,
    override val isLoadingDeviceInfo: Boolean = false,
    override val deviceInfoLoaded: Boolean = false,
    override val deviceInfoSectionError: Boolean = false,
    override val firmwareVersion: String? = null,
    override val deviceTimeUtc: String? = null,
    override val deviceTimeEpochSeconds: Long? = null,
    override val clockDriftSeconds: Double? = null,
    override val deviceActionsExpanded: Boolean = false,
    override val isSendingAdvert: Boolean = false,
    override val isSyncingTime: Boolean = false,
    override val isRebooting: Boolean = false,
    val showRebootConfirm: Boolean = false,
) : RemoteIdentitySectionState,
    RemoteRadioSectionState,
    RemoteContactInfoSectionState,
    RemoteBehaviorSectionState,
    RemoteSecuritySectionState,
    RemoteDeviceInfoSectionState,
    RemoteDeviceActionsSectionState

/**
 * Drives the room management surface over a [RoomSettingsController]: the room-only Room Access and
 * Behavior sections plus the shared radio, identity, contact-info, security, device-info, and
 * device-action sections its [RoomSettingsController.helper] serves. Apply sends only changed fields.
 *
 * Sections load on their first expansion. A late CLI reply — one that lands after its
 * section request timed out — is adopted through [RoomSettingsController.handleLateResponse], so a
 * blank field fills without spending more airtime.
 */
class RoomSettingsViewModel(
    scope: CoroutineScope,
    internal val controller: RoomSettingsController,
    internal val roomAdminService: () -> RoomAdminService? = { null },
    private val ownsCLIHandler: Boolean = true,
) {
    private val ownerJob = SupervisorJob(scope.coroutineContext[Job])
    private val feedback = SettingsApplyFeedback(retained = !ownsCLIHandler)
    internal val scope = CoroutineScope(scope.coroutineContext + ownerJob + feedback)
    internal val helper: NodeSettingsController get() = controller.helper

    /** Same-package sections write this flow. Callers read [state]. */
    internal val settingsState = MutableStateFlow(RoomSettingsUiState())
    val state: StateFlow<RoomSettingsUiState> = settingsState.asStateFlow()

    internal val roomAccessRequest = RemoteSectionRequest(this.scope)
    internal val roomAccessJob: Job? get() = roomAccessRequest.job
    internal val radioRequest = RemoteSectionRequest(this.scope)
    internal val radioJob: Job? get() = radioRequest.job
    internal val behaviorRequest = RemoteSectionRequest(this.scope)
    internal val behaviorJob: Job? get() = behaviorRequest.job
    internal val identityRequest = RemoteSectionRequest(this.scope)
    internal val identityJob: Job? get() = identityRequest.job
    internal val contactInfoRequest = RemoteSectionRequest(this.scope)
    internal val contactInfoJob: Job? get() = contactInfoRequest.job
    internal val deviceInfoRequest = RemoteSectionRequest(this.scope)
    internal val deviceInfoJob: Job? get() = deviceInfoRequest.job
    private var cliHandlerService: RoomAdminService? = null

    init {
        if (ownsCLIHandler) registerLateResponseRecovery()
    }

    private fun registerLateResponseRecovery() {
        val service = roomAdminService() ?: return
        cliHandlerService = service
        scope.launch {
            service.setCLIHandler { _, response ->
                // Fan-out runs before the waiter records this text. Leaving it to the waiter keeps a
                // timely reply from filling an older unanswered query on this screen.
                if (response == service.cliBodyClaimedByWaiter()) return@setCLIHandler
                handleLateResponse(response)
            }
        }
    }

    fun settingsShown(): Long = feedback.shown()

    fun settingsHidden(token: Long) = feedback.hidden(token)

    fun handleLateResponse(response: String) {
        if (!ownerJob.isActive) return
        if (controller.handleLateResponse(response)) publishRecoveredFields()
    }

    /** Drops the shared CLI handler and every coroutine owned by this settings surface. */
    fun cleanup() {
        if (!ownerJob.isActive) return
        val service = cliHandlerService
        cliHandlerService = null
        if (service != null) {
            // Enter NonCancellable before cancelling the owner so a later reply cannot fill this dismissed room.
            scope.launch(start = CoroutineStart.UNDISPATCHED) {
                withContext(NonCancellable) {
                    service.setCLIHandler { _, _ -> }
                }
            }
        }
        ownerJob.cancel()
    }

    /** Republishes every field a late CLI reply may have filled, clearing that section's error with it. */
    private fun publishRecoveredFields() {
        settingsState.update {
            it.withIdentitySettings(helper.identitySettingsFields())
                .withRadioSettings(helper.radioSettingsFields())
                .withContactInfoSettings(helper.contactInfoSettingsFields())
                .copy(
                    deviceInfoLoaded = helper.deviceInfoLoaded || helper.firmwareVersion != null,
                    deviceInfoSectionError = helper.deviceInfoError,
                    firmwareVersion = helper.firmwareVersion,
                    deviceTimeUtc = helper.deviceTimeUtcString,
                    clockDriftSeconds = helper.clockDriftSeconds,
                ).withBehaviorSettings(controller.behaviorIntervalFields())
        }
    }
}
