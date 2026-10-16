package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.update
import one.meshcore.shared.remotenode.behaviorIntervalFields
import one.meshcore.shared.remotenode.contactInfoSettingsFields
import one.meshcore.shared.remotenode.identitySettingsFields
import one.meshcore.shared.remotenode.radioSettingsFields

/** Loads and applies the disclosure sections on the repeater settings screen. */
interface NodeSettingsSectionLoads {
    /** Resumes unloaded expanded sections when the settings screen is showing. */
    fun loadOpenSections()

    fun loadDeviceInfo()

    fun loadIdentity()

    fun loadRadio()

    fun loadContactInfo()

    fun loadBehavior()

    fun applyIdentity()

    fun applyRadio()

    fun applyContactInfo()

    fun applyBehavior()
}

internal class NodeSettingsSections(
    private val model: NodeSettingsModel,
    private val regionCommands: NodeRegionCommands,
) : NodeSettingsSectionLoads {
    private val scope get() = model.scope
    private val dependencies get() = model.dependencies
    private val helper get() = model.helper
    private val controller get() = model.controller

    override fun loadOpenSections() {
        val state = model.state.value
        if (remoteSectionNeedsLoad(state.deviceInfoExpanded, state.deviceInfoLoaded, state.isLoadingDeviceInfo)) {
            loadDeviceInfo()
        }
        if (remoteSectionNeedsLoad(state.identityExpanded, state.identityLoaded, state.isLoadingIdentity)) {
            loadIdentity()
        }
        if (remoteSectionNeedsLoad(state.radioExpanded, state.radioLoaded, state.isLoadingRadio)) {
            loadRadio()
        }
        if (remoteSectionNeedsLoad(state.contactInfoExpanded, state.contactInfoLoaded, state.isLoadingContactInfo)) {
            loadContactInfo()
        }
        if (remoteSectionNeedsLoad(state.behaviorExpanded, state.behaviorLoaded, state.isLoadingBehavior)) {
            loadBehavior()
        }
        if (remoteSectionNeedsLoad(state.regionsExpanded, state.regionsLoaded, state.isLoadingRegions)) {
            regionCommands.loadRegions()
        }
    }

    override fun loadDeviceInfo() {
        if (dependencies.repeaterAdminService() == null) return
        model.stateUpdates.replaceSectionLoad(
            request = model.deviceInfoRequest,
            begin = { it.copy(isLoadingDeviceInfo = true, deviceInfoSectionError = false) },
            cancelled = { it.copy(isLoadingDeviceInfo = false, firmwareVersion = helper.firmwareVersion) },
        ) {
            helper.fetchDeviceInfo()
            model.stateUpdates.update {
                it.copy(
                    isLoadingDeviceInfo = false,
                    deviceInfoLoaded = helper.deviceInfoLoaded || helper.firmwareVersion != null,
                    deviceInfoSectionError = helper.deviceInfoError,
                    firmwareVersion = helper.firmwareVersion,
                    supportsRegionDefaultScope = controller.supportsRegionDefaultScope,
                    deviceTimeUtc = helper.deviceTimeUtcString,
                    deviceTimeEpochSeconds = helper.deviceTimeEpochSeconds,
                    clockDriftSeconds = helper.clockDriftSeconds,
                )
            }
        }
    }

    override fun loadIdentity() {
        if (dependencies.repeaterAdminService() == null) return
        model.stateUpdates.replaceSectionLoad(
            request = model.identityRequest,
            begin = { it.copy(isLoadingIdentity = true, identitySectionError = false) },
            cancelled = { it.withIdentitySettings(helper.identitySettingsFields(), loading = false) },
        ) {
            helper.fetchIdentity()
            model.stateUpdates.update { it.withIdentitySettings(helper.identitySettingsFields(), loading = false) }
        }
    }

    override fun loadRadio() {
        if (dependencies.repeaterAdminService() == null) return
        model.stateUpdates.replaceSectionLoad(
            request = model.radioRequest,
            begin = { it.copy(isLoadingRadio = true, radioSectionError = false) },
            cancelled = {
                it.withRadioSettings(
                    helper.radioSettingsFields(),
                    loading = false,
                    modified = helper.radioSettingsModified,
                )
            },
        ) {
            helper.fetchRadioSettings()
            model.stateUpdates.update {
                it.withRadioSettings(
                    helper.radioSettingsFields(),
                    loading = false,
                    modified = helper.radioSettingsModified,
                )
            }
        }
    }

    override fun loadContactInfo() {
        if (dependencies.repeaterAdminService() == null) return
        model.stateUpdates.replaceSectionLoad(
            request = model.contactInfoRequest,
            begin = { it.copy(isLoadingContactInfo = true, contactInfoSectionError = false) },
            cancelled = { it.withContactInfoSettings(helper.contactInfoSettingsFields(), loading = false) },
        ) {
            helper.fetchContactInfo()
            model.stateUpdates.update {
                it.withContactInfoSettings(helper.contactInfoSettingsFields(), loading = false)
            }
        }
    }

    override fun applyIdentity() {
        if (model.reportedMissingService()) return
        scope.launchFlashedSettingsApply(
            updates = SettingsApplyState(
                begin = { it.copy(isApplyingIdentity = true, applyBanner = null, identityApplySuccess = false) },
                finish = { current, result ->
                    current.copy(
                        isApplyingIdentity = false,
                        identityValidation = helper.identityValidation,
                        identityModified = helper.identitySettingsModified,
                        applyBanner = result.bannerUnlessApplied(),
                    )
                },
                cancelled = { it.copy(isApplyingIdentity = false, identityModified = helper.identitySettingsModified) },
            ),
            kind = SettingsApplyFeedback.Kind.IDENTITY,
            state = model.stateUpdates,
            apply = helper::applyIdentitySettings,
            flash = { current, success -> current.copy(identityApplySuccess = success) },
        )
    }

    override fun applyRadio() {
        if (model.reportedMissingService()) return
        scope.launchRadioApply(
            updates = SettingsApplyState(
                begin = { it.copy(isApplyingRadio = true, applyBanner = null) },
                finish = { current, result ->
                    current.copy(
                        isApplyingRadio = false,
                        radioModified = helper.radioSettingsModified,
                        applyBanner = result.toRadioBanner(),
                    )
                },
                cancelled = { it.copy(isApplyingRadio = false, radioModified = helper.radioSettingsModified) },
            ),
            state = model.stateUpdates,
            apply = helper::applyRadioSettings,
        )
    }

    override fun applyContactInfo() {
        if (model.reportedMissingService()) return
        scope.launchFlashedSettingsApply(
            updates = SettingsApplyState(
                begin = { it.copy(isApplyingContactInfo = true, applyBanner = null, contactInfoApplySuccess = false) },
                finish = { current, result ->
                    current.copy(
                        isApplyingContactInfo = false,
                        contactInfoModified = helper.contactInfoSettingsModified,
                        applyBanner = result.bannerUnlessApplied(),
                    )
                },
                cancelled = {
                    it.copy(isApplyingContactInfo = false, contactInfoModified = helper.contactInfoSettingsModified)
                },
            ),
            kind = SettingsApplyFeedback.Kind.CONTACT_INFO,
            state = model.stateUpdates,
            apply = helper::applyContactInfoSettings,
            flash = { current, success -> current.copy(contactInfoApplySuccess = success) },
        )
    }

    override fun loadBehavior() {
        if (dependencies.repeaterAdminService() == null) return
        model.stateUpdates.replaceSectionLoad(
            request = model.behaviorRequest,
            begin = { it.copy(isLoadingBehavior = true, behaviorSectionError = false) },
            cancelled = {
                it.withBehaviorSettings(
                    controller.behaviorIntervalFields(),
                    loading = false,
                    repeaterEnabled =
                    controller.repeaterEnabled == true,
                )
            },
        ) {
            controller.fetchBehaviorSettings()
            model.stateUpdates.update {
                it.withBehaviorSettings(
                    controller.behaviorIntervalFields(),
                    loading = false,
                    repeaterEnabled = controller.repeaterEnabled == true,
                )
            }
        }
    }

    override fun applyBehavior() {
        if (model.reportedMissingService()) return
        scope.launchFlashedSettingsApply(
            updates = SettingsApplyState(
                begin = { it.copy(isApplyingBehavior = true, applyBanner = null, behaviorApplySuccess = false) },
                finish = { current, result ->
                    current.copy(
                        isApplyingBehavior = false,
                        behaviorModified = controller.behaviorSettingsModified,
                        behaviorValidation = controller.behaviorValidation,
                        applyBanner = result.bannerUnlessApplied(),
                    )
                },
                cancelled = {
                    it.copy(isApplyingBehavior = false, behaviorModified = controller.behaviorSettingsModified)
                },
            ),
            kind = SettingsApplyFeedback.Kind.BEHAVIOR,
            state = model.stateUpdates,
            apply = controller::applyBehaviorSettings,
            flash = { current, success -> current.copy(behaviorApplySuccess = success) },
        )
    }
}
