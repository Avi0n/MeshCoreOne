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

    /// Formats distance using the locale's measurement system
    /// - Parameter meters: Distance in meters to format
    /// - Returns: Formatted string like "12.4 km" or "7.7 mi"
    static func formatDistance(_ meters: Double) -> String {
        Measurement(value: meters, unit: UnitLength.meters)
            .formatted(.measurement(width: .abbreviated, usage: .road))
    }

    /// Formats frequency with a locale-aware unit symbol
    /// - Parameter mhz: Frequency in MHz
    /// - Returns: Formatted string like "906 MHz" or "915.5 MHz"
    static func formatFrequency(_ mhz: Double) -> String {
        let fractionDigits = mhz.truncatingRemainder(dividingBy: 1) == 0 ? 0 : 1
        return Measurement(value: mhz, unit: UnitFrequency.megahertz)
            .formatted(.measurement(
                width: .abbreviated,
                usage: .asProvided,
                numberFormatStyle: .number.precision(.fractionLength(fractionDigits))
            ))
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
    /// - Returns: Localized string like "906 MHz, k=1.33, 60% 1st Fresnel threshold"
    static func formatAssumptions(frequencyMHz: Double, k: Double) -> String {
        L10n.Tools.Tools.LineOfSight.assumptions(formatFrequency(frequencyMHz), formatKFactor(k))
    }
}
