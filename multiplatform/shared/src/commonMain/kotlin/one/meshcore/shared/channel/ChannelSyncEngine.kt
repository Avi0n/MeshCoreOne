package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.chat.secretChangedIndices
import one.meshcore.shared.meshcore.ChannelInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable

/** Radio slot sync and the retry of slots that previously failed. */
interface ChannelSyncCommands {
    /**
     * Installs the handler for slots whose occupant changed (vacated or rewritten with a different
     * secret). Mirrors Swift `setSlotOccupantChangedHandler`.
     */
    fun setSlotOccupantChanged(handler: ChannelSlotOccupantChanged)

    /**
     * Reads every slot `0 until [maxChannels]` from the radio and reconciles the local store in one
     * transaction. [usePipelinedRead] selects the bounded-window batch path (nRF52/BLE, ESP32/WiFi) over
     * the serial per-slot path; both share the classify-then-persist tail. A second concurrent sync throws
     * [ChannelServiceError.SyncAlreadyInProgress]. Mirrors `syncChannels(radioID:maxChannels:usePipelinedRead:)`.
     */
    suspend fun syncChannels(radioID: Uuid, maxChannels: Int, usePipelinedRead: Boolean): ChannelSyncResult

    /**
     * Retries only the slots that previously failed: a pre-delay, a stricter breaker threshold, and an
     * upsert of the recovered channels only — never a delete or prune. An empty recovery skips the persist.
     * Mirrors `retryFailedChannels(radioID:indices:)`.
     */
    suspend fun retryFailedChannels(radioID: Uuid, indices: List<Int>): ChannelSyncResult
}

