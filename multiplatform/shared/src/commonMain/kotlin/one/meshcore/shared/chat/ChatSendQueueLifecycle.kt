package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.runReportingThrowable

/**
 * Purges orphan-radio rows, then pre-migration `attemptCount == null` rows. Runs on connect
 * before [hydrate] so a forgotten radio and ambiguous legacy rows are dropped rather than
 * replayed. Mirrors `PersistenceStore.warmUp`'s purge step.
 */
suspend fun ChatSendQueueService.warmUp() {
    if (isRetired) return
    // A store fault leaves orphans for the next connect.
    runReportingThrowable(onFailure = { _ -> 0 }) { store.purgeOrphanPendingSends() }
    if (isRetired) return
    // A store fault leaves legacy rows for the next connect.
    runReportingThrowable(onFailure = { _ -> 0 }) { store.purgeLegacyAttemptCountRows() }
}

/**
 * Reads every persisted row for this radio in sequence order and enqueues each into its queue. Latched
 * per instance (one instance per connection), so two view models cannot double-replay. Mirrors `hydrate`.
 */
suspend fun ChatSendQueueService.hydrate() {
    val shouldRun = hydrateMutex.withLock {
        if (isRetired || hasHydrated) {
            false
        } else {
            hasHydrated = true
            true
        }
    }
    if (!shouldRun) return

    logger.info("hydrate begin radio=${radioID.value}")
    val rows = runReportingThrowable(
        onFailure = { error ->
            logger.error("Hydration failed: ${error.message ?: error.toString()}")
            null
        },
    ) { store.fetchPendingSends(radioID) } ?: return
    for (dto in rows) {
        if (isRetired) break
        when (val envelope = dto.toEnvelope()) {
            is DirectMessageEnvelope -> dmQueue.enqueue(envelope)
            is ChannelMessageEnvelope -> channelQueue.enqueue(envelope)
            null -> Unit
        }
    }
    logger.info("hydrate complete count=${rows.size} radio=${radioID.value}")
}

/**
 * Observes the connection-state stream and fires the transport-open trigger once each time the
 * connection enters `.ready` (both the `.connected -> .ready` success edge and the `.syncing -> .ready`
 * recovery edge). If the initial state can already drain, fires immediately. Mirrors `observeConnectionState`.
 */
fun ChatSendQueueService.observeConnectionState(initial: DeviceConnectionState, states: Flow<DeviceConnectionState>) {
    if (isRetired) return
    connectionStateJob?.cancel()
    if (initial.canDrainSendQueue) transportDidOpen()
    connectionStateJob = launchWhileActive {
        var previous = initial
        states.collect { state ->
            if (!previous.canDrainSendQueue && state.canDrainSendQueue) transportDidOpen()
            previous = state
        }
    }
}

/** Fires the trigger that wakes a parked drain. Idempotent (arming an armed flag is a no-op). */
fun ChatSendQueueService.transportDidOpen() {
    if (isRetired) return
    launchWhileActive { if (!isRetired) triggers.fire() }
}

/** Stops admission and active drains before the first teardown suspension. */
fun ChatSendQueueService.retire() {
    dmQueue.retire()
    channelQueue.retire()
    retirement.complete(Unit)
}

/** Joins retired work and accepted persistence before a successor can hydrate. Rows survive. */
suspend fun ChatSendQueueService.shutdown() {
    retire()
    withContext(NonCancellable) {
        connectionStateJob?.cancelAndJoin()
        connectionStateJob = null
        dmQueue.close()
        channelQueue.close()
        admissionMutex.withLock { Unit }
    }
}

private fun ChatSendQueueService.launchWhileActive(block: suspend () -> Unit): Job {
    val job = scope.launch(start = CoroutineStart.LAZY) { if (!isRetired) block() }
    val registration = retirement.invokeOnCompletion { job.cancel() }
    job.invokeOnCompletion { registration.dispose() }
    job.start()
    return job
}

/** Test hook: awaits both queues' current drain passes. */
suspend fun ChatSendQueueService.awaitDrainCompletion() {
    dmQueue.awaitDrainCompletion()
    channelQueue.awaitDrainCompletion()
}
