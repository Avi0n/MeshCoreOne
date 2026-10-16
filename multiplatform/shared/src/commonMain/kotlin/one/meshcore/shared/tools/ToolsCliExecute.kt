package one.meshcore.shared.tools

import one.meshcore.shared.remotenode.CLIOutputType

/** Clears the waiting flag and appends the cancellation line (mirrors Node CLI / iOS). */
fun ToolsCliSession.cancelCurrentCommand() {
    // A pending confirmation has no running command and leaves the waiting flag false, so it must be
    // cleared here or the confirm prompt would wedge.
    if (pendingConfirmation != null) {
        pendingConfirmation = null
        appendOutput(messages.cancelled, CLIOutputType.ERROR)
        return
    }
    if (!isWaitingForResponse) return
    isWaitingForResponse = false
    appendOutput(messages.cancelled, CLIOutputType.ERROR)
}

suspend fun ToolsCliSession.executeCommand(command: String) {
    if (isWaitingForResponse) return
    val trimmed = command.trim()
    if (dispatchPendingOrBlank(trimmed)) return
    val promptPrefix = promptText.trim()
    addToHistory(trimmed)
    appendOutput("$promptPrefix $trimmed", CLIOutputType.COMMAND)
    currentInput = ""
    clearTabState()

    val parts = trimmed.split(" ", limit = 2)
    val cmd = parts[0].lowercase()
    val args = if (parts.size > 1) parts[1].trim() else ""
    handleCommand(cmd, args, raw = trimmed)
}

private suspend fun ToolsCliSession.dispatchPendingOrBlank(trimmed: String): Boolean {
    val promptPrefix = promptText.trim()
    val login = pendingLoginContact
    val confirmation = pendingConfirmation
    // Both pending prompts are answered before the empty-input check, because an empty line has to
    // cancel them. Returning early instead leaves the password prompt armed while the terminal shows
    // the ordinary prompt, so the next command the user types is sent over the mesh as the password.
    return when {
        login != null -> {
            pendingLoginContact = null
            appendOutput("$promptPrefix ****", CLIOutputType.COMMAND)
            currentInput = ""
            clearTabState()
            if (trimmed.isEmpty()) {
                appendOutput(messages.cancelled, CLIOutputType.ERROR)
            } else {
                completeLogin(login, trimmed)
            }
            true
        }
        // Pending yes/no answer for a destructive local command (mirrors iOS pendingConfirmation).
        confirmation != null -> {
            pendingConfirmation = null
            appendOutput("$promptPrefix $trimmed", CLIOutputType.COMMAND)
            currentInput = ""
            clearTabState()
            val answer = trimmed.lowercase()
            if (answer == "yes" || answer == "y") {
                executeLocal(confirmation)
            } else {
                appendOutput(messages.cancelled, CLIOutputType.ERROR)
            }
            true
        }
        trimmed.isEmpty() -> {
            appendOutput(promptPrefix, CLIOutputType.COMMAND)
            true
        }
        else -> false
    }
}

internal suspend fun ToolsCliSession.handleCommand(cmd: String, args: String, raw: String) {
    if (dispatchSessionShortcut(cmd)) return
    // Local radio commands are interpreted app-side; the same words keep passing through to repeaters on
    // remote sessions.
    if (isLocalSession && dispatchParsedLocal(raw)) return
    dispatchNamedCommand(cmd, args, raw)
}

// "s1"/"s2" are shorthand for "session 1"/"session 2".
private suspend fun ToolsCliSession.dispatchSessionShortcut(cmd: String): Boolean {
    val number = parseSessionShortcut(cmd) ?: return false
    handleSession(number.toString())
    return true
}

private suspend fun ToolsCliSession.dispatchParsedLocal(raw: String): Boolean =
    when (val result = CliLocalCommandParser.parse(raw)) {
        is CliLocalParseResult.Command -> handleLocalCommand(result.command)
        is CliLocalParseResult.Invalid -> {
            renderParseError(result.error)
            true
        }
        CliLocalParseResult.NotLocal -> false
    }

private suspend fun ToolsCliSession.dispatchNamedCommand(cmd: String, args: String, raw: String) {
    when {
        cmd == "help" -> showHelp()
        cmd == "clear" && (isLocalSession || args.isEmpty()) -> outputLines = emptyList()
        cmd == "session" -> handleSession(args)
        cmd == "login" || cmd == "remote" -> handleLogin(args)
        cmd == "logout" -> handleLogout()
        cmd == "nodes" && isLocalSession -> listNodes()
        cmd == "channels" && isLocalSession -> listChannels()
        else -> passthroughOrUnknown(cmd, raw)
    }
}

private suspend fun ToolsCliSession.passthroughOrUnknown(cmd: String, raw: String) {
    val remote = activeRemote
    if (remote != null && this.remote != null) {
        passthroughRemote(remote, raw)
    } else {
        appendOutput("${messages.unknownCommand} $cmd", CLIOutputType.ERROR)
    }
}

/** Returns the session index for an `s<N>` shortcut, or null when [cmd] is not one. */
internal fun ToolsCliSession.parseSessionShortcut(cmd: String): Long? {
    if (!cmd.startsWith("s") || cmd.length <= 1) return null
    return parseCliSessionNumber(cmd.drop(1))
}

/** Renders the common help block plus the session-appropriate (local or repeater) block. */
internal fun ToolsCliSession.showHelp() {
    messages.helpCommonLines.forEach { appendOutput(it, CLIOutputType.RESPONSE) }
    val extra = if (isLocalSession) messages.helpLocalLines else messages.helpRepeaterLines
    extra.forEach { appendOutput(it, CLIOutputType.RESPONSE) }
}
