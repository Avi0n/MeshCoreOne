package one.meshcore.shared.connection

import kotlinx.coroutines.flow.map
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.contact.setSyncingContacts
import one.meshcore.shared.contact.waitForAdvertIdle
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.Uuid

// Persists identity + reachability, builds the container, runs the iOS-parity post-connect sync
// (contacts → channels → message backlog), and reaches Ready. Shared by both transports;
// `reachability` is merged replace-by-transport-type onto the device row.

internal suspend fun ConnectionManager.finishConnection(
    handle: String,
    rowId: Uuid,
    identity: HandshakeIdentity,
    generation: Long,
) {
    val newSession = session ?: error("session cleared during handshake")
    val resolvedRadioId = resolveRadioId(rowId, identity.selfInfo.publicKey)
    detectedPlatform = resolveDevicePlatform(identity.capabilities.model, currentTransportType)
    if (currentTransportType == TransportType.WIFI) {
        logger.info(wifiPlatformDetectedLine(identity.capabilities.model, detectedPlatform))
    } else {
        logger.info(
            blePlatformDetectedLine(
                identity.capabilities.model,
                detectedPlatform,
                detectedPlatform.recommendedWritePacingMillis,
            ),
        )
    }
    // Persist only after this attempt still owns the generation: a superseded connect that wrote
    // last-connection here would make the next launch follow the abandoned radio.
    if (!ownsConnectAttempt(generation)) return
    persistFinishedDevice(handle, rowId, resolvedRadioId, identity)
    val container = finishedServiceContainer(newSession, resolvedRadioId, identity)
    if (!ownsConnectAttempt(generation)) {
        container.tearDown()
        return
    }
    services = container
    // Emit Connected only after `services` is assigned so host UI can bind to the live graph.
    // RX-log and 0x8A collectors start in `container.start()`, with ACK, before runFullSync.
    mutableState.value = ConnectionState.Connected(handle)
    // Suppress OS message notifications for the whole sync window (iOS SyncCoordinator
    // isSuppressingNotifications) so backlog + mid-sync live events do not flood the user.
    syncSuppressionOwner = generation
    notifier.setSyncNotificationSuppression(true)
    logger.info(SUPPRESSING_NOTIFICATIONS_DURING_SYNC)
    runInitialSync(handle, container, resolvedRadioId, generation)
}

private suspend fun ConnectionManager.persistFinishedDevice(
    handle: String,
    rowId: Uuid,
    resolvedRadioId: Uuid,
    identity: HandshakeIdentity,
) {
    persistDevice(rowId, resolvedRadioId, identity.selfInfo, identity.capabilities, identity.reachability)
    applyLastConnectionPersist(
        persist = { lastConnectionStore?.persist(rowId, resolvedRadioId, identity.selfInfo.name, handle) },
        logger = logger,
        failLine = FAILED_PERSIST_LAST_CONNECTION,
    )
    logger.info(connectionEstablishedLine(resolvedRadioId))
}

private fun ConnectionManager.finishedServiceContainer(
    newSession: MeshSession,
    resolvedRadioId: Uuid,
    identity: HandshakeIdentity,
): ServiceContainer = connectedServiceContainer(
    newSession,
    ServiceContainerIdentity(
        radioId = resolvedRadioId,
        selfPublicKey = identity.selfInfo.publicKey,
        selfNodeName = identity.selfInfo.name,
        maxContacts = identity.capabilities.maxContacts,
        maxChannels = identity.capabilities.maxChannels,
    ),
)

