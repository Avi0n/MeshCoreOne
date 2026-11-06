import Foundation
import MeshCore

extension ConnectionManager {
  struct PreparedConnection: Sendable {
    let services: ServiceContainer
    let device: DeviceDTO
    let repeatRanges: [MeshCore.FrequencyRange]
  }

  func enqueueMetadata<T: Sendable>(
    _ operation: @escaping @MainActor @Sendable (PersistenceStore) async throws -> T
  ) -> Task<T, Error> {
    let predecessor = metadataCommitTask
    let store = persistenceStore
    let work = Task {
      await predecessor?.value
      return try await operation(store)
    }
    metadataCommitTask = Task { _ = await work.result }
    return work
  }

  func prepareConnection(
    _ request: ConnectionRequest,
    attempt: ConnectionAttempt,
    resource: ConnectionResource,
    selfInfo: SelfInfo,
    capabilities: DeviceCapabilities
  ) async throws -> PreparedConnection {
    attempt.identity.publicKey = selfInfo.publicKey
    try requireAttemptOwnership(request, attempt)
    var deviceID: UUID = if let bluetoothID = attempt.target.deviceID {
      bluetoothID
    } else {
      await wifiDeviceID(publicKey: selfInfo.publicKey)
    }
    try requireAttemptOwnership(request, attempt)
    let byID = try await persistenceStore.fetchDevice(id: deviceID)
    try requireAttemptOwnership(request, attempt)
    let existing: DeviceDTO? = if let byID { byID } else { try await persistenceStore.fetchDevice(publicKey: selfInfo.publicKey) }
    attempt.identity.deviceID = existing?.id ?? deviceID
    attempt.identity.radioID = existing?.radioID
    try requireAttemptOwnership(request, attempt)
    let reconciled = try await reconcilePendingIdentity(
      request: request, attempt: attempt, deviceID: deviceID, selfInfo: selfInfo
    )
    try requireAttemptOwnership(request, attempt)
    if reconciled, attempt.target.transportType == .wifi {
      deviceID = await wifiDeviceID(publicKey: selfInfo.publicKey)
      try requireAttemptOwnership(request, attempt)
    }
    let canonicalExisting = try await persistenceStore.fetchDevice(id: deviceID)
    attempt.identity.deviceID = canonicalExisting?.id ?? deviceID
    attempt.identity.radioID = canonicalExisting?.radioID ?? existing?.radioID
    try requireAttemptOwnership(request, attempt)
    let config = await (try? resource.session.getAutoAddConfig()) ?? AutoAddConfig(bitmask: 0)
    try requireAttemptOwnership(request, attempt)
    let observation = DeviceDTO(from: createDevice(
      deviceID: deviceID, radioID: canonicalExisting?.radioID ?? existing?.radioID ?? UUID(), selfInfo: selfInfo,
      capabilities: capabilities, autoAddConfig: config, existingDevice: canonicalExisting ?? existing
    ))
    let bluetoothPeripheralID = attempt.target.deviceID
    let registration = enqueueMetadata { store in
      try await store.registerConnectionDevice(observation, bluetoothPeripheralID: bluetoothPeripheralID)
    }
    let device = try await registration.value
    attempt.identity = ConnectionDeviceIdentity(device)
    try requireAttemptOwnership(request, attempt)
    if membershipBatch?.radioID != device.radioID { membershipBatch = nil }
    let ranges = await capabilities.clientRepeat ? ((try? resource.session.getRepeatFreq()) ?? []) : []
    try requireAttemptOwnership(request, attempt)
    let container = try await buildServiceContainer(
      request, attempt: attempt, resource: resource, device: device
    )
    return PreparedConnection(services: container, device: device, repeatRanges: ranges)
  }

