import Foundation
import MeshCore

// MARK: - Verified Settings Methods

public extension SettingsService {
  /// Set node name with verification
  /// Returns the verified self info for UI update
  func setNodeNameVerified(_ name: String) async throws -> MeshCore.SelfInfo {
    let truncated = name.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)
    try await setNodeName(truncated)

    let selfInfo = try await getSelfInfo()

    guard selfInfo.name == truncated else {
      throw SettingsServiceError.verificationFailed(
        expected: truncated,
        actual: selfInfo.name
      )
    }

    eventContinuation?.yield(.nameUpdated(truncated))
    return selfInfo
  }

  /// Set location with verification
  func setLocationVerified(latitude: Double, longitude: Double) async throws -> MeshCore.SelfInfo {
    try await coordinateSerializer.withSerialization { [self] in
      try await setLocationVerifiedOwned(latitude: latitude, longitude: longitude)
    }
  }

  /// Writes one latitude and keeps the longitude read after GPS is off.
  func setLatitudeVerified(_ latitude: Double) async throws -> MeshCore.SelfInfo {
    try await coordinateSerializer.withSerialization { [self] in
      try await disableSupportedGPS()
      let current = try await getSelfInfo()
      return try await setLocationVerifiedOwned(latitude: latitude, longitude: current.longitude)
    }
  }

  /// Writes one longitude and keeps the latitude read after GPS is off.
  func setLongitudeVerified(_ longitude: Double) async throws -> MeshCore.SelfInfo {
    try await coordinateSerializer.withSerialization { [self] in
      try await disableSupportedGPS()
      let current = try await getSelfInfo()
      return try await setLocationVerifiedOwned(latitude: current.latitude, longitude: longitude)
    }
  }

  private func setLocationVerifiedOwned(latitude: Double, longitude: Double) async throws -> MeshCore.SelfInfo {
    // Calculate the scaled values we're actually sending
    let scaledLatSent = Int32(latitude * 1_000_000)
    let scaledLonSent = Int32(longitude * 1_000_000)

    // log when attempting to clear location
    let isClearingLocation = scaledLatSent == 0 && scaledLonSent == 0
    logger.debug("[Location] setLocationVerified called - lat: \(latitude), lon: \(longitude), isClearing: \(isClearingLocation)")

    try await setLocation(latitude: latitude, longitude: longitude)

    // Read back and compare at scaled integer level for precise diagnostics
    let selfInfo = try await getSelfInfo()
    let scaledLatReceived = Int32(selfInfo.latitude * 1_000_000)
    let scaledLonReceived = Int32(selfInfo.longitude * 1_000_000)

    let latDiff = abs(scaledLatSent - scaledLatReceived)
    let lonDiff = abs(scaledLonSent - scaledLonReceived)

    // Tolerance of 2 scaled units (~0.2m) handles floating-point conversion
    let tolerance: Int32 = 2

    guard latDiff <= tolerance, lonDiff <= tolerance else {
      logger.error("[Location] Verification failed - sent: (\(scaledLatSent), \(scaledLonSent)), received: (\(scaledLatReceived), \(scaledLonReceived)), diff: (lat=\(latDiff), lon=\(lonDiff))")

      if isClearingLocation {
        logger.warning("[Location] Clear location failed - device reports non-zero coordinates. Device may have active GPS or firmware doesn't support (0,0).")
      }

      let expectedLat = Double(scaledLatSent) / 1_000_000
      let expectedLon = Double(scaledLonSent) / 1_000_000
      throw SettingsServiceError.verificationFailed(
        expected: "(\(expectedLat), \(expectedLon))",
        actual: "(\(selfInfo.latitude), \(selfInfo.longitude))"
      )
    }

    eventContinuation?.yield(.coordinatesUpdated(latitude: latitude, longitude: longitude))
    return selfInfo
  }

  /// Set a manual location, turning off device GPS first when needed so the value persists.
  func setManualLocationVerified(latitude: Double, longitude: Double) async throws -> MeshCore.SelfInfo {
    try await coordinateSerializer.withSerialization { [self] in
      try await disableSupportedGPS()
      return try await setLocationVerifiedOwned(latitude: latitude, longitude: longitude)
    }
  }

  /// Turns supported GPS off before a manual coordinate write. The caller holds `coordinateSerializer`.
  private func disableSupportedGPS() async throws {
    let gpsState = try await getDeviceGPSState()
    if gpsState.isSupported, gpsState.isEnabled {
      _ = try await setDeviceGPSEnabledVerified(false)
    }
  }

  /// Verified radio parameters. `frequencyKHz` is kHz and `bandwidthKHz` is Hz, matching `setRadioParams`.
  /// `preserveLiveRepeat` reads the live repeat flag after this owns the RF operation and keeps the current frequency while repeat is on.
  func setRadioParamsVerified(
    frequencyKHz: UInt32,
    bandwidthKHz: UInt32,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    clientRepeat: Bool? = nil,
    appliedRadioPresetID: String? = nil,
    preserveLiveRepeat: Bool = false
  ) async throws -> MeshCore.SelfInfo {
    try await radioSerializer.withSerialization {
      var frequency = frequencyKHz
      var repeatWrite = clientRepeat
      if preserveLiveRepeat {
        let repeating = try await self.queryDevice().clientRepeat
        repeatWrite = repeating
        if repeating {
          let current = try await self.getSelfInfo()
          frequency = UInt32((current.radioFrequency * 1000).rounded())
        }
      }
      return try await self.setRadioParamsVerifiedOwned(
        frequencyKHz: frequency,
        bandwidthKHz: bandwidthKHz,
        spreadingFactor: spreadingFactor,
        codingRate: codingRate,
        clientRepeat: repeatWrite,
        catalogStamp: appliedRadioPresetID.map { .set($0) } ?? .reconcile
      )
    }
  }

  /// Changes the center frequency and keeps the radio's current bandwidth, spreading factor, and coding rate.
  /// Those siblings are read after this call owns the RF operation, so an older snapshot cannot replace them.
  func setRadioFrequencyVerified(
    frequencyKHz: UInt32,
    clientRepeat: Bool? = nil
  ) async throws -> MeshCore.SelfInfo {
    try await radioSerializer.withSerialization { [self] in
      let current = try await getSelfInfo()
      let bandwidthHz = UInt32((current.radioBandwidth * 1000).rounded())
      return try await setRadioParamsVerifiedOwned(
        frequencyKHz: frequencyKHz,
        bandwidthKHz: bandwidthHz,
        spreadingFactor: current.radioSpreadingFactor,
        codingRate: current.radioCodingRate,
        clientRepeat: clientRepeat
      )
    }
  }

  /// Apply radio preset with verification
  func applyRadioPresetVerified(_ preset: RadioPreset) async throws -> MeshCore.SelfInfo {
    try await radioSerializer.withSerialization { [self] in
      try await applyRadioPresetOwned(preset)
    }
  }

  private func applyRadioPresetOwned(_ preset: RadioPreset) async throws -> MeshCore.SelfInfo {
    logger.info("[Radio] Applying preset: \(preset.name) (\(preset.id))")
    let selfInfo = try await setRadioParamsAndVerify(
      frequencyKHz: preset.frequencyKHz,
      bandwidthKHz: preset.bandwidthHz,
      spreadingFactor: preset.spreadingFactor,
      codingRate: preset.codingRate
    )
    let change = radioChange(
      frequencyKHz: preset.frequencyKHz,
      bandwidthHz: preset.bandwidthHz,
      spreadingFactor: preset.spreadingFactor,
      codingRate: preset.codingRate,
      catalogStamp: .reconcile
    )
    if let mode = preset.pathHashMode {
      do {
        let capabilities = try await queryDevice()
        if capabilities.supportsPathHashMode {
          _ = try await setPathHashModeVerified(mode)
        }
      } catch {
        eventContinuation?.yield(.radioUpdated(change))
        throw error
      }
    }
    // Stamp the catalog id only after the optional path-hash write, so a failed
    // hash write cannot persist a catalog id over RF-equal aliases.
    eventContinuation?.yield(.radioUpdated(radioChange(
      frequencyKHz: preset.frequencyKHz,
      bandwidthHz: preset.bandwidthHz,
      spreadingFactor: preset.spreadingFactor,
      codingRate: preset.codingRate,
      catalogStamp: .set(preset.id)
    )))
    return selfInfo
  }

  private func setRadioParamsVerifiedOwned(
    frequencyKHz: UInt32,
    bandwidthKHz: UInt32,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    clientRepeat: Bool? = nil,
    catalogStamp: SettingsEvent.CatalogStamp = .reconcile
  ) async throws -> MeshCore.SelfInfo {
    let selfInfo = try await setRadioParamsAndVerify(
      frequencyKHz: frequencyKHz,
      bandwidthKHz: bandwidthKHz,
      spreadingFactor: spreadingFactor,
      codingRate: codingRate,
      clientRepeat: clientRepeat
    )
    eventContinuation?.yield(.radioUpdated(radioChange(
      frequencyKHz: frequencyKHz,
      bandwidthHz: bandwidthKHz,
      spreadingFactor: spreadingFactor,
      codingRate: codingRate,
      catalogStamp: catalogStamp
    )))
    return selfInfo
  }

  /// Set TX power with verification
  func setTxPowerVerified(_ power: Int8) async throws -> MeshCore.SelfInfo {
    logger.info("[Radio] Sending TX power: \(power)dBm")

    try await setTxPower(power)

    let selfInfo = try await getSelfInfo()

    guard selfInfo.txPower == power else {
      logger.warning("[Radio] TX power verification failed - expected: \(power)dBm, device reports: \(selfInfo.txPower)dBm")
      throw SettingsServiceError.verificationFailed(
        expected: "\(power)",
        actual: "\(selfInfo.txPower)"
      )
    }

    logger.info("[Radio] TX power verified: \(power)dBm")
    eventContinuation?.yield(.txPowerUpdated(power))
    return selfInfo
  }

  /// Set other params with verification
  func setOtherParamsVerified(
    autoAddContacts: Bool,
    telemetryModes: TelemetryModes,
    advertLocationPolicy: AdvertLocationPolicy,
    multiAcks: UInt8
  ) async throws -> MeshCore.SelfInfo {
    let intent = OtherParamsIntent(
      manualAddContacts: !autoAddContacts,
      telemetryModeBase: telemetryModes.base,
      telemetryModeLocation: telemetryModes.location,
      telemetryModeEnvironment: telemetryModes.environment,
      advertisementLocationPolicy: advertLocationPolicy.rawValue,
      multiAcks: multiAcks
    ).normalized()
    let modes = TelemetryModes(
      base: intent.telemetryModeBase ?? 0,
      location: intent.telemetryModeLocation ?? 0,
      environment: intent.telemetryModeEnvironment ?? 0
    )
    try await setOtherParams(
      autoAddContacts: autoAddContacts,
      telemetryModes: modes,
      advertLocationPolicy: advertLocationPolicy,
      multiAcks: multiAcks
    )

    let selfInfo = try await getSelfInfo()

    // manualAddContacts is inverted (false = auto-add enabled)
    guard selfInfo.manualAddContacts != autoAddContacts else {
      throw SettingsServiceError.verificationFailed(
        expected: "autoAdd=\(autoAddContacts)",
        actual: "autoAdd=\(!selfInfo.manualAddContacts)"
      )
    }

    eventContinuation?.yield(.otherParamsUpdated(intent))
    return selfInfo
  }

  /// Writes only the fields the caller supplies. Unspecified siblings are read inside the session serializer.
  func setOtherParamsVerified(
    autoAddContacts: Bool? = nil,
    telemetryModeBase: UInt8? = nil,
    telemetryModeLocation: UInt8? = nil,
    telemetryModeEnvironment: UInt8? = nil,
    advertLocationPolicy: AdvertLocationPolicy? = nil,
    advertLocationPolicyRaw: UInt8? = nil,
    multiAcks: UInt8? = nil
  ) async throws {
    let intent = OtherParamsIntent(
      manualAddContacts: autoAddContacts.map { !$0 },
      telemetryModeBase: telemetryModeBase,
      telemetryModeLocation: telemetryModeLocation,
      telemetryModeEnvironment: telemetryModeEnvironment,
      advertisementLocationPolicy: advertLocationPolicyRaw ?? advertLocationPolicy?.rawValue,
      multiAcks: multiAcks
    ).normalized()
    guard !intent.isEmpty else { return }
    try await applyOtherParams(intent)
    eventContinuation?.yield(.otherParamsUpdated(intent))
  }

  /// Compatibility overload: map boolean sharing to `prefs` policy when enabled.
  @available(*, deprecated, message: "Use advertLocationPolicy overload instead")
  func setOtherParamsVerified(
    autoAddContacts: Bool,
    telemetryModes: TelemetryModes,
    shareLocationPublicly: Bool,
    multiAcks: UInt8
  ) async throws -> MeshCore.SelfInfo {
    try await setOtherParamsVerified(
      autoAddContacts: autoAddContacts,
      telemetryModes: telemetryModes,
      advertLocationPolicy: shareLocationPublicly ? .prefs : .none,
      multiAcks: multiAcks
    )
  }
}

