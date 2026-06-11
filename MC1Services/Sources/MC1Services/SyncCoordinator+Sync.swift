// SyncCoordinator+Sync.swift
import Foundation

// MARK: - Full Sync & Connection Lifecycle

extension SyncCoordinator {
    private struct ContactChannelSyncResult: Sendable {
        let contacts: SyncPhaseStatus
        let channels: SyncPhaseStatus
        let channelRetryIndices: [UInt8]
    }

    // MARK: - Full Sync

    /// Performs full sync of contacts, channels, and messages from device.
    ///
    /// This is the core sync method that ensures all data is pulled from the device.
    /// It syncs in order: contacts → channels → messages.
    ///
    /// - Parameters:
    ///   - radioID: The connected device UUID
    ///   - dataStore: Persistence store for data operations
    ///   - contactService: Service for contact sync
    ///   - channelService: Service for channel sync
    ///   - messagePollingService: Service for message polling
    ///   - appStateProvider: Optional provider for foreground/background state. When nil,
    ///     defaults to foreground mode (channels sync). When provided and app is backgrounded,
    ///     channel sync is skipped to reduce BLE traffic.
    ///   - rxLogService: Optional service for updating contact public keys after sync.
    ///   - forceFullSync: When true, ignores lastContactSync watermark and fetches all contacts.
    @discardableResult
    public func performFullSync(
        radioID: UUID,
        dataStore: PersistenceStore,
        contactService: some ContactServiceProtocol,
        channelService: some ChannelServiceProtocol,
        messagePollingService: some MessagePollingServiceProtocol,
        appStateProvider: AppStateProvider? = nil,
        rxLogService: RxLogService? = nil,
        notificationService: NotificationService? = nil,
        forceFullSync: Bool = false,
        channelSyncConfig: ChannelSyncConfig = .none,
        platformName: String = "unknown"
    ) async throws -> FullSyncResult {
        // Prevent concurrent syncs — actor-local flag avoids the TOCTOU window
        // that existed when guarding via `await state.isSyncing`
        guard !isSyncInProgress else {
            logger.warning("performFullSync called while already syncing, ignoring duplicate")
            return .skipped
        }
        isSyncInProgress = true
        defer { isSyncInProgress = false }

        return try await runFullSync(
            radioID: radioID,
            dataStore: dataStore,
            contactService: contactService,
            channelService: channelService,
            messagePollingService: messagePollingService,
            appStateProvider: appStateProvider,
            rxLogService: rxLogService,
            notificationService: notificationService,
            forceFullSync: forceFullSync,
            channelSyncConfig: channelSyncConfig,
            platformName: platformName
        )
    }

