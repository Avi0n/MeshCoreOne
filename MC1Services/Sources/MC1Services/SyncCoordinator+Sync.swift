// SyncCoordinator+Sync.swift
import Foundation

// MARK: - Full Sync & Connection Lifecycle

extension SyncCoordinator {

    // MARK: - Full Sync

    /// Performs full sync of contacts, channels, and messages from device.
    ///
    /// This is the core sync method that ensures all data is pulled from the device.
    /// It syncs in order: contacts → channels → messages.
    ///
    /// - Parameters:
    ///   - deviceID: The connected device UUID
    ///   - dataStore: Persistence store for data operations
    ///   - contactService: Service for contact sync
    ///   - channelService: Service for channel sync
    ///   - messagePollingService: Service for message polling
    ///   - appStateProvider: Optional provider for foreground/background state. When nil,
    ///     defaults to foreground mode (channels sync). When provided and app is backgrounded,
    ///     channel sync is skipped to reduce BLE traffic.
    ///   - rxLogService: Optional service for updating contact public keys after sync.
    ///   - forceFullSync: When true, ignores lastContactSync watermark and fetches all contacts.
    public func performFullSync(
        deviceID: UUID,
        dataStore: PersistenceStore,
        contactService: some ContactServiceProtocol,
        channelService: some ChannelServiceProtocol,
        messagePollingService: some MessagePollingServiceProtocol,
        appStateProvider: AppStateProvider? = nil,
        rxLogService: RxLogService? = nil,
        notificationService: NotificationService? = nil,
        forceFullSync: Bool = false,
        throttling: SyncThrottlingConfig = .none
    ) async throws {
        // Prevent concurrent syncs — actor-local flag avoids the TOCTOU window
        // that existed when guarding via `await state.isSyncing`
        guard !isSyncInProgress else {
            logger.warning("performFullSync called while already syncing, ignoring duplicate")
            return
        }
        isSyncInProgress = true
        defer { isSyncInProgress = false }

        logger.info("Starting full sync for device \(deviceID)")
        let syncStart = ContinuousClock.now

        do {
            // Set phase before triggering pill visibility
            logger.info("[Sync] State → .syncing(.contacts)")
            await setState(.syncing(progress: SyncProgress(phase: .contacts, current: 0, total: 0)))
            hasEndedSyncActivity = false
            logger.info("[Sync] Calling onSyncActivityStarted")
            await onSyncActivityStarted?()

            // Perform contacts and channels sync (activity should show pill)
            do {
                try await syncContactsAndChannels(
                    deviceID: deviceID,
                    dataStore: dataStore,
                    contactService: contactService,
                    channelService: channelService,
                    appStateProvider: appStateProvider,
                    rxLogService: rxLogService,
                    forceFullSync: forceFullSync,
                    channelSyncSkipWindow: throttling.channelSyncSkipWindow,
                    lastCleanChannelSync: throttling.lastCleanChannelSync
                )
            } catch {
                // End sync activity on error during contacts/channels phase
                await endSyncActivityOnce()
                throw error
            }

            // End sync activity before messages phase (pill should hide).
            // During resync, the outer bracket (beginResyncActivity) holds syncActivityCount >= 1,
            // so this inner succeeded=true decrement cannot reach zero and prematurely trigger
            // the "Ready" toast. During initial sync there is no outer bracket, and reaching
            // zero here is the correct "sync complete" signal.
            await endSyncActivityOnce(succeeded: true)

            // Phase 3: Messages (no pill for this phase)
            logger.info("[Sync] State → .syncing(.messages)")
            await setState(.syncing(progress: SyncProgress(phase: .messages, current: 0, total: 0)))
            let messageStart = ContinuousClock.now
            let messageCount = try await messagePollingService.pollAllMessages(
                messageDelay: throttling.messageDelay,
                breathingInterval: throttling.breathingInterval,
                breathingDuration: throttling.breathingDuration
            )
            let messageElapsed = ContinuousClock.now - messageStart
            logger.info("[Sync] Phase end: messages - \(messageCount) polled in \(messageElapsed)")

            // Clear notification suppression immediately after catch-up poll completes.
            // All catch-up messages have been processed with suppression active;
            // any subsequent event-monitor messages are genuinely new and should notify.
            if let notificationService {
                cancelSuppressionWatchdog()
                await MainActor.run {
                    notificationService.isSuppressingNotifications = false
                }
            }

            await notifyConversationsChanged()

            // Complete
            logger.info("[Sync] State → .synced")
            await setState(.synced)
            await setLastSyncDate(Date())

            let elapsed = ContinuousClock.now - syncStart
            logger.info("Full sync complete in \(elapsed)")
        } catch let error as CancellationError {
            // Defensive: ensure activity count is decremented even if cancellation
            // occurs outside the contacts/channels error path.
            await endSyncActivityOnce()
            await setState(.idle)
            throw error
        } catch {
            // Defensive: ensure activity count is decremented even if an error is
            // thrown from a path that bypasses the inner contacts/channels catch.
            await endSyncActivityOnce()
            logger.warning("[Sync] State → .failed: \(error.localizedDescription)")
            await setState(.failed(.syncFailed(error.localizedDescription)))
            throw error
        }
    }

