package one.meshcore.shared.chat

import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable

/**
 * Persists a DM row first, then enqueues the envelope so a restart replays it. Throws
 * [ChatSendQueueServiceException.PersistFailed] if the store write fails.
 */
suspend fun ChatSendQueueService.enqueueDM(envelope: DirectMessageEnvelope) {
    admitPendingWrite {
        persist(envelope.toPendingSendDto(newPendingSendId(), radioID, sequence = 0, now(), attemptCount = 0))
    }
    dmQueue.enqueue(envelope)
}

/** Channel counterpart of [enqueueDM]. */
suspend fun ChatSendQueueService.enqueueChannel(envelope: ChannelMessageEnvelope) {
    admitPendingWrite {
        persist(envelope.toPendingSendDto(newPendingSendId(), radioID, sequence = 0, now(), attemptCount = 0))
    }
    channelQueue.enqueue(envelope)
}

/**
 * Manual DM retry. Replaces any parked row for the message, flips the persisted row back to `.pending`
 * (a row already `.delivered` is left alone) and inserts the fresh row — all in one store transaction —
 * then enqueues. The status flip is load-bearing, not cosmetic: the shared status guards refuse every
 * later terminal write from `.failed`, so a retry that only moved the in-memory row would leave the
 * delivered message reading "Failed" on the next cold load. Mirrors the Swift `replacePendingSendForRetry`
 * plus `signalDMEnqueued` pair.
 */
suspend fun ChatSendQueueService.retryDM(envelope: DirectMessageEnvelope) {
    admitPendingWrite {
        val dto = envelope.toPendingSendDto(newPendingSendId(), radioID, sequence = 0, now(), attemptCount = 0)
        replaceForRetry(envelope.messageID, dto)
    }
    signalDMEnqueued(envelope)
}

/**
 * Manual channel retry. Same transaction as [retryDM]: drop parked rows, flip the message back to
 * `.pending` unless it is already delivered, insert one fresh queue row, then enqueue.
 */
suspend fun ChatSendQueueService.retryChannel(envelope: ChannelMessageEnvelope) {
    admitPendingWrite {
        val dto = envelope.toPendingSendDto(newPendingSendId(), radioID, sequence = 0, now(), attemptCount = 0)
        replaceForRetry(envelope.messageID, dto)
    }
    channelQueue.enqueue(envelope)
}

/**
 * Enqueues a DM whose row was already written in one transaction by the manual-retry path
 * (`replacePendingSendForRetry`); the in-memory enqueue is the only step left. Mirrors `signalDMEnqueued`.
 */
suspend fun ChatSendQueueService.signalDMEnqueued(envelope: DirectMessageEnvelope) = dmQueue.enqueue(envelope)

private suspend fun ChatSendQueueService.admitPendingWrite(write: suspend () -> Unit) {
    if (isRetired) throw ChatSendQueueServiceException.NotConnected()
    admissionMutex.withLock {
        if (isRetired) throw ChatSendQueueServiceException.NotConnected()
        write()
    }
}

/**
 * Drain-path persist. A Room fault is logged and swallowed so the drain can park or respawn,
 * matching iOS `try?` on these writes.
 */
internal suspend fun ChatSendQueueService.persistDrainState(block: suspend () -> Unit) {
    runReportingThrowable(
        onFailure = { error ->
            logger.error("Failed to persist send-queue drain state: ${error.message ?: error.toString()}")
        },
    ) {
        block()
    }
}

internal suspend fun ChatSendQueueService.persist(dto: PendingSendDto) {
    runReportingThrowable(
        onFailure = { error ->
            logger.error("Persisting envelope failed: ${error.message ?: error.toString()}")
            throw ChatSendQueueServiceException.PersistFailed(error)
        },
    ) {
        store.insertPendingSendAssigningSequence(dto)
    }
}

internal suspend fun ChatSendQueueService.replaceForRetry(messageID: Uuid, dto: PendingSendDto) {
    runReportingThrowable(
        onFailure = { error -> throw ChatSendQueueServiceException.PersistFailed(error) },
    ) {
        store.replacePendingSendForRetry(messageID, dto)
    }
}