  func buildServiceContainer(
    _ request: ConnectionRequest,
    attempt: ConnectionAttempt,
    resource: ConnectionResource,
    device: DeviceDTO
  ) async throws -> ServiceContainer {
    let wifiState = radioWiFiState(for: device.radioID)
    wifiState.openAdmission()
    let container = ServiceContainer(
      session: resource.session, dataStore: persistenceStore, radioID: device.radioID,
      appStateProvider: appStateProvider, connectionStateEvents: connectionStateEvents,
      initialConnectionState: .connected, radioWiFiState: wifiState,
      notificationService: notificationServiceForTesting(), ackEvents: attempt.ackEvents
    )
    attempt.preparedServices = container
    await wireCleanChannelSyncCallback(on: container)
    try requireAttemptOwnership(request, attempt)
    await container.nodeConfigService.setIdentityImportHandler { [weak self, weak container] key in
      guard let self, let container else { throw CancellationError() }
      return try await self.importIdentity(key, expectedServices: container, deviceID: device.id)
    }
    try requireAttemptOwnership(request, attempt)
    try await container.warmUp()
    try requireAttemptOwnership(request, attempt)
    return container
  }

  func commitConnection(
    _ request: ConnectionRequest,
    attempt: ConnectionAttempt,
    link: ConnectionLink,
    repeatRanges: [MeshCore.FrequencyRange]
  ) async throws {
    try requireAttemptOwnership(request, attempt)
    let id = link.device.id
    let method = link.target.method
    let selection = enqueueMetadata { store in
      #if DEBUG
        try await self.testConnectionSelectionHandler?(request.id)
      #endif
      return try await store.selectConnectionDevice(id: id, method: method)
    }
    link.device = try await selection.value
    try requireAttemptOwnership(request, attempt)
    persistConnection(deviceID: id, radioID: link.device.radioID, deviceName: link.device.nodeName)
    try publish(link, repeatRanges: repeatRanges)
    request.committedGeneration = link.generation
    attempt.resource = nil
    attempt.preparedServices = nil
  }

  // MARK: - Session Helpers

  private static let sessionInitializationTimeout: Duration = .seconds(10)

  private static let floodScopeBaselineMinimumFirmwareVersion: UInt8 = 8

  /// Starts a session and queries device capabilities.
  func initializeSession(
    _ session: MeshCoreSession,
    attempt: ConnectionAttempt? = nil
  ) async throws -> (SelfInfo, DeviceCapabilities) {
    if let attempt {
      attempt.ackEvents = await session.events(filter: .anyAcknowledgement)
    }
    do {
      try await withTimeout(Self.sessionInitializationTimeout, operationName: "session.start") {
        try await session.start(disconnectTransportOnFailure: false)
      }
    } catch {
      logger.warning("[BLE] session.start() timed out or failed: \(error.localizedDescription)")
      throw error
    }

    guard let selfInfo = await session.currentSelfInfo else {
      logger.warning("[BLE] selfInfo is nil after session.start()")
      throw ConnectionError.initializationFailed("Failed to get device self info")
    }
    if let attempt {
      attempt.identity.publicKey = selfInfo.publicKey
      guard let request = desiredRequest else { throw CancellationError() }
      try requireAttemptOwnership(request, attempt)
    }
    let capabilities: DeviceCapabilities
    do {
      capabilities = try await withTimeout(Self.sessionInitializationTimeout, operationName: "queryDevice") {
        try await session.queryDevice()
      }
    } catch {
      logger.warning("[BLE] queryDevice() timed out or failed: \(error.localizedDescription)")
      throw error
    }
    if let attempt {
      guard let request = desiredRequest else { throw CancellationError() }
      try requireAttemptOwnership(request, attempt)
    }
    // Clear an override left by a previous connection before publishing the session.
    if capabilities.firmwareVersion >= Self.floodScopeBaselineMinimumFirmwareVersion {
      do {
        try await withTimeout(Self.sessionInitializationTimeout, operationName: "floodScopeBaseline") {
          try await session.setFloodScope(scopeKey: FloodScope.disabled.scopeKey())
        }
      } catch {
        logger.warning("[BLE] flood scope baseline timed out or failed: \(error.localizedDescription)")
        throw error
      }
      if let attempt {
        guard let request = desiredRequest else { throw CancellationError() }
        try requireAttemptOwnership(request, attempt)
      }
    }
    return (selfInfo, capabilities)
  }

  // MARK: - Service Wiring Helpers