internal class ChannelSyncEngine(
    private val session: ChannelSession,
    private val store: ChannelStore,
    private val state: ChannelServiceState,
    private val cache: ChannelRxCacheRefresh,
    private val reader: ChannelSlotReader,
) : ChannelSyncCommands {
    override fun setSlotOccupantChanged(handler: ChannelSlotOccupantChanged) {
        state.slotOccupantChanged = handler
    }

    override suspend fun syncChannels(radioID: Uuid, maxChannels: Int, usePipelinedRead: Boolean): ChannelSyncResult =
        state.withChannelSyncOperation {
            state.mutex.withLock {
                if (state.isSyncing) {
                    state.logger?.warning("Channel sync already in progress, rejecting concurrent request")
                    throw ChannelServiceError.SyncAlreadyInProgress()
                }
                state.isSyncing = true
            }
            try {
                if (usePipelinedRead) {
                    syncChannelsPipelined(radioID, maxChannels)
                } else {
                    syncChannelsSerial(radioID, maxChannels)
                }
            } finally {
                withContext(NonCancellable) {
                    state.mutex.withLock { state.isSyncing = false }
                }
            }
        }

    override suspend fun retryFailedChannels(radioID: Uuid, indices: List<Int>): ChannelSyncResult =
        state.withChannelSyncOperation {
            state.mutex.withLock {
                if (state.isSyncing) {
                    state.logger?.warning("Channel sync already in progress, rejecting concurrent request")
                    throw ChannelServiceError.SyncAlreadyInProgress()
                }
                if (indices.isEmpty()) return@withChannelSyncOperation ChannelSyncResult(channelsSynced = 0)
                state.isSyncing = true
            }
            try {
                retryFailedChannelsLocked(radioID, indices)
            } finally {
                withContext(NonCancellable) {
                    state.mutex.withLock { state.isSyncing = false }
                }
            }
        }

    private suspend fun syncChannelsSerial(radioID: Uuid, maxChannels: Int): ChannelSyncResult {
        val syncErrors = mutableListOf<ChannelSyncError>()
        val configured = mutableListOf<ChannelInfo>()
        val unconfiguredIndices = mutableListOf<Int>()
        var consecutiveFailures = 0

        var index = 0
        while (index < maxChannels) {
            if (consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD_INITIAL) {
                state.logger?.error(
                    "Circuit breaker open: $consecutiveFailures consecutive timeouts, aborting sync",
                )
                for (remaining in index until maxChannels) {
                    syncErrors += ChannelSyncClassifier.circuitBreakerError(remaining)
                }
                break
            }
            when (val outcome = reader.readSlot(index)) {
                is ChannelSlotReader.SlotOutcome.Configured -> {
                    configured += outcome.info
                    consecutiveFailures = 0
                }

                ChannelSlotReader.SlotOutcome.Unconfigured -> {
                    unconfiguredIndices += index
                    consecutiveFailures = 0
                }

                is ChannelSlotReader.SlotOutcome.Failed -> {
                    consecutiveFailures = ChannelSyncClassifier.nextConsecutiveFailureCount(
                        outcome.error,
                        consecutiveFailures,
                    )
                    syncErrors += outcome.error
                }
            }
            index++
        }

        return finalizeChannelSync(radioID, maxChannels, configured, unconfiguredIndices, syncErrors)
    }

    /**
     * One bounded-window `getChannels` exchange in place of N serial reads, then acknowledged
     * reconciliation of any dropped requests. A hard send failure throws and persists nothing; an idle
     * stall returns a partial set to reconcile. Classification and the single-transaction persist match the
     * serial path, so a slot that could not be read lands in neither list and is never deleted.
     */
    private suspend fun syncChannelsPipelined(radioID: Uuid, maxChannels: Int): ChannelSyncResult {
        val syncErrors = mutableListOf<ChannelSyncError>()
        val configured = mutableListOf<ChannelInfo>()
        val unconfiguredIndices = mutableListOf<Int>()

        val batch = session.getChannels((0 until maxChannels).toList())
        for (info in batch.received) {
            if (ChannelSecret.isChannelConfigured(info.name, info.secret)) {
                configured += info
            } else {
                unconfiguredIndices += info.index
            }
        }

        // The consecutive-failure count is meaningful again on this serial reconcile, so the breaker
        // applies here. A dropped reconcile read leaves its index in neither list, so no row is deleted.
        var consecutiveFailures = 0
        var cursor = 0
        while (cursor < batch.missing.size) {
            val index = batch.missing[cursor]
            if (consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD_INITIAL) {
                state.logger?.error(reconcileCircuitBreakerOpenLine(consecutiveFailures))
                for (remaining in cursor until batch.missing.size) {
                    syncErrors += ChannelSyncClassifier.circuitBreakerError(batch.missing[remaining])
                }
                break
            }
            when (val outcome = reader.readSlot(index, logFailure = false)) {
                is ChannelSlotReader.SlotOutcome.Configured -> {
                    configured += outcome.info
                    consecutiveFailures = 0
                }

                ChannelSlotReader.SlotOutcome.Unconfigured -> {
                    unconfiguredIndices += index
                    consecutiveFailures = 0
                }

                is ChannelSlotReader.SlotOutcome.Failed -> {
                    consecutiveFailures = ChannelSyncClassifier.nextConsecutiveFailureCount(
                        outcome.error,
                        consecutiveFailures,
                    )
                    state.logger?.warning(reconcileFailedForChannelLine(index, outcome.error.description))
                    syncErrors += outcome.error
                }
            }
            cursor++
        }

        return finalizeChannelSync(radioID, maxChannels, configured, unconfiguredIndices, syncErrors)
    }

    /**
     * Persists a completed read pass in a single transaction and reports the result. Shared by both paths
     * so their classification-to-persist tail cannot drift: upsert configured, delete rows at unconfigured
     * slots, prune orphans beyond [maxChannels], never touch a slot in neither list. Vacated slots and
     * secret-changed occupants fire the slot handler; the fresh list feeds the RX decrypt cache.
     */
    private suspend fun finalizeChannelSync(
        radioID: Uuid,
        maxChannels: Int,
        configured: List<ChannelInfo>,
        unconfiguredIndices: List<Int>,
        syncErrors: List<ChannelSyncError>,
    ): ChannelSyncResult {
        val priorChannels = snapshotChannels(radioID)
        currentCoroutineContext().ensureActive()

        // A failed batch persist is reported and stops the sync. Cancellation still propagates.
        val channels = runReportingThrowable(
            onFailure = { error -> Result.failure(error) },
        ) {
            Result.success(store.batchSaveChannels(radioID, configured, unconfiguredIndices, pruneBeyond = maxChannels))
        }.getOrElse { error ->
            state.logger?.error("Channel batch persist failed: ${error.message ?: error.toString()}")
            return ChannelSyncResult(
                channelsSynced = 0,
                errors = syncErrors + configured.map {
                    ChannelSyncError(
                        it.index,
                        ChannelSyncErrorType.DatabaseError,
                        "Batch persist failed: ${error.message}",
                    )
                },
            )
        }

        currentCoroutineContext().ensureActive()
        val vacatedSlots = priorChannels.map { it.index }.toSet() - channels.map { it.index }.toSet()
        val changedSlots = vacatedSlots + secretChangedIndices(priorChannels, configured)
        if (changedSlots.isNotEmpty()) state.slotOccupantChanged.onOccupantChanged(radioID, changedSlots)
        logPostSyncChannelDiagnostics(channels)
        currentCoroutineContext().ensureActive()
        cache.apply(channels)

        return ChannelSyncResult(channelsSynced = configured.size, errors = syncErrors)
    }

    // A failed channel snapshot is an empty baseline, so a prune does not report occupant changes.
    // Cancellation still propagates.
    private suspend fun snapshotChannels(radioID: Uuid): List<ChannelDto> = runReportingThrowable(
        onFailure = { _ ->
            state.logger?.warning(
                "Pre-sync channel snapshot failed for radio ${radioID.value}; " +
                    "skipping occupant-changed for any slots this prune vacates",
            )
            emptyList()
        },
    ) {
        store.fetchChannels(radioID)
    }

    /**
     * iOS `SyncCoordinator.logPostSyncChannelDiagnostics`. Computed from the list just persisted so a
     * second fetch cannot turn a clean sync into a failed one. A failed pass is logged and the sync
     * still finishes. Cancellation still propagates.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun logPostSyncChannelDiagnostics(channels: List<ChannelDto>) {
        try {
            val emptyNameWithSecret = channels
                .filter { it.name.isEmpty() && it.secret.any { byte -> byte.toInt() != 0 } }
                .map { it.index }
                .sorted()
            state.logger?.info(
                "Post-sync channel diagnostics: total=${channels.size}, " +
                    "emptyNameWithSecret=${emptyNameWithSecret.size}",
            )
            if (emptyNameWithSecret.isNotEmpty()) {
                state.logger?.warning(
                    "Post-sync channels with empty names and non-zero secrets: $emptyNameWithSecret",
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            state.logger?.error("Failed to compute post-sync channel diagnostics: ${error.message ?: error}")
        }
    }

    // A failed retry persist is logged and returned as a database error. Cancellation still propagates.
    private suspend fun retryFailedChannelsLocked(radioID: Uuid, indices: List<Int>): ChannelSyncResult {
        state.logger?.info(retryingFailedChannelsLine(indices))
        delay(RETRY_PRE_DELAY_MS)
        val read = readRetrySlots(indices)
        if (read.configured.isEmpty()) return ChannelSyncResult(channelsSynced = 0, errors = read.syncErrors)

        // A failed retry snapshot is a silent empty baseline, so a secret change is not reported.
        // Cancellation still propagates.
        val priorChannels = runReportingThrowable(
            onFailure = { _ -> emptyList() },
        ) {
            store.fetchChannels(radioID)
        }
        currentCoroutineContext().ensureActive()
        val persisted = runReportingThrowable(
            onFailure = { error -> Result.failure(error) },
        ) {
            Result.success(
                store.batchSaveChannels(
                    radioID,
                    read.configured,
                    unconfiguredIndices = emptyList(),
                    pruneBeyond = null,
                ),
            )
        }
        return persisted.fold(
            onSuccess = { allChannels ->
                currentCoroutineContext().ensureActive()
                val changedSlots = secretChangedIndices(priorChannels, read.configured)
                if (changedSlots.isNotEmpty()) state.slotOccupantChanged.onOccupantChanged(radioID, changedSlots)
                currentCoroutineContext().ensureActive()
                cache.apply(allChannels)
                ChannelSyncResult(channelsSynced = read.configured.size, errors = read.syncErrors)
            },
            onFailure = { error ->
                val persistError = error.message
                state.logger?.error("$RETRY_BATCH_PERSIST_FAILED_PREFIX: ${error.message ?: error.toString()}")
                val errors = read.syncErrors + read.configured.map {
                    ChannelSyncError(
                        it.index,
                        ChannelSyncErrorType.DatabaseError,
                        "Retry persist failed: $persistError",
                    )
                }
                ChannelSyncResult(channelsSynced = 0, errors = errors)
            },
        )
    }

    /** Reads each failed slot until the retry breaker opens, then records the rest as breaker errors. */
    private suspend fun readRetrySlots(indices: List<Int>): RetrySlotRead {
        val syncErrors = mutableListOf<ChannelSyncError>()
        val configured = mutableListOf<ChannelInfo>()
        var consecutiveFailures = 0
        var cursor = 0
        while (cursor < indices.size) {
            val index = indices[cursor]
            if (consecutiveFailures >= CIRCUIT_BREAKER_THRESHOLD_RETRY) {
                state.logger?.warning(retryCircuitBreakerOpenLine(consecutiveFailures))
                for (remaining in cursor until indices.size) {
                    syncErrors += ChannelSyncClassifier.circuitBreakerError(indices[remaining])
                }
                break
            }
            when (val outcome = reader.readSlot(index, logFailure = false)) {
                is ChannelSlotReader.SlotOutcome.Configured -> {
                    configured += outcome.info
                    consecutiveFailures = 0
                    state.logger?.info(retrySucceededForChannelLine(index))
                }

                ChannelSlotReader.SlotOutcome.Unconfigured -> consecutiveFailures = 0

                is ChannelSlotReader.SlotOutcome.Failed -> {
                    consecutiveFailures = ChannelSyncClassifier.nextConsecutiveFailureCount(
                        outcome.error,
                        consecutiveFailures,
                    )
                    syncErrors += outcome.error
                    state.logger?.warning(retryFailedForChannelLine(index, outcome.error.description))
                }
            }
            cursor++
        }
        return RetrySlotRead(configured, syncErrors)
    }

    private data class RetrySlotRead(val configured: List<ChannelInfo>, val syncErrors: List<ChannelSyncError>)

    private companion object {
        const val CIRCUIT_BREAKER_THRESHOLD_INITIAL = 3
        const val CIRCUIT_BREAKER_THRESHOLD_RETRY = 2
        const val RETRY_PRE_DELAY_MS = 500L
    }
}
