import Foundation
import MeshCore

extension ConnectionManager {
  func startWiFiHeartbeat() {
    guard let link = installedLink, link.target.transportType == .wifi else { return }
    stopWiFiHeartbeat()
    let slot = UUID()
    heartbeatSlotID = slot
    wifiHeartbeatTask = Task { @MainActor [weak self] in
      guard let self else { return }
      defer {
        if self.heartbeatSlotID == slot { self.wifiHeartbeatTask = nil; self.heartbeatSlotID = nil }
      }
      while !Task.isCancelled {
        do { try await self.sleepForWiFiHeartbeat() } catch { return }
        guard self.installedLink === link, self.heartbeatSlotID == slot else { return }
        if self.shouldPauseWiFiHeartbeatProbe { continue }
        do {
          _ = try await link.resource.session.getTime()
        } catch {
          guard !Task.isCancelled, self.installedLink === link, !self.shouldPauseWiFiHeartbeatProbe else { continue }
          Task { @MainActor [weak self] in
            guard let self, self.installedLink === link else { return }
            await self.handleWiFiDisconnection(error: error, remoteLossAt: nil, isHeartbeat: true, source: link.resource.wifi)
          }
          return
        }
      }
    }
  }

  var shouldPauseWiFiHeartbeatProbe: Bool {
    powerOffBlocksReconnect || connectionState == .syncing || resyncTask != nil || channelRetryTask != nil
  }

  private func sleepForWiFiHeartbeat() async throws {
    #if DEBUG
      if let clock = testWiFiHeartbeatClock { try await clock.sleep(for: Self.wifiHeartbeatInterval); return }
    #endif
    try await Task.sleep(for: Self.wifiHeartbeatInterval)
  }

  func stopWiFiHeartbeat() {
    if let wifiHeartbeatTask { retiringWorkers.append(wifiHeartbeatTask) }
    heartbeatSlotID = nil
    wifiHeartbeatTask?.cancel()
    wifiHeartbeatTask = nil
  }

  func handleWiFiDisconnection(
    error: Error?, remoteLossAt: CFAbsoluteTime?, isHeartbeat: Bool = false, source: (any WiFiMeshTransport)? = nil
  ) async {
    guard let link = installedLink, link.target.transportType == .wifi,
          let request = desiredRequest, request.id == link.requestID else { return }
    if let source, source !== link.resource.wifi { return }
    if let remoteLossAt { notePowerOffRemoteLoss(deviceID: link.device.id, eventTime: remoteLossAt) }
    guard connectionIntent.wantsConnection else { return }
    if isHeartbeat, powerOffBlocksReconnect { return }
    let barrier = beginOwnedRetirement(previousRequest: request, phase: .connecting, closePhysical: true, retainMembership: true)
    wifiDisconnectionReceipt = barrier.receipt
    defer {
      if wifiDisconnectionReceipt?.id == barrier.receipt.id { wifiDisconnectionReceipt = nil }
    }
    await onAutoReconnectStarted?(barrier.receipt)
    await barrier.value
    guard owns(request), isCurrent(barrier.receipt) else { return }
    startWiFiReconnection()
  }

