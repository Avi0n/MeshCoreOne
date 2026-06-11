import Foundation

/// Sync retry policy (initial sync, resync loop, channel-only retry) over the
/// connection lifecycle `ConnectionManager` owns. The loops' stored tasks
/// (`resyncTask`, `channelRetryTask`) and `resyncAttemptCount` live on
/// `ConnectionManager` itself because extensions cannot add instance storage.
extension ConnectionManager {

    // MARK: - Retry Policy Constants

    /// Maximum resync attempts before giving up
    static let maxResyncAttempts = 3

    /// Interval between resync attempts
    static let resyncInterval: Duration = .seconds(2)

    static let maxChannelRetryAttempts = 2
    static let channelRetryInitialDelay: Duration = .seconds(2)

    // MARK: - Cancellation Helpers

    /// Cancels any resync retry loop in progress.
    /// The cancelled task's catch-all calls endResyncActivity(succeeded: false) asynchronously,
    /// but callers that also trigger handleDisconnect don't need to wait for it;
    /// handleDisconnect zeroes syncActivityCount independently, and the onEnded callback's
    /// guard (syncActivityCount > 0) prevents underflow.
    func cancelResyncLoop() {
        resyncTask?.cancel()
        resyncTask = nil
        resyncAttemptCount = 0
    }

    func cancelChannelRetry() {
        channelRetryTask?.cancel()
        channelRetryTask = nil
    }

    // MARK: - Initial Sync

    /// Performs initial sync with automatic resync loop on failure.
    /// Returns `true` if sync completed successfully, `false` if it failed and a resync loop was started.
    /// - Parameters:
    ///   - radioID: The radio ID for data scoping
    ///   - services: The service container
    ///   - transportType: The transport being used (determines whether BLE throttling applies)
    ///   - context: Optional context string for logging (e.g., "WiFi reconnect")
    ///   - forceFullSync: When true, forces complete data exchange regardless of sync state
    func performInitialSync(
        radioID: UUID,
        services: ServiceContainer,
        transportType: TransportType = .bluetooth,
        context: String = "",
        forceFullSync: Bool = false
    ) async -> Bool {
        let channelSyncConfig = currentChannelSyncConfig(for: radioID, transportType: transportType)
        do {
            let result = try await services.syncCoordinator.onConnectionEstablished(
                radioID: radioID,
                services: services,
                forceFullSync: forceFullSync,
                channelSyncConfig: channelSyncConfig,
                platformName: "\(self.detectedPlatform)"
            )

            if !result.channelRetryIndices.isEmpty {
                scheduleChannelOnlyRetry(
                    radioID: radioID,
                    services: services,
                    indices: result.channelRetryIndices
                )
            }

            if result.isConnectionUsable {
                return true
            }

            guard connectionIntent.wantsConnection else { return false }
            let prefix = context.isEmpty ? "" : "\(context): "
            logger.warning("\(prefix)Initial sync did not produce usable contacts, starting resync loop")
            startResyncLoop(radioID: radioID, services: services, transportType: transportType, forceFullSync: forceFullSync)
            return false
        } catch {
            // Don't start resync if user disconnected while sync was in progress
            guard connectionIntent.wantsConnection else { return false }
            let prefix = context.isEmpty ? "" : "\(context): "
            logger.warning("\(prefix)Initial sync failed, starting resync loop: \(error.localizedDescription)")
            startResyncLoop(radioID: radioID, services: services, transportType: transportType, forceFullSync: forceFullSync)
            return false
        }
    }

