import Foundation

/// Formatting utilities for Line of Sight results display
enum LOSFormatters {

    /// Formats diffraction loss for display
    /// - Parameter loss: Diffraction loss in dB
    /// - Returns: Formatted string like "+ 8.4 dB" or nil if loss is negligible (< 0.1 dB)
    static func formatDiffractionLoss(_ loss: Double) -> String? {
        guard abs(loss) >= 0.1 else { return nil }
        return "+ \(loss.formatted(.number.precision(.fractionLength(1)))) dB"
    }

    /// Formats total path loss for display
    /// - Parameter loss: Path loss in dB
    /// - Returns: Formatted string like "126.6 dB"
    static func formatPathLoss(_ loss: Double) -> String {
        "\(loss.formatted(.number.precision(.fractionLength(1)))) dB"
    }

    /// Formats clearance percentage for display
    /// - Parameter percent: Clearance percentage (may be outside 0-100 range)
    /// - Returns: Integer percentage clamped to 0-100 range
    static func formatClearancePercent(_ percent: Double) -> Int {
        Int(max(0, min(100, percent)))
    }

    /// Formats distance in kilometers
    /// - Parameter meters: Distance in meters to format
    /// - Returns: Formatted string like "12.4 km"
    static func formatDistance(_ meters: Double) -> String {
        let km = meters / 1000
        return "\(km.formatted(.number.precision(.fractionLength(1)))) km"
    }

    /// Formats frequency for display in assumptions
    /// - Parameter mhz: Frequency in MHz
    /// - Returns: Formatted string like "906 MHz" or "915.5 MHz"
    static func formatFrequency(_ mhz: Double) -> String {
        if mhz.truncatingRemainder(dividingBy: 1) == 0 {
            return "\(Int(mhz)) MHz"
        } else {
            return "\(mhz.formatted(.number.precision(.fractionLength(1)))) MHz"
        }
    }

    /// Formats k-factor for display
    /// - Parameter k: Refraction k-factor
    /// - Returns: Formatted string like "k=1.33"
    static func formatKFactor(_ k: Double) -> String {
        "k=\(k.formatted(.number.precision(.fractionLength(2))))"
    }

    /// Formats complete assumptions line
    /// - Parameters:
    ///   - frequencyMHz: Operating frequency in MHz
    ///   - k: Refraction k-factor
    /// - Returns: String like "906 MHz, k=1.33, 60% 1st Fresnel threshold"
    static func formatAssumptions(frequencyMHz: Double, k: Double) -> String {
        "\(formatFrequency(frequencyMHz)), \(formatKFactor(k)), 60% 1st Fresnel threshold"
    }
}
