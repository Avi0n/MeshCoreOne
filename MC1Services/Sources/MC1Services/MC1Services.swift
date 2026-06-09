// MC1Services - iOS-specific services for MeshCore One
// Re-exports MeshCore so consumers only need to import MC1Services

@_exported import MeshCore

extension Locale {
    /// POSIX locale — always uses period as decimal separator.
    /// Use for technical values like radio frequency and bandwidth.
    public static let posix = Locale(identifier: "en_US_POSIX")
}

/// MC1Services provides iOS-specific implementations on top of MeshCore:
/// - SwiftData models (Device, Contact, Message, Channel)
/// - PersistenceStore (@ModelActor for SwiftData operations)
/// - iOS BLE transport with state restoration and background mode
/// - Notification, Keychain, and AccessorySetupKit services
/// - High-level service layer (ContactService, MessageService, ChannelService)
public enum MC1ServicesVersion {
    public static let version = "0.1.0"
}

// MARK: - Type Aliases

/// Alias for PersistenceStore (backwards compatibility)
public typealias DataStore = PersistenceStore

/// Alias for StatusResponse (backwards compatibility with PocketMeshKit)
public typealias RemoteNodeStatus = StatusResponse

/// Alias for Neighbour (backwards compatibility with PocketMeshKit naming)
public typealias NeighbourInfo = Neighbour

// MARK: - StatusResponse Extensions (PocketMeshKit compatibility)

extension StatusResponse {
    /// Uptime in seconds (compatibility alias)
    public var uptimeSeconds: UInt32 { uptime }

    /// Battery level in millivolts (compatibility conversion)
    public var batteryMillivolts: UInt16 { UInt16(clamping: battery) }

    /// Last RSSI value (compatibility alias)
    public var lastRssi: Int16 { Int16(clamping: lastRSSI) }

    /// Last SNR value (compatibility conversion)
    public var lastSnr: Float { Float(lastSNR) }

    /// Repeater RX airtime in seconds (compatibility alias)
    public var repeaterRxAirtimeSeconds: UInt32 { rxAirtime }
}

// MARK: - TelemetryResponse Extensions (PocketMeshKit compatibility)

extension TelemetryResponse {
    /// Decoded LPP data points from the raw telemetry data.
    /// Uses MeshCore's LPPDecoder to parse the raw bytes into structured sensor values.
    public var dataPoints: [LPPDataPoint] {
        LPPDecoder.decode(rawData)
    }
}

// MARK: - Radio Options

import OSLog

private let radioLogger = PersistentLogger(subsystem: "com.mc1.services", category: "radio")

/// Standard LoRa radio parameter options for configuration UI
public enum RadioOptions {
    /// Available bandwidth options in Hz (internal representation for picker tags)
    /// Display values: 7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125, 250, 500 kHz
    ///
    /// Note: These values are passed directly to the protocol layer. Despite the
    /// misleading parameter name `bandwidthKHz` in FrameCodec.encodeSetRadioParams,
    /// the firmware actually expects bandwidth in Hz.
    public static let bandwidthsHz: [UInt32] = [
        7_800, 10_400, 15_600, 20_800, 31_250, 41_700, 62_500, 125_000, 250_000, 500_000
    ]

    /// Bandwidth options in kHz for CLI protocol display
    public static let bandwidthsKHz: [Double] = bandwidthsHz.map { Double($0) / 1000.0 }

    /// Valid spreading factor range (SF5-SF12)
    public static let spreadingFactors: ClosedRange<Int> = 5...12

    /// Valid coding rate range (5-8, representing 4/5 through 4/8)
    public static let codingRates: ClosedRange<Int> = 5...8

    /// Format bandwidth Hz value for display (e.g., 7800 -> "7.8", 125000 -> "125")
    /// Uses switch for known values to ensure deterministic, O(1) output.
    public static func formatBandwidth(_ hz: UInt32) -> String {
        switch hz {
        case 7_800: return "7.8"
        case 10_400: return "10.4"
        case 15_600: return "15.6"
        case 20_800: return "20.8"
        case 31_250: return "31.25"
        case 41_700: return "41.7"
        case 62_500: return "62.5"
        case 125_000: return "125"
        case 250_000: return "250"
        case 500_000: return "500"
        default:
            // Fallback for unexpected values (e.g., from nearestBandwidth edge cases)
            let khz = Double(hz) / 1000.0
            if khz.truncatingRemainder(dividingBy: 1) == 0 {
                return "\(Int(khz))"
            } else {
                let formatter = NumberFormatter()
                formatter.locale = Locale(identifier: "en_US_POSIX")
                formatter.minimumFractionDigits = 0
                formatter.maximumFractionDigits = 2
                return formatter.string(from: NSNumber(value: khz)) ?? "\(khz)"
            }
        }
    }