extension SettingsService {
  /// Compares an imported radio tuple after this call holds the RF owner.
  /// An unchanged tuple sends no command; both outcomes publish `.reconcile` before release.
  func applyImportedRadioParams(_ radio: MeshCoreNodeConfig.RadioSettings) async throws {
    try await radioSerializer.withSerialization { [self] in
      try await applyImportedRadioParamsOwned(radio)
    }
  }

  private func applyImportedRadioParamsOwned(_ radio: MeshCoreNodeConfig.RadioSettings) async throws {
    let current = try await getSelfInfo()
    let comparable = MeshCoreNodeConfig.RadioSettings(
      frequency: radio.frequency,
      bandwidth: radio.bandwidth,
      spreadingFactor: radio.spreadingFactor,
      codingRate: radio.codingRate,
      txPower: current.txPower
    )
    guard radioParamsNeedWrite(comparable, current: current) else {
      eventContinuation?.yield(.radioUpdated(radioChange(
        frequencyKHz: radio.frequency,
        bandwidthHz: radio.bandwidth,
        spreadingFactor: radio.spreadingFactor,
        codingRate: radio.codingRate,
        catalogStamp: .reconcile
      )))
      return
    }
    _ = try await setRadioParamsVerifiedOwned(
      frequencyKHz: radio.frequency,
      bandwidthKHz: radio.bandwidth,
      spreadingFactor: radio.spreadingFactor,
      codingRate: radio.codingRate,
      catalogStamp: .reconcile
    )
  }
}

