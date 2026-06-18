import Foundation
import Testing
@testable import MC1
@testable import MC1Services

/// Behavioral coverage for `RSSIScanTracker`, the scan-orchestration model shared by the iOS
/// device picker (`DeviceSelectionSheet`) and the macOS scan picker (`DeviceScannerSheet`). The
/// pure signal math lives in `RSSITuning` (see `RSSITuningTests`); this suite pins the stateful
/// wiring around it — RSSI smoothing across packets, name preservation, the unusable-reading skip,
/// and stale-peripheral expiry — so a silent regression in either picker's device list is caught.
@Suite("RSSIScanTracker Tests")
@MainActor
struct RSSIScanTrackerTests {

    /// A finished stream of the given discoveries, so `consume` drains and returns without its
    /// background expiry sweep firing — the first sweep is one `RSSITuning.expiryTick` away and the
    /// sweep task is cancelled the moment the finished stream completes.
    private func finishedStream(_ discoveries: [DiscoveredDevice]) -> AsyncStream<DiscoveredDevice> {
        AsyncStream { continuation in
            for discovery in discoveries { continuation.yield(discovery) }
            continuation.finish()
        }
    }

    @Test("consume ingests a usable discovery and exposes its name and tier")
    func ingestsUsableDiscovery() async {
        let tracker = RSSIScanTracker()
        let id = UUID()

        await tracker.consume(finishedStream([
            DiscoveredDevice(id: id, name: "Radio", rssi: -50)
        ]))

        #expect(tracker.isAdvertising(id))
        #expect(tracker.devices[id]?.name == "Radio")
        // -50 dBm is at or above strongThreshold, so a first reading maps to strong.
        #expect(tracker.signalTier(for: id) == .strong)
    }

    @Test("a later packet without a name keeps the previously advertised name and smooths RSSI")
    func preservesNameAndSmooths() async {
        let tracker = RSSIScanTracker()
        let id = UUID()

        await tracker.consume(finishedStream([
            DiscoveredDevice(id: id, name: "Radio", rssi: -50),
            DiscoveredDevice(id: id, name: nil, rssi: -55)
        ]))

        #expect(tracker.devices[id]?.name == "Radio")
        #expect(tracker.devices[id]?.rssi == RSSITuning.smooth(newRSSI: -55, previousRSSI: -50))
    }

    @Test("unknown ids report no tier and are not advertising")
    func unknownIdsAreEmpty() {
        let tracker = RSSIScanTracker()

        #expect(tracker.signalTier(for: UUID()) == nil)
        #expect(tracker.isAdvertising(UUID()) == false)
    }

    @Test("an unusable reading is skipped and creates no entry",
          arguments: [RSSITuning.unavailableRSSI, 0, 10])
    func skipsUnusableReadings(rssi: Int) async {
        let tracker = RSSIScanTracker()
        let id = UUID()

        await tracker.consume(finishedStream([
            DiscoveredDevice(id: id, name: "Radio", rssi: rssi)
        ]))

        #expect(tracker.isAdvertising(id) == false)
        #expect(tracker.signalTier(for: id) == nil)
    }

    @Test("expireStale drops a peripheral last seen before the stale window")
    func expiresStalePeripheral() async {
        let tracker = RSSIScanTracker()
        let id = UUID()
        await tracker.consume(finishedStream([
            DiscoveredDevice(id: id, name: "Radio", rssi: -50)
        ]))
        #expect(tracker.isAdvertising(id))

        // Sweep from a point past the stale window so the just-ingested entry is stale.
        tracker.expireStale(asOf: Date.now.addingTimeInterval(RSSITuning.staleWindow + 1))

        #expect(tracker.isAdvertising(id) == false)
        #expect(tracker.signalTier(for: id) == nil)
        #expect(tracker.devices[id] == nil)
    }

    @Test("expireStale keeps a freshly seen peripheral")
    func keepsFreshPeripheral() async {
        let tracker = RSSIScanTracker()
        let id = UUID()
        await tracker.consume(finishedStream([
            DiscoveredDevice(id: id, name: "Radio", rssi: -50)
        ]))

        tracker.expireStale(asOf: .now)

        #expect(tracker.isAdvertising(id))
    }
}
