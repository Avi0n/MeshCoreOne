package one.meshcore.shared.room.ui

import one.meshcore.shared.remotenode.ui.SettingsApplyFeedback
import one.meshcore.shared.remotenode.ui.SettingsApplyState
import one.meshcore.shared.remotenode.ui.bannerUnlessApplied
import one.meshcore.shared.remotenode.ui.launchFlashedSettingsApply
import one.meshcore.shared.remotenode.ui.launchPasswordChange
import one.meshcore.shared.remotenode.ui.launchRadioApply
import one.meshcore.shared.remotenode.ui.toRadioBanner

fun RoomSettingsViewModel.applyRoomAccess() {
    scope.launchFlashedSettingsApply(
        updates = SettingsApplyState(
            begin = { it.copy(isApplyingRoomAccess = true, applyBanner = null, roomAccessApplySuccess = false) },
            finish = { current, result ->
                current.copy(
                    isApplyingRoomAccess = false,
                    roomAccessModified = controller.roomAccessModified,
                    applyBanner = result.bannerUnlessApplied(),
                )
            },
            cancelled = {
                it.copy(
                    isApplyingRoomAccess = false,
                    roomAccessModified = controller.roomAccessModified,
                )
            },
        ),
        kind = SettingsApplyFeedback.Kind.ROOM_ACCESS,
        state = settingsState,
        apply = controller::applyRoomAccess,
        flash = { current, success -> current.copy(roomAccessApplySuccess = success) },
    )
}

fun RoomSettingsViewModel.applyBehavior() {
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
                it.copy(
                    isApplyingBehavior = false,
                    behaviorModified = controller.behaviorSettingsModified,
                )
            },
        ),
        kind = SettingsApplyFeedback.Kind.BEHAVIOR,
        state = settingsState,
        apply = controller::applyBehaviorSettings,
        flash = { current, success -> current.copy(behaviorApplySuccess = success) },
    )
}

fun RoomSettingsViewModel.applyRadio() {
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
            cancelled = {
                it.copy(
                    isApplyingRadio = false,
                    radioModified = helper.radioSettingsModified,
                )
            },
        ),
        state = settingsState,
        apply = helper::applyRadioSettings,
    )
}

fun RoomSettingsViewModel.applyIdentity() {
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
            cancelled = {
                it.copy(
                    isApplyingIdentity = false,
                    identityModified = helper.identitySettingsModified,
                )
            },
        ),
        kind = SettingsApplyFeedback.Kind.IDENTITY,
        state = settingsState,
        apply = helper::applyIdentitySettings,
        flash = { current, success -> current.copy(identityApplySuccess = success) },
    )
}

fun RoomSettingsViewModel.applyContactInfo() {
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
        state = settingsState,
        apply = helper::applyContactInfoSettings,
        flash = { current, success -> current.copy(contactInfoApplySuccess = success) },
    )
}

fun RoomSettingsViewModel.changePassword() {
    scope.launchPasswordChange(
        updates = SettingsApplyState(
            begin = { it.copy(isChangingPassword = true, applyBanner = null, changePasswordSuccess = false) },
            finish = { current, banner ->
                current.copy(
                    isChangingPassword = false,
                    newPassword = helper.newPassword,
                    confirmPassword = helper.confirmPassword,
                    applyBanner = banner,
                )
            },
            cancelled = { it.copy(isChangingPassword = false) },
        ),
        state = settingsState,
        change = helper::changePassword,
        flash = { current, success -> current.copy(changePasswordSuccess = success) },
    )
}
