package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.update

/** Admin password fields and the change command. */
interface NodeSettingsSecurity {
    fun setNewPassword(value: String)

    fun setConfirmPassword(value: String)

    fun changePassword()
}

internal class NodeSettingsPasswordEditor(private val model: NodeSettingsModel) : NodeSettingsSecurity {
    private val scope get() = model.scope
    private val helper get() = model.helper

    override fun setNewPassword(value: String) {
        helper.newPassword = value
        model.stateUpdates.update { it.copy(newPassword = value) }
    }

    override fun setConfirmPassword(value: String) {
        helper.confirmPassword = value
        model.stateUpdates.update { it.copy(confirmPassword = value) }
    }

    override fun changePassword() {
        if (model.reportedMissingService()) return
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
            state = model.stateUpdates,
            change = helper::changePassword,
            flash = { current, success -> current.copy(changePasswordSuccess = success) },
        )
    }
}
