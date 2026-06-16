import Foundation

/// A single editable point on a battery discharge curve: an open-circuit voltage
/// (in millivolts) mapped to a charge percentage.
///
/// Anchors are an editor-facing representation. The device firmware consumes a
/// fixed 11-point OCV array (mV at 100%, 90% … 0%), so the editor seeds anchors
/// from that array and resamples back to it on save — see `BatteryCurve`.
///
/// A valid curve keeps anchors ordered by **strictly increasing** millivolts and
/// **non-decreasing** percent.
public struct BatteryAnchor: Equatable, Sendable, Identifiable {
    public let id: UUID
    /// Open-circuit voltage in millivolts.
    public var millivolts: Int
    /// Charge level at this voltage, 0–100.
    public var percent: Int

    public init(id: UUID = UUID(), millivolts: Int, percent: Int) {
        self.id = id
        self.millivolts = millivolts
        self.percent = percent
    }
}

/// Reasons a set of anchors fails validation. Mapped to localized copy in the UI
/// layer; this type stays free of presentation strings.
public enum BatteryCurveValidationError: Equatable, Sendable {
    case tooFewAnchors
    case millivoltsOutOfRange
    case percentOutOfRange
    case voltagesNotIncreasing
    case percentsDecreasing
}

/// Conversion and validation helpers between the editor's anchor model and the
/// device's fixed 11-point OCV array.
public enum BatteryCurve {

    /// Number of points the device OCV array carries (100% … 0% in 10% steps).
    public static let pointCount = 11

    /// Valid range for an anchor's voltage, shared with `OCVPreset`.
    public static let validMillivoltRange = OCVPreset.validMillivoltRange

    /// A curve needs at least two anchors to define a slope.
    public static let minAnchors = 2

    /// Upper bound on anchors, to keep the editor list bounded.
    public static let maxAnchors = 30

    /// The percentages the device array maps, in storage order (descending).
    static var devicePercents: [Int] {
        stride(from: 100, through: 0, by: -10).map { $0 }
    }

    // MARK: - Seeding

    /// Builds editor anchors from the device's 11-point descending OCV array.
    ///
    /// The device array is indexed 100% → 0%; the returned anchors are ordered by
    /// ascending voltage (0% first, 100% last) to match the editor's layout.
    public static func anchors(fromOCVArray ocv: [Int]) -> [BatteryAnchor] {
        guard ocv.count == pointCount else {
            return anchors(fromOCVArray: OCVPreset.liIon.ocvArray)
        }
        // ocv[i] is the voltage at percent (10 - i) * 10. Walk high index → low
        // index so the result ascends in both voltage and percent.
        return (0..<pointCount).reversed().map { index in
            BatteryAnchor(millivolts: ocv[index], percent: (10 - index) * 10)
        }
    }

    // MARK: - Resampling

    /// Resamples anchors into the device's 11-point descending OCV array by linear
    /// interpolation over percent, clamping outside the anchored range.
    ///
    /// The result is forced strictly descending (the device requires each point's
    /// voltage to exceed the next) and clamped to `validMillivoltRange`.
    public static func ocvArray(fromAnchors anchors: [BatteryAnchor]) -> [Int] {
        let sorted = anchors.sorted { $0.percent < $1.percent }
        guard let first = sorted.first, let last = sorted.last else {
            return OCVPreset.liIon.ocvArray
        }

        func millivolts(atPercent target: Int) -> Double {
            if target <= first.percent { return Double(first.millivolts) }
            if target >= last.percent { return Double(last.millivolts) }
            for (lower, upper) in zip(sorted, sorted.dropFirst())
            where target >= lower.percent && target <= upper.percent {
                guard upper.percent != lower.percent else { return Double(upper.millivolts) }
                let fraction = Double(target - lower.percent) / Double(upper.percent - lower.percent)
                return Double(lower.millivolts) + fraction * Double(upper.millivolts - lower.millivolts)
            }
            return Double(last.millivolts)
        }

        var result = devicePercents.map { percent -> Int in
            let value = Int(millivolts(atPercent: percent).rounded())
            return min(max(value, validMillivoltRange.lowerBound), validMillivoltRange.upperBound)
        }

        // Guarantee a strictly descending array even where the interpolated curve
        // is flat or the clamp collapsed adjacent points.
        for index in 1..<result.count where result[index] >= result[index - 1] {
            result[index] = max(result[index - 1] - 1, validMillivoltRange.lowerBound)
        }
        return result
    }

    // MARK: - Validation

    /// Returns the first reason the anchors are invalid, or `nil` if the curve is valid.
    /// Assumes anchors are in display order (ascending voltage).
    public static func validationError(for anchors: [BatteryAnchor]) -> BatteryCurveValidationError? {
        guard anchors.count >= minAnchors else { return .tooFewAnchors }
        for anchor in anchors {
            if !validMillivoltRange.contains(anchor.millivolts) { return .millivoltsOutOfRange }
            if !(0...100).contains(anchor.percent) { return .percentOutOfRange }
        }
        for (lower, upper) in zip(anchors, anchors.dropFirst()) {
            if upper.millivolts <= lower.millivolts { return .voltagesNotIncreasing }
            if upper.percent < lower.percent { return .percentsDecreasing }
        }
        return nil
    }

    // MARK: - Mutation

    /// Returns the anchors with a new anchor inserted at the midpoint of the widest
    /// voltage gap, interpolating its percent. A no-op below two anchors or at the cap.
    public static func anchorInsertedAtLargestGap(_ anchors: [BatteryAnchor]) -> [BatteryAnchor] {
        guard anchors.count >= 2, anchors.count < maxAnchors else { return anchors }

        var widestIndex = 0
        var widestGap = Int.min
        for index in 0..<(anchors.count - 1) {
            let gap = anchors[index + 1].millivolts - anchors[index].millivolts
            if gap > widestGap {
                widestGap = gap
                widestIndex = index
            }
        }

        let lower = anchors[widestIndex]
        let upper = anchors[widestIndex + 1]
        let midpoint = BatteryAnchor(
            millivolts: (lower.millivolts + upper.millivolts) / 2,
            percent: (lower.percent + upper.percent) / 2
        )

        var result = anchors
        result.insert(midpoint, at: widestIndex + 1)
        return result
    }
}
