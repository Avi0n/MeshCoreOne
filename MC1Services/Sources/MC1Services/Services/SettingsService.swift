import Foundation
import MeshCore
import os

// MARK: - Settings Service Errors

public enum SettingsServiceError: Error, LocalizedError, Sendable {
    case notConnected
    case sendFailed
    case invalidResponse
    case sessionError(MeshCoreError)
    case verificationFailed(expected: String, actual: String)
    case deviceGPSVerificationFailed(expectedEnabled: Bool, actualEnabled: Bool)

    public var errorDescription: String? {
        switch self {
        case .notConnected: return "Device not connected"
        case .sendFailed: return "Failed to send command"
        case .invalidResponse: return "Invalid response from device"
        case .sessionError(let error): return error.localizedDescription
        case .verificationFailed(let expected, let actual):
            return "Setting was not saved. Expected '\(expected)' but device reports '\(actual)'."
        case .deviceGPSVerificationFailed(let expectedEnabled, let actualEnabled):
            let expected = expectedEnabled ? "On" : "Off"
            let actual = actualEnabled ? "On" : "Off"
            return "Device GPS setting was not saved. Expected '\(expected)' but device reports '\(actual)'."
        }
    }

    /// Whether this error suggests a connection issue that might be resolved by retrying
    public var isRetryable: Bool {
        switch self {
        case .sendFailed, .notConnected:
            return true
        case .sessionError(let error):
            if case .timeout = error { return true }
            return false
        default:
            return false
        }
    }
}

// MARK: - Radio Preset

/// Geographic regions for radio preset filtering
public enum RadioRegion: String, CaseIterable, Sendable {
    case northAmerica = "North America"
    case europe = "Europe"
    case oceania = "Oceania"
    case asia = "Asia"

    /// Regions that should be shown for a given locale
    public static func regionsForLocale(_ locale: Locale = .current) -> [RadioRegion] {
        guard let regionCode = locale.region?.identifier else {
            return RadioRegion.allCases
        }

        switch regionCode {
        case "US", "CA":
            return [.northAmerica, .europe, .oceania, .asia]
        case "AU", "NZ":
            return [.oceania, .northAmerica, .europe, .asia]
        case "GB", "DE", "FR", "IT", "ES", "PT", "CH", "CZ", "IE", "NL", "BE", "AT":
            return [.europe, .northAmerica, .oceania, .asia]
        case "VN", "TH", "MY", "SG", "PH", "ID":
            return [.asia, .oceania, .europe, .northAmerica]
        default:
            return RadioRegion.allCases
        }
    }

    /// Short code for display in compact UI elements
    public var shortCode: String {
        switch self {
        case .northAmerica: return "NA"
        case .europe: return "EU"
        case .oceania: return "AU"
        case .asia: return "AS"
        }
    }
}

/// Geographic availability tier for a `RadioPreset`. Used by the recommendation
/// algorithm to choose the most-specific community-curated preset that matches
/// the user's `RegionSelection`.
public enum PresetAvailability: Sendable, Equatable {
    case continent(RadioRegion)
    case countries(Set<String>)                                          // ISO-3166 α-2
    case subRegions(country: String, areas: Set<String>)                 // ISO 3166-2
    case counties(country: String, state: String, keys: Set<String>)     // normalized US county names
}

/// Radio configuration preset for common regional settings
public struct RadioPreset: Identifiable, Sendable, Equatable {
    public let id: String
    public let name: String
    public let region: RadioRegion
    public let frequencyMHz: Double
    public let bandwidthKHz: Double
    public let spreadingFactor: UInt8
    public let codingRate: UInt8

    /// Section header for repeat mode presets (e.g., "EU/Asia", "US/AU/NZ")
    public let repeatSectionHeader: String?
    public let availability: PresetAvailability
    /// Higher value = preferred within a geographic tier. Standard presets use 100; community-recommended favorites use 110.
    public let recommendationPriority: Int

    /// Frequency in kHz for protocol encoding
    public var frequencyKHz: UInt32 { UInt32(frequencyMHz * 1000) }

    /// Bandwidth in Hz for protocol encoding
    public var bandwidthHz: UInt32 { UInt32(bandwidthKHz * 1000) }

