import Foundation

public extension MeshCoreSession {
  // MARK: - Device Info Commands

  /// Sends the app-start command to initialize communication with the device.
  ///
  /// This is typically called automatically by ``start()``.
  ///
  /// - Returns: Information about the device itself.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't emit `selfInfo`.
  func sendAppStart() async throws -> SelfInfo {
    let data = PacketBuilder.appStart(clientId: configuration.clientIdentifier)
    return try await sendAndWait(data) { event in
      if case let .selfInfo(info) = event { return info }
      return nil
    }
  }

  /// Queries the device for its capabilities and system information.
  ///
  /// - Returns: Information about the device hardware, firmware, and supported features.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't emit `deviceInfo`.
  func queryDevice() async throws -> DeviceCapabilities {
    let data = PacketBuilder.deviceQuery()
    return try await sendAndWait(data) { event in
      if case let .deviceInfo(info) = event { return info }
      return nil
    }
  }

  /// Retrieves the current battery status from the device.
  ///
  /// - Returns: Battery voltage and charge level information.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't emit battery info.
  func getBattery() async throws -> BatteryInfo {
    try await sendAndWait(PacketBuilder.getBattery()) { event in
      if case let .battery(info) = event { return info }
      return nil
    }
  }

  // MARK: - Device Configuration Commands

