package one.meshcore.shared.remotenode

import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.NodeCliCoordinator.PendingCliRequest

/** Completes the in-flight waiter when [text] matches it, and returns that reply body. */
internal suspend fun NodeCliCoordinator.handleCLIResponse(senderPrefix: ByteArray, text: String): String? {
    val prefix = senderPrefix.take(PUBLIC_KEY_PREFIX_LENGTH)
    val resolved = mutex.withLock {
        val pending = pendingCLIRequests[prefix]
        if (pending == null) {
            logger?.debug(unmatchedCliResponseLine(text.take(CLI_RESPONSE_LOG_PREFIX)))
            return@withLock null
        }
        val body = resolveResponseBody(pending, text)
        if (body == null) {
            logDroppedCliResponse(pending, text)
            return@withLock null
        }
        pendingCLIRequests.remove(prefix)
        pending to body
    }
    if (resolved == null) return null
    val (pending, body) = resolved
    pending.deferred.complete(body)
    return body
}

private fun NodeCliCoordinator.logDroppedCliResponse(pending: PendingCliRequest, text: String) {
    val echoed = CLIResponse.splitEchoedPrefix(text)
    if (echoed != null) {
        logger?.warning(droppingStaleCliResponseLine(echoed.prefix, pending.wirePrefix))
    } else {
        logger?.warning(droppingCliResponseMismatchLine(pending.command, text.take(CLI_RESPONSE_LOG_PREFIX)))
    }
}

/**
 * The reply body to deliver, or null to drop. A reply echoing the pending wire prefix is delivered
 * (prefix stripped); one echoing a different prefix is stale. An unprefixed reply falls back to shape
 * validation unless the pending command accepts any response.
 */
private fun resolveResponseBody(pending: PendingCliRequest, text: String): String? {
    val echoed = CLIResponse.splitEchoedPrefix(text)
    if (echoed != null) {
        return if (echoed.prefix == pending.wirePrefix) echoed.body else null
    }
    return if (pending.acceptsAnyResponse || CLIResponse.isPlausibleResponse(text, pending.command)) text else null
}

/** Removes and returns the pending request if it is still the given one. */
internal suspend fun NodeCliCoordinator.takePendingCLIRequest(prefix: List<Byte>, requestID: Long) = mutex.withLock {
    if (pendingCLIRequests[prefix]?.id == requestID) pendingCLIRequests.remove(prefix)
    Unit
}

/**
 * The next cycling CLI wire prefix ("00|" through "FF|"). Called only under [mutex], so the counter is
 * mutated by one command at a time and never mints a duplicate prefix across concurrent commands.
 */
internal fun NodeCliCoordinator.makeCLIWirePrefix(): String {
    val value = cliPrefixCounter
    cliPrefixCounter = (cliPrefixCounter + 1) and CLI_PREFIX_MASK
    return byteArrayOf(value.toByte()).toUppercaseHex() + CLIResponse.ECHO_PREFIX_SEPARATOR
}

/** True when the contact is not flood-routed; a missing contact counts as direct so a stale path can clear. */
internal suspend fun NodeCliCoordinator.isDirectRouted(radioID: Uuid, publicKey: ByteArray): Boolean =
    when (val flood = store.contactFloodRouted(radioID, publicKey)) {
        null -> true
        else -> !flood
    }