    public init(
        id: String,
        name: String,
        region: RadioRegion,
        frequencyMHz: Double,
        bandwidthKHz: Double,
        spreadingFactor: UInt8,
        codingRate: UInt8,
        repeatSectionHeader: String? = nil,
        availability: PresetAvailability,
        recommendationPriority: Int = 100
    ) {
        self.id = id
        self.name = name
        self.region = region
        self.frequencyMHz = frequencyMHz
        self.bandwidthKHz = bandwidthKHz
        self.spreadingFactor = spreadingFactor
        self.codingRate = codingRate
        self.repeatSectionHeader = repeatSectionHeader
        self.availability = availability
        self.recommendationPriority = recommendationPriority
    }
}

/// Static collection of all available radio presets
public enum RadioPresets {
    public static let all: [RadioPreset] = [
        // Oceania
        RadioPreset(id: "au-915", name: "Australia", region: .oceania,
                    frequencyMHz: 915.800, bandwidthKHz: 250, spreadingFactor: 10, codingRate: 5,
                    availability: .countries(["AU"])),
        RadioPreset(id: "au-narrow", name: "Australia (Narrow)", region: .oceania,
                    frequencyMHz: 916.575, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 8,
                    availability: .countries(["AU"])),
        RadioPreset(id: "au-mid", name: "Australia (Mid)", region: .oceania,
                    frequencyMHz: 915.075, bandwidthKHz: 125, spreadingFactor: 9, codingRate: 5,
                    availability: .countries(["AU"])),
        RadioPreset(id: "au-sa-wa", name: "Australia: SA, WA", region: .oceania,
                    frequencyMHz: 923.125, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                    availability: .subRegions(country: "AU", areas: ["AU-SA", "AU-WA"])),
        RadioPreset(id: "au-qld", name: "Australia: QLD", region: .oceania,
                    frequencyMHz: 923.125, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 5,
                    availability: .subRegions(country: "AU", areas: ["AU-QLD"])),
        RadioPreset(id: "nz-lr", name: "New Zealand", region: .oceania,
                    frequencyMHz: 917.375, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5,
                    availability: .countries(["NZ"])),
        RadioPreset(id: "nz-narrow", name: "New Zealand (Narrow)", region: .oceania,
                    frequencyMHz: 917.375, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                    availability: .countries(["NZ"])),

        // Europe
        RadioPreset(id: "eu-narrow", name: "EU/UK (Narrow)", region: .europe,
                    frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                    availability: .continent(.europe), recommendationPriority: 110),
        RadioPreset(id: "eu-lr", name: "EU/UK (Deprecated)", region: .europe,
                    frequencyMHz: 869.525, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5,
                    availability: .continent(.europe)),
        RadioPreset(id: "cz-narrow", name: "Czech Republic (Narrow)", region: .europe,
                    frequencyMHz: 869.432, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                    availability: .countries(["CZ"])),
        RadioPreset(id: "eu-433-lr", name: "EU 433MHz (Long Range)", region: .europe,
                    frequencyMHz: 433.650, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5,
                    availability: .continent(.europe)),
        RadioPreset(id: "eu-433-narrow", name: "EU 433MHz (Narrow)", region: .europe,
                    frequencyMHz: 433.650, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                    availability: .continent(.europe)),
        RadioPreset(id: "pt-433", name: "Portugal 433", region: .europe,
                    frequencyMHz: 433.375, bandwidthKHz: 62.5, spreadingFactor: 9, codingRate: 6,
                    availability: .countries(["PT"])),
        RadioPreset(id: "pt-868", name: "Portugal 868", region: .europe,
                    frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 6,
                    availability: .countries(["PT"]), recommendationPriority: 110),
        RadioPreset(id: "ch", name: "Switzerland", region: .europe,
                    frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                    availability: .countries(["CH"])),
        RadioPreset(id: "nl", name: "Netherlands", region: .europe,
                    frequencyMHz: 869.618, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                    availability: .countries(["NL"]), recommendationPriority: 110),

        // North America
        RadioPreset(id: "us-ca", name: "USA/Canada (Recommended)", region: .northAmerica,
                    frequencyMHz: 910.525, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                    availability: .countries(["US", "CA"]), recommendationPriority: 110),
        RadioPreset(id: "wcmesh", name: "WCMesh (SoCal)", region: .northAmerica,
                    frequencyMHz: 927.875, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 5,
                    availability: .counties(country: "US", state: "US-CA", keys: [
                        "los angeles", "orange", "san diego", "riverside", "san bernardino",
                        "ventura", "imperial", "kern", "santa barbara", "san luis obispo",
                    ])),

        // Asia
        RadioPreset(id: "vn-narrow", name: "Vietnam (Narrow)", region: .asia,
                    frequencyMHz: 920.250, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 5,
                    availability: .countries(["VN"]), recommendationPriority: 110),
        RadioPreset(id: "vn", name: "Vietnam (Deprecated)", region: .asia,
                    frequencyMHz: 920.250, bandwidthKHz: 250, spreadingFactor: 11, codingRate: 5,
                    availability: .countries(["VN"])),
    ]