  /// Gets the current device time.
  ///
  /// - Returns: The device's current time.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func getTime() async throws -> Date {
    try await sendAndWait(PacketBuilder.getTime()) { event in
      if case let .currentTime(date) = event { return date }
      return nil
    }
  }

  /// Sets the device's current time.
  ///
  /// - Parameter date: The time to set on the device.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setTime(_ date: Date) async throws {
    try await sendSimpleCommand(PacketBuilder.setTime(date))
  }

  /// Sets the device's advertised name.
  ///
  /// - Parameter name: The name to advertise (max 32 bytes UTF-8).
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setName(_ name: String) async throws {
    try await sendSimpleCommand(PacketBuilder.setName(name))
  }

  /// Sets the device's GPS coordinates.
  ///
  /// - Parameters:
  ///   - latitude: Latitude in degrees (-90 to 90).
  ///   - longitude: Longitude in degrees (-180 to 180).
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setCoordinates(latitude: Double, longitude: Double) async throws {
    try await sendSimpleCommand(PacketBuilder.setCoordinates(latitude: latitude, longitude: longitude))
  }

  /// Sets the radio transmission power level.
  ///
  /// - Parameter power: Power level in dBm (range: -9 to 30).
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setTxPower(_ power: Int8) async throws {
    try await sendSimpleCommand(PacketBuilder.setTxPower(power))
  }

  /// Configures radio parameters for LoRa communication.
  ///
  /// - Parameters:
  ///   - frequency: Center frequency in MHz (e.g., 915.0).
  ///   - bandwidth: Signal bandwidth in kHz (e.g., 125.0, 250.0, 500.0).
  ///   - spreadingFactor: LoRa spreading factor (7-12, higher = longer range but slower).
  ///   - codingRate: Error correction coding rate (5-8).
  ///   - clientRepeat: Whether to enable client repeat mode (v9+ firmware, omitted if nil).
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setRadio(
    frequency: Double,
    bandwidth: Double,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    clientRepeat: Bool? = nil
  ) async throws {
    try await sendSimpleCommand(PacketBuilder.setRadio(
      frequency: frequency,
      bandwidth: bandwidth,
      spreadingFactor: spreadingFactor,
      codingRate: codingRate,
      clientRepeat: clientRepeat
    ))
  }

  /// Gets the allowed frequency ranges for client repeat mode (v9+ firmware).
  ///
  /// - Returns: The allowed frequency ranges for repeat mode.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't emit repeat-frequency data.
  func getRepeatFreq() async throws -> [FrequencyRange] {
    try await sendAndWait(PacketBuilder.getRepeatFreq()) { event in
      if case let .allowedRepeatFreq(ranges) = event { return ranges }
      return nil
    }
  }

  /// Configures radio timing parameters for fine-tuning.
  ///
  /// - Parameters:
  ///   - rxDelay: Receive delay in microseconds.
  ///   - af: Auto-frequency correction parameter.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setTuning(rxDelay: UInt32, af: UInt32) async throws {
    try await sendSimpleCommand(PacketBuilder.setTuning(rxDelay: rxDelay, af: af))
  }

  /// Sets miscellaneous device parameters.
  ///
  /// This is a low-level command that sets all "other params" at once.
  /// Consider using granular setters like ``setManualAddContacts(_:)`` instead.
  ///
  /// - Parameters:
  ///   - manualAddContacts: Whether contacts require manual approval before adding.
  ///   - telemetryModeEnvironment: Environment telemetry reporting mode (0-3).
  ///   - telemetryModeLocation: Location telemetry reporting mode (0-3).
  ///   - telemetryModeBase: Base telemetry reporting mode (0-3).
  ///   - advertisementLocationPolicy: Location inclusion policy for advertisements.
  ///   - multiAcks: Number of acknowledgment retries.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setOtherParams(
    manualAddContacts: Bool,
    telemetryModeEnvironment: UInt8,
    telemetryModeLocation: UInt8,
    telemetryModeBase: UInt8,
    advertisementLocationPolicy: UInt8,
    multiAcks: UInt8? = nil
  ) async throws {
    try await sendSimpleCommand(PacketBuilder.setOtherParams(
      manualAddContacts: manualAddContacts,
      telemetryModeEnvironment: telemetryModeEnvironment,
      telemetryModeLocation: telemetryModeLocation,
      telemetryModeBase: telemetryModeBase,
      advertisementLocationPolicy: advertisementLocationPolicy,
      multiAcks: multiAcks
    ))
  }

  /// Sets the device PIN for administrative access.
  ///
  /// - Parameter pin: 4-digit PIN as a 32-bit unsigned integer.
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setDevicePin(_ pin: UInt32) async throws {
    try await sendSimpleCommand(PacketBuilder.setDevicePin(pin))
  }

  // MARK: - Granular Device Configuration

  /// Sets the base telemetry mode (preserves other settings).
  ///
  /// Uses a read-modify-write pattern: reads current settings from `selfInfo`,
  /// modifies only the requested value, then writes all settings back.
  ///
  /// - Parameter mode: Telemetry mode value (0-3, higher bits are masked off).
  /// - Throws: ``MeshCoreError/sessionNotStarted`` if device info unavailable.
  func setTelemetryModeBase(_ mode: UInt8) async throws {
    try await mutateOtherParams { $0.telemetryModeBase = mode & 0b11 }
  }

  /// Sets the location telemetry mode (preserves other settings).
  ///
  /// - Parameter mode: Telemetry mode value (0-3).
  /// - Throws: ``MeshCoreError/sessionNotStarted`` if device info unavailable.
  func setTelemetryModeLocation(_ mode: UInt8) async throws {
    try await mutateOtherParams { $0.telemetryModeLocation = mode & 0b11 }
  }

  /// Sets the environment telemetry mode (preserves other settings).
  ///
  /// - Parameter mode: Telemetry mode value (0-3).
  /// - Throws: ``MeshCoreError/sessionNotStarted`` if device info unavailable.
  func setTelemetryModeEnvironment(_ mode: UInt8) async throws {
    try await mutateOtherParams { $0.telemetryModeEnvironment = mode & 0b11 }
  }

  /// Sets the manual add contacts mode (preserves other settings).
  ///
  /// When enabled, contacts discovered via advertisement must be manually approved
  /// before being added to the device's contact list.
  ///
  /// - Parameter enabled: Whether contacts must be manually approved.
  /// - Throws: ``MeshCoreError/sessionNotStarted`` if device info unavailable.
  func setManualAddContacts(_ enabled: Bool) async throws {
    try await mutateOtherParams { $0.manualAddContacts = enabled }
  }

  /// Sets the multi-acks count (preserves other settings).
  ///
  /// - Parameter count: Number of acknowledgment retries.
  /// - Throws: ``MeshCoreError/sessionNotStarted`` if device info unavailable.
  func setMultiAcks(_ count: UInt8) async throws {
    try await mutateOtherParams { $0.multiAcks = count }
  }

  /// Sets the advertisement location policy (preserves other settings).
  ///
  /// - Parameter policy: Location advertising policy value.
  /// - Throws: ``MeshCoreError/sessionNotStarted`` if device info unavailable.
  func setAdvertisementLocationPolicy(_ policy: UInt8) async throws {
    try await mutateOtherParams { $0.advertisementLocationPolicy = policy }
  }

  /// Gets the current auto-add configuration from the device.
  ///
  /// - Returns: The auto-add configuration (bitmask + max hops).
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't emit auto-add configuration.
  func getAutoAddConfig() async throws -> AutoAddConfig {
    try await sendAndWait(PacketBuilder.getAutoAddConfig()) { event in
      if case let .autoAddConfig(config) = event { return config }
      return nil
    }
  }

  /// Sets the auto-add configuration on the device.
  ///
  /// - Parameter config: The auto-add configuration (bitmask + max hops).
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  ///           ``MeshCoreError/deviceError(code:)`` if the device returns an error.
  func setAutoAddConfig(_ config: AutoAddConfig) async throws {
    try await sendSimpleCommand(PacketBuilder.setAutoAddConfig(config))
  }

  /// Serializes a granular "other params" change against any other granular setter.
  ///
  /// The read-modify-write (read the current config, apply `transform`, write it back)
  /// spans several wire exchanges. Holding `otherParamsSerializer` across the whole
  /// sequence keeps two concurrent setters from each reading the same snapshot and then
  /// reverting the other's write when they store the full config back.
  private func mutateOtherParams(
    _ transform: @escaping @Sendable (inout OtherParamsConfig) -> Void
  ) async throws {
    try await otherParamsSerializer.withSerialization { [self] in
      var config = try await currentOtherParams()
      transform(&config)
      try await applyOtherParams(config)
    }
  }

  /// Returns the current device configuration from selfInfo.
  ///
  /// - Returns: Current other params configuration.
  /// - Throws: ``MeshCoreError/sessionNotStarted`` if selfInfo unavailable after refresh.
  private func currentOtherParams() async throws -> OtherParamsConfig {
    if let info = selfInfo {
      return OtherParamsConfig(from: info)
    }

    // Refresh selfInfo if not available
    selfInfo = try await sendAppStart()
    guard let info = selfInfo else {
      throw MeshCoreError.sessionNotStarted
    }
    return OtherParamsConfig(from: info)
  }

  /// Applies other params configuration to device.
  private func applyOtherParams(_ config: OtherParamsConfig) async throws {
    try await setOtherParams(
      manualAddContacts: config.manualAddContacts,
      telemetryModeEnvironment: config.telemetryModeEnvironment,
      telemetryModeLocation: config.telemetryModeLocation,
      telemetryModeBase: config.telemetryModeBase,
      advertisementLocationPolicy: config.advertisementLocationPolicy,
      multiAcks: config.multiAcks
    )

    // Refresh selfInfo to keep cache consistent
    selfInfo = try await sendAppStart()
  }

  /// Reboots the device.
  ///
  /// Sends a reboot command to the device. The session will be disconnected.
  /// You must create a new session after the device restarts.
  ///
  /// - Throws: ``MeshTransportError`` if the command cannot be sent.
  func reboot() async throws {
    try await transport.send(PacketBuilder.reboot())
  }

  /// Retrieves telemetry data from the device.
  ///
  /// - Returns: Device telemetry including battery, temperature, and sensor data.
  ///   When `selfInfo` is available, only telemetry for the current device is accepted.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func getSelfTelemetry() async throws -> TelemetryResponse {
    let expectedPrefix = selfInfo.map { Data($0.publicKey.prefix(6)) }
    return try await sendAndWait(PacketBuilder.getSelfTelemetry()) { event in
      if case let .telemetryResponse(response) = event,
         expectedPrefix == nil || response.publicKeyPrefix == expectedPrefix {
        return response
      }
      return nil
    }
  }

  /// Retrieves all custom variables stored on the device.
  ///
  /// - Returns: Dictionary mapping variable names to values.
  /// - Throws: ``MeshCoreError/deviceError(code:)`` if the device rejects the
  ///   request (e.g. firmware predating custom-var support), or
  ///   ``MeshCoreError/timeout`` if the device doesn't respond.
  func getCustomVars() async throws -> [String: String] {
    try await sendAndWaitWithError(
      PacketBuilder.getCustomVars(),
      matching: { event in
        if case let .customVars(vars) = event { return vars }
        return nil
      },
      errorMatcher: Self.deviceErrorMatcher
    )
  }

  /// Sets a custom variable on the device.
  ///
  /// - Parameters:
  ///   - key: Variable name (max 32 bytes).
  ///   - value: Variable value (max 256 bytes).
  /// - Throws: ``MeshCoreError/timeout`` or ``MeshCoreError/deviceError(code:)`` on failure.
  func setCustomVar(key: String, value: String) async throws {
    try await sendSimpleCommand(PacketBuilder.setCustomVar(key: key, value: value))
  }

  /// Exports the device's private key.
  ///
  /// This is a sensitive operation that exposes the device's cryptographic identity.
  /// The exported key can be imported into another device to clone its identity.
  ///
  /// - Returns: The 32-byte private key.
  /// - Throws: ``MeshCoreError/featureDisabled`` if private key export is disabled on the device,
  ///   or ``MeshCoreError/timeout`` if the device doesn't respond.
  func exportPrivateKey() async throws -> Data {
    try await sendAndWaitWithError(
      PacketBuilder.exportPrivateKey()
    ) { event in
      if case let .privateKey(key) = event { return key }
      return nil
    } errorMatcher: { event in
      if case .disabled = event {
        return MeshCoreError.featureDisabled
      }
      if case let .error(code) = event {
        return MeshCoreError.deviceError(code: code ?? 0)
      }
      return nil
    }
  }

  /// Imports a private key into the device.
  ///
  /// This replaces the device's cryptographic identity and refreshes cached self info.
  /// Use with caution.
  ///
  /// - Parameter key: The 64-byte expanded private key to import.
  /// - Throws: ``MeshCoreError/featureDisabled`` if the device does not support key import,
  ///   ``MeshCoreError/timeout`` if the device does not acknowledge the import,
  ///   or ``MeshCoreError/deviceError(code:)`` for a matched device error response.
  func importPrivateKey(_ key: Data) async throws {
    guard key.count == PacketBuilder.privateKeySize else {
      throw MeshCoreError.invalidInput("Full \(PacketBuilder.privateKeySize)-byte private key required for importPrivateKey")
    }
    let succeeded: Bool = try await sendAndWaitWithError(
      PacketBuilder.importPrivateKey(key)
    ) { event in
      if case .ok(value: nil) = event { return true }
      if case .disabled = event { return false }
      return nil
    } errorMatcher: { event in
      guard case let .error(code?) = event else { return nil }
      return MeshCoreError.deviceError(code: code)
    }
    if !succeeded {
      throw MeshCoreError.featureDisabled
    }
    selfInfo = try await sendAppStart()
  }

  // MARK: - Stats Commands

  /// Retrieves core device statistics.
  ///
  /// - Returns: Core statistics including uptime and system metrics.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func getStatsCore() async throws -> CoreStats {
    try await sendAndWait(PacketBuilder.getStatsCore()) { event in
      if case let .statsCore(stats) = event { return stats }
      return nil
    }
  }

  /// Retrieves radio statistics.
  ///
  /// - Returns: Radio statistics including RSSI, SNR, and transmission counts.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func getStatsRadio() async throws -> RadioStats {
    try await sendAndWait(PacketBuilder.getStatsRadio()) { event in
      if case let .statsRadio(stats) = event { return stats }
      return nil
    }
  }

  /// Retrieves packet statistics.
  ///
  /// - Returns: Packet statistics including sent, received, and dropped counts.
  /// - Throws: ``MeshCoreError/timeout`` if the device doesn't respond.
  func getStatsPackets() async throws -> PacketStats {
    try await sendAndWait(PacketBuilder.getStatsPackets()) { event in
      if case let .statsPackets(stats) = event { return stats }
      return nil
    }
  }
}