    /// Attempts to resync data after a previous sync failure.
    /// Unlike onConnectionEstablished, does NOT rewire handlers or restart event monitoring.
    /// - Parameters:
    ///   - deviceID: The connected device UUID
    ///   - services: The ServiceContainer with all services
    ///   - forceFullSync: When true, forces a full contact sync instead of incremental.
    ///   - throttling: Sync throttling parameters (message delay, breathing, channel skip).
    /// - Returns: `true` if sync succeeded, `false` if it failed
    public func performResync(
        deviceID: UUID,
        services: ServiceContainer,
        forceFullSync: Bool = false,
        throttling: SyncThrottlingConfig = .none
    ) async -> Bool {
        #if DEBUG
        if let override = performResyncOverride {
            return await override(deviceID, services)
        }
        #endif
        logger.info("Attempting resync for device \(deviceID)")

        await MainActor.run {
            logger.info("Suppressing message notifications during resync")
            services.notificationService.isSuppressingNotifications = true
        }
        startSuppressionWatchdog(services: services)
        logger.info("[Sync] Pausing auto-fetch for resync")
        await services.messagePollingService.pauseAutoFetch()

        do {
            try await performFullSync(
                deviceID: deviceID,
                dataStore: services.dataStore,
                contactService: services.contactService,
                channelService: services.channelService,
                messagePollingService: services.messagePollingService,
                appStateProvider: services.appStateProvider,
                rxLogService: services.rxLogService,
                notificationService: services.notificationService,
                forceFullSync: forceFullSync,
                throttling: throttling
            )

            await wireDiscoveryHandlers(services: services, deviceID: deviceID)

            await drainHandlersAndResumeNotifications(services: services, context: "resync complete")
            logger.info("[Sync] Resuming auto-fetch after resync")
            await services.messagePollingService.resumeAutoFetch()

            logger.info("Resync succeeded")
            return true
        } catch {
            await drainHandlersAndResumeNotifications(services: services, context: "resync failed")
            await services.messagePollingService.resumeAutoFetch()

            logger.warning("Resync failed: \(error.localizedDescription)")
            await setState(.failed(.syncFailed(error.localizedDescription)))
            return false
        }
    }

    // MARK: - Connection Lifecycle