    /// Repeat mode frequency presets with regional grouping.
    /// BW 62.5 kHz + CR 8 maximize range for portable repeaters.
    /// SF varies by band: higher for lower-power EU bands, lower for US.
    public static let repeatPresets: [RadioPreset] = [
        RadioPreset(id: "repeat-433", name: "433 MHz", region: .europe,
                    frequencyMHz: 433.000, bandwidthKHz: 62.5, spreadingFactor: 9, codingRate: 8,
                    repeatSectionHeader: "EU/Asia",
                    availability: .continent(.europe)),
        RadioPreset(id: "repeat-869", name: "869 MHz", region: .europe,
                    frequencyMHz: 869.000, bandwidthKHz: 62.5, spreadingFactor: 8, codingRate: 8,
                    repeatSectionHeader: "EU",
                    availability: .continent(.europe)),
        RadioPreset(id: "repeat-918", name: "918 MHz", region: .northAmerica,
                    frequencyMHz: 918.000, bandwidthKHz: 62.5, spreadingFactor: 7, codingRate: 8,
                    repeatSectionHeader: "US/AU/NZ",
                    availability: .continent(.northAmerica)),
    ]

    /// Get presets filtered and sorted by user's locale
    public static func presetsForLocale(_ locale: Locale = .current) -> [RadioPreset] {
        let preferredRegions = RadioRegion.regionsForLocale(locale)

        return all.sorted { a, b in
            let aIndex = preferredRegions.firstIndex(of: a.region) ?? preferredRegions.count
            let bIndex = preferredRegions.firstIndex(of: b.region) ?? preferredRegions.count
            if aIndex != bIndex {
                return aIndex < bIndex
            }
            return a.name < b.name
        }
    }

    /// Find preset matching current device settings (approximate match)
    public static func matchingPreset(
        frequencyKHz: UInt32,
        bandwidthKHz: UInt32,
        spreadingFactor: UInt8,
        codingRate: UInt8
    ) -> RadioPreset? {
        let freqMHz = Double(frequencyKHz) / 1000.0
        let bwKHz = Double(bandwidthKHz) / 1000.0

        return all.first { preset in
            abs(preset.frequencyMHz - freqMHz) < 0.1 &&
            abs(preset.bandwidthKHz - bwKHz) < 1.0 &&
            preset.spreadingFactor == spreadingFactor &&
            preset.codingRate == codingRate
        }
    }

    /// Find repeat preset matching current device settings (exact match)
    public static func matchingRepeatPreset(
        frequencyKHz: UInt32,
        bandwidthKHz: UInt32,
        spreadingFactor: UInt8,
        codingRate: UInt8
    ) -> RadioPreset? {
        repeatPresets.first { preset in
            preset.frequencyKHz == frequencyKHz &&
            preset.bandwidthHz == bandwidthKHz &&
            preset.spreadingFactor == spreadingFactor &&
            preset.codingRate == codingRate
        }
    }

    /// Stable recommendation order, computed once. `recommendationPriority` is a
    /// compile-time constant on each preset so the sort output never changes.
    private static let recommendationOrder: [RadioPreset] = all.sorted {
        $0.recommendationPriority != $1.recommendationPriority
            ? $0.recommendationPriority > $1.recommendationPriority
            : $0.id < $1.id
    }

