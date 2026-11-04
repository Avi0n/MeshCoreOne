import Foundation
import MeshCore

public extension ConnectionManager {
  @discardableResult
  func connect(to deviceID: UUID, forceFullSync: Bool = false, forceReconnect: Bool = false) async throws -> ConnectionResult {
    try Task.checkCancellation()
    return try await requestConnection(
      targets: [.bluetooth(deviceID)], origin: .explicit, forceFullSync: forceFullSync, forceReconnect: forceReconnect
    )
  }

  @discardableResult
  func connectViaBluetooth(peripheralID: UUID, forceFullSync: Bool = false) async throws -> ConnectionResult {
    try await connect(to: peripheralID, forceFullSync: forceFullSync, forceReconnect: true)
  }

  @discardableResult
  func connectViaWiFi(host: String, port: UInt16, forceFullSync: Bool = false) async throws -> ConnectionResult {
    let normalized = host.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !normalized.isEmpty else { throw WiFiTransportError.invalidHost }
    return try await requestConnection(
      targets: [.wifi(host: normalized, port: port)], origin: .explicit,
      forceFullSync: forceFullSync, forceReconnect: true
    )
  }

  @discardableResult
  func simulatorConnect() async throws -> ConnectionResult {
    try await requestConnection(targets: [.simulator], origin: .simulator, forceFullSync: false, forceReconnect: false)
  }

  internal func reconnectLastUsedTransport(device: DeviceDTO?, lastDeviceID: UUID) async {
    let bluetooth = device?.bluetoothPeripheralID.map { ConnectionRequest.Target.bluetooth($0) }
    let wifi: ConnectionRequest.Target? = if case let .wifi(host, port, _) = device?.wifiConnectionMethod { .wifi(host: host, port: port) } else { nil }
    let targets: [ConnectionRequest.Target] = if case .wifi = device?.lastUsedConnectionMethod {
      [wifi, bluetooth].compactMap(\.self)
    } else {
      [bluetooth ?? .bluetooth(lastDeviceID), wifi].compactMap(\.self)
    }
    do {
      _ = try await requestConnection(targets: targets, origin: .launch, forceFullSync: false, forceReconnect: false)
    } catch let failure as ConnectionRequestFailure {
      guard isCurrent(failure) else { return }
      if case BLEError.authenticationFailed = failure.underlying {
        surfaceAuthenticationFailure(deviceID: bluetooth?.deviceID ?? lastDeviceID)
      }
      if targets.contains(where: { $0.transportType == .bluetooth }) { startReconnectionWatchdog() }
    } catch {
      logger.warning("Launch reconnect failed: \(error.localizedDescription)")
    }
  }

  @discardableResult
  func disconnect(reason: DisconnectReason = .userInitiated) async -> ConnectionLossReceipt {
    let operation = beginDisconnect(reason: reason)
    let metadata = metadataCommitTask
    await operation.barrier.value
    await metadata?.value
    if isCurrent(operation.receipt) { await onConnectionLost?(operation.receipt) }
    return operation.receipt
  }

  internal func beginDisconnect(reason: DisconnectReason) -> (barrier: ConnectionRetirement, receipt: ConnectionLossReceipt) {
    let previous = desiredRequest
    switch reason {
    case .userInitiated, .statusMenuDisconnectTap, .forgetDevice, .deviceRemovedFromSettings, .factoryReset, .switchingDevice, .powerOff:
      connectionIntent = .userDisconnected
      surfacedAuthFailureDeviceID = nil
      lastCleanChannelSync = nil
      lastAttemptedChannelSync = nil
    case .resyncFailed, .wifiAddressChange, .wifiReconnectPrep, .bluetoothConnectPrep, .pairingFailed:
      break
    }
    let target = connectionIntent.wantsConnection ? previous?.target ?? .disconnected : .disconnected
    let terminal = ConnectionRequest(
      targets: [target], origin: .explicit, forceFullSync: false, forceReconnect: false,
      continuing: connectionIntent.wantsConnection ? previous : nil
    )
    desiredRequest = terminal
    logLifecycle("disconnectAdmitted", context: lifecycleContext(request: terminal, attempt: nil, link: nil), reason: reason.rawValue)
    previous?.worker?.cancel()
    previous?.binding?.revoke()
    if reason != .powerOff { invalidatePowerOffForConnectionChange() }
    cancelExpectedWiFiReboots()
    reconnectionCoordinator.clear()
    cancelWiFiReconnection()
    stopReconnectionWatchdog()
    persistIntent()
    let barrier = beginOwnedRetirement(
      previousRequest: previous, phase: .disconnected, closePhysical: true, retainMembership: false, closeUnboundPhysical: true
    )
    _ = enqueueMetadata { store in try await store.clearActiveConnection() }
    return (barrier, barrier.receipt)
  }

  internal func establishSimulator(_ request: ConnectionRequest) async throws -> ConnectionResult {
    let attempt = ConnectionAttempt(target: .simulator)
    installAttempt(attempt, on: request, ownedByAdmissionWorker: true)
    do {
      await simulatorMode.connect()
      try requireAttemptOwnership(request, attempt)
      let physical = SimulatorMockTransport()
      let binding = attempt.binding
      let session = makeBoundSession(
        transport: SessionBoundTransport(transport: physical, binding: binding),
        scope: request.radioCLICorrelationScope
      )
      let resource = ConnectionResource(binding: binding, transport: physical, session: session, wifi: nil, ble: nil)
      attempt.resource = resource
      let container = ServiceContainer(
        session: session, dataStore: persistenceStore, radioID: MockDataProvider.simulatorDeviceID,
        appStateProvider: appStateProvider, connectionStateEvents: connectionStateEvents,
        initialConnectionState: .connected, radioWiFiState: radioWiFiState(for: MockDataProvider.simulatorDeviceID),
        notificationService: notificationServiceForTesting()
      )
      attempt.preparedServices = container
      try await simulatorMode.seedDataStore(persistenceStore)
      try requireAttemptOwnership(request, attempt)
      await container.chatSendQueueService.hydrate()
      try requireAttemptOwnership(request, attempt)
      let link = ConnectionLink(
        requestID: request.id, target: .simulator, resource: resource, services: container,
        device: MockDataProvider.simulatorDevice, synthetic: true
      )
      try await commitConnection(request, attempt: attempt, link: link, repeatRanges: [])
      await onConnectionReady?(link.snapshot)
      guard owns(request), installedLink === link else { return .superseded }
      _ = await completeSync(link, syncResult: FullSyncResult(contacts: .clean, channels: .clean, messages: .clean))
      guard owns(request), installedLink === link else { return .superseded }
      return .operational(link.snapshot)
    } catch {
      await retireCandidate(request, attempt: attempt, closePhysical: true)
      throw error
    }
  }
}
