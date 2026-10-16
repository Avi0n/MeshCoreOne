package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.update

/** Disclosure toggles. Expanding an unloaded section fetches it. */
interface NodeSettingsExpansion {
    fun setDeviceInfoExpanded(expanded: Boolean)

    fun setIdentityExpanded(expanded: Boolean)

    fun setRadioExpanded(expanded: Boolean)

    fun setContactInfoExpanded(expanded: Boolean)

    fun setBehaviorExpanded(expanded: Boolean)

    fun setRegionsExpanded(expanded: Boolean)

    fun setSecurityExpanded(expanded: Boolean)

    fun setDeviceActionsExpanded(expanded: Boolean)

    fun setShowRebootConfirm(show: Boolean)

    fun clearApplyBanner()
}

internal class NodeSettingsSectionExpansion(
    private val model: NodeSettingsModel,
    private val sections: NodeSettingsSections,
    private val regionCommands: NodeRegionCommands,
) : NodeSettingsExpansion {

    override fun setDeviceInfoExpanded(expanded: Boolean) {
        model.stateUpdates.update { it.copy(deviceInfoExpanded = expanded) }
        val current = model.stateUpdates.value
        if (expanded && !current.deviceInfoLoaded && !current.isLoadingDeviceInfo) sections.loadDeviceInfo()
    }

    override fun setIdentityExpanded(expanded: Boolean) {
        model.stateUpdates.update { it.copy(identityExpanded = expanded) }
        val current = model.stateUpdates.value
        if (expanded && !current.identityLoaded && !current.isLoadingIdentity) sections.loadIdentity()
    }

    override fun setRadioExpanded(expanded: Boolean) {
        model.stateUpdates.update { it.copy(radioExpanded = expanded) }
        val current = model.stateUpdates.value
        if (expanded && !current.radioLoaded && !current.isLoadingRadio) sections.loadRadio()
    }

    override fun setContactInfoExpanded(expanded: Boolean) {
        model.stateUpdates.update { it.copy(contactInfoExpanded = expanded) }
        val current = model.stateUpdates.value
        if (expanded && !current.contactInfoLoaded && !current.isLoadingContactInfo) sections.loadContactInfo()
    }

    override fun setBehaviorExpanded(expanded: Boolean) {
        model.stateUpdates.update { it.copy(behaviorExpanded = expanded) }
        val current = model.stateUpdates.value
        if (expanded && !current.behaviorLoaded && !current.isLoadingBehavior) sections.loadBehavior()
    }

    override fun setRegionsExpanded(expanded: Boolean) {
        model.stateUpdates.update { it.copy(regionsExpanded = expanded) }
        val current = model.stateUpdates.value
        if (expanded && !current.regionsLoaded && !current.isLoadingRegions) regionCommands.loadRegions()
    }

    override fun setSecurityExpanded(expanded: Boolean) {
        model.stateUpdates.update { it.copy(securityExpanded = expanded) }
    }

    override fun setDeviceActionsExpanded(expanded: Boolean) {
        model.stateUpdates.update { it.copy(deviceActionsExpanded = expanded) }
    }

    override fun setShowRebootConfirm(show: Boolean) {
        model.stateUpdates.update { it.copy(showRebootConfirm = show) }
    }

    override fun clearApplyBanner() {
        model.stateUpdates.update { it.copy(applyBanner = null) }
    }
}