    /// Returns the most-specific community-curated preset for `region`.
    /// Tier 0 (county) → Tier 1 (sub-region) → Tier 2 (country) → Tier 3 (continent).
    /// Returns nil for regions not covered by any tier (e.g. Bermuda).
    public static func recommended(for region: RegionSelection) -> RadioPreset? {
        let stable = recommendationOrder

        // Tier 0: counties
        if let adminCode = region.administrativeAreaCode,
           let countyKey = region.countyKey,
           let preset = stable.first(where: {
               if case .counties(let c, let s, let keys) = $0.availability {
                   return c == region.countryCode && s == adminCode && keys.contains(countyKey)
               }
               return false
           }) { return preset }

        // Tier 1: sub-regions
        if let adminCode = region.administrativeAreaCode,
           let preset = stable.first(where: {
               if case .subRegions(let c, let areas) = $0.availability {
                   return c == region.countryCode && areas.contains(adminCode)
               }
               return false
           }) { return preset }

        // Tier 2: countries
        if let preset = stable.first(where: {
            if case .countries(let codes) = $0.availability {
                return codes.contains(region.countryCode)
            }
            return false
        }) { return preset }

        // Tier 3: continent
        if let continent = RegionalAreas.continents[region.countryCode],
           let preset = stable.first(where: {
               if case .continent(let r) = $0.availability { return r == continent }
               return false
           }) { return preset }

        return nil
    }

    /// Returns the alternatives list for the region's country (or continent if no
    /// country-level matches exist). The list always includes `.counties` and
    /// `.subRegions` presets for the country regardless of the user's specific
    /// county/state, so a Sacramento user can still pick `wcmesh` manually.
    public static func presets(for region: RegionSelection) -> [RadioPreset] {
        let countryAndBelow = all.filter { preset in
            switch preset.availability {
            case .counties(let c, _, _): return c == region.countryCode
            case .subRegions(let c, _): return c == region.countryCode
            case .countries(let codes): return codes.contains(region.countryCode)
            case .continent: return false
            }
        }
        if !countryAndBelow.isEmpty { return countryAndBelow }
        guard let continent = RegionalAreas.continents[region.countryCode] else { return [] }
        return all.filter { preset in
            if case .continent(let r) = preset.availability { return r == continent }
            return false
        }
    }
}

// MARK: - Telemetry Modes

/// Packed telemetry mode configuration
public struct TelemetryModes: Sendable, Equatable {
    public var base: UInt8
    public var location: UInt8
    public var environment: UInt8

    public init(base: UInt8 = 0, location: UInt8 = 0, environment: UInt8 = 0) {
        self.base = base & 0b11
        self.location = location & 0b11
        self.environment = environment & 0b11
    }

    /// Packed value for protocol encoding
    public var packed: UInt8 {
        (environment << 4) | (location << 2) | base
    }

    public init(packed: UInt8) {
        self.base = packed & 0b11
        self.location = (packed >> 2) & 0b11
        self.environment = (packed >> 4) & 0b11
    }
}

// MARK: - Advert Location Policy

/// Location inclusion policy for advertisements.
public enum AdvertLocationPolicy: UInt8, Sendable, CaseIterable {
    case none = 0
    case share = 1
    case prefs = 2

    public var isEnabled: Bool {
        self != .none
    }
}

public struct DeviceGPSState: Sendable, Equatable {
    public let isSupported: Bool
    public let isEnabled: Bool

    public init(isSupported: Bool, isEnabled: Bool) {
        self.isSupported = isSupported
        self.isEnabled = isEnabled
    }
}

// MARK: - Settings Events

/// Events emitted by SettingsService when device settings change.
public enum SettingsEvent: Sendable {
    case deviceUpdated(MeshCore.SelfInfo)
    case autoAddConfigUpdated(MeshCore.AutoAddConfig)
    case clientRepeatUpdated(Bool)
    case pathHashModeUpdated(UInt8)
    case allowedRepeatFreqUpdated([MeshCore.FrequencyRange])
    case defaultFloodScopeUpdated(String?)
}

// MARK: - Settings Service

