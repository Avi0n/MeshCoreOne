import Foundation

extension ConnectionManager {
  func owns(_ link: ConnectionLink) -> Bool {
    installedLink === link && desiredRequest?.id == link.requestID && connectionIntent.wantsConnection
  }

  func ownsRuntime(_ link: ConnectionLink) -> Bool {
    owns(link) && link.services.isAcceptingRuntimeWork
  }

  func reconcileConnectedListeners(for link: ConnectionLink) async -> Bool {
    guard ownsRuntime(link), !Task.isCancelled else { return false }
    if let worker = link.listenerRepairWorker { return await worker.value }
    let worker = Task { @MainActor in
      defer { link.listenerRepairWorker = nil }
      guard self.ownsRuntime(link), !Task.isCancelled else { return false }
      if !link.services.isEventMonitoringActive {
        await link.services.startEventMonitoring(radioID: link.device.radioID, enableAutoFetch: false)
      }
      guard self.ownsRuntime(link), !Task.isCancelled else { return false }
      let fetching = await link.services.messagePollingService.isAutoFetching
      guard self.ownsRuntime(link), !Task.isCancelled else { return false }
      if !fetching {
        #if DEBUG
          await self.testMessageListenerRepairHandler?(link.generation)
        #endif
        guard self.ownsRuntime(link), !Task.isCancelled else { return false }
        await link.services.messagePollingService.startAutoFetch(radioID: link.device.radioID)
      }
      return self.ownsRuntime(link) && !Task.isCancelled
    }
    link.listenerRepairWorker = worker
    return await worker.value
  }

  @discardableResult
  func completeSync(_ link: ConnectionLink, syncResult: FullSyncResult?) async -> Bool {
    let succeeded = syncResult?.isConnectionUsable == true
    guard ownsRuntime(link) else { return false }
    if succeeded {
      guard await reconcileConnectedListeners(for: link), ownsRuntime(link) else { return false }
      link.usableSyncResult = syncResult
      transition(link, to: .ready)
      surfacedAuthFailureDeviceID = nil
      if link.target.transportType == .wifi, wifiHeartbeatTask == nil { startWiFiHeartbeat() }
    } else {
      transition(link, to: .syncing)
    }
    if let worker = link.completionWorker { return await worker.value }
    if link.didCompleteSync { return ownsRuntime(link) }
    if !succeeded, link.target.transportType == .bluetooth { return true }
    let operationID = UUID()
    link.completionID = operationID
    let worker = Task { @MainActor in
      defer {
        if link.completionID == operationID { link.completionWorker = nil; link.completionID = nil }
      }
      guard self.ownsRuntime(link), !Task.isCancelled else { return false }
      if !link.synthetic, !link.timeEffectsClaimed {
        link.timeEffectsClaimed = true
        await self.syncDeviceTimeIfNeeded(for: link)
        guard self.ownsRuntime(link), !Task.isCancelled else { return false }
        await RadioTimezone.syncIfNeeded(
          supportsRadioCLI: link.device.supportsRadioCLI, secondsFromGMT: TimeZone.current.secondsFromGMT(),
          settings: link.services.settingsService, stillCurrent: { self.ownsRuntime(link) && !Task.isCancelled }
        )
      }
      guard self.ownsRuntime(link), !Task.isCancelled else { return false }
      guard let usableSyncResult = link.usableSyncResult else { return true }
      if !link.synthetic, let batch = self.membershipBatch, batch.radioID == link.device.radioID {
        await link.services.remoteNodeService.handleLinkReconnection(radioID: batch.radioID, sessionIDs: batch.sessionIDs)
        guard self.ownsRuntime(link), !Task.isCancelled else { return false }
        if self.membershipBatch?.id == batch.id { self.membershipBatch?.sessionIDs.subtract(batch.sessionIDs) }
      }
      guard self.ownsRuntime(link), !Task.isCancelled else { return false }
      await self.revalidateExpectedWiFiReboot(link)
      guard self.ownsRuntime(link), !Task.isCancelled else { return false }
      link.didCompleteSync = true
      await self.onDeviceSynced?(link.snapshot, usableSyncResult.hasCleanRecipientData)
      return self.ownsRuntime(link)
    }
    link.completionWorker = worker
    return await worker.value
  }

  /// A backward clock step triggers remote replay protection until the previous time is reached.
  /// Limit each step to the tolerance while still recovering clocks far in the future.
  private static let deviceClockDriftTolerance: TimeInterval = 5

  /// Syncs the device clock when it drifts beyond `deviceClockDriftTolerance`.
  func syncDeviceTimeIfNeeded(for link: ConnectionLink) async {
    guard ownsRuntime(link) else { return }
    let session = link.resource.session
    do {
      let deviceTime = try await withTimeout(.seconds(5), operationName: "getTime") {
        try await session.getTime()
      }
      guard ownsRuntime(link), !Task.isCancelled else { return }
      let timeDifference = abs(deviceTime.timeIntervalSinceNow)
      if timeDifference > Self.deviceClockDriftTolerance {
        try await withTimeout(.seconds(5), operationName: "setTime") {
          try await session.setTime(Date())
        }
        logger.info("Synced device time (was off by \(Int(timeDifference))s)")
      } else {
        logger.info("Device time in sync (drift: \(Int(timeDifference))s)")
      }
    } catch {
      logger.warning("Failed to sync device time: \(error.localizedDescription)")
    }
  }
}
