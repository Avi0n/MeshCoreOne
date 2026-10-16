package one.meshcore.shared.connection

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.meshcore.shared.contact.setSyncingContacts
import one.meshcore.shared.contact.syncContacts
import one.meshcore.shared.contact.waitForAdvertIdle
import one.meshcore.shared.model.Uuid

/**
 * Pulls contacts and records the watermark a later connect syncs from.
 *
 * The watermark is what keeps the destructive full-sync prune off the reconnect path: a full sync
 * deletes every local contact the radio did not report and cascades that contact's messages, reactions
 * and pending sends, so a radio that answers with a short table once would take the responder's history
 * with it. A radio with no watermark yet — a first pairing — still syncs full, and the user-driven
 * refresh stays full so a genuinely evicted contact is still reconciled.
 */
internal suspend fun ConnectionManager.syncContactsPhase(
    container: ServiceContainer,
    radioId: Uuid,
    forceFull: Boolean,
): Boolean = phaseSucceeded(PHASE_CONTACTS) {
    forceFullSyncThisCycle = forceFull
    var ranInvalidWatermarkRecovery = false
    val capacity = localContactTableCapacity(radioId)
    val since = if (forceFullSyncThisCycle || capacity.atCapacity) {
        null
    } else {
        when (val use = contactWatermarkUse(contactSyncWatermark(radioId), nowEpochSeconds().toLong())) {
            ContactWatermarkUse.None -> null
            is ContactWatermarkUse.Incremental -> incrementalContactSince(use.watermark)
            is ContactWatermarkUse.Invalid ->
                if (invalidWatermarkRecoveryRadioID == radioId) {
                    incrementalContactSince(use.stored)
                } else {
                    ranInvalidWatermarkRecovery = true
                    null
                }
        }
    }
    if (since != null) {
        logger.info(syncContactsPhaseStartLine(true, since.toString()))
    } else {
        val reason = if (forceFullSyncThisCycle) {
            "forced"
        } else if (capacity.atCapacity) {
            "at capacity (local ${capacity.realContactCount} of max ${capacity.maxContacts})"
        } else if (ranInvalidWatermarkRecovery) {
            "invalid watermark"
        } else {
            "no watermark"
        }
        logger.info(syncContactsPhaseStartLine(false, reason))
    }
    val result = container.contactService.syncContacts(radioId, since = since)
    val kind = if (since != null) "incremental" else "full"
    logger.info(syncContactsPhaseEndLine(result.contactsReceived, kind))
    persistContactSyncWatermark(radioId, result.lastSyncTimestamp)
    if (ranInvalidWatermarkRecovery) invalidWatermarkRecoveryRadioID = radioId
    if (since == null) container.advertContactSyncClaim.markFullContactSyncCompleted()
    // iOS `performFullSync` ends with `notifyContactsChanged` so Map (and Nodes) reload
    // pins after the radio table lands. Without this a Map tab open during connect stays stale.
    onContactsChanged()
}

/**
 * Runs the channel and message-backlog phases for their effect, and reports the channel slots a further
 * attempt could still recover. Neither phase decides whether the connection is usable — that is the
 * contacts phase alone, matching the Swift `FullSyncResult.isConnectionUsable` — and each is guarded on
 * its own so one phase's failure cannot cost the other its run.
 *
 * A channel phase that threw outright reports no slots, matching the Swift `performFullSync` early
 * return, which carries an empty `channelRetryIndices` out of that same catch: nothing classified those
 * slots, so nothing knows which are worth re-reading.
 */
