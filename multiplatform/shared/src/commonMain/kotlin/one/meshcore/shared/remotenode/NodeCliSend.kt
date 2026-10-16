package one.meshcore.shared.remotenode

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.Uuid
import kotlin.time.Duration

/**
 * Sends a CLI command and waits for the matching response (admin only). Replies to structured `get`
 * queries must echo the wire prefix or parse to their expected shape; anything else is dropped instead
 * of misattributed. [timeout] is the hard ceiling, clamped by the firmware round-trip via the CLI policy.
 */
suspend fun NodeCliCoordinator.sendCLICommand(
    sessionID: Uuid,
    command: String,
    timeout: Duration = DEFAULT_CLI_TIMEOUT,
): String = performCLICommand(sessionID, command, timeout, acceptsAnyResponse = false)

/**
 * Sends a raw CLI command and returns the next response verbatim (admin only), waiting its turn on the
 * node's single CLI slot. After the reply, an admin `password ` change clears the stored credential so
 * the next login re-prompts.
 */
suspend fun NodeCliCoordinator.sendRawCLICommand(
    sessionID: Uuid,
    command: String,
    timeout: Duration = DEFAULT_CLI_TIMEOUT,
): String {
    val response = performCLICommand(sessionID, command, timeout, acceptsAnyResponse = true)
    val session = store.fetchSessionById(sessionID)
    if (session != null) handlePasswordChangeIfNeeded(command, sessionID, session.publicKey)
    return response
}

/**
 * Shared send path: acquires the node's CLI slot so exactly one command is in flight per node, then
 * performs the exchange, releasing the slot to the next waiter afterward.
 */
private suspend fun NodeCliCoordinator.performCLICommand(
    sessionID: Uuid,
    command: String,
    timeout: Duration,
    acceptsAnyResponse: Boolean,
): String {
    val session = requireAdminSession(sessionID)
    val prefix = session.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)
    val command = RemoteCLICommandRewriter.rewrite(command)
    auditLogger.logCLICommand(session.publicKey, command)

    val slot = mutex.withLock { cliSlots.getOrPut(prefix) { Semaphore(1) } }
    return slot.withPermit {
        val exchange: suspend () -> String = {
            performCLIExchange(session.publicKey, prefix, command, acceptsAnyResponse, timeout)
        }
        // Reboot gets no reply (timeout means "sent"); wrapping it in flood recovery would fire a second
        // reboot on that expected timeout, so fire-and-forget commands skip recovery.
        val response = if (isFireAndForgetCLI(command)) {
            exchange()
        } else {
            performWithDirectPathFloodRecovery(session.radioID, session.publicKey, exchange)
        }
        auditLogger.logCLIResponse(session.publicKey, response)
        response
    }
}

/** Commands that intentionally get no reply (or treat timeout as success), matching the Swift set. */
private fun isFireAndForgetCLI(command: String): Boolean {
    val lower = command.trim().lowercase()
    return lower == "reboot" || lower.startsWith("reboot ")
}

private suspend fun NodeCliCoordinator.requireAdminSession(sessionID: Uuid): RemoteNodeSessionDto {
    val session = store.fetchSessionById(sessionID) ?: throw RemoteNodeError.SessionNotFound
    if (!session.isAdmin) throw RemoteNodeError.PermissionDenied
    return session
}
