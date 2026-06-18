import Foundation
import OSLog

private let radioLogger = PersistentLogger(subsystem: "com.mc1", category: "radio")

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
