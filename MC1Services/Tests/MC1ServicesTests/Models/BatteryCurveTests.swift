import Foundation
import Testing
@testable import MC1Services

@Suite("BatteryCurve")
struct BatteryCurveTests {

    @Test("Seeds 11 ascending anchors from a device OCV array")
    func seedsAnchorsFromArray() {
        let anchors = BatteryCurve.anchors(fromOCVArray: OCVPreset.liIon.ocvArray)

        #expect(anchors.count == BatteryCurve.pointCount)
        // Ascending in both voltage and percent, 0% first and 100% last.
        #expect(anchors.first?.percent == 0)
        #expect(anchors.last?.percent == 100)
        #expect(anchors.map(\.percent) == [0, 10, 20, 30, 40, 50, 60, 70, 80, 90, 100])
        let voltages = anchors.map(\.millivolts)
        #expect(voltages == voltages.sorted())
    }

    @Test("Falls back to Li-Ion when the source array is not 11 points")
    func seedFallsBackOnWrongCount() {
        let anchors = BatteryCurve.anchors(fromOCVArray: [4000, 3500])
        let expected = BatteryCurve.anchors(fromOCVArray: OCVPreset.liIon.ocvArray)
        #expect(anchors.map(\.millivolts) == expected.map(\.millivolts))
    }

    @Test("Resampling a seeded preset round-trips back to the original array")
    func roundTripPreservesPreset() {
        for preset in OCVPreset.selectablePresets {
            let original = preset.ocvArray
            let anchors = BatteryCurve.anchors(fromOCVArray: original)
            let resampled = BatteryCurve.ocvArray(fromAnchors: anchors)
            #expect(resampled == original, "round-trip changed \(preset.rawValue)")
        }
    }

    @Test("Resampling always yields 11 strictly descending points")
    func resampleStrictlyDescending() {
        let anchors = [
            BatteryAnchor(millivolts: 3300, percent: 0),
            BatteryAnchor(millivolts: 3700, percent: 40),
            BatteryAnchor(millivolts: 4200, percent: 100)
        ]
        let array = BatteryCurve.ocvArray(fromAnchors: anchors)

        #expect(array.count == BatteryCurve.pointCount)
        for (current, next) in zip(array, array.dropFirst()) {
            #expect(current > next, "expected strictly descending, got \(array)")
        }
        // Endpoints clamp to the anchored extremes.
        #expect(array.first == 4200)
        #expect(array.last == 3300)
    }

    @Test("Resampling interpolates linearly between anchors")
    func resampleInterpolates() {
        let anchors = [
            BatteryAnchor(millivolts: 3000, percent: 0),
            BatteryAnchor(millivolts: 4000, percent: 100)
        ]
        let array = BatteryCurve.ocvArray(fromAnchors: anchors)
        // Device index 5 is 50%, halfway between 3000 and 4000.
        #expect(array[5] == 3500)
    }

    @Test("Validation accepts a well-formed curve")
    func validationAcceptsValid() {
        let anchors = BatteryCurve.anchors(fromOCVArray: OCVPreset.liIon.ocvArray)
        #expect(BatteryCurve.validationError(for: anchors) == nil)
    }

    @Test("Validation rejects non-increasing voltages and too-few anchors")
    func validationRejectsInvalid() {
        #expect(BatteryCurve.validationError(for: []) == .tooFewAnchors)
        #expect(BatteryCurve.validationError(for: [
            BatteryAnchor(millivolts: 3000, percent: 0)
        ]) == .tooFewAnchors)
        #expect(BatteryCurve.validationError(for: [
            BatteryAnchor(millivolts: 4000, percent: 0),
            BatteryAnchor(millivolts: 3000, percent: 100)
        ]) == .voltagesNotIncreasing)
        #expect(BatteryCurve.validationError(for: [
            BatteryAnchor(millivolts: 3000, percent: 60),
            BatteryAnchor(millivolts: 4000, percent: 40)
        ]) == .percentsDecreasing)
    }

    @Test("Insert adds an anchor at the midpoint of the widest voltage gap")
    func insertAtLargestGap() {
        let anchors = [
            BatteryAnchor(millivolts: 3000, percent: 0),
            BatteryAnchor(millivolts: 3200, percent: 20),
            BatteryAnchor(millivolts: 4200, percent: 100)
        ]
        let result = BatteryCurve.anchorInsertedAtLargestGap(anchors)

        #expect(result.count == 4)
        // Widest gap is 3200 -> 4200; midpoint is (3700, 60).
        #expect(result[2].millivolts == 3700)
        #expect(result[2].percent == 60)
        #expect(BatteryCurve.validationError(for: result) == nil)
    }

    @Test("Insert is a no-op at the anchor cap")
    func insertRespectsCap() {
        let many = (0..<BatteryCurve.maxAnchors).map {
            BatteryAnchor(millivolts: 3000 + $0 * 10, percent: $0)
        }
        #expect(BatteryCurve.anchorInsertedAtLargestGap(many).count == BatteryCurve.maxAnchors)
    }
}