private extension SettingsService {
  /// Writes and verifies RF. The caller publishes the radio change.
  func setRadioParamsAndVerify(
    frequencyKHz: UInt32,
    bandwidthKHz: UInt32,
    spreadingFactor: UInt8,
    codingRate: UInt8,
    clientRepeat: Bool? = nil
  ) async throws -> MeshCore.SelfInfo {
    logger.info("[Radio] Sending params: freq=\(frequencyKHz)kHz, bw=\(bandwidthKHz)Hz, sf=\(spreadingFactor), cr=\(codingRate), repeat=\(String(describing: clientRepeat))")

    try await writeRadioParams(
      frequencyKHz: frequencyKHz,
      bandwidthKHz: bandwidthKHz,
      spreadingFactor: spreadingFactor,
      codingRate: codingRate,
      clientRepeat: clientRepeat
    )

    let selfInfo = try await getSelfInfo()

    let expectedFreqMHz = Double(frequencyKHz) / 1000.0
    let expectedBwMHz = Double(bandwidthKHz) / 1000.0

    guard abs(selfInfo.radioFrequency - expectedFreqMHz) < 0.001,
          abs(selfInfo.radioBandwidth - expectedBwMHz) < 0.001,
          selfInfo.radioSpreadingFactor == spreadingFactor,
          selfInfo.radioCodingRate == codingRate else {
      logger
        .warning(
          // swiftlint:disable:next line_length
          "[Radio] Verification failed - expected: freq=\(expectedFreqMHz)MHz, bw=\(expectedBwMHz)kHz, sf=\(spreadingFactor), cr=\(codingRate); device reports: freq=\(selfInfo.radioFrequency)MHz, bw=\(selfInfo.radioBandwidth)kHz, sf=\(selfInfo.radioSpreadingFactor), cr=\(selfInfo.radioCodingRate)"
        )
      throw SettingsServiceError.verificationFailed(
        expected: "freq=\(frequencyKHz), bw=\(bandwidthKHz), sf=\(spreadingFactor), cr=\(codingRate)",
        actual: "freq=\(selfInfo.radioFrequency), bw=\(selfInfo.radioBandwidth), sf=\(selfInfo.radioSpreadingFactor), cr=\(selfInfo.radioCodingRate)"
      )
    }

    if let expectedRepeat = clientRepeat {
      let capabilities = try await queryDevice()
      guard capabilities.clientRepeat == expectedRepeat else {
        logger.warning("[Radio] Client repeat verification failed - expected: \(expectedRepeat), device reports: \(capabilities.clientRepeat)")
        throw SettingsServiceError.verificationFailed(
          expected: "clientRepeat=\(expectedRepeat)",
          actual: "clientRepeat=\(capabilities.clientRepeat)"
        )
      }
      logger.info("[Radio] Client repeat verified: \(expectedRepeat)")
      eventContinuation?.yield(.clientRepeatUpdated(expectedRepeat))
    }

    logger.info("[Radio] Params verified successfully")
    return selfInfo
  }
}

private func radioChange(
  frequencyKHz: UInt32,
  bandwidthHz: UInt32,
  spreadingFactor: UInt8,
  codingRate: UInt8,
  catalogStamp: SettingsEvent.CatalogStamp
) -> SettingsEvent.RadioChange {
  SettingsEvent.RadioChange(
    frequencyKHz: frequencyKHz,
    bandwidthHz: bandwidthHz,
    spreadingFactor: spreadingFactor,
    codingRate: codingRate,
    catalogStamp: catalogStamp
  )
}
