import Foundation
import MeshCore

// MARK: - Verified Settings Methods

extension SettingsService {

    /// Set node name with verification
    /// Returns the verified self info for UI update
    public func setNodeNameVerified(_ name: String) async throws -> MeshCore.SelfInfo {
        let truncated = name.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)
        try await setNodeName(truncated)

        let selfInfo = try await getSelfInfo()

        guard selfInfo.name == truncated else {
            throw SettingsServiceError.verificationFailed(
                expected: truncated,
                actual: selfInfo.name
            )
        }

        eventContinuation?.yield(.deviceUpdated(selfInfo))
        return selfInfo
    }

    /// Set location with verification
    public func setLocationVerified(latitude: Double, longitude: Double) async throws -> MeshCore.SelfInfo {
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

        guard latDiff <= tolerance && lonDiff <= tolerance else {
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

        eventContinuation?.yield(.deviceUpdated(selfInfo))
        return selfInfo
    }

    /// Set a manual location, turning off device GPS first when needed so the value persists.
    public func setManualLocationVerified(latitude: Double, longitude: Double) async throws -> MeshCore.SelfInfo {
        let gpsState = try await getDeviceGPSState()
        if gpsState.isSupported, gpsState.isEnabled {
            _ = try await setDeviceGPSEnabledVerified(false)
        }
        return try await setLocationVerified(latitude: latitude, longitude: longitude)
    }

    /// Set radio parameters with verification.
    ///
    /// Same unit conventions as `setRadioParams(frequencyKHz:bandwidthKHz:...)` —
    /// `frequencyKHz` is in kHz (869618 → 869.618 MHz) and `bandwidthKHz` is in Hz
    /// (62500 → 62.5 kHz) despite the suffix. See that method for the full rationale.
    public func setRadioParamsVerified(
        frequencyKHz: UInt32,
        bandwidthKHz: UInt32,
        spreadingFactor: UInt8,
        codingRate: UInt8,
        clientRepeat: Bool? = nil
    ) async throws -> MeshCore.SelfInfo {
        logger.info("[Radio] Sending params: freq=\(frequencyKHz)kHz, bw=\(bandwidthKHz)Hz, sf=\(spreadingFactor), cr=\(codingRate), repeat=\(String(describing: clientRepeat))")

        try await setRadioParams(
            frequencyKHz: frequencyKHz,
            bandwidthKHz: bandwidthKHz,
            spreadingFactor: spreadingFactor,
            codingRate: codingRate,
            clientRepeat: clientRepeat
        )

        let selfInfo = try await getSelfInfo()

        let expectedFreqMHz = Double(frequencyKHz) / 1000.0
        let expectedBwMHz = Double(bandwidthKHz) / 1000.0

        guard abs(selfInfo.radioFrequency - expectedFreqMHz) < 0.001 &&
              abs(selfInfo.radioBandwidth - expectedBwMHz) < 0.001 &&
              selfInfo.radioSpreadingFactor == spreadingFactor &&
              selfInfo.radioCodingRate == codingRate else {
            // swiftlint:disable:next line_length
            logger.warning("[Radio] Verification failed - expected: freq=\(expectedFreqMHz)MHz, bw=\(expectedBwMHz)kHz, sf=\(spreadingFactor), cr=\(codingRate); device reports: freq=\(selfInfo.radioFrequency)MHz, bw=\(selfInfo.radioBandwidth)kHz, sf=\(selfInfo.radioSpreadingFactor), cr=\(selfInfo.radioCodingRate)")
            throw SettingsServiceError.verificationFailed(
                expected: "freq=\(frequencyKHz), bw=\(bandwidthKHz), sf=\(spreadingFactor), cr=\(codingRate)",
                actual: "freq=\(selfInfo.radioFrequency), bw=\(selfInfo.radioBandwidth), sf=\(selfInfo.radioSpreadingFactor), cr=\(selfInfo.radioCodingRate)"
            )
        }

        // Verify clientRepeat via queryDevice if it was explicitly set
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
        eventContinuation?.yield(.deviceUpdated(selfInfo))
        return selfInfo
    }

    /// Apply radio preset with verification
    public func applyRadioPresetVerified(_ preset: RadioPreset) async throws -> MeshCore.SelfInfo {
        logger.info("[Radio] Applying preset: \(preset.name) (\(preset.id))")
        return try await setRadioParamsVerified(
            frequencyKHz: preset.frequencyKHz,
            bandwidthKHz: preset.bandwidthHz,
            spreadingFactor: preset.spreadingFactor,
            codingRate: preset.codingRate
        )
    }

    /// Set TX power with verification
    public func setTxPowerVerified(_ power: Int8) async throws -> MeshCore.SelfInfo {
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
        eventContinuation?.yield(.deviceUpdated(selfInfo))
        return selfInfo
    }

    /// Set other params with verification
    public func setOtherParamsVerified(
        autoAddContacts: Bool,
        telemetryModes: TelemetryModes,
        advertLocationPolicy: AdvertLocationPolicy,
        multiAcks: UInt8
    ) async throws -> MeshCore.SelfInfo {
        try await setOtherParams(
            autoAddContacts: autoAddContacts,
            telemetryModes: telemetryModes,
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

        eventContinuation?.yield(.deviceUpdated(selfInfo))
        return selfInfo
    }

    /// Convenience overload: uses the device's current values as defaults, overriding only the supplied parameters.
    public func setOtherParamsVerified(
        from device: DeviceDTO,
        autoAddContacts: Bool? = nil,
        telemetryModes: TelemetryModes? = nil,
        advertLocationPolicy: AdvertLocationPolicy? = nil,
        multiAcks: UInt8? = nil
    ) async throws -> MeshCore.SelfInfo {
        try await setOtherParamsVerified(
            autoAddContacts: autoAddContacts ?? !device.manualAddContacts,
            telemetryModes: telemetryModes ?? device.telemetryModes,
            advertLocationPolicy: advertLocationPolicy ?? device.advertLocationPolicyMode,
            multiAcks: multiAcks ?? device.multiAcks
        )
    }

    /// Compatibility overload: map boolean sharing to `prefs` policy when enabled.
    @available(*, deprecated, message: "Use advertLocationPolicy overload instead")
    public func setOtherParamsVerified(
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