    /// Called by ConnectionManager when connection is established.
    /// Wires handlers, starts event monitoring, and performs initial sync.
    ///
    /// This is the critical method that fixes the handler wiring gap:
    /// 1. Wire message handlers FIRST (before events can arrive)
    /// 2. Start event monitoring (handlers are now ready)
    /// 3. Perform full sync (contacts, channels, messages)
    /// 4. Wire discovery handlers (for ongoing contact discovery)
    ///
    /// - Parameters:
    ///   - deviceID: The connected device UUID
    ///   - services: The ServiceContainer with all services
    ///   - forceFullSync: When true, forces a full contact sync instead of incremental.
    ///   - throttling: Sync throttling parameters (message delay, breathing, channel skip).
    public func onConnectionEstablished(
        deviceID: UUID,
        services: ServiceContainer,
        forceFullSync: Bool = false,
        throttling: SyncThrottlingConfig = .none
    ) async throws {
        logger.info("Connection established for device \(deviceID)")

        // Prevent duplicate sync if already syncing (race condition during rapid auto-reconnect cycles)
        guard !isSyncInProgress else {
            logger.warning("onConnectionEstablished called while already syncing, ignoring duplicate")
            return
        }

        // Suppress message notifications during sync to avoid flooding user on reconnect
        // Unread counts and badges still update - only system notifications are suppressed
        await MainActor.run {
            logger.info("Suppressing message notifications during sync")
            services.notificationService.isSuppressingNotifications = true
        }
        startSuppressionWatchdog(services: services)

        do {
            // Defer advert-driven contact fetches during sync to avoid BLE contention
            await services.advertisementService.setSyncingContacts(true)

            // 1. Wire message handlers FIRST (before events can arrive)
            await wireMessageHandlers(services: services, deviceID: deviceID)

            // Clean up legacy blocked sender messages still in DB from older app versions
            await deleteBlockedSenderMessages(deviceID: deviceID, dataStore: services.dataStore)

            // 2. NOW start event monitoring (handlers are ready), but delay auto-fetch and advert monitoring until after sync
            logger.info("[Sync] Starting event monitoring for device \(deviceID.uuidString.prefix(8))")
            await services.startEventMonitoring(deviceID: deviceID, enableAutoFetch: false)

            // 3. Export device private key for direct message decryption
            do {
                let privateKey = try await services.session.exportPrivateKey()
                await services.rxLogService.updatePrivateKey(privateKey)
                logger.debug("Device private key exported for direct message decryption")
            } catch {
                logger.warning("Failed to export private key: \(error.localizedDescription)")
            }

            // 4. Perform full sync
            try await performFullSync(
                deviceID: deviceID,
                dataStore: services.dataStore,
                contactService: services.contactService,
                channelService: services.channelService,
                messagePollingService: services.messagePollingService,
                appStateProvider: services.appStateProvider,
                rxLogService: services.rxLogService,
                notificationService: services.notificationService,
                forceFullSync: forceFullSync,
                throttling: throttling
            )

            // 5. Wire discovery handlers (for ongoing contact discovery)
            await wireDiscoveryHandlers(services: services, deviceID: deviceID)

            // 6. Flush deferred advert-driven contact fetches now that handlers are wired
            await services.advertisementService.setSyncingContacts(false)

            // 7. Drain pending message handlers and resume notifications
            await drainHandlersAndResumeNotifications(services: services, context: "sync complete")

            // 8. Start auto-fetch after suppression is cleared to avoid notification spam
            logger.info("[Sync] Starting auto-fetch for device \(deviceID.uuidString.prefix(8))")
            await services.messagePollingService.startAutoFetch(deviceID: deviceID)

            logger.info("Connection setup complete for device \(deviceID)")
        } catch {
            // Drain pending message handlers and resume notifications
            await drainHandlersAndResumeNotifications(services: services, context: "sync failed")
            await services.advertisementService.setSyncingContacts(false)
            throw error
        }
    }

    /// Called when disconnecting from device
    ///
    /// If disconnect occurs mid-sync (during contacts or channels phase), we must call
    /// onSyncActivityEnded to decrement the activity count, otherwise the pill stays stuck.
    public func onDisconnected(services: ServiceContainer) async {
        let currentState = await state
        logger.warning(
            "[Sync] onDisconnected called - syncState: \(String(describing: currentState)), hasEndedSyncActivity: \(hasEndedSyncActivity)"
        )

        // Safety net: clear sync guard flag on disconnect
        if isSyncInProgress {
            logger.warning("isSyncInProgress still true at disconnect — clearing as safety net")
        }
        isSyncInProgress = false

        // Note: pending reactions are NOT cleared on disconnect - they persist for the app session
        // This handles temporary BLE disconnects without losing queued reactions
        unresolvedChannelIndices.removeAll()
        lastUnresolvedChannelSummaryAt = nil

        // If we're mid-sync in contacts or channels phase, end the activity to hide the pill
        if case .syncing(let progress) = currentState,
           progress.phase == .contacts || progress.phase == .channels {
            await endSyncActivityOnce()
        }

        logger.info("[Sync] State → .idle (disconnected)")
        await setState(.idle)

        // Safety net: ensure suppression is cleared on disconnect
        // Handles edge cases like connection dropping mid-sync or force-quit
        cancelSuppressionWatchdog()
        await MainActor.run {
            services.notificationService.isSuppressingNotifications = false
        }

        logger.info("Disconnected, sync state reset to idle")
    }

