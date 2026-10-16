package one.meshcore.shared.room.ui

import kotlinx.coroutines.flow.update

fun RoomSettingsViewModel.setRoomAccessExpanded(expanded: Boolean) {
    settingsState.update { it.copy(roomAccessExpanded = expanded) }
    val current = settingsState.value
    if (expanded && !current.roomAccessLoaded && !current.isLoadingRoomAccess) loadRoomAccess()
}

fun RoomSettingsViewModel.setRadioExpanded(expanded: Boolean) {
    settingsState.update { it.copy(radioExpanded = expanded) }
    val current = settingsState.value
    if (expanded && !current.radioLoaded && !current.isLoadingRadio) loadRadio()
}

fun RoomSettingsViewModel.setBehaviorExpanded(expanded: Boolean) {
    settingsState.update { it.copy(behaviorExpanded = expanded) }
    val current = settingsState.value
    if (expanded && !current.behaviorLoaded && !current.isLoadingBehavior) loadBehavior()
}

fun RoomSettingsViewModel.setIdentityExpanded(expanded: Boolean) {
    settingsState.update { it.copy(identityExpanded = expanded) }
    val current = settingsState.value
    if (expanded && !current.identityLoaded && !current.isLoadingIdentity) loadIdentity()
}

fun RoomSettingsViewModel.setContactInfoExpanded(expanded: Boolean) {
    settingsState.update { it.copy(contactInfoExpanded = expanded) }
    val current = settingsState.value
    if (expanded && !current.contactInfoLoaded && !current.isLoadingContactInfo) loadContactInfo()
}

fun RoomSettingsViewModel.setSecurityExpanded(expanded: Boolean) {
    settingsState.update { it.copy(securityExpanded = expanded) }
}

fun RoomSettingsViewModel.setDeviceInfoExpanded(expanded: Boolean) {
    settingsState.update { it.copy(deviceInfoExpanded = expanded) }
    val current = settingsState.value
    if (expanded && !current.deviceInfoLoaded && !current.isLoadingDeviceInfo) loadDeviceInfo()
}

fun RoomSettingsViewModel.setDeviceActionsExpanded(expanded: Boolean) {
    settingsState.update { it.copy(deviceActionsExpanded = expanded) }
}
