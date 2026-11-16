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
    if let resyncTask { retiringWorkers.append(resyncTask) }
    resyncSlotID = nil
    resyncTask?.cancel()
    resyncTask = nil
    resyncAttemptCount = 0
  }

  func cancelChannelRetry() {
    if let channelRetryTask { retiringWorkers.append(channelRetryTask) }
    channelRetrySlotID = nil
    channelRetryTask?.cancel()
    channelRetryTask = nil
  }

  // MARK: - Initial Sync

  /// Performs initial sync with automatic resync loop on failure.
  /// Returns the completed result, or nil when the sync throws. A non-usable result
  /// still starts the resync loop. Readiness stays `isConnectionUsable`.
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
  ) async -> FullSyncResult? {
    guard self.services === services, services.isAcceptingRuntimeWork else { return nil }
    let channelSyncConfig = currentChannelSyncConfig(for: radioID, transportType: transportType)
    do {
      let result = try await services.syncCoordinator.onConnectionEstablished(
        radioID: radioID,
        dependencies: services.syncDependencies,
        forceFullSync: forceFullSync,
        channelSyncConfig: channelSyncConfig,
        platformName: "\(detectedPlatform)"
      )

      guard self.services === services, services.isAcceptingRuntimeWork, connectionIntent.wantsConnection else { return nil }
      if !result.channelRetryIndices.isEmpty {
        scheduleChannelOnlyRetry(
          radioID: radioID,
          services: services,
          indices: result.channelRetryIndices
        )
      }

      if !result.isConnectionUsable, connectionIntent.wantsConnection {
        let prefix = context.isEmpty ? "" : "\(context): "
        logger.warning("\(prefix)Initial sync did not produce usable contacts, starting resync loop")
        startResyncLoop(radioID: radioID, services: services, transportType: transportType, forceFullSync: forceFullSync)
      }
      return result
    } catch {
      // Don't start resync if user disconnected while sync was in progress
      guard connectionIntent.wantsConnection, self.services === services, services.isAcceptingRuntimeWork else { return nil }
      let prefix = context.isEmpty ? "" : "\(context): "
      logger.warning("\(prefix)Initial sync failed, starting resync loop: \(error.localizedDescription)")
      startResyncLoop(radioID: radioID, services: services, transportType: transportType, forceFullSync: forceFullSync)
      return nil
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
    guard let link = installedLink, link.services === services, ownsRuntime(link) else { return }
    cancelResyncLoop()
    let slot = UUID()
    resyncSlotID = slot
    resyncAttemptCount = 0
    resyncTask = Task { @MainActor in
      defer {
        if self.resyncSlotID == slot {
          self.resyncTask = nil
          self.resyncSlotID = nil
          self.resyncAttemptCount = 0
        }
      }
      await services.syncCoordinator.beginResyncActivity()
      var ended = false
      for attempt in 1...Self.maxResyncAttempts {
        do { try await reconnectionClock.sleep(for: Self.resyncInterval) } catch { break }
        guard self.ownsRuntime(link), self.resyncSlotID == slot, !Task.isCancelled else { break }
        self.resyncAttemptCount = attempt
        let config = self.currentChannelSyncConfig(for: radioID, transportType: transportType)
        let result = await services.syncCoordinator.performResync(
          radioID: radioID, dependencies: services.syncDependencies, forceFullSync: forceFullSync,
          channelSyncConfig: config, platformName: "\(self.detectedPlatform)"
        )
        guard self.ownsRuntime(link), self.resyncSlotID == slot, !Task.isCancelled else { break }
        if let result, result.isConnectionUsable {
          await services.syncCoordinator.endResyncActivity(succeeded: true)
          ended = true
          guard self.ownsRuntime(link), self.resyncSlotID == slot, !Task.isCancelled else { break }
          _ = await self.completeSync(link, syncResult: result)
          break
        }
        if attempt == Self.maxResyncAttempts {
          await services.syncCoordinator.endResyncActivity(succeeded: false)
          ended = true
          guard self.ownsRuntime(link), self.resyncSlotID == slot, !Task.isCancelled else { break }
          self.onResyncFailed?(link.snapshot)
          Task { @MainActor in
            guard self.ownsRuntime(link) else { return }
            await self.disconnect(reason: .resyncFailed)
          }
        }
      }
      if !ended { await services.syncCoordinator.endResyncActivity(succeeded: false) }
    }
  }

  /// Schedules a bounded channel-only retry after a partial channel phase.
  /// This keeps contacts/messages out of the retry path when the connection is otherwise usable.
  func scheduleChannelOnlyRetry(
    radioID: UUID,
    services: ServiceContainer,
    indices: [UInt8]
  ) {
    guard self.services === services, services.isAcceptingRuntimeWork else { return }
    let initialIndices = Array(Set(indices)).sorted()
    guard !initialIndices.isEmpty else { return }

    cancelChannelRetry()
    let slot = UUID()
    channelRetrySlotID = slot

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
              self.services === services, services.isAcceptingRuntimeWork else { break }

        logger.info("Channel-only retry \(attempt)/\(Self.maxChannelRetryAttempts) for \(pendingIndices.count) channel(s)")
        let result = await services.syncCoordinator.retryChannels(
          radioID: radioID,
          channelService: services.channelService,
          indices: pendingIndices
        )

        guard !Task.isCancelled, self.services === services, channelRetrySlotID == slot else { return }
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

      if channelRetrySlotID == slot {
        if !pendingIndices.isEmpty {
          logger.warning("Channel-only retry exhausted with \(pendingIndices.count) retryable channel(s) still pending")
        }
        channelRetryTask = nil
        channelRetrySlotID = nil
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
    let usePipelinedChannelRead = switch (detectedPlatform, transportType) {
    case (.nrf52, .bluetooth): true
    case (.esp32, .wifi): true
    default: false
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