  /// Records clean channel sync completion for the next connection's sync policy.
  func wireCleanChannelSyncCallback(on services: ServiceContainer) async {
    await services.syncCoordinator.setCleanChannelSyncCallback { [weak self, weak services] radioID in
      await MainActor.run {
        guard let self, let services, self.services === services,
              self.connectedDevice?.radioID == radioID else { return }
        self.lastCleanChannelSync = (radioID: radioID, completedAt: Date())
      }
    }
    await services.syncCoordinator.setChannelSyncAttemptedCallback { [weak self, weak services] radioID in
      await MainActor.run {
        guard let self, let services, self.services === services,
              self.connectedDevice?.radioID == radioID else { return }
        self.lastAttemptedChannelSync = (radioID: radioID, attemptedAt: Date())
      }
    }
  }

  /// Uses the same resolved Radio ID as `ServiceContainer` so persisted Device and
  /// `PendingSend` rows share a partition, including on first pair.
  func createDevice(
    deviceID: UUID,
    radioID: UUID,
    selfInfo: MeshCore.SelfInfo,
    capabilities: MeshCore.DeviceCapabilities,
    autoAddConfig: MeshCore.AutoAddConfig,
    existingDevice: DeviceDTO? = nil,
    connectionMethods: [ConnectionMethod] = []
  ) -> Device {
    // Merge new connection methods with existing ones, replacing by transport type
    var mergedMethods = ConnectionMethod.canonicalized(existingDevice?.connectionMethods ?? [])
    for method in connectionMethods {
      if method.isWiFi {
        mergedMethods.removeAll { $0.isWiFi }
      } else if method.isBluetooth {
        mergedMethods.removeAll { $0.isBluetooth }
      }
      mergedMethods.append(method)
    }

    let device = Device(
      id: deviceID,
      radioID: radioID,
      publicKey: selfInfo.publicKey,
      nodeName: selfInfo.name,
      firmwareVersion: capabilities.firmwareVersion,
      firmwareVersionString: capabilities.version,
      manufacturerName: capabilities.model,
      buildDate: capabilities.firmwareBuild,
      maxContacts: UInt16(capabilities.maxContacts),
      maxChannels: UInt8(min(capabilities.maxChannels, 255)),
      frequency: UInt32(selfInfo.radioFrequency * 1000), // Convert MHz to kHz
      bandwidth: UInt32(selfInfo.radioBandwidth * 1000), // Convert kHz to Hz
      spreadingFactor: selfInfo.radioSpreadingFactor,
      codingRate: selfInfo.radioCodingRate,
      txPower: selfInfo.txPower,
      maxTxPower: selfInfo.maxTxPower,
      latitude: selfInfo.latitude,
      longitude: selfInfo.longitude,
      blePin: capabilities.blePin,
      clientRepeat: capabilities.clientRepeat,
      pathHashMode: capabilities.pathHashMode,
      defaultFloodScopeName: existingDevice?.defaultFloodScopeName,
      preRepeatFrequency: existingDevice?.preRepeatFrequency,
      preRepeatBandwidth: existingDevice?.preRepeatBandwidth,
      preRepeatSpreadingFactor: existingDevice?.preRepeatSpreadingFactor,
      preRepeatCodingRate: existingDevice?.preRepeatCodingRate,
      manualAddContacts: selfInfo.manualAddContacts,
      autoAddConfig: autoAddConfig.bitmask,
      autoAddMaxHops: autoAddConfig.maxHops,
      multiAcks: selfInfo.multiAcks,
      telemetryModeBase: selfInfo.telemetryModeBase,
      telemetryModeLoc: selfInfo.telemetryModeLocation,
      telemetryModeEnv: selfInfo.telemetryModeEnvironment,
      advertLocationPolicy: selfInfo.advertisementLocationPolicy,
      lastConnected: Date(),
      lastContactSync: existingDevice?.lastContactSync ?? 0,
      isActive: true,
      ocvPreset: existingDevice?.ocvPreset
        ?? OCVPreset.preset(forManufacturer: capabilities.model)?.rawValue,
      appliedRadioPresetID: existingDevice?.appliedRadioPresetID,
      customOCVArrayString: existingDevice?.customOCVArrayString,
      connectionMethods: mergedMethods,
      knownRegions: existingDevice?.knownRegions ?? []
    )

    // If repeat mode was disabled externally, clear orphaned pre-repeat settings
    if !capabilities.clientRepeat, existingDevice?.preRepeatFrequency != nil {
      device.preRepeatFrequency = nil
      device.preRepeatBandwidth = nil
      device.preRepeatSpreadingFactor = nil
      device.preRepeatCodingRate = nil
    }

    return device
  }
}