    /// Starts a retry loop to resync after initial sync failure.
    /// Retries every 2 seconds, shows "Sync Failed" pill and disconnects after 3 failures.
    /// Holds a sync activity bracket so the "Syncing" pill stays visible across retries.
    /// - Parameters:
    ///   - radioID: The radio ID for data scoping
    ///   - services: The ServiceContainer with all services
    ///   - forceFullSync: When true, forces complete data exchange regardless of sync state
    func startResyncLoop(
        radioID: UUID,
        services: ServiceContainer,
        transportType: TransportType = .bluetooth,
        forceFullSync: Bool = false
    ) {
        resyncTask?.cancel()
        resyncAttemptCount = 0

        // Note: No [weak self] needed - Task is stored property, self is @MainActor class.
        // Task inherits MainActor isolation, no retain cycle risk.
        resyncTask = Task {
            // Hold sync activity for the entire resync loop so the "Syncing" pill stays visible.
            // Must be inside the task body: placing it before task assignment introduced a
            // suspension point where resyncTask was still nil, breaking the dedup guard in
            // checkSyncHealth().
            await services.syncCoordinator.beginResyncActivity()
            var didEndResyncActivity = false

            while !Task.isCancelled {
                try? await Task.sleep(for: Self.resyncInterval)
                guard !Task.isCancelled else { break }

                guard connectionIntent.wantsConnection,
                      connectionState.isOperational else { break }

                resyncAttemptCount += 1
                logger.info("Resync attempt \(resyncAttemptCount)/\(Self.maxResyncAttempts)")

                let channelSyncConfig = self.currentChannelSyncConfig(for: radioID, transportType: transportType)
                let success = await services.syncCoordinator.performResync(
                    radioID: radioID,
                    services: services,
                    forceFullSync: forceFullSync,
                    channelSyncConfig: channelSyncConfig,
                    platformName: "\(self.detectedPlatform)"
                )

                if success {
                    logger.info("Resync succeeded")
                    resyncAttemptCount = 0

                    // Run post-sync hooks deferred when initial sync failed.
                    // Guard each await: disconnect(), device switch, or a new
                    // reconnect cycle may have torn down the connection.
                    guard !Task.isCancelled,
                          connectionIntent.wantsConnection,
                          connectionState.isOperational,
                          self.services === services else { break }

                    await syncDeviceTimeIfNeeded()

                    guard !Task.isCancelled,
                          connectionIntent.wantsConnection,
                          connectionState.isOperational,
                          self.services === services else { break }

                    // Re-authenticate room sessions before onDeviceSynced to avoid
                    // BLE contention with stale node cleanup's fire-and-forget Task.
                    let sessionIDs = sessionsAwaitingReauth
                    if !sessionIDs.isEmpty {
                        await services.remoteNodeService.handleBLEReconnection(sessionIDs: sessionIDs)
                    }

                    guard !Task.isCancelled,
                          connectionIntent.wantsConnection,
                          connectionState.isOperational,
                          self.services === services else { break }

                    // Report success only after confirming the loop is still authoritative.
                    // Earlier placement fired the "Ready" toast before these guards,
                    // relying on handleDisconnect as an accidental backstop.
                    await services.syncCoordinator.endResyncActivity(succeeded: true)
                    didEndResyncActivity = true

                    // Only clear consumed IDs after confirming the loop is still valid.
                    // Any IDs appended during the await (via teardownSessionForReconnect) survive.
                    sessionsAwaitingReauth.subtract(sessionIDs)

                    // Promote from .syncing to .ready now that sync completed.
                    // Not using promoteToReady() because: (1) its guards (services identity,
                    // connectionIntent) are already checked above, and (2) it would re-run
                    // time sync and onDeviceSynced, duplicating the resync loop's own post-sync work.
                    connectionState = .ready

                    await onDeviceSynced?()

                    break
                }

                if resyncAttemptCount >= Self.maxResyncAttempts {
                    logger.warning("Resync failed \(Self.maxResyncAttempts) times, disconnecting")
                    await services.syncCoordinator.endResyncActivity(succeeded: false)
                    didEndResyncActivity = true
                    onResyncFailed?()
                    await disconnect(reason: .resyncFailed)
                    break
                }
            }

            // Catch-all for cancellation or guard exits
            if !didEndResyncActivity {
                await services.syncCoordinator.endResyncActivity(succeeded: false)
            }

            // Only nil resyncTask if this task wasn't cancelled. When startResyncLoop()
            // is called while a previous loop is running, it cancels the old task and
            // assigns a new one. The old task's catch-all must not nil resyncTask or it
            // would destroy the replacement.
            if !Task.isCancelled {
                resyncTask = nil
            }
        }
    }

    /// Schedules a bounded channel-only retry after a partial channel phase.
    /// This keeps contacts/messages out of the retry path when the connection is otherwise usable.
    func scheduleChannelOnlyRetry(
        radioID: UUID,
        services: ServiceContainer,
        indices: [UInt8]
    ) {
        let initialIndices = Array(Set(indices)).sorted()
        guard !initialIndices.isEmpty else { return }

        channelRetryTask?.cancel()

        channelRetryTask = Task {
            var pendingIndices = initialIndices

            for attempt in 1...Self.maxChannelRetryAttempts {
                guard !Task.isCancelled else { break }

                let delaySeconds = 2 << (attempt - 1)
                let delay = max(Self.channelRetryInitialDelay, .seconds(delaySeconds))
                do {
                    try await Task.sleep(for: delay)
                } catch {
                    break
                }

                guard connectionIntent.wantsConnection,
                      connectionState.isOperational,
                      self.services === services else { break }

                logger.info("Channel-only retry \(attempt)/\(Self.maxChannelRetryAttempts) for \(pendingIndices.count) channel(s)")
                let result = await services.syncCoordinator.retryChannels(
                    radioID: radioID,
                    channelService: services.channelService,
                    indices: pendingIndices
                )

                if result.isComplete {
                    logger.info("Channel-only retry recovered all pending channels")
                    pendingIndices = []
                    break
                }

                pendingIndices = result.retryableIndices
                guard !pendingIndices.isEmpty else {
                    logger.warning("Channel-only retry stopped with non-retryable channel errors")
                    break
                }
            }

            if !Task.isCancelled {
                if !pendingIndices.isEmpty {
                    logger.warning("Channel-only retry exhausted with \(pendingIndices.count) retryable channel(s) still pending")
                }
                channelRetryTask = nil
            }
        }
    }

    // MARK: - Channel Sync Configuration

    /// Builds a channel sync config for the current device and transport.
    /// BLE and WiFi both use platform-specific values because ESP32 radios can saturate either transport.
    func currentChannelSyncConfig(for radioID: UUID, transportType: TransportType) -> ChannelSyncConfig {
        // Policy gate for pipelined channel reads. nRF52 over BLE amortizes the radio's
        // per-write slave-latency penalty; ESP32 over WiFi avoids the per-round-trip TCP stall
        // that makes serial channel reads roughly 200ms each. ESP32 over BLE has a write-only
        // characteristic (no Write Commands), so it stays serial. MeshCoreSession.getChannels
        // enforces a second capability gate on the transport, so this is the policy half of a
        // two-gate design and the downstream re-check is intentional, not dead.
        let usePipelinedChannelRead: Bool
        switch (detectedPlatform, transportType) {
        case (.nrf52, .bluetooth): usePipelinedChannelRead = true
        case (.esp32, .wifi): usePipelinedChannelRead = true
        default: usePipelinedChannelRead = false
        }

        return detectedPlatform.channelSyncConfig(
            lastCleanChannelSync: lastCleanChannelSync?.radioID == radioID
                ? lastCleanChannelSync?.completedAt : nil,
            lastAttemptedChannelSync: lastAttemptedChannelSync?.radioID == radioID
                ? lastAttemptedChannelSync?.attemptedAt : nil,
            usePipelinedChannelRead: usePipelinedChannelRead
        )
    }
}
