package one.meshcore.shared.remotenode

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** CLI send used by repeater behavior and region commands. */
internal class RepeaterCliTransport(private val sendCommand: suspend (command: String, timeout: Duration) -> String) {
    suspend fun commandSucceeded(command: String): Boolean {
        val response = checkedSettingsCommand { sendCommand(command, DEFAULT_TIMEOUT) }
        return CLIResponse.parse(response) is CLIResponse.Ok
    }

    companion object {
        val DEFAULT_TIMEOUT: Duration = RemoteOperationTimeoutPolicy.DEFAULT_CLI
        val REGION_FETCH_TIMEOUT: Duration = 10.seconds
    }
}
