package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Runs a node CLI command and walks the local history. */
interface NodeCliCommands {
    /**
     * Runs one entered [command]: echoes the prompt, records history, and dispatches. `help` and `clear`
     * are handled locally; everything else is sent raw to the node. A no-op while a response is pending.
     */
    suspend fun executeCommand(command: String)

    /** Appends the cancellation line if a command is in flight, mirroring `cancelCurrentCommand`. */
    fun cancelCurrentCommand()

    fun historyUp()

    fun historyDown()
}

internal class NodeCliCommandRunner(
    private val state: NodeCliState,
    internal var sessionName: String,
    private val messages: () -> NodeCliMessages,
    private val output: NodeCliOutput,
    private val sendRawCommand: suspend (command: String, timeout: Duration) -> String,
) : NodeCliCommands {
    private var commandGeneration = 0L

    val promptText: String
        get() = if (state.isWaitingForResponse) "" else "@$sessionName${messages().promptSuffix} "

    override suspend fun executeCommand(command: String) {
        val trimmed = command.trim()
        if (state.isWaitingForResponse) return
        val promptPrefix = promptText.trim()

        if (trimmed.isEmpty()) {
            output.appendOutput(promptPrefix, CLIOutputType.COMMAND)
            return
        }

        addToHistory(trimmed)
        output.appendOutput("$promptPrefix $trimmed", CLIOutputType.COMMAND)
        state.currentInput = ""

        val parts = trimmed.split(" ", limit = 2)
        val cmd = parts[0].lowercase()
        val args = if (parts.size > 1) parts[1] else ""
        handleCommand(cmd, args, trimmed)
    }

    override fun cancelCurrentCommand() {
        commandGeneration++
        if (state.isWaitingForResponse) {
            state.isWaitingForResponse = false
            output.appendOutput(messages().cancelled, CLIOutputType.ERROR)
        }
    }

    private suspend fun handleCommand(cmd: String, args: String, raw: String) {
        when {
            cmd == "help" -> showHelp()
            cmd == "clear" && args.isEmpty() -> state.outputLines = emptyList()
            else -> sendCommand(raw)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendCommand(command: String) {
        val normalized = command.lowercase()
        val isReboot = normalized == "reboot" || normalized == "reboot now"
        val generation = ++commandGeneration
        val context = currentCoroutineContext()
        fun checkOwner() {
            context.ensureActive()
            if (generation != commandGeneration) throw CancellationException("CLI command was replaced")
        }
        state.isWaitingForResponse = true
        try {
            if (isReboot) {
                // Reboot does not reply; both success and timeout mean "sent".
                try {
                    sendRawCommand(command, REBOOT_TIMEOUT)
                    checkOwner()
                    output.appendOutput(messages().rebootSent, CLIOutputType.SUCCESS)
                } catch (_: RemoteNodeError.Timeout) {
                    checkOwner()
                    output.appendOutput(messages().rebootSent, CLIOutputType.SUCCESS)
                }
            } else {
                val response = sendRawCommand(command, DEFAULT_COMMAND_TIMEOUT)
                checkOwner()
                output.appendOutput(response, CLIOutputType.RESPONSE)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            checkOwner()
            output.appendOutput(error.message ?: error.toString(), CLIOutputType.ERROR)
        } finally {
            if (generation == commandGeneration) state.isWaitingForResponse = false
        }
    }

    private fun showHelp() {
        messages().help.forEach { output.appendOutput(it, CLIOutputType.RESPONSE) }
    }

    private fun addToHistory(command: String) {
        state.commandHistory = (state.commandHistory + command).takeLast(MAX_HISTORY_ENTRIES)
        state.historyIndex = null
    }

    override fun historyUp() {
        if (state.commandHistory.isEmpty()) return
        val index = state.historyIndex
        state.historyIndex = when {
            index == null -> state.commandHistory.size - 1
            index > 0 -> index - 1
            else -> index
        }
        state.historyIndex?.let { state.currentInput = state.commandHistory[it] }
    }

    override fun historyDown() {
        val index = state.historyIndex ?: return
        if (index < state.commandHistory.size - 1) {
            state.historyIndex = index + 1
            state.currentInput = state.commandHistory[index + 1]
        } else {
            state.historyIndex = null
            state.currentInput = ""
        }
    }

    private companion object {
        const val MAX_HISTORY_ENTRIES = 100
        val REBOOT_TIMEOUT: Duration = 2.seconds
        val DEFAULT_COMMAND_TIMEOUT: Duration = 10.seconds
    }
}