internal fun ConnectionManager.connectedServiceContainer(
    newSession: MeshSession,
    identity: ServiceContainerIdentity,
): ServiceContainer = ServiceContainer(
    session = newSession,
    database = database,
    identity = identity,
    runtime = ServiceContainerRuntime(
        scope = scope,
        nodePasswordStore = nodePasswordStore,
        lastConnectionStore = lastConnectionStore,
        nowEpochSeconds = nowEpochSeconds,
    ),
    edges = ServiceContainerEdges(
        notifiers = ServiceContainerNotifiers(
            notifier = notifier,
            reactionNotifier = reactionNotifier,
            contactDiscoveryNotifier = contactDiscoveryNotifier,
            activeConversation = activeConversation,
            shouldNotifyDiscovery = shouldNotifyDiscovery,
        ),
        chat = ServiceContainerChat(
            messageServiceConfig = messageServiceConfig,
            sendQueueConfig = sendQueueConfig,
            threadCoordinatorRegistry = threadCoordinatorRegistry(identity.radioId),
            threadChatStore = threadChatStore(identity.radioId),
            warmConversationPreviews = warmConversationPreviews,
            chatEnvInputs = chatEnvInputs,
            unknownSenderLabel = unknownSenderLabel,
            roomNameFallback = roomNameFallback,
        ),
        sync = ServiceContainerSync(
            rxChannelCache = rxChannelCache,
            slotOccupantChanged = slotOccupantChanged,
            onContactsChanged = onContactsChanged,
            onConversationsChanged = onConversationsChanged,
            discoveredNodes = discoveredNodes,
            rxLogStore = rxLogStore,
            debugLogStore = debugLogStore,
            isInForeground = { !scenePhaseKnown || isAppActive },
        ),
    ),
)

// Every resume point carries the same supersede fence and every fence exits the same way.
// Collapsing them into nested conditions would hide which awaits a superseding connect can land in.

private suspend fun ConnectionManager.runInitialSync(
    handle: String,
    container: ServiceContainer,
    resolvedRadioId: Uuid,
    generation: Long,
) {
    var resumeContext = RESUME_CONTEXT_SYNC_FAILED
    try {
        // Hold advert fetches for contacts + channels + backlog (iOS setSyncingContacts around
        // runFullSync). Inner syncContacts must not re-arm delta GET_CONTACTS mid-window.
        container.contactService.setSyncingContacts(resolvedRadioId, isSyncing = true)
        container.advertContactSyncClaim.setFullSyncActive(true)
        // Event monitors before sync, so a message arriving mid-pull has a handler to land in.
        logger.info(syncStartingEventMonitoringLine(resolvedRadioId))
        container.start(
            connectionStates = state.map { it.toDeviceConnectionState() },
            initialState = ConnectionState.Connected(handle).toDeviceConnectionState(),
        )
        if (!ownsConnectCeremony(generation, container)) return
        val phases = collectSyncPhases(handle, container, resolvedRadioId)
        if (!ownsConnectCeremony(generation, container)) return
        val continued = if (phases.contactsClean) {
            applyCleanContactSync(handle, container, generation, phases)
            true
        } else {
            finishUnusableContactSync(handle, container, resolvedRadioId, generation, phases.forced)
        }
        if (continued) resumeContext = RESUME_CONTEXT_SYNC_COMPLETE
    } finally {
        if (ownsConnectAttempt(generation) && services === container) initialSyncInProgress = false
        container.advertContactSyncClaim.setFullSyncActive(false)
        container.contactService.setSyncingContacts(resolvedRadioId, isSyncing = false)
        // Only the attempt that opened this window closes it. A superseded ceremony leaving through an
        // early return would otherwise clear the suppression a live ceremony had already claimed,
        // flooding the user with its backlog. The owner still always releases it, so a supersession
        // with no replacement cannot leave notifications suppressed for good.
        clearSyncSuppressionIfOwner(generation, container, resumeContext)
    }
}

