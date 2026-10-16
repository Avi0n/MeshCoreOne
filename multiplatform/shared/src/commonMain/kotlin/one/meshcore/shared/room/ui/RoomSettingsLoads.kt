package one.meshcore.shared.room.ui

import kotlinx.coroutines.flow.update
import one.meshcore.shared.remotenode.contactInfoSettingsFields
import one.meshcore.shared.remotenode.identitySettingsFields
import one.meshcore.shared.remotenode.radioSettingsFields
import one.meshcore.shared.remotenode.ui.remoteSectionNeedsLoad
import one.meshcore.shared.remotenode.ui.replaceSectionLoad
import one.meshcore.shared.room.behaviorIntervalFields

/** Resumes unloaded expanded sections when the settings screen is showing. */
fun RoomSettingsViewModel.loadOpenSections() {
    val state = this.state.value
    if (remoteSectionNeedsLoad(state.roomAccessExpanded, state.roomAccessLoaded, state.isLoadingRoomAccess)) {
        loadRoomAccess()
    }
    if (remoteSectionNeedsLoad(state.radioExpanded, state.radioLoaded, state.isLoadingRadio)) {
        loadRadio()
    }
    if (remoteSectionNeedsLoad(state.behaviorExpanded, state.behaviorLoaded, state.isLoadingBehavior)) {
        loadBehavior()
    }
    if (remoteSectionNeedsLoad(state.identityExpanded, state.identityLoaded, state.isLoadingIdentity)) {
        loadIdentity()
    }
    if (remoteSectionNeedsLoad(state.contactInfoExpanded, state.contactInfoLoaded, state.isLoadingContactInfo)) {
        loadContactInfo()
    }
    if (remoteSectionNeedsLoad(state.deviceInfoExpanded, state.deviceInfoLoaded, state.isLoadingDeviceInfo)) {
        loadDeviceInfo()
    }
}

fun RoomSettingsViewModel.loadRoomAccess() {
    settingsState.replaceSectionLoad(
        request = roomAccessRequest,
        begin = { it.copy(isLoadingRoomAccess = true, roomAccessSectionError = false) },
        cancelled = {
            it.copy(
                isLoadingRoomAccess = false,
                guestPassword = controller.guestPassword,
                allowReadOnly = controller.allowReadOnly,
                roomAccessModified = controller.roomAccessModified,
            )
        },
    ) {
        controller.fetchRoomAccess()
        settingsState.update {
            it.copy(
                isLoadingRoomAccess = false,
                roomAccessLoaded = controller.roomAccessLoaded,
                roomAccessSectionError = controller.roomAccessError,
                guestPassword = controller.guestPassword,
                allowReadOnly = controller.allowReadOnly,
                roomAccessModified = controller.roomAccessModified,
            )
        }
    }
}

fun RoomSettingsViewModel.loadBehavior() {
    settingsState.replaceSectionLoad(
        request = behaviorRequest,
        begin = { it.copy(isLoadingBehavior = true, behaviorSectionError = false) },
        cancelled = { it.withBehaviorSettings(controller.behaviorIntervalFields(), loading = false) },
    ) {
        controller.fetchBehaviorSettings()
        settingsState.update {
            it.withBehaviorSettings(controller.behaviorIntervalFields(), loading = false)
        }
    }
}

fun RoomSettingsViewModel.loadRadio() {
    settingsState.replaceSectionLoad(
        request = radioRequest,
        begin = { it.copy(isLoadingRadio = true, radioSectionError = false) },
        cancelled = {
            it.withRadioSettings(helper.radioSettingsFields(), loading = false, modified = helper.radioSettingsModified)
        },
    ) {
        helper.fetchRadioSettings()
        settingsState.update {
            it.withRadioSettings(
                helper.radioSettingsFields(),
                loading = false,
                modified = helper.radioSettingsModified,
            )
        }
    }
}

fun RoomSettingsViewModel.loadIdentity() {
    settingsState.replaceSectionLoad(
        request = identityRequest,
        begin = { it.copy(isLoadingIdentity = true, identitySectionError = false) },
        cancelled = { it.withIdentitySettings(helper.identitySettingsFields(), loading = false) },
    ) {
        helper.fetchIdentity()
        settingsState.update { it.withIdentitySettings(helper.identitySettingsFields(), loading = false) }
    }
}

fun RoomSettingsViewModel.loadContactInfo() {
    settingsState.replaceSectionLoad(
        request = contactInfoRequest,
        begin = { it.copy(isLoadingContactInfo = true, contactInfoSectionError = false) },
        cancelled = { it.withContactInfoSettings(helper.contactInfoSettingsFields(), loading = false) },
    ) {
        helper.fetchContactInfo()
        settingsState.update { it.withContactInfoSettings(helper.contactInfoSettingsFields(), loading = false) }
    }
}

fun RoomSettingsViewModel.loadDeviceInfo() {
    settingsState.replaceSectionLoad(
        request = deviceInfoRequest,
        begin = { it.copy(isLoadingDeviceInfo = true, deviceInfoSectionError = false) },
        cancelled = { it.copy(isLoadingDeviceInfo = false, firmwareVersion = helper.firmwareVersion) },
    ) {
        helper.fetchDeviceInfo()
        settingsState.update {
            it.copy(
                isLoadingDeviceInfo = false,
                deviceInfoLoaded = helper.deviceInfoLoaded || helper.firmwareVersion != null,
                deviceInfoSectionError = helper.deviceInfoError,
                firmwareVersion = helper.firmwareVersion,
                deviceTimeUtc = helper.deviceTimeUtcString,
                deviceTimeEpochSeconds = helper.deviceTimeEpochSeconds,
                clockDriftSeconds = helper.clockDriftSeconds,
            )
        }
    }
}