    /// Find nearest valid bandwidth for a device value that may not be in the standard list.
    /// Handles firmware float precision issues where values like 7800 Hz may be stored as
    /// 7.8 kHz (float) and returned as 7799 or 7801 Hz.
    ///
    /// Logs a debug message when fallback occurs to help diagnose unexpected device values.
    public static func nearestBandwidth(to hz: UInt32) -> UInt32 {
        if bandwidthsHz.contains(hz) {
            return hz
        }
        // Use explicit unsigned comparison to avoid Int64 type promotion
        let nearest = bandwidthsHz.min { lhs, rhs in
            let lhsDiff = lhs > hz ? lhs - hz : hz - lhs
            let rhsDiff = rhs > hz ? rhs - hz : hz - rhs
            return lhsDiff < rhsDiff
        } ?? 250_000

        radioLogger.debug("Bandwidth \(hz) Hz not in standard options, using nearest: \(nearest) Hz")
        return nearest
    }
}

// MARK: - CLI Response

/// Parsed CLI response from repeater
public enum CLIResponse: Sendable, Equatable {
    case ok
    case error(String)
    case unknownCommand(String)  // Specific case for "Error: unknown command"
    case version(String)
    case deviceTime(String)
    case name(String)
    case radio(frequency: Double, bandwidth: Double, spreadingFactor: Int, codingRate: Int)
    case txPower(Int)
    case repeatMode(Bool)
    case advertInterval(Int)
    case floodAdvertInterval(Int)  // Value is in hours, not minutes
    case floodMax(Int)
    case latitude(Double)
    case longitude(Double)
    case ownerInfo(String)
    case raw(String)

    /// Parse a CLI response text into a structured type
    /// Note: Response correlation must be handled by the caller based on pending query tracking
    public static func parse(_ text: String, forQuery query: String? = nil) -> CLIResponse {
        var trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)

        // Strip MeshCore CLI prompt prefix if present
        // Firmware prepends "> " to all CLI command responses
        if trimmed.hasPrefix("> ") {
            trimmed = String(trimmed.dropFirst(2))
        } else if trimmed == ">" {
            trimmed = ""
        }

        // Success responses: "OK" or "OK - clock set: ..." etc.
        if trimmed == "OK" || trimmed.hasPrefix("OK - ") {
            return .ok
        }

        if trimmed.lowercased().hasPrefix("error") || trimmed.hasPrefix("ERR:") {
            // Check for "unknown command" specifically for defensive handling
            if trimmed.lowercased().contains("unknown command") {
                return .unknownCommand(trimmed)
            }
            return .error(trimmed)
        }

        // Firmware version: "MeshCore v1.10.0 (2025-04-18)" or "v1.11.0 (2025-04-18)"
        // Some firmware builds omit "MeshCore " prefix
        if trimmed.hasPrefix("MeshCore v") || (trimmed.hasPrefix("v") && trimmed.contains("(")) {
            return .version(trimmed)
        }

        // Use query hint to match version responses that don't have standard prefix
        if query == "ver" {
            return .version(trimmed)
        }

        // Freeform text fields: query hint takes priority over broad content heuristics
        // below (e.g. deviceTime matches any string with ":" and "/", which is common
        // in repeater names and contact info like "Contact: KD7ABC / 145.230")
        if query == "get name" {
            return .name(trimmed)
        }

        if query == "get owner.info" {
            return .ownerInfo(trimmed)
        }

        // Clock response: "06:40 - 18/4/2025 UTC" or contains time-like patterns
        if trimmed.contains("UTC") || (trimmed.contains(":") && trimmed.contains("/")) {
            return .deviceTime(trimmed)
        }

        // Radio params: "915.000,250.0,10,5" (freq,bw,sf,cr)
        // Use query hint to disambiguate from other comma-separated values
        if query == "get radio" {
            let parts = trimmed.split(separator: ",").map { String($0).trimmingCharacters(in: .whitespaces) }
            if parts.count >= 4,
               let freq = Double(parts[0]),
               let bw = Double(parts[1]),
               let sf = Int(parts[2]),
               let cr = Int(parts[3]) {
                return .radio(frequency: freq, bandwidth: bw, spreadingFactor: sf, codingRate: cr)
            }
        }