    /// Full-sync body without the `isSyncInProgress` claim. Callers must hold
    /// the claim already: `performFullSync` takes it per call, while
    /// `onConnectionEstablished` claims it before its handler-wiring awaits so
    /// racing connection setups cannot double-wire.
    private func runFullSync(
        radioID: UUID,
        dataStore: PersistenceStore,
        contactService: some ContactServiceProtocol,
        channelService: some ChannelServiceProtocol,
        messagePollingService: some MessagePollingServiceProtocol,
        appStateProvider: AppStateProvider? = nil,
        rxLogService: RxLogService? = nil,
        notificationService: NotificationService? = nil,
        forceFullSync: Bool = false,
        channelSyncConfig: ChannelSyncConfig = .none,
        platformName: String = "unknown"
    ) async throws -> FullSyncResult {
        logger.info("Starting full sync for device \(radioID)")
        let syncStart = ContinuousClock.now

        do {
            // Set phase before triggering pill visibility
            logger.info("[Sync] State → .syncing(.contacts)")
            await setState(.syncing(progress: SyncProgress(phase: .contacts, current: 0, total: 0)))
            hasEndedSyncActivity = false
            logger.info("[Sync] Calling onSyncActivityStarted")
            await onSyncActivityStarted?()

            // Perform contacts and channels sync (activity should show pill).
            // Contacts remain connection-critical. Channel errors are degraded
            // state: keep existing local channels and let the caller schedule a
            // channel-only retry instead of replaying contacts.
            let contactChannelResult = try await syncContactsAndChannels(
                radioID: radioID,
                dataStore: dataStore,
                contactService: contactService,
                channelService: channelService,
                appStateProvider: appStateProvider,
                rxLogService: rxLogService,
                forceFullSync: forceFullSync,
                channelSyncSkipWindow: channelSyncConfig.channelSyncSkipWindow,
                lastCleanChannelSync: channelSyncConfig.lastCleanChannelSync,
                lastAttemptedChannelSync: channelSyncConfig.lastAttemptedChannelSync,
                usePipelinedRead: channelSyncConfig.usePipelinedChannelRead
            )

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
            let messageCount: Int
            let messageStatus: SyncPhaseStatus
            do {
                messageCount = try await messagePollingService.pollAllMessages()
                messageStatus = .clean
            } catch let error as CancellationError {
                throw error
            } catch {
                messageCount = 0
                messageStatus = .failed(error.localizedDescription)
                logger.warning("[Sync] Message polling failed after contacts/channels: \(error.localizedDescription)")
            }
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
            logger.info("[Sync] Complete: platform=\(platformName), messages=\(messageCount), duration=\(elapsed)")
            return FullSyncResult(
                contacts: contactChannelResult.contacts,
                channels: contactChannelResult.channels,
                messages: messageStatus,
                channelRetryIndices: contactChannelResult.channelRetryIndices
            )
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
    /// Unlike onConnectionEstablished, does not rewire handlers or restart event monitoring.
    /// - Parameters:
    ///   - radioID: The connected device UUID
    ///   - services: The ServiceContainer with all services
    ///   - forceFullSync: When true, forces a full contact sync instead of incremental.
    ///   - channelSyncConfig: Channel sync skip configuration.
    ///   - platformName: Platform name for instrumentation logging.
    /// - Returns: `true` if sync succeeded, `false` if it failed
    public func performResync(
        radioID: UUID,
        services: ServiceContainer,
        forceFullSync: Bool = false,
        channelSyncConfig: ChannelSyncConfig = .none,
        platformName: String = "unknown"
    ) async -> Bool {
        #if DEBUG
        if let override = performResyncOverride {
            return await override(radioID, services)
        }
        #endif
        logger.info("Attempting resync for device \(radioID)")

        await MainActor.run {
            logger.info("Suppressing message notifications during resync")
            services.notificationService.isSuppressingNotifications = true
        }
        startSuppressionWatchdog(services: services)
        logger.info("[Sync] Pausing auto-fetch for resync")
        await services.messagePollingService.pauseAutoFetch()

        do {
            let result = try await performFullSync(
                radioID: radioID,
                dataStore: services.dataStore,
                contactService: services.contactService,
                channelService: services.channelService,
                messagePollingService: services.messagePollingService,
                appStateProvider: services.appStateProvider,
                rxLogService: services.rxLogService,
                notificationService: services.notificationService,
                forceFullSync: forceFullSync,
                channelSyncConfig: channelSyncConfig,
                platformName: platformName
            )

            await wireDiscoveryHandlers(services: services, radioID: radioID)

            await drainHandlersAndResumeNotifications(services: services, context: "resync complete")
            logger.info("[Sync] Resuming auto-fetch after resync")
            await services.messagePollingService.resumeAutoFetch()

            if result.isConnectionUsable {
                logger.info("Resync succeeded")
                return true
            }

            logger.warning("Resync completed without usable contacts")
            return false
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
    /// 1. Wire message handlers first (before events can arrive)
    /// 2. Start event monitoring (handlers are now ready)
    /// 3. Perform full sync (contacts, channels, messages)
    /// 4. Wire discovery handlers (for ongoing contact discovery)
    ///
    /// - Parameters:
    ///   - radioID: The connected device UUID
    ///   - services: The ServiceContainer with all services
    ///   - forceFullSync: When true, forces a full contact sync instead of incremental.
    ///   - channelSyncConfig: Channel sync skip configuration.
    ///   - platformName: Platform name for instrumentation logging.
    @discardableResult
    public func onConnectionEstablished(
        radioID: UUID,
        services: ServiceContainer,
        forceFullSync: Bool = false,
        channelSyncConfig: ChannelSyncConfig = .none,
        platformName: String = "unknown"
    ) async throws -> FullSyncResult {
        logger.info("Connection established for device \(radioID)")

        // Claim synchronously before the wiring awaits below. A read-only guard
        // let two racing calls (rapid auto-reconnect cycles) both pass and
        // double-wire handlers; the claim makes the loser skip immediately.
        guard !isSyncInProgress else {
            logger.warning("onConnectionEstablished called while already syncing, ignoring duplicate")
            return .skipped
        }
        isSyncInProgress = true
        defer { isSyncInProgress = false }

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

            // 1. Wire message handlers first (before events can arrive)
            await wireMessageHandlers(services: services, radioID: radioID)

            // Clean up legacy blocked sender messages still in DB from older app versions
            await deleteBlockedSenderMessages(radioID: radioID, dataStore: services.dataStore)

            // 2. Now start event monitoring (handlers are ready), but delay auto-fetch and advert monitoring until after sync
            logger.info("[Sync] Starting event monitoring for device \(radioID.uuidString.prefix(8))")
            await services.startEventMonitoring(radioID: radioID, enableAutoFetch: false)

            // 3. Export device private key for direct message decryption
            do {
                let privateKey = try await services.session.exportPrivateKey()
                await services.rxLogService.updatePrivateKey(privateKey)
                logger.debug("Device private key exported for direct message decryption")
            } catch {
                logger.warning("Failed to export private key: \(error.localizedDescription)")
            }

            // 4. Perform full sync (claim is already held; the guarded entry
            // point would see its own claim and skip)
            let syncResult = try await runFullSync(
                radioID: radioID,
                dataStore: services.dataStore,
                contactService: services.contactService,
                channelService: services.channelService,
                messagePollingService: services.messagePollingService,
                appStateProvider: services.appStateProvider,
                rxLogService: services.rxLogService,
                notificationService: services.notificationService,
                forceFullSync: forceFullSync,
                channelSyncConfig: channelSyncConfig,
                platformName: platformName
            )

            // 5. Wire discovery handlers (for ongoing contact discovery)
            await wireDiscoveryHandlers(services: services, radioID: radioID)

            // 6. Flush deferred advert-driven contact fetches now that handlers are wired
            await services.advertisementService.setSyncingContacts(false)

            // 7. Drain pending message handlers and resume notifications
            await drainHandlersAndResumeNotifications(services: services, context: "sync complete")

            // 8. Start auto-fetch after suppression is cleared to avoid notification spam
            logger.info("[Sync] Starting auto-fetch for device \(radioID.uuidString.prefix(8))")
            await services.messagePollingService.startAutoFetch(radioID: radioID)

            logger.info("Connection setup complete for device \(radioID)")
            return syncResult
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

        // Note: pending reactions are not cleared on disconnect - they persist for the app session
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
        radioID: UUID,
        dataStore: PersistenceStore,
        contactService: some ContactServiceProtocol,
        channelService: some ChannelServiceProtocol,
        appStateProvider: AppStateProvider?,
        rxLogService: RxLogService?,
        forceFullSync: Bool,
        channelSyncSkipWindow: Duration = .zero,
        lastCleanChannelSync: Date? = nil,
        lastAttemptedChannelSync: Date? = nil,
        usePipelinedRead: Bool = false
    ) async throws -> ContactChannelSyncResult {
        // Fetch device once for both contacts (lastContactSync) and channels (maxChannels)
        let device = try await dataStore.fetchDevice(radioID: radioID)

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
        let contactResult = try await contactService.syncContacts(radioID: radioID, since: lastContactSync)
        let contactElapsed = ContinuousClock.now - contactStart
        let syncType = contactResult.isIncremental ? "incremental" : "full"
        let forced = forceFullSync ? ", forced" : ""
        logger.info("[Sync] Phase end: contacts - \(contactResult.contactsReceived) (\(syncType)\(forced)) in \(contactElapsed)")
        await notifyContactsChanged()

        // Update lastContactSync watermark for future incremental syncs
        if contactResult.lastSyncTimestamp > 0 {
            try await dataStore.updateDeviceLastContactSync(
                radioID: radioID,
                timestamp: contactResult.lastSyncTimestamp
            )
        }

        // Update RxLogService with contact public keys for direct message decryption
        if let rxLogService {
            do {
                let publicKeys = try await dataStore.fetchContactPublicKeysByPrefix(radioID: radioID)
                await rxLogService.updateContactPublicKeys(publicKeys)
                logger.debug("Updated \(publicKeys.count) contact public keys for direct message decryption")
            } catch {
                logger.error("Failed to fetch contact public keys: \(error)")
            }
        }

        // Phase 2: Channels (foreground only)
        var channelStatus: SyncPhaseStatus = .skipped
        var channelRetryIndices: [UInt8] = []
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
                      channelSyncSkipWindow > .zero else { return false }
                let now = Date()
                if let lastSync = lastCleanChannelSync,
                   now.timeIntervalSince(lastSync) < Double(channelSyncSkipWindow.components.seconds) {
                    return true
                }
                if let lastAttempt = lastAttemptedChannelSync,
                   now.timeIntervalSince(lastAttempt) < Double(channelSyncSkipWindow.components.seconds) {
                    return true
                }
                return false
            }()

            if shouldSkipChannels {
                channelStatus = .skipped
                logger.info("[Sync] Skipping channel sync (recent clean or attempted sync)")
            } else {
                logger.info("[Sync] State → .syncing(.channels)")
                await setState(.syncing(progress: SyncProgress(phase: .channels, current: 0, total: 0)))
                let maxChannels = device?.maxChannels ?? 0
                await onChannelSyncAttempted?(radioID)

                let channelStart = ContinuousClock.now
                let channelResult: ChannelSyncResult
                do {
                    channelResult = try await channelService.syncChannels(
                        radioID: radioID,
                        maxChannels: maxChannels,
                        usePipelinedRead: usePipelinedRead
                    )
                } catch let error as CancellationError {
                    throw error
                } catch {
                    let channelElapsed = ContinuousClock.now - channelStart
                    channelStatus = .partial
                    logger.warning(
                        "[Sync] Phase end: channels partial after thrown error in \(channelElapsed): \(error.localizedDescription)"
                    )
                    await logPostSyncChannelDiagnostics(radioID: radioID, dataStore: dataStore)
                    if let rxLogService {
                        await refreshRxLogChannels(radioID: radioID, dataStore: dataStore, rxLogService: rxLogService)
                    }
                    return ContactChannelSyncResult(
                        contacts: .clean,
                        channels: channelStatus,
                        channelRetryIndices: channelRetryIndices
                    )
                }
                let channelElapsed = ContinuousClock.now - channelStart
                logger.info("[Sync] Phase end: channels - \(channelResult.channelsSynced) synced (device capacity: \(maxChannels)) in \(channelElapsed)")

                var channelPhaseClean = channelResult.isComplete
                let hasNonRetryableErrors = channelResult.errors.count > channelResult.retryableIndices.count
                var remainingRetryableIndices = channelResult.retryableIndices

                // Retry failed channels once if there are retryable errors
                if !channelResult.isComplete {
                    let retryableIndices = channelResult.retryableIndices
                    if !retryableIndices.isEmpty {
                        logger.info("Retrying \(retryableIndices.count) failed channels")
                        let retryResult: ChannelSyncResult
                        do {
                            retryResult = try await channelService.retryFailedChannels(
                                radioID: radioID,
                                indices: retryableIndices
                            )
                        } catch let error as CancellationError {
                            throw error
                        } catch {
                            logger.warning("Channel retry failed: \(error.localizedDescription)")
                            channelPhaseClean = false
                            remainingRetryableIndices = retryableIndices
                            retryResult = ChannelSyncResult(
                                channelsSynced: 0,
                                errors: retryableIndices.map {
                                    ChannelSyncError(
                                        index: $0,
                                        errorType: .timeout,
                                        description: error.localizedDescription
                                    )
                                }
                            )
                        }

                        if retryResult.isComplete && !hasNonRetryableErrors {
                            logger.info("Retry recovered \(retryResult.channelsSynced) channels")
                            channelPhaseClean = true
                        } else {
                            remainingRetryableIndices = retryResult.retryableIndices
                            if hasNonRetryableErrors {
                                logger.warning("Channels have non-retryable errors, phase not clean")
                            }
                            logger.warning("Channels still failing after retry: \(retryResult.errors.map { $0.index })")
                            channelPhaseClean = false
                        }
                    }
                }

                if channelPhaseClean {
                    channelStatus = .clean
                    await onCleanChannelSync?(radioID)
                } else {
                    channelStatus = .partial
                    channelRetryIndices = remainingRetryableIndices
                }
            }

            await logPostSyncChannelDiagnostics(radioID: radioID, dataStore: dataStore)
            if let rxLogService {
                await refreshRxLogChannels(radioID: radioID, dataStore: dataStore, rxLogService: rxLogService)
            }
        } else {
            channelStatus = .skipped
            logger.info("Skipping channel sync (app in background)")
        }

        return ContactChannelSyncResult(
            contacts: .clean,
            channels: channelStatus,
            channelRetryIndices: channelRetryIndices
        )
    }

    /// Retries only unresolved channel indices without replaying contacts/messages.
    @discardableResult
    public func retryChannels(
        radioID: UUID,
        channelService: some ChannelServiceProtocol,
        indices: [UInt8]
    ) async -> ChannelSyncResult {
        guard !indices.isEmpty else {
            return ChannelSyncResult(channelsSynced: 0, errors: [])
        }

        guard !isSyncInProgress else {
            logger.info("Channel-only retry skipped because sync is already active")
            return ChannelSyncResult(
                channelsSynced: 0,
                errors: indices.map {
                    ChannelSyncError(
                        index: $0,
                        errorType: .circuitBreaker,
                        description: "Skipped because sync is already active"
                    )
                }
            )
        }

        isSyncInProgress = true
        defer { isSyncInProgress = false }

        logger.info("[Sync] State → .syncing(.channels) (channel-only retry)")
        await setState(.syncing(progress: SyncProgress(phase: .channels, current: 0, total: indices.count)))
        await onSyncActivityStarted?()
        await onChannelSyncAttempted?(radioID)

        let result: ChannelSyncResult
        do {
            result = try await channelService.retryFailedChannels(radioID: radioID, indices: indices)
        } catch is CancellationError {
            await onSyncActivityEnded?(false)
            await setState(.idle)
            return ChannelSyncResult(
                channelsSynced: 0,
                errors: indices.map {
                    ChannelSyncError(index: $0, errorType: .transportError, description: "Retry cancelled")
                }
            )
        } catch {
            logger.warning("Channel-only retry failed: \(error.localizedDescription)")
            await onSyncActivityEnded?(false)
            await setState(.synced)
            return ChannelSyncResult(
                channelsSynced: 0,
                errors: indices.map {
                    ChannelSyncError(index: $0, errorType: .transportError, description: error.localizedDescription)
                }
            )
        }

        if result.isComplete {
            await onCleanChannelSync?(radioID)
        }

        await onSyncActivityEnded?(result.isComplete)
        await setState(.synced)
        return result
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

    private func logPostSyncChannelDiagnostics(radioID: UUID, dataStore: PersistenceStore) async {
        do {
            let channels = try await dataStore.fetchChannels(radioID: radioID)
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
        radioID: UUID,
        dataStore: PersistenceStore,
        rxLogService: RxLogService
    ) async {
        do {
            let channels = try await dataStore.fetchChannels(radioID: radioID)
            let secrets = Dictionary(uniqueKeysWithValues: channels.map { ($0.index, $0.secret) })
            let names = Dictionary(uniqueKeysWithValues: channels.map { ($0.index, $0.name) })
            await rxLogService.updateChannels(secrets: secrets, names: names)
            logger.debug("Refreshed RxLogService channel cache with \(channels.count) channels")
        } catch {
            logger.error("Failed to refresh RxLogService channel cache: \(error)")
        }
    }
}