internal suspend fun ConnectionManager.runSyncPhases(
    container: ServiceContainer,
    contactsClean: Boolean,
): ConnectionManager.SyncPhasesOutcome {
    val forced = forceFullSyncThisCycle
    forceFullSyncThisCycle = false
    val radioId = container.radioId
    val now = nowEpochSeconds()
    val inForeground = !scenePhaseKnown || isAppActive
    val skipWindow = shouldSkipChannelSync(
        forceFullSync = forced,
        skipWindowSeconds = detectedPlatform.channelSyncSkipWindowSeconds,
        nowEpochSeconds = now,
        lastCleanAt = lastCleanChannelSync?.takeIf { it.first == radioId }?.second,
        lastAttemptedAt = lastAttemptedChannelSync?.takeIf { it.first == radioId }?.second,
    )
    val channelResult = if (!inForeground) {
        logger.info(SYNC_SKIP_CHANNEL_BACKGROUND)
        null
    } else if (skipWindow) {
        logger.info(SYNC_SKIP_CHANNEL)
        null
    } else {
        logger.info(syncStateSyncingLine(PHASE_CHANNELS))
        lastAttemptedChannelSync = radioId to now
        val pipelined = usePipelinedChannelRead(detectedPlatform, currentTransportType)
        phaseResult(PHASE_CHANNELS) { container.liveSync.syncChannelsFromDevice(usePipelinedRead = pipelined) }
            ?.also {
                if (it.isComplete) lastCleanChannelSync = radioId to nowEpochSeconds()
                logger.info(syncChannelsPhaseEndLine(it.channelsSynced, container.maxChannels))
            }
    }
    // The syncing pill spans contacts and channels only, closing before the message backlog exactly as
    // the Swift bracket does: the backlog drain can run for minutes on a busy radio, and the identity
    // graph the user is waiting on is already in place. A channel slot that failed is degraded state,
    // not a failed sync, so the verdict here is the contacts phase's alone.
    logger.info(syncCallingActivityEndedLine(contactsClean))
    endSyncActivity(container, succeeded = contactsClean)
    logger.info(syncStateSyncingLine(PHASE_MESSAGES))
    val messageCount = phaseResult(PHASE_MESSAGES) { container.liveSync.pollBacklog() }
    if (messageCount != null) logger.info(syncMessagesPhaseEndLine(messageCount))
    return ConnectionManager.SyncPhasesOutcome(
        retryableIndices = channelResult?.retryableIndices.orEmpty(),
        messageCount = messageCount ?: 0,
    )
}

// Whether one phase ran without throwing. Cancellation is never a verdict: it propagates, so a
// cancelled loop stops instead of spending an attempt.
internal suspend fun ConnectionManager.phaseSucceeded(name: String, phase: suspend () -> Unit): Boolean =
    phaseResult(name, phase) != null

// One phase's result, or null when it threw. Guarding a phase must not cost its caller the outcome:
// the channel phase's retryable slots are the manager's only signal that a channel row is missing.
internal suspend fun <T> ConnectionManager.phaseResult(name: String, phase: suspend () -> T): T? = runSyncPhase(
    onFailure = { error ->
        logger.warning(syncPhaseFailLine(name, error.message ?: error.toString()))
        null
    },
) {
    phase()
}

/**
 * Retries the full sync every [ResyncPolicy.interval] until it comes back clean or the attempt budget is
 * spent, mirroring the Swift `startResyncLoop`. A clean pass promotes to [ConnectionState.Ready] and
 * clears the sync-failed pill; a spent budget raises the pill and drops the link.
 */
internal fun ConnectionManager.startResyncLoop(
    handle: String,
    container: ServiceContainer,
    radioId: Uuid,
    forceFullSync: Boolean,
    generation: Long,
) {
    cancelResyncLoop()
    resyncJob = scope.launch {
        // One activity spans the whole loop, not one per attempt, so a radio that is retrying holds a
        // steady syncing pill instead of flickering between attempts (Swift `beginResyncActivity`).
        startSyncActivity(container)
        var released = false
        try {
            val outcome = runResyncLoop(container, radioId, forceFullSync)
            endSyncActivity(container, succeeded = outcome == ConnectionManager.ResyncOutcome.SUCCEEDED)
            released = true
            when (outcome) {
                ConnectionManager.ResyncOutcome.SUCCEEDED -> completeResync(handle, container, generation)
                ConnectionManager.ResyncOutcome.EXHAUSTED -> failResync(container)
                ConnectionManager.ResyncOutcome.ABANDONED -> Unit
            }
        } finally {
            // A cancelled or abandoned loop still releases the pill, matching the Swift catch-all.
            if (!released) endSyncActivity(container, succeeded = false)
        }
    }
}

// Waits out the interval, spends one attempt, and repeats until an outcome is settled.
internal suspend fun ConnectionManager.runResyncLoop(
    container: ServiceContainer,
    radioId: Uuid,
    forceFullSync: Boolean,
): ConnectionManager.ResyncOutcome {
    var attempts = 0
    var outcome: ConnectionManager.ResyncOutcome? = null
    while (outcome == null) {
        delay(resyncPolicy.interval)
        // Fence on container identity so a loop orphaned by a later reconnect cycle neither resyncs nor
        // disconnects against a container the manager has since replaced; without it the exhaustion
        // branch could tear down a healthy successor connection.
        if (!isSyncLoopValid(container)) return ConnectionManager.ResyncOutcome.ABANDONED
        attempts++
        logger.info(resyncAttemptLine(attempts, resyncPolicy.maxAttempts))
        outcome = when {
            performResync(container, radioId, forceFullSync) -> ConnectionManager.ResyncOutcome.SUCCEEDED
            attempts >= resyncPolicy.maxAttempts -> ConnectionManager.ResyncOutcome.EXHAUSTED
            else -> null
        }
    }
    return outcome
}