/// Service for managing device settings via MeshCore session.
/// Handles radio configuration, node settings, Bluetooth settings, and device info.
public actor SettingsService {
    private let session: MeshCoreSession
    private let logger = PersistentLogger(subsystem: "com.mc1", category: "SettingsService")

    private var eventContinuation: AsyncStream<SettingsEvent>.Continuation?

    public init(session: MeshCoreSession) {
        self.session = session
    }

    /// Stream of settings change events.
    /// Only one active subscriber is supported. Subsequent calls replace the previous subscriber.
    public func events() -> AsyncStream<SettingsEvent> {
        AsyncStream { continuation in
            Task { self.setContinuation(continuation) }
            continuation.onTermination = { @Sendable _ in
                Task { await self.clearContinuation() }
            }
        }
    }

    private func setContinuation(_ continuation: AsyncStream<SettingsEvent>.Continuation) {
        if eventContinuation != nil {
            logger.warning("Replacing existing SettingsService event stream subscriber")
        }
        eventContinuation?.finish()
        self.eventContinuation = continuation
    }

    private func clearContinuation() {
        eventContinuation = nil
    }

    // MARK: - Radio Settings

    /// Apply a radio preset to the device
    public func applyRadioPreset(_ preset: RadioPreset) async throws {
        try await setRadioParams(
            frequencyKHz: preset.frequencyKHz,
            bandwidthKHz: preset.bandwidthHz,
            spreadingFactor: preset.spreadingFactor,
            codingRate: preset.codingRate
        )
    }

    /// Set radio parameters manually.
    ///
    /// Both numeric parameters are integer values that get divided by 1000 before being
    /// forwarded to `session.setRadio`. Pass values in the same scaled-integer form that
    /// `RadioPreset.frequencyKHz` and `RadioPreset.bandwidthHz` use:
    /// - `frequencyKHz`: frequency expressed in kHz (e.g. `869618` → 869.618 MHz on the wire)
    /// - `bandwidthKHz`: bandwidth expressed in Hz (e.g. `62500` → 62.5 kHz on the wire)
    ///
    /// The `bandwidthKHz` name is preserved for source compatibility despite the value
    /// actually being in Hz; do not pass `Int(62.5)` here.
    public func setRadioParams(
        frequencyKHz: UInt32,
        bandwidthKHz: UInt32,
        spreadingFactor: UInt8,
        codingRate: UInt8,
        clientRepeat: Bool? = nil
    ) async throws {
        do {
            try await session.setRadio(
                frequency: Double(frequencyKHz) / 1000.0,
                bandwidth: Double(bandwidthKHz) / 1000.0,
                spreadingFactor: spreadingFactor,
                codingRate: codingRate,
                clientRepeat: clientRepeat
            )
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Set transmit power
    public func setTxPower(_ power: Int8) async throws {
        do {
            try await session.setTxPower(power)
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    // MARK: - Node Settings

    /// Set the publicly visible node name
    public func setNodeName(_ name: String) async throws {
        let truncated = name.utf8Prefix(maxBytes: ProtocolLimits.maxUsableNameBytes)
        do {
            try await session.setName(truncated)
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Set node location (latitude/longitude in degrees)
    public func setLocation(latitude: Double, longitude: Double) async throws {
        do {
            try await session.setCoordinates(latitude: latitude, longitude: longitude)
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    // MARK: - Bluetooth Settings

    /// Set BLE PIN (0 = disabled/random, 100000-999999 = fixed PIN)
    public func setBlePin(_ pin: UInt32) async throws {
        do {
            try await session.setDevicePin(pin)
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    // MARK: - Other Settings

    /// Set other device parameters (contacts, telemetry, location policy)
    public func setOtherParams(
        autoAddContacts: Bool,
        telemetryModes: TelemetryModes,
        advertLocationPolicy: AdvertLocationPolicy,
        multiAcks: UInt8
    ) async throws {
        try await setOtherParams(
            autoAddContacts: autoAddContacts,
            telemetryModes: telemetryModes,
            advertLocationPolicyRaw: advertLocationPolicy.rawValue,
            multiAcks: multiAcks
        )
    }

    /// Set other device parameters, taking the advertisement location policy as a raw byte.
    ///
    /// Used by config import so a policy value not modeled by ``AdvertLocationPolicy`` (e.g. from
    /// newer firmware) is forwarded to the device verbatim instead of being coerced.
    public func setOtherParams(
        autoAddContacts: Bool,
        telemetryModes: TelemetryModes,
        advertLocationPolicyRaw: UInt8,
        multiAcks: UInt8
    ) async throws {
        do {
            try await session.setOtherParams(
                manualAddContacts: !autoAddContacts,
                telemetryModeEnvironment: telemetryModes.environment,
                telemetryModeLocation: telemetryModes.location,
                telemetryModeBase: telemetryModes.base,
                advertisementLocationPolicy: advertLocationPolicyRaw,
                multiAcks: multiAcks
            )
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Compatibility overload: map boolean sharing to `prefs` policy when enabled.
    @available(*, deprecated, message: "Use advertLocationPolicy overload instead")
    public func setOtherParams(
        autoAddContacts: Bool,
        telemetryModes: TelemetryModes,
        shareLocationPublicly: Bool,
        multiAcks: UInt8
    ) async throws {
        try await setOtherParams(
            autoAddContacts: autoAddContacts,
            telemetryModes: telemetryModes,
            advertLocationPolicy: shareLocationPublicly ? .prefs : .none,
            multiAcks: multiAcks
        )
    }

    // MARK: - Factory Reset

    /// Perform factory reset on device
    public func factoryReset() async throws {
        do {
            try await session.factoryReset()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Reboot the device
    public func reboot() async throws {
        do {
            try await session.reboot()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    // MARK: - Device Info

    /// Fetch battery and storage information from device
    /// - Returns: BatteryInfo with current values
    /// - Throws: SettingsServiceError if not connected or communication fails
    public func getBattery() async throws -> BatteryInfo {
        do {
            return try await session.getBattery()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Query device capabilities
    public func queryDevice() async throws -> DeviceCapabilities {
        do {
            return try await session.queryDevice()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Get self info by sending appStart
    public func getSelfInfo() async throws -> MeshCore.SelfInfo {
        do {
            return try await session.sendAppStart()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    // MARK: - Verified Settings Methods

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

    // MARK: - Auto-Add Config

    /// Get auto-add configuration from device
    public func getAutoAddConfig() async throws -> MeshCore.AutoAddConfig {
        do {
            return try await session.getAutoAddConfig()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Refresh auto-add config from device (for initial load)
    /// Fetches current value and triggers callback to update connected device
    public func refreshAutoAddConfig() async throws {
        let config = try await getAutoAddConfig()
        eventContinuation?.yield(.autoAddConfigUpdated(config))
    }

    // MARK: - Repeat Frequency Ranges

    /// Get allowed repeat frequency ranges from device
    private func getRepeatFreq() async throws -> [MeshCore.FrequencyRange] {
        do {
            return try await session.getRepeatFreq()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Refresh repeat frequency ranges from device and notify observers
    public func refreshRepeatFreqRanges() async throws {
        let ranges = try await getRepeatFreq()
        eventContinuation?.yield(.allowedRepeatFreqUpdated(ranges))
    }

    /// Refresh device info from the device and notify observers.
    /// Use this instead of `setLocationVerified` when the device already has correct coordinates (e.g. from its own GPS).
    public func refreshDeviceInfo() async throws {
        let selfInfo = try await getSelfInfo()
        eventContinuation?.yield(.deviceUpdated(selfInfo))
    }

    /// Set auto-add configuration on device
    public func setAutoAddConfig(_ config: MeshCore.AutoAddConfig) async throws {
        do {
            try await session.setAutoAddConfig(config)
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Set auto-add configuration with verification
    public func setAutoAddConfigVerified(_ config: MeshCore.AutoAddConfig) async throws -> MeshCore.AutoAddConfig {
        try await setAutoAddConfig(config)

        let actualConfig = try await getAutoAddConfig()

        guard actualConfig == config else {
            throw SettingsServiceError.verificationFailed(
                expected: "bitmask=\(config.bitmask), maxHops=\(config.maxHops)",
                actual: "bitmask=\(actualConfig.bitmask), maxHops=\(actualConfig.maxHops)"
            )
        }

        eventContinuation?.yield(.autoAddConfigUpdated(actualConfig))
        return actualConfig
    }

    // MARK: - Path Hash Mode

    /// Sets the path hash mode on the device.
    ///
    /// - Parameter mode: Hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes).
    public func setPathHashMode(_ mode: UInt8) async throws {
        do {
            try await session.setPathHashMode(mode)
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Sets the path hash mode with verification via queryDevice.
    ///
    /// - Parameter mode: Hash mode (0=1-byte, 1=2-byte, 2=3-byte hashes).
    /// - Returns: The verified mode value from the device.
    public func setPathHashModeVerified(_ mode: UInt8) async throws -> UInt8 {
        try await setPathHashMode(mode)

        let capabilities = try await queryDevice()
        guard capabilities.pathHashMode == mode else {
            throw SettingsServiceError.verificationFailed(
                expected: "pathHashMode=\(mode)",
                actual: "pathHashMode=\(capabilities.pathHashMode)"
            )
        }

        eventContinuation?.yield(.pathHashModeUpdated(mode))
        return mode
    }

    // MARK: - Default Flood Scope

    /// Fetches the device's persisted default flood scope.
    ///
    /// Requires firmware v11+; older firmware rejects the opcode and surfaces
    /// ``SettingsServiceError/sessionError(_:)`` with `MeshCoreError.deviceError`.
    ///
    /// - Returns: The persisted scope name, or `nil` when none is configured.
    public func getDefaultFloodScope() async throws -> String? {
        do {
            let scope = try await session.getDefaultFloodScope()
            let name = scope?.name
            eventContinuation?.yield(.defaultFloodScopeUpdated(name))
            return name
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Persists the device's default flood scope and verifies via a follow-up read.
    ///
    /// Passing `nil` for `name` clears the persisted scope. Non-nil names are sent as
    /// ``MeshCore/FloodScope/region(_:)`` — firmware derives the key and stores both.
    /// Names are truncated to ``ProtocolLimits/maxDefaultFloodScopeNameBytes`` UTF-8 bytes
    /// before both key derivation and send, so the stored display and derived scope key
    /// agree on the same byte sequence.
    ///
    /// - Parameter name: Region name to persist, or `nil` to clear.
    /// - Returns: The verified name read back from the device.
    public func setDefaultFloodScopeVerified(name: String?) async throws -> String? {
        let expected: String? = (name?.isEmpty == false)
            ? name?.utf8Prefix(maxBytes: ProtocolLimits.maxDefaultFloodScopeNameBytes)
            : nil
        do {
            if let expected {
                try await session.setDefaultFloodScope(name: expected, scope: .region(expected))
            } else {
                try await session.setDefaultFloodScope(name: "", scope: .disabled)
            }
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }

        let actual = try await getDefaultFloodScope()
        guard actual == expected else {
            throw SettingsServiceError.verificationFailed(
                expected: expected ?? "(cleared)",
                actual: actual ?? "(cleared)"
            )
        }
        return actual
    }

    // MARK: - Stats

    /// Get core statistics
    public func getStatsCore() async throws -> CoreStats {
        do {
            return try await session.getStatsCore()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Get radio statistics
    public func getStatsRadio() async throws -> RadioStats {
        do {
            return try await session.getStatsRadio()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Get packet statistics
    public func getStatsPackets() async throws -> PacketStats {
        do {
            return try await session.getStatsPackets()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    // MARK: - Custom Variables

    /// Get custom variables from device
    public func getCustomVars() async throws -> [String: String] {
        do {
            return try await session.getCustomVars()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    public func getDeviceGPSState() async throws -> DeviceGPSState {
        let vars = try await getCustomVars()
        return Self.deviceGPSState(from: vars)
    }

    /// Set a custom variable on device
    public func setCustomVar(key: String, value: String) async throws {
        do {
            try await session.setCustomVar(key: key, value: value)
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    public func setDeviceGPSEnabledVerified(_ enabled: Bool) async throws -> DeviceGPSState {
        try await setCustomVar(key: "gps", value: enabled ? "1" : "0")

        let state = try await getDeviceGPSState()
        guard state.isSupported else {
            throw SettingsServiceError.deviceGPSVerificationFailed(
                expectedEnabled: enabled,
                actualEnabled: false
            )
        }
        guard state.isEnabled == enabled else {
            throw SettingsServiceError.deviceGPSVerificationFailed(
                expectedEnabled: enabled,
                actualEnabled: state.isEnabled
            )
        }

        try await refreshDeviceInfo()
        return state
    }

    // MARK: - Private Key Management

    /// Export private key from device
    public func exportPrivateKey() async throws -> Data {
        do {
            return try await session.exportPrivateKey()
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    /// Import private key to device
    public func importPrivateKey(_ key: Data) async throws {
        do {
            try await session.importPrivateKey(key)
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    // MARK: - Signing

    /// Sign data using device's private key
    public func sign(_ data: Data) async throws -> Data {
        do {
            return try await session.sign(data)
        } catch let error as MeshCoreError {
            throw SettingsServiceError.sessionError(error)
        }
    }

    private static func deviceGPSState(from vars: [String: String]) -> DeviceGPSState {
        guard let value = vars["gps"] else {
            return DeviceGPSState(isSupported: false, isEnabled: false)
        }
        return DeviceGPSState(isSupported: true, isEnabled: value == "1")
    }
}
