package one.meshcore.shared.tools

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.contact.pathHopCount
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.CLIOutputType
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RemoteOperationTimeoutPolicy

private const val LOCAL_SESSION_NUMBER = 1L
private const val FIRST_REMOTE_SESSION_NUMBER = 2L

/**
 * A failed login prints the failure message, or the timeout copy when that message is blank.
 * Cancellation leaves the transcript.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ToolsCliSession.completeLogin(contact: ContactDto, password: String) {
    val bridge = remote
    if (bridge == null) {
        appendOutput(messages.remoteLoginHint, CLIOutputType.RESPONSE)
        return
    }
    isWaitingForResponse = true
    try {
        finishStoredLogin(bridge, contact, password)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        val msg = error.message?.takeIf { it.isNotBlank() } ?: messages.timeout
        appendOutput("${messages.loginFailed} $msg", CLIOutputType.ERROR)
    } finally {
        stopCountdown()
        isWaitingForResponse = false
    }
}

private suspend fun ToolsCliSession.finishStoredLogin(
    bridge: ToolsCliRemoteBridge,
    contact: ContactDto,
    password: String,
) {
    val radioID = bridge.radioId()
    if (radioID == null) {
        appendOutput(messages.notConnected, CLIOutputType.ERROR)
        return
    }
    recordSignedInSession(bridge, radioID, contact, password)
}

private suspend fun ToolsCliSession.recordSignedInSession(
    bridge: ToolsCliRemoteBridge,
    radioID: Uuid,
    contact: ContactDto,
    password: String,
) {
    val sessionId = bridge.createSession(radioID, contact)
    // The raw out-path byte, not a decoded hop count: LoginTimeoutConfig decodes it itself, and
    // the 0xFF flood sentinel has to reach it or a flood login is given a 5 s zero-hop budget.
    val ok = bridge.login(sessionId, password, contact.outPathLength) { seconds ->
        startCountdown(seconds)
    }
    if (!ok) {
        appendOutput("${messages.loginFailed} ${messages.loginFailedAuth}", CLIOutputType.ERROR)
        return
    }
    bridge.storePassword(password, contact.publicKey)
    val remoteSession = ToolsCliSession.RemoteCliSession(
        id = sessionId,
        name = contact.name,
        outPathLength = contact.outPathLength,
    )
    remoteSessions.removeAll { it.id == sessionId }
    remoteSessions += remoteSession
    activeRemote = remoteSession
    appendOutput("${messages.loginSuccess} @${contact.name}", CLIOutputType.SUCCESS)
}

internal suspend fun ToolsCliSession.handleLogout() {
    val session = activeRemote
    if (session == null) {
        appendOutput(messages.notLoggedIn, CLIOutputType.ERROR)
        return
    }
    isWaitingForResponse = true
    try {
        remote?.logout(session.id)
        remoteSessions.removeAll { it.id == session.id }
        activeRemote = null
        appendOutput(messages.logoutSuccess, CLIOutputType.SUCCESS)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        remoteSessions.removeAll { it.id == session.id }
        activeRemote = null
        appendOutput(messages.logoutSuccess, CLIOutputType.SUCCESS)
    } finally {
        isWaitingForResponse = false
    }
}

/**
 * A remote command timeout prints the command-timeout copy, and any other failure prints its
 * message or the timeout copy when that message is missing. A blank message stays blank, and
 * cancellation leaves the transcript.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun ToolsCliSession.passthroughRemote(session: ToolsCliSession.RemoteCliSession, command: String) {
    val bridge = remote ?: return
    isWaitingForResponse = true
    try {
        // Reboot never replies; a fire-and-forget send that times out is the expected success.
        if (command.lowercase().startsWith("reboot")) {
            try {
                bridge.sendRawCommand(session.id, command, RemoteOperationTimeoutPolicy.FIRE_AND_FORGET_CLI)
            } catch (_: RemoteNodeError.Timeout) {
                // Treated as success below.
            }
            appendOutput(messages.rebootSent, CLIOutputType.SUCCESS)
            return
        }
        val response = bridge.sendRawCommand(session.id, command)
        appendOutput(response, CLIOutputType.RESPONSE)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: RemoteNodeError.Timeout) {
        appendOutput(messages.commandTimeout, CLIOutputType.ERROR)
    } catch (error: Throwable) {
        appendOutput(error.message ?: messages.timeout, CLIOutputType.ERROR)
    } finally {
        isWaitingForResponse = false
    }
}

internal suspend fun ToolsCliSession.listNodes() {
    isWaitingForResponse = true
    try {
        val contacts = fetchContacts()
        if (contacts == null) {
            appendOutput(messages.notConnected, CLIOutputType.ERROR)
            return
        }
        val nodes = contacts
            .filter { it.contactType == ContactType.REPEATER || it.contactType == ContactType.ROOM }
            .sortedWith(
                compareBy<ContactDto> { it.contactType != ContactType.REPEATER }
                    .thenBy { it.name.lowercase() },
            )
        if (nodes.isEmpty()) {
            appendOutput(messages.noNodes, CLIOutputType.RESPONSE)
            return
        }
        appendOutput(messages.nodesHeader(nodes.size), CLIOutputType.RESPONSE)
        for (node in nodes) {
            val kind = if (node.contactType == ContactType.REPEATER) {
                messages.kindRepeater
            } else {
                messages.kindRoom
            }
            val route = formatRoute(node)
            val paddedName = node.name.padEnd(ToolsCliSession.NAME_PAD)
            val paddedKind = kind.padEnd(ToolsCliSession.KIND_PAD)
            appendOutput("  $paddedName $paddedKind$route", CLIOutputType.RESPONSE)
        }
    } finally {
        isWaitingForResponse = false
    }
}

internal suspend fun ToolsCliSession.listChannels() {
    isWaitingForResponse = true
    try {
        val channels = fetchChannels()
        if (channels == null) {
            appendOutput(messages.notConnected, CLIOutputType.ERROR)
            return
        }
        if (channels.isEmpty()) {
            appendOutput(messages.noChannels, CLIOutputType.RESPONSE)
            return
        }
        val sorted = channels.sortedBy { it.index }
        appendOutput(messages.channelsHeader(sorted.size), CLIOutputType.RESPONSE)
        for (channel in sorted) {
            val name = channel.name.ifEmpty { "(${messages.channelEmpty})" }
            appendOutput("  [${channel.index}] $name", CLIOutputType.RESPONSE)
        }
    } finally {
        isWaitingForResponse = false
    }
}

internal fun parseCliSessionNumber(token: String): Long? {
    val digits = if (token.startsWith('+') || token.startsWith('-')) token.drop(1) else token
    if (digits.any { it !in '0'..'9' }) return null
    return token.toLongOrNull()
}

internal fun ToolsCliSession.handleSession(args: String) {
    val token = args.trim()
    val number = parseCliSessionNumber(token)
    when {
        token.isEmpty() || token.equals("list", ignoreCase = true) -> {
            appendOutput(messages.sessionListHeader, CLIOutputType.RESPONSE)
            val localMark = if (activeRemote == null) "*" else " "
            appendOutput(
                "  $localMark $LOCAL_SESSION_NUMBER. $deviceName (${messages.sessionLocal})",
                CLIOutputType.RESPONSE,
            )
            remoteSessions.forEachIndexed { index, session ->
                val mark = if (activeRemote?.id == session.id) "*" else " "
                appendOutput("  $mark ${index + FIRST_REMOTE_SESSION_NUMBER}. @${session.name}", CLIOutputType.RESPONSE)
            }
        }
        token.equals("local", ignoreCase = true) || number == LOCAL_SESSION_NUMBER -> {
            activeRemote = null
            appendOutput("${messages.sessionSwitched} $deviceName", CLIOutputType.SUCCESS)
        }
        else -> {
            val match = when {
                number == null -> remoteSessions.firstOrNull { it.name.equals(token, ignoreCase = true) }
                number < FIRST_REMOTE_SESSION_NUMBER -> null
                number - FIRST_REMOTE_SESSION_NUMBER >= remoteSessions.size -> null
                else -> remoteSessions[(number - FIRST_REMOTE_SESSION_NUMBER).toInt()]
            }
            if (match != null) {
                activeRemote = match
                appendOutput("${messages.sessionSwitched} @${match.name}", CLIOutputType.SUCCESS)
            } else {
                appendOutput("${messages.sessionNotFound} $token", CLIOutputType.ERROR)
            }
        }
    }
}

internal fun ToolsCliSession.formatRoute(contact: ContactDto): String = when {
    contact.isFloodRouted -> messages.routeFlood
    contact.pathHopCount == 0 -> messages.routeDirect
    else -> messages.routeHops(contact.pathHopCount)
}