// One resync attempt: the same phases and the same verdict as the initial window.
internal suspend fun ConnectionManager.performResync(
    container: ServiceContainer,
    radioId: Uuid,
    forceFullSync: Boolean,
): Boolean {
    // The attempt keeps its own bracket, nested inside the loop's, exactly as a Swift resync attempt
    // runs the whole `performFullSync` bracket inside `beginResyncActivity`. The outer hold is what
    // stops an attempt that recovers channels from announcing "Ready" before the loop has finished.
    // iOS performResync re-enables suppression for this attempt; the connect finally already cleared it.
    notifier.setSyncNotificationSuppression(true)
    logger.info(SUPPRESSING_NOTIFICATIONS_DURING_RESYNC)
    logger.info(attemptingResyncLine(radioId))
    // iOS pauses the session drain for the whole attempt so pollBacklog owns the radio queue.
    container.liveSync.pauseAutoMessageFetching()
    var resumeContext = RESUME_CONTEXT_RESYNC_FAILED
    return try {
        runSyncPhase(
            onFailure = { error ->
                logger.warning(resyncFailedLine(error.message ?: error.toString()))
                false
            },
        ) {
            container.contactService.setSyncingContacts(radioId, isSyncing = true)
            container.advertContactSyncClaim.setFullSyncActive(true)
            startSyncActivity(container)
            logger.info(syncStateSyncingLine(PHASE_CONTACTS))
            if (!container.advertContactSyncClaim.waitForAdvertIdle()) {
                logger.warning(ADVERT_CLAIM_WAIT_TIMED_OUT)
                endSyncActivity(container, succeeded = false)
                return false
            }
            val contactsClean = syncContactsPhase(container, radioId, forceFull = forceFullSync)
            if (contactsClean) pendingForceFullSync = false
            runSyncPhases(container, contactsClean)
            if (contactsClean) onConversationsChanged()
            if (!contactsClean) logger.warning(RESYNC_COMPLETED_WITHOUT_USABLE_CONTACTS)
            resumeContext = RESUME_CONTEXT_RESYNC_COMPLETE
            contactsClean
        }
    } finally {
        container.advertContactSyncClaim.setFullSyncActive(false)
        container.contactService.setSyncingContacts(radioId, isSyncing = false)
        if (canResumeSyncNotifications(container)) {
            logger.info(resumingNotificationsLine(resumeContext))
            notifier.setSyncNotificationSuppression(false)
        }
        container.liveSync.resumeAutoMessageFetching()
    }
}

// The deferred post-sync hooks a failed initial sync skipped. Promotion comes before the remote-session
// re-authentication, matching Swift: that exchange is bounded but multi-second, and holding the syncing
// pill and the send queue's ready gate through it delays the user for work that is not sync. The fence is
// re-checked around the await because a disconnect or a fresh reconnect cycle can land inside it.
internal suspend fun ConnectionManager.completeResync(handle: String, container: ServiceContainer, generation: Long) {
    if (!isSyncLoopValid(container)) {
        logPromotionSuppressed(container)
        return
    }
    logger.info(RESYNC_SUCCEEDED)
    logger.info(syncStartingAutoFetchLine(container.radioId))
    container.liveSync.startAutoMessageFetching()
    connectionUi?.hideSyncFailedPill()
    mutableState.value = ConnectionState.Ready(handle)
    logger.info(SYNC_STATE_SYNCED)
    syncDeviceTimeIfNeeded()
    if (!isSyncLoopValid(container)) {
        logger.warning("Promotion suppressed after time sync: ${promotionSuppressedReason(container)}")
        return
    }
    reauthenticatePendingRemoteSessions(container, generation)
}

// The budget is spent: raise the pill so the user learns the radio is not synced, then drop the link.
// The connection intent survives (see [DisconnectReason.RESYNC_FAILED]) so the radio stays wanted.
internal suspend fun ConnectionManager.failResync(container: ServiceContainer) {
    if (!isSyncLoopValid(container)) return
    logger.warning(resyncFailedTimesLine(resyncPolicy.maxAttempts))
    connectionUi?.showSyncFailedPill()
    // Release the loop handle before disconnecting: disconnect cancels the resync job, and this coroutine
    // is that job — cancelling it here would abort the disconnect at its next suspension point.
    resyncJob = null
    disconnect(DisconnectReason.RESYNC_FAILED)
}

// Whether this loop still owns the live connection: the user still wants it, the manager has not replaced
// the container underneath it, and the connection is still operational.
internal fun ConnectionManager.isSyncLoopValid(container: ServiceContainer): Boolean =
    wantsConnection && services === container && mutableState.value.toDeviceConnectionState().isOperational