  func startWiFiReconnection() {
    guard !powerOffBlocksReconnect, wifiReconnectTask == nil,
          let request = desiredRequest, case .wifi = request.target else { return }
    let cooldown: TimeInterval
    #if DEBUG
      cooldown = testWiFiReconnectCooldown ?? Self.wifiReconnectCooldown
    #else
      cooldown = Self.wifiReconnectCooldown
    #endif
    if let last = lastWiFiReconnectStartTime, Date().timeIntervalSince(last) < cooldown {
      Task { @MainActor in await self.finishWiFiRecovery(request) }
      return
    }
    lastWiFiReconnectStartTime = Date()
    let slot = UUID()
    wifiRecoverySlotID = slot
    wifiReconnectAttempt = 0
    let predecessor = retirementTask
    wifiReconnectTask = Task { @MainActor in
      defer {
        if self.wifiRecoverySlotID == slot {
          self.wifiReconnectTask = nil
          self.wifiRecoverySlotID = nil
          self.wifiReconnectAttempt = 0
        }
      }
      #if DEBUG
        await self.testWiFiRecoveryEntryHandler?()
      #endif
      guard self.owns(request), self.wifiRecoverySlotID == slot, !Task.isCancelled else { return }
      await predecessor?.value
      guard self.owns(request), self.wifiRecoverySlotID == slot, !Task.isCancelled else { return }
      let started = ContinuousClock.now
      let limit: Duration
      #if DEBUG
        limit = self.testWiFiMaxReconnectDuration ?? Self.wifiMaxReconnectDuration
      #else
        limit = Self.wifiMaxReconnectDuration
      #endif
      while !Task.isCancelled, self.owns(request), self.wifiRecoverySlotID == slot,
            self.connectionIntent.wantsConnection, !self.powerOffBlocksReconnect {
        if ContinuousClock.now - started > limit { break }
        self.wifiReconnectAttempt += 1
        let retirement = self.retirementTask
        await retirement?.value
        guard self.owns(request), self.wifiRecoverySlotID == slot, !Task.isCancelled,
              self.connectionIntent.wantsConnection, !self.powerOffBlocksReconnect else { return }
        do {
          let result = try await self.establishAttempt(request, preservePhysicalBLE: false)
          if case .operational = result { return }
          guard self.owns(request) else { return }
        } catch {
          guard self.owns(request), !Task.isCancelled else { return }
          self.logger.warning("WiFi reconnect failed: \(error.localizedDescription)")
        }
        let delay = min(Self.wifiReconnectBaseDelay * pow(2, Double(self.wifiReconnectAttempt - 1)), Self.wifiReconnectMaxDelay)
        do { try await Task.sleep(for: .seconds(delay)) } catch { return }
      }
      guard self.owns(request), !Task.isCancelled else { return }
      Task { @MainActor in await self.finishWiFiRecovery(request) }
    }
  }

  static let wifiReconnectBaseDelay: TimeInterval = 0.5
  static let wifiReconnectMaxDelay: TimeInterval = 4

  private func finishWiFiRecovery(_ request: ConnectionRequest) async {
    guard owns(request), installedLink == nil else { return }
    let barrier = beginOwnedRetirement(previousRequest: request, phase: .disconnected, closePhysical: true, retainMembership: true)
    await barrier.value
    guard isCurrent(barrier.receipt) else { return }
    await onConnectionLost?(barrier.receipt)
  }

  public func checkWiFiConnectionHealth() async {
    if wifiReconnectTask != nil || shouldDeferOpportunisticReconnect { return }
    if let link = installedLink, let socket = link.resource.wifi {
      let connected = await socket.isConnected
      guard installedLink === link else { return }
      if !connected { await handleWiFiDisconnection(error: nil, remoteLossAt: nil, source: socket) }
      return
    }
    guard connectionState == .disconnected, connectionIntent.wantsConnection, connectionTeardownCount == 0 else { return }
    let observed = desiredRequest?.id
    let host: String
    let port: UInt16
    if let request = desiredRequest {
      guard case let .wifi(requestHost, requestPort) = request.target else { return }
      host = requestHost
      port = requestPort
    } else {
      guard let deviceID = lastConnectedDeviceID,
            let device = try? await persistenceStore.fetchDevice(id: deviceID),
            case let .wifi(savedHost, savedPort, _) = device.lastUsedConnectionMethod else { return }
      host = savedHost
      port = savedPort
    }
    guard desiredRequest?.id == observed, !shouldDeferOpportunisticReconnect,
          connectionTeardownCount == 0, connectionState == .disconnected, connectionIntent.wantsConnection else { return }
    do {
      _ = try await requestConnection(targets: [.wifi(host: host, port: port)], origin: .recovery, forceFullSync: false, forceReconnect: false)
    } catch { logger.warning("WiFi foreground reconnect failed: \(error.localizedDescription)") }
  }

  func wifiDeviceID(publicKey: Data) async -> UUID {
    let rows = await (try? persistenceStore.fetchDevices(publicKey: publicKey)) ?? []
    if let row = rows.first(where: { $0.bluetoothPeripheralID != nil }) ?? rows.first { return row.bluetoothPeripheralID ?? row.id }
    return DeviceIdentity.deriveUUID(from: publicKey)
  }
}
