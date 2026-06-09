import Foundation
import Testing
import SwiftUI

@testable import MC1

/// Pure-math coverage for `RSSITuning`, the signal-strength math shared by the iOS device
/// picker (`DeviceSelectionSheet`) and the macOS scan picker (`DeviceScannerSheet`). A
/// regression in a threshold comparison or sign would degrade signal bars on both platforms
/// at once, so the deterministic helpers are pinned here.
@Suite("RSSITuning Tests")
struct RSSITuningTests {

    // Aliases so the hysteresis-band arithmetic reads against the production constants rather than
    // bare dBm literals; a threshold or hysteresis change then moves those boundaries with it. The
    // first-reading test below deliberately keeps absolute literals to pin the constants themselves.
    private let strong = RSSITuning.strongThreshold
    private let medium = RSSITuning.mediumThreshold
    private let hysteresis = RSSITuning.tierHysteresis

    // MARK: - isUsable

    @Test("isUsable accepts negative dBm readings and rejects the unavailable sentinels",
          arguments: [
            (-50, true),   // in range
            (-1, true),    // weak but real
            (-127, false), // CoreBluetooth unavailable sentinel
            (0, false),    // non-negative => unavailable
            (10, false)    // positive => unavailable
          ])
    func isUsable(rssi: Int, expected: Bool) {
        #expect(RSSITuning.isUsable(rssi) == expected)
    }

    // MARK: - smooth

    @Test("smooth returns the new sample unchanged when there is no prior reading")
    func smoothNoPrior() {
        #expect(RSSITuning.smooth(newRSSI: -50, previousRSSI: nil) == -50)
    }

    @Test("smooth weights the newest sample at 0.2 against the previous smoothed value")
    func smoothWithPrior() {
        // Int(0.2 * -50 + 0.8 * -70) == Int(-66.0) == -66
        #expect(RSSITuning.smooth(newRSSI: -50, previousRSSI: -70) == -66)
    }

    // MARK: - tier, first reading (no hysteresis)

    @Test("first reading maps directly at the tier thresholds",
          arguments: [
            (-60, RSSITuning.SignalTier.strong), // exactly strongThreshold => strong
            (-80, .medium),                      // exactly mediumThreshold => medium
            (-81, .weak)                         // below mediumThreshold => weak
          ])
    func tierFirstReading(rssi: Int, expected: RSSITuning.SignalTier) {
        // Bare dBm literals are intentional here: this is the absolute-value guard that pins the
        // production thresholds, so a silent change to strongThreshold/mediumThreshold is caught
        // rather than tracked away by the constant-derived hysteresis tests below.
        #expect(RSSITuning.tier(currentTier: nil, smoothedRSSI: rssi) == expected)
    }

    // MARK: - tier, hysteresis

    @Test("a reading inside the hysteresis band holds the current tier")
    func tierHoldsWithinBand() {
        // Strong holds until the reading drops past strongThreshold - tierHysteresis.
        #expect(RSSITuning.tier(currentTier: .strong, smoothedRSSI: strong - hysteresis) == .strong)
        // Medium holds within its band (between the two thresholds).
        #expect(RSSITuning.tier(currentTier: .medium, smoothedRSSI: -70) == .medium)
        // Weak holds until the reading clears mediumThreshold + tierHysteresis.
        #expect(RSSITuning.tier(currentTier: .weak, smoothedRSSI: medium + hysteresis - 1) == .weak)
    }

    @Test("a reading clearing the band by the hysteresis margin flips the tier")
    func tierFlipsPastBand() {
        // Weak -> medium once the reading reaches mediumThreshold + tierHysteresis.
        #expect(RSSITuning.tier(currentTier: .weak, smoothedRSSI: medium + hysteresis) == .medium)
        // Strong -> weak once the reading drops below mediumThreshold - tierHysteresis.
        #expect(RSSITuning.tier(currentTier: .strong, smoothedRSSI: medium - hysteresis - 1) == .weak)
    }

    @Test("from medium, a reading moves up, drops, or holds at the band edges")
    func tierFromMedium() {
        // Holds within the band.
        #expect(RSSITuning.tier(currentTier: .medium, smoothedRSSI: -70) == .medium)
        // Up to strong once the reading reaches strongThreshold + tierHysteresis.
        #expect(RSSITuning.tier(currentTier: .medium, smoothedRSSI: strong + hysteresis) == .strong)
        // Down to weak only on a strict drop below mediumThreshold - tierHysteresis: the boundary
        // value itself (-83) holds, so the drop is asserted one dBm lower (-84).
        #expect(RSSITuning.tier(currentTier: .medium, smoothedRSSI: medium - hysteresis) == .medium)
        #expect(RSSITuning.tier(currentTier: .medium, smoothedRSSI: medium - hysteresis - 1) == .weak)
    }

    @Test("a single strong reading jumps weak straight to strong")
    func tierDoubleJumpFromWeak() {
        #expect(RSSITuning.tier(currentTier: .weak, smoothedRSSI: strong + hysteresis) == .strong)
    }

    // MARK: - fillLevel / color

    @Test("fillLevel maps each tier to its glyph fill",
          arguments: [
            (RSSITuning.SignalTier.strong, 1.0),
            (.medium, 0.66),
            (.weak, 0.33)
          ])
    func fillLevel(tier: RSSITuning.SignalTier, expected: Double) {
        #expect(RSSITuning.fillLevel(forTier: tier) == expected)
    }

    @Test("color maps each tier to its glyph color")
    func colorPerTier() {
        #expect(RSSITuning.color(forTier: .strong) == .green)
        #expect(RSSITuning.color(forTier: .medium) == .yellow)
        #expect(RSSITuning.color(forTier: .weak) == .red)
    }
}
