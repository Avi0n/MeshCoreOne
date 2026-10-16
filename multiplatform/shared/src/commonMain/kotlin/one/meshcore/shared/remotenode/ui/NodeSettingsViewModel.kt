package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.NodeSettingsValidation
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.RepeaterRegionEntry
import one.meshcore.shared.remotenode.RepeaterSettingsController

/**
 * Observable state for the repeater settings screen. Each disclosure section tracks its own loading and
 * error flags so a failure in one does not blank the others (same convention as [NodeStatusState]).
 */
data class NodeSettingsState(
    val routePathExpanded: Boolean = false,
    override val deviceInfoExpanded: Boolean = false,
    override val isLoadingDeviceInfo: Boolean = false,
    override val deviceInfoLoaded: Boolean = false,
    override val deviceInfoSectionError: Boolean = false,
    override val firmwareVersion: String? = null,
    override val deviceTimeUtc: String? = null,
    override val deviceTimeEpochSeconds: Long? = null,
    override val clockDriftSeconds: Double? = null,
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
    override val contactInfoExpanded: Boolean = false,
    override val isLoadingContactInfo: Boolean = false,
    override val contactInfoLoaded: Boolean = false,
    override val contactInfoSectionError: Boolean = false,
    override val ownerInfo: String = "",
    override val ownerInfoTooLong: Boolean = false,
    override val contactInfoModified: Boolean = false,
    override val isApplyingContactInfo: Boolean = false,
    override val contactInfoApplySuccess: Boolean = false,
    override val behaviorExpanded: Boolean = false,
    override val isLoadingBehavior: Boolean = false,
    override val behaviorLoaded: Boolean = false,
    override val behaviorSectionError: Boolean = false,
    val repeaterEnabled: Boolean = false,
    override val advertIntervalText: String = "",
    override val floodAdvertIntervalText: String = "",
    override val floodMaxHopsText: String = "",
    override val behaviorModified: Boolean = false,
    override val behaviorValidation: NodeSettingsValidation.BehaviorValidation? = null,
    override val isApplyingBehavior: Boolean = false,
    override val behaviorApplySuccess: Boolean = false,
    val regionsExpanded: Boolean = false,
    val isLoadingRegions: Boolean = false,
    val regionsLoaded: Boolean = false,
    val regionsSectionError: Boolean = false,
    val regions: List<RepeaterRegionEntry> = emptyList(),
    val newRegionName: String = "",
    val newRegionParent: RepeaterRegionEntry.Parent = RepeaterRegionEntry.Parent.Unscoped,
    val defaultScopeName: String? = null,
    val defaultScopeLoaded: Boolean = false,
    val isLoadingDefaultScope: Boolean = false,
    val supportsRegionDefaultScope: Boolean = false,
    val hasUnsavedRegionChanges: Boolean = false,
    val isSavingRegions: Boolean = false,
    val regionsSaveSuccess: Boolean = false,
    val blockedDeleteRegionName: String? = null,
    override val securityExpanded: Boolean = false,
    override val newPassword: String = "",
    override val confirmPassword: String = "",
    override val isChangingPassword: Boolean = false,
    override val changePasswordSuccess: Boolean = false,
    override val deviceActionsExpanded: Boolean = false,
    override val isRebooting: Boolean = false,
    override val isSendingAdvert: Boolean = false,
    override val isSyncingTime: Boolean = false,
    val showRebootConfirm: Boolean = false,
    override val applyBanner: ApplyBanner? = null,
) : RemoteIdentitySectionState,
    RemoteRadioSectionState,
    RemoteContactInfoSectionState,
    RemoteBehaviorSectionState,
    RemoteSecuritySectionState,
    RemoteDeviceInfoSectionState,
    RemoteDeviceActionsSectionState

/** A transient apply outcome the screen shows as a snackbar-style banner. */
sealed interface ApplyBanner {
    data object Success : ApplyBanner

    /** Radio parameters were accepted but only take effect after the node restarts. */
    data object RadioApplied : ApplyBanner