    // MARK: - Sync Helpers

    /// Syncs contacts and channels from the device (phases 1 and 2 of full sync).
    private func syncContactsAndChannels(
        deviceID: UUID,
        dataStore: PersistenceStore,
        contactService: some ContactServiceProtocol,
        channelService: some ChannelServiceProtocol,
        appStateProvider: AppStateProvider?,
        rxLogService: RxLogService?,
        forceFullSync: Bool,
        channelSyncSkipWindow: Duration = .zero,
        lastCleanChannelSync: Date? = nil
    ) async throws {
        // Fetch device once for both contacts (lastContactSync) and channels (maxChannels)
        let device = try await dataStore.fetchDevice(id: deviceID)

        // Phase 1: Contacts (incremental unless forced full)
        let lastContactSync: Date? = forceFullSync ? nil : {
            guard let timestamp = device?.lastContactSync, timestamp > 0 else { return nil }
            return Date(timeIntervalSince1970: Double(timestamp))
        }()
        if let watermark = lastContactSync {
            logger.info("[Sync] Phase start: contacts (incremental, watermark=\(watermark.formatted(.iso8601)))")
        } else {
            let reason = forceFullSync ? "forceFullSync" : "no watermark"
            logger.notice("[Sync] Phase start: contacts (FULL sync, reason=\(reason)) — local contacts not on device will be pruned")
        }

        let contactStart = ContinuousClock.now
        let contactResult = try await contactService.syncContacts(deviceID: deviceID, since: lastContactSync)
        let contactElapsed = ContinuousClock.now - contactStart
        let syncType = contactResult.isIncremental ? "incremental" : "full"
        let forced = forceFullSync ? ", forced" : ""
        logger.info("[Sync] Phase end: contacts - \(contactResult.contactsReceived) (\(syncType)\(forced)) in \(contactElapsed)")
        await notifyContactsChanged()

        // Update lastContactSync watermark for future incremental syncs
        if contactResult.lastSyncTimestamp > 0 {
            try await dataStore.updateDeviceLastContactSync(
                deviceID: deviceID,
                timestamp: contactResult.lastSyncTimestamp
            )
        }

        // Update RxLogService with contact public keys for direct message decryption
        if let rxLogService {
            do {
                let publicKeys = try await dataStore.fetchContactPublicKeysByPrefix(deviceID: deviceID)
                await rxLogService.updateContactPublicKeys(publicKeys)
                logger.debug("Updated \(publicKeys.count) contact public keys for direct message decryption")
            } catch {
                logger.error("Failed to fetch contact public keys: \(error)")
            }
        }

        // Phase 2: Channels (foreground only)
        logger.debug("About to check foreground state, provider exists: \(appStateProvider != nil)")
        let shouldSyncChannels: Bool
        if let provider = appStateProvider {
            logger.debug("Calling isInForeground...")
            shouldSyncChannels = await provider.isInForeground
            logger.debug("isInForeground returned: \(shouldSyncChannels)")
        } else {
            logger.debug("No appStateProvider, defaulting to foreground mode")
            shouldSyncChannels = true
        }
        logger.debug("Proceeding with shouldSyncChannels=\(shouldSyncChannels)")
        if shouldSyncChannels {
            let shouldSkipChannels: Bool = {
                guard !forceFullSync,
                      channelSyncSkipWindow > .zero,
                      let lastSync = lastCleanChannelSync else { return false }
                return Date().timeIntervalSince(lastSync) < Double(channelSyncSkipWindow.components.seconds)
            }()

            if shouldSkipChannels {
                logger.info("[Sync] Skipping channel sync (clean sync completed recently)")
            } else {
                logger.info("[Sync] State → .syncing(.channels)")
                await setState(.syncing(progress: SyncProgress(phase: .channels, current: 0, total: 0)))
                let maxChannels = device?.maxChannels ?? 0

                let channelStart = ContinuousClock.now
                let channelResult = try await channelService.syncChannels(deviceID: deviceID, maxChannels: maxChannels)
                let channelElapsed = ContinuousClock.now - channelStart
                logger.info("[Sync] Phase end: channels - \(channelResult.channelsSynced) synced (device capacity: \(maxChannels)) in \(channelElapsed)")

                var channelPhaseClean = channelResult.isComplete
                let hasNonRetryableErrors = channelResult.errors.count > channelResult.retryableIndices.count

                // Retry failed channels once if there are retryable errors
                if !channelResult.isComplete {
                    let retryableIndices = channelResult.retryableIndices
                    if !retryableIndices.isEmpty {
                        logger.info("Retrying \(retryableIndices.count) failed channels")
                        let retryResult = try await channelService.retryFailedChannels(
                            deviceID: deviceID,
                            indices: retryableIndices
                        )

                        if retryResult.isComplete && !hasNonRetryableErrors {
                            logger.info("Retry recovered \(retryResult.channelsSynced) channels")
                            channelPhaseClean = true
                        } else {
                            if hasNonRetryableErrors {
                                logger.warning("Channels have non-retryable errors, phase not clean")
                            }
                            logger.warning("Channels still failing after retry: \(retryResult.errors.map { $0.index })")
                            channelPhaseClean = false
                        }
                    }
                }

                if channelPhaseClean {
                    await onCleanChannelSync?(deviceID)
                }
            }

            await logPostSyncChannelDiagnostics(deviceID: deviceID, dataStore: dataStore)
            if let rxLogService {
                await refreshRxLogChannels(deviceID: deviceID, dataStore: dataStore, rxLogService: rxLogService)
            }
        } else {
            logger.info("Skipping channel sync (app in background)")
        }
    }

