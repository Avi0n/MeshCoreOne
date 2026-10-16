package one.meshcore.shared.remotenode.ui

/** Reboot, advert, and clock sync. */
interface NodeSettingsDeviceActions {
    fun confirmReboot()

    fun sendAdvert()

    fun syncTime()
}

internal class NodeSettingsDeviceActionRunner(private val model: NodeSettingsModel) : NodeSettingsDeviceActions {
    private val scope get() = model.scope
    private val helper get() = model.helper

    override fun confirmReboot() {
        if (model.reportedMissingService()) return
        scope.launchDeviceAction(
            updates = SettingsApplyState(
                begin = { it.copy(isRebooting = true, showRebootConfirm = false, applyBanner = null) },
                finish = { current, banner -> current.copy(isRebooting = false, applyBanner = banner) },
                cancelled = { it.copy(isRebooting = false) },
            ),
            kind = SettingsApplyFeedback.Kind.REBOOT,
            state = model.stateUpdates,
            act = { helper.reboot().toSentBanner(ApplyBanner.RebootSent) },
        )
    }

    override fun sendAdvert() {
        if (model.reportedMissingService()) return
        scope.launchDeviceAction(
            updates = SettingsApplyState(
                begin = { it.copy(isSendingAdvert = true, applyBanner = null) },
                finish = { current, banner -> current.copy(isSendingAdvert = false, applyBanner = banner) },
                cancelled = { it.copy(isSendingAdvert = false) },
            ),
            kind = SettingsApplyFeedback.Kind.ADVERT,
            state = model.stateUpdates,
            act = { helper.forceAdvert().toSentBanner(ApplyBanner.AdvertSent) },
        )
    }

    override fun syncTime() {
        if (model.reportedMissingService()) return
        scope.launchDeviceAction(
            updates = SettingsApplyState(
                begin = { it.copy(isSyncingTime = true, applyBanner = null) },
                finish = { current, banner ->
                    current.copy(
                        isSyncingTime = false,
                        applyBanner = banner,
                        deviceTimeUtc = helper.deviceTimeUtcString,
                        deviceTimeEpochSeconds = helper.deviceTimeEpochSeconds,
                        clockDriftSeconds = helper.clockDriftSeconds,
                    )
                },
                cancelled = { it.copy(isSyncingTime = false) },
            ),
            kind = SettingsApplyFeedback.Kind.TIME_SYNC,
            state = model.stateUpdates,
            act = { helper.syncTime().toBanner() },
        )
    }
}