    data object PartialFailure : ApplyBanner
    data object InvalidFields : ApplyBanner
    data object NotLoaded : ApplyBanner
    data class TransportError(val message: String) : ApplyBanner
    data object RegionsSaved : ApplyBanner
    data object RegionsSaveFailed : ApplyBanner
    data object RegionUnknown : ApplyBanner
    data object RegionAddFailed : ApplyBanner
    data object RegionRemoveFailed : ApplyBanner
    data object RegionNotEmpty : ApplyBanner
    data object RegionAddValidationFailed : ApplyBanner
    data object PasswordEmpty : ApplyBanner
    data object PasswordMismatch : ApplyBanner
    data object PasswordChanged : ApplyBanner
    data object PasswordFailed : ApplyBanner
    data object RebootSent : ApplyBanner
    data object AdvertSent : ApplyBanner
    data object TimeSynced : ApplyBanner
    data object ClockAhead : ApplyBanner
    data object SyncTimeFailed : ApplyBanner

    /** The admin session is gone, so the action was never attempted. */
    data object NoService : ApplyBanner

    /** A radio-only apply that the node refused; the general "some settings failed" would misname it. */
    data object RadioApplyFailed : ApplyBanner

    /** The node's own words for a failure, shown verbatim because we cannot improve on them. */
    data class FirmwareError(val message: String) : ApplyBanner

    /** A reply we could not classify, quoted so it can be reported. */
    data class UnexpectedResponse(val response: String) : ApplyBanner
}

/**
 * Drives the repeater settings screen over a [RepeaterSettingsController] built from the live admin
 * service's CLI senders. Sections load on appear because the cards stay open; field edits update both
 * the UI state and the controller so Apply only sends changed values. Security (password) and device
 * actions (reboot) are included.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class NodeSettingsViewModel private constructor(private val parts: NodeSettingsParts) :
    NodeSettingsLifecycle by parts.model,
    NodeSettingsSectionLoads by parts.sections,
    NodeSettingsExpansion by parts.expansion,
    NodeSettingsProfileEdits by parts.profile,
    NodeSettingsTuningEdits by parts.tuning,
    NodeSettingsRegionDraft by parts.regionDraft,
    NodeSettingsSecurity by parts.security,
    NodeSettingsDeviceActions by parts.actions,
    NodeRegionEditing by parts.regionCommands {
    val state: StateFlow<NodeSettingsState>
        get() = parts.model.state

    fun setRoutePathExpanded(expanded: Boolean) {
        parts.model.stateUpdates.update { it.copy(routePathExpanded = expanded) }
    }

    constructor(
        scope: CoroutineScope,
        sessionId: Uuid,
        dependencies: Dependencies,
        nowEpochSeconds: () -> Long = { kotlin.time.Clock.System.now().epochSeconds },
    ) : this(NodeSettingsParts(scope, sessionId, dependencies, nowEpochSeconds))

    class Dependencies(
        val sessionId: () -> Uuid? = { null },
        val ownsCLIHandler: Boolean = true,
        val isCurrent: () -> Boolean = { true },
        val repeaterAdminService: () -> RepeaterAdminService?,
    )
}

private class NodeSettingsParts(
    scope: CoroutineScope,
    sessionId: Uuid,
    dependencies: NodeSettingsViewModel.Dependencies,
    nowEpochSeconds: () -> Long,
) {
    val model = NodeSettingsModel(scope, sessionId, dependencies, nowEpochSeconds)
    val regionCommands = NodeRegionCommands(
        scope = model.scope,
        state = model.stateUpdates,
        dependencies = dependencies,
        settings = { model.controller },
        reportedMissingService = model::reportedMissingService,
        canMutateRegions = model::canMutateRegions,
    )
    val sections = NodeSettingsSections(model, regionCommands)
    val expansion = NodeSettingsSectionExpansion(model, sections, regionCommands)
    val profile = NodeSettingsProfileEditor(model)
    val tuning = NodeSettingsTuningEditor(model)
    val regionDraft = NodeSettingsRegionDraftEditor(model)
    val security = NodeSettingsPasswordEditor(model)
    val actions = NodeSettingsDeviceActionRunner(model)

    init {
        model.regionCommands = regionCommands
        model.start()
    }
}
