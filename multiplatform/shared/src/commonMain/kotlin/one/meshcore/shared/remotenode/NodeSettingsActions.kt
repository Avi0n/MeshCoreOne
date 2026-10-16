package one.meshcore.shared.remotenode

/** Password change, reboot, and advert. */
interface NodeMaintenance {
    var newPassword: String
    var confirmPassword: String

    suspend fun changePassword(): PasswordApplyResult

    /**
     * Reboots the node. Firmware reboots without replying, so the short fire-and-forget wait expiring is
     * the expected outcome and reports as sent; only a real transport failure is an error.
     */
    suspend fun reboot(): ActionResult

    suspend fun forceAdvert(): ActionResult
}

internal class NodeSettingsActions(private val exchange: NodeSettingsExchange) : NodeMaintenance {
    override var newPassword: String = ""
    override var confirmPassword: String = ""

    override suspend fun changePassword(): PasswordApplyResult {
        if (newPassword.isEmpty()) return PasswordApplyResult.Empty
        if (newPassword != confirmPassword) return PasswordApplyResult.Mismatch
        val sent = newPassword
        return try {
            val response = exchange.sendAndWait("password $sent", rawMatching = true)
            if (NodeSettingsResponseParser.isPasswordChangeSuccessful(response)) {
                if (newPassword == sent && confirmPassword == sent) {
                    newPassword = ""
                    confirmPassword = ""
                }
                PasswordApplyResult.Applied
            } else {
                PasswordApplyResult.Failed
            }
        } catch (e: RemoteNodeError) {
            PasswordApplyResult.TransportError(e)
        }
    }

    override suspend fun reboot(): ActionResult = try {
        exchange.sendAndWait(REBOOT_COMMAND, RemoteOperationTimeoutPolicy.FIRE_AND_FORGET_CLI)
        ActionResult.Sent
    } catch (_: RemoteNodeError.Timeout) {
        ActionResult.Sent
    } catch (e: RemoteNodeError) {
        ActionResult.TransportError(e)
    }

    override suspend fun forceAdvert(): ActionResult = sendAction(ADVERT_COMMAND)

    private suspend fun sendAction(command: String): ActionResult = try {
        exchange.sendAndWait(command)
        ActionResult.Sent
    } catch (e: RemoteNodeError) {
        ActionResult.TransportError(e)
    }

    private companion object {
        const val REBOOT_COMMAND = "reboot"
        const val ADVERT_COMMAND = "advert"
    }
}
