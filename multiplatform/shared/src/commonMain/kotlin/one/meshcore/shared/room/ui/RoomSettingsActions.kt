package one.meshcore.shared.room.ui

import kotlinx.coroutines.flow.update
import one.meshcore.shared.remotenode.ui.ApplyBanner
import one.meshcore.shared.remotenode.ui.SettingsApplyFeedback
import one.meshcore.shared.remotenode.ui.SettingsApplyState
import one.meshcore.shared.remotenode.ui.launchDeviceAction
import one.meshcore.shared.remotenode.ui.toBanner
import one.meshcore.shared.remotenode.ui.toSentBanner

fun RoomSettingsViewModel.setShowRebootConfirm(show: Boolean) {
    settingsState.update { it.copy(showRebootConfirm = show) }
}

fun RoomSettingsViewModel.clearApplyBanner() {
    settingsState.update { it.copy(applyBanner = null) }
}

fun RoomSettingsViewModel.sendAdvert() {
    scope.launchDeviceAction(
        updates = SettingsApplyState(
            begin = { it.copy(isSendingAdvert = true, applyBanner = null) },
            finish = { current, banner -> current.copy(isSendingAdvert = false, applyBanner = banner) },
            cancelled = { it.copy(isSendingAdvert = false) },
        ),
        kind = SettingsApplyFeedback.Kind.ADVERT,
        state = settingsState,
        act = { helper.forceAdvert().toSentBanner(ApplyBanner.AdvertSent) },
    )
}

fun RoomSettingsViewModel.syncTime() {
    scope.launchDeviceAction(
        updates = SettingsApplyState(
            begin = { it.copy(isSyncingTime = true, applyBanner = null) },
            finish = { current, banner ->
                current.copy(
                    isSyncingTime = false,
                    applyBanner = banner,
                    deviceTimeUtc = helper.deviceTimeUtcString,
                    clockDriftSeconds = helper.clockDriftSeconds,
                )
            },
            cancelled = { it.copy(isSyncingTime = false) },
        ),
        kind = SettingsApplyFeedback.Kind.TIME_SYNC,
        state = settingsState,
        act = { helper.syncTime().toBanner() },
    )
}

fun RoomSettingsViewModel.confirmReboot() {
    scope.launchDeviceAction(
        updates = SettingsApplyState(
            begin = { it.copy(isRebooting = true, showRebootConfirm = false, applyBanner = null) },
            finish = { current, banner -> current.copy(isRebooting = false, applyBanner = banner) },
            cancelled = { it.copy(isRebooting = false) },
        ),
        kind = SettingsApplyFeedback.Kind.REBOOT,
        state = settingsState,
        act = { helper.reboot().toSentBanner(ApplyBanner.RebootSent) },
    )
}