        // TX power: integer dBm value
        if query == "get tx", let power = Int(trimmed) {
            return .txPower(power)
        }

        // Repeat mode: "on" or "off"
        if query == "get repeat" {
            if trimmed.lowercased() == "on" {
                return .repeatMode(true)
            } else if trimmed.lowercased() == "off" {
                return .repeatMode(false)
            }
        }

        // Advert interval: integer minutes
        if query == "get advert.interval", let interval = Int(trimmed) {
            return .advertInterval(interval)
        }

        // Flood advert interval: integer hours
        if query == "get flood.advert.interval", let interval = Int(trimmed) {
            return .floodAdvertInterval(interval)
        }

        // Flood max: integer hops
        if query == "get flood.max", let maxHops = Int(trimmed) {
            return .floodMax(maxHops)
        }

        // Latitude: decimal degrees
        if query == "get lat", let lat = Double(trimmed) {
            return .latitude(lat)
        }

        // Longitude: decimal degrees
        if query == "get lon", let lon = Double(trimmed) {
            return .longitude(lon)
        }

        return .raw(trimmed)
    }
}

// MARK: - LPP Data Point Extensions

extension LPPDataPoint {
    /// Human-readable type name for the sensor channel
    public var typeName: String {
        type.name
    }

    /// Formatted value with appropriate unit suffix
    public var formattedValue: String {
        switch (type, value) {
        case (.voltage, .float(let v)): return "\(v.formatted(.number.precision(.fractionLength(3)))) V"
        case (.temperature, .float(let t)):
            let v = type.convertedValue(t)
            return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
        case (.humidity, .float(let h)): return "\(h.formatted(.number.precision(.fractionLength(1))))%"
        case (.barometer, .float(let p)):
            let v = type.convertedValue(p)
            return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
        case (.illuminance, .integer(let i)): return "\(i) lux"
        case (.percentage, .integer(let p)): return "\(p)%"
        case (.current, .float(let c)): return "\(c.formatted(.number.precision(.fractionLength(3)))) A"
        case (.power, .float(let p)): return "\(p.formatted(.number.precision(.fractionLength(1)))) W"
        case (.frequency, .float(let f)): return "\(f.formatted(.number.precision(.fractionLength(1)))) Hz"
        case (.altitude, .float(let a)):
            let v = type.convertedValue(a)
            return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
        case (.distance, .float(let d)):
            let v = type.convertedValue(d)
            return "\(v.formatted(.number.precision(.fractionLength(type.convertedFractionLength)))) \(type.localizedUnitSymbol)"
        case (.energy, .float(let e)): return "\(e.formatted(.number.precision(.fractionLength(3)))) kWh"
        case (.direction, .float(let d)): return "\(d.formatted(.number.precision(.fractionLength(0))))\u{00B0}"
        case (_, .digital(let b)): return b ? "On" : "Off"
        case (_, .integer(let i)): return "\(i)"
        case (_, .float(let f)): return f.formatted(.number.precision(.fractionLength(3)))
        case (_, .vector3(let x, let y, let z)):
            return "(\(x.formatted(.number.precision(.fractionLength(2)))), \(y.formatted(.number.precision(.fractionLength(2)))), \(z.formatted(.number.precision(.fractionLength(2)))))"
        case (_, .gps(let lat, let lon, let alt)):
            return "\(lat.formatted(.number.precision(.fractionLength(5)))), \(lon.formatted(.number.precision(.fractionLength(5)))) @ \(alt.formatted(.number.precision(.fractionLength(1))))m"
        case (_, .rgb(let r, let g, let b)):
            return "RGB(\(r), \(g), \(b))"
        case (_, .timestamp(let date)):
            return date.formatted(date: .abbreviated, time: .shortened)
        }
    }

    /// Estimated battery percentage based on voltage (3.0V=0%, 4.2V=100%)
    /// Returns nil for non-voltage types or non-float values
    public var batteryPercentage: Int? {
        guard type == .voltage, case .float(let voltage) = value else {
            return nil
        }

        let minVoltage: Double = 3.0
        let maxVoltage: Double = 4.2
        let percentage = (voltage - minVoltage) / (maxVoltage - minVoltage) * 100
        return max(0, min(100, Int(percentage.rounded())))
    }
}