private suspend fun ConnectionManager.collectSyncPhases(
    handle: String,
    container: ServiceContainer,
    resolvedRadioId: Uuid,
): SyncPhaseResult {
    logger.info(startingFullSyncLine(resolvedRadioId))
    val syncStart = nowEpochSeconds()
    initialSyncInProgress = true
    mutableState.value = ConnectionState.Syncing(handle)
    logger.info(syncStateSyncingLine(PHASE_CONTACTS))
    // Phase order mirrors SyncCoordinator.performFullSync: contacts, channels, then message backlog.
    // Only the contacts phase decides whether the connection is usable (the Swift
    // FullSyncResult.isConnectionUsable): a channel slot or a backlog read that drops leaves a radio
    // that still carries the identity graph, and holding it at Syncing would spend the resync budget
    // and drop a working link.
    logger.info(SYNC_CALLING_ACTIVITY_STARTED)
    startSyncActivity(container)
    val forced = pendingForceFullSync
    val advertIdle = container.advertContactSyncClaim.waitForAdvertIdle()
    val contactsClean = if (!advertIdle) {
        logger.warning(ADVERT_CLAIM_WAIT_TIMED_OUT)
        false
    } else {
        syncContactsPhase(container, resolvedRadioId, forceFull = forced)
    }
    if (contactsClean) pendingForceFullSync = false
    // A wait timeout must not start GET_CONTACTS or channels/backlog. iOS throws
    // `syncFailed` before `runFullSync` and the resync loop retries the whole window.
    val phases = if (!advertIdle) {
        endSyncActivity(container, succeeded = false)
        ConnectionManager.SyncPhasesOutcome(retryableIndices = emptyList(), messageCount = 0)
    } else {
        runSyncPhases(container, contactsClean)
    }
    scheduleChannelOnlyRetry(container, phases.retryableIndices)
    return SyncPhaseResult(contactsClean, phases.messageCount, syncStart, forced)
}

private suspend fun ConnectionManager.applyCleanContactSync(
    handle: String,
    container: ServiceContainer,
    generation: Long,
    phases: SyncPhaseResult,
) {
    logger.info(
        syncCompleteLine(
            platform = detectedPlatform.name.lowercase(),
            messageCount = phases.messageCount,
            durationSeconds = nowEpochSeconds() - phases.syncStart,
        ),
    )
    // iOS clears suppression after the catch-up poll, then starts auto-fetch so live
    // events notify. Clearing here (before completeSync's auto-fetch) matches that order.
    clearSyncSuppressionIfOwner(generation, container, RESUME_CONTEXT_SYNC_COMPLETE)
    // iOS `runFullSync` ends with `notifyConversationsChanged` after channels + backlog.
    // Contacts-phase `onContactsChanged` ran before `batchSaveChannels`.
    onConversationsChanged()
    completeSync(handle, container, generation)
}

private suspend fun ConnectionManager.finishUnusableContactSync(
    handle: String,
    container: ServiceContainer,
    resolvedRadioId: Uuid,
    generation: Long,
    forced: Boolean,
): Boolean {
    // iOS `promoteToReady` still corrects the clock on WiFi when contact sync failed.
    // BLE skips it so a saturated link is not pressed further. Ready stays deferred
    // to the resync success branch, but auto-fetch starts here: iOS
    // `onConnectionEstablished` enables it even when contacts are not usable, so
    // `messagesWaiting` does not sit on the radio until the first resync poll.
    if (currentTransportType == TransportType.WIFI) {
        syncDeviceTimeIfNeeded()
        if (!ownsConnectCeremony(generation, container)) return false
    }
    logger.warning(INITIAL_SYNC_NO_USABLE_CONTACTS)
    logger.info(syncStartingAutoFetchLine(container.radioId))
    container.liveSync.startAutoMessageFetching()
    startResyncLoop(
        handle,
        container,
        resolvedRadioId,
        forceFullSync = forced,
        generation = generation,
    )
    return true
}

internal data class HandshakeIdentity(
    val selfInfo: SelfInfo,
    val capabilities: DeviceCapabilities,
    val reachability: List<ConnectionMethod>,
)

private data class SyncPhaseResult(
    val contactsClean: Boolean,
    val messageCount: Int,
    val syncStart: Double,
    val forced: Boolean,
)