    /// Cancels the suppression watchdog, resumes notifications, then waits for pending
    /// message handlers to drain. Suppression is cleared first as defense-in-depth for
    /// error paths where `performFullSync` throws before reaching `pollAllMessages()`.
    /// Both operations are idempotent, so double-clearing from the happy path is harmless.
    private func drainHandlersAndResumeNotifications(services: ServiceContainer, context: String) async {
        cancelSuppressionWatchdog()
        await MainActor.run {
            logger.info("Resuming message notifications (\(context))")
            services.notificationService.isSuppressingNotifications = false
        }

        let pendingHandlerDrainTimeout: Duration = .seconds(30)
        let didDrainPendingHandlers = await services.messagePollingService.waitForPendingHandlers(timeout: pendingHandlerDrainTimeout)
        if !didDrainPendingHandlers {
            logger.warning("Timed out waiting for pending message handlers")
        }
    }

    private func logPostSyncChannelDiagnostics(deviceID: UUID, dataStore: PersistenceStore) async {
        do {
            let channels = try await dataStore.fetchChannels(deviceID: deviceID)
            let emptyNameWithSecretIndices = channels
                .filter { $0.name.isEmpty && $0.hasSecret }
                .map(\.index)
                .sorted()
            logger.info(
                "Post-sync channel diagnostics: total=\(channels.count), emptyNameWithSecret=\(emptyNameWithSecretIndices.count)"
            )
            if !emptyNameWithSecretIndices.isEmpty {
                logger.warning(
                    "Post-sync channels with empty names and non-zero secrets: \(emptyNameWithSecretIndices)"
                )
            }
        } catch {
            logger.error("Failed to compute post-sync channel diagnostics: \(error)")
        }
    }

    private func refreshRxLogChannels(
        deviceID: UUID,
        dataStore: PersistenceStore,
        rxLogService: RxLogService
    ) async {
        do {
            let channels = try await dataStore.fetchChannels(deviceID: deviceID)
            let secrets = Dictionary(uniqueKeysWithValues: channels.map { ($0.index, $0.secret) })
            let names = Dictionary(uniqueKeysWithValues: channels.map { ($0.index, $0.name) })
            await rxLogService.updateChannels(secrets: secrets, names: names)
            logger.debug("Refreshed RxLogService channel cache with \(channels.count) channels")
        } catch {
            logger.error("Failed to refresh RxLogService channel cache: \(error)")
        }
    }
}
