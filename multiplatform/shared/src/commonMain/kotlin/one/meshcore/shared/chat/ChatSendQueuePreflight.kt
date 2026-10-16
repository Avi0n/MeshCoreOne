package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable

/** Result of the top-of-drain gate + attempt bump. */
internal data class PreflightResult(val postBumpCount: Int, val preserveTimestamp: Boolean)

internal sealed interface ReadOutcome<out T> {
    data class Found<T>(val value: T) : ReadOutcome<T>
    data object Missing : ReadOutcome<Nothing>
    data class Transient(val error: Throwable) : ReadOutcome<Nothing>
}

/**
 * The `hasPendingSend` gate + attempt bump every drain runs before wire work. Returns null when the row
 * is gone (terminal — abandon). Parks (throws [CancellationException]) on a store fault. Mirrors
 * `preflightAndBump` wrapped with the caller's park-on-throw.
 */
internal suspend fun ChatSendQueueService.bumpOrPark(messageID: Uuid, kind: String): PreflightResult? {
    if (pendingSendGone(messageID, kind)) return null
    return bumpedPreflight(messageID, kind)
}

internal suspend fun ChatSendQueueService.pendingSendGone(messageID: Uuid, kind: String): Boolean {
    val gate = classifyRead { if (store.hasPendingSend(messageID)) true else null }
    when (gate) {
        is ReadOutcome.Found -> return false
        ReadOutcome.Missing -> {
            logger.info("$kind drain abandoned messageID=${messageID.value} reason=PendingSendGone")
            return true
        }
        is ReadOutcome.Transient -> {
            logger.warning(
                "$kind drain hasPendingSend transient error: ${gate.error.message ?: gate.error.toString()}; " +
                    "parking envelope",
            )
            persistDrainState { store.updateMessageStatusUnlessDelivered(messageID, MessageStatus.PENDING) }
            parkAndCancel(messageID, kind)
        }
    }
}

internal suspend fun ChatSendQueueService.bumpedPreflight(messageID: Uuid, kind: String): PreflightResult? {
    val postBumpCount = runReportingThrowable(
        onFailure = { error ->
            logger.warning(
                "incrementPendingSendAttemptCount failed: ${error.message ?: error.toString()}; " +
                    "parking envelope for next transport-open",
            )
            persistDrainState { store.updateMessageStatusUnlessDelivered(messageID, MessageStatus.PENDING) }
            parkAndCancel(messageID, kind)
        },
    ) {
        store.incrementPendingSendAttemptCount(messageID)
    }
    if (postBumpCount == null) {
        logger.info(
            "$kind drain: PendingSend row gone for messageID=${messageID.value}; treating as terminal",
        )
        return null
    }
    return PreflightResult(postBumpCount, preserveTimestamp = postBumpCount > 1)
}

/** Awaits a transport-open trigger up to the bounded timeout, then throws to drive the requeue protocol. */
internal suspend fun ChatSendQueueService.parkAndCancel(messageID: Uuid, kind: String): Nothing {
    waitForTransportOpen(messageID, kind)
    throw CancellationException("send-queue park")
}

/** Parks on the trigger up to [ChatSendQueueConfig.transportWaitTimeout]; true if fired, false on timeout. */
internal suspend fun ChatSendQueueService.waitForTransportOpen(messageID: Uuid, kind: String): Boolean = try {
    val fired = withTimeoutOrNull(config.transportWaitTimeout) {
        triggers.wait()
        true
    } ?: false
    if (!fired) {
        logger.info(
            "$kind drain timeout-without-fire after ${config.transportWaitTimeout.inWholeSeconds}s " +
                "for ${messageID.value}; requeueing",
        )
    }
    fired
} catch (cancellation: CancellationException) {
    logger.info("$kind drain cancelled mid-wait for ${messageID.value}; requeueing")
    throw cancellation
}

/** Wraps a nullable store read into a tri-state so the drain distinguishes "row deleted" from "store fault". */
internal suspend fun <T> ChatSendQueueService.classifyRead(block: suspend () -> T?): ReadOutcome<T> =
    runReportingThrowable(
        onFailure = { error -> ReadOutcome.Transient(error) },
    ) {
        block()?.let { ReadOutcome.Found(it) } ?: ReadOutcome.Missing
    }
