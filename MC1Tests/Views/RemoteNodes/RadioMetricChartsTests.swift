import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Radio metric history charts", .bug("https://github.com/Avi0n/MeshCoreOne/issues/463"))
@MainActor
struct RadioMetricChartsTests {
  private let publicKey = Data(repeating: 0xA1, count: 32)
  private let start = Date(timeIntervalSince1970: 1_700_000_000)

  @Test
  func `suspect status sample cannot set the shared packet chart scale`() {
    let valid = NodeStatusSnapshotDTO(
      timestamp: start, nodePublicKey: publicKey,
      batteryMillivolts: 4000, lastSNR: 8, lastRSSI: -90, noiseFloor: -110,
      sentFlood: 1200, receivedFlood: 4000
    )
    let plotted = charts(for: [valid, suspectSnapshot()]).snapshots
    #expect(plotted == [valid])

    let sent = plotted.compactMap { snapshot in
      snapshot.sentFlood.map {
        MetricChartView.DataPoint(id: snapshot.id, date: snapshot.timestamp, value: Double($0))
      }
    }
    let received = plotted.compactMap { snapshot in
      snapshot.receivedFlood.map {
        MetricChartView.DataPoint(id: snapshot.id, date: snapshot.timestamp, value: Double($0))
      }
    }
    let domain = [MetricChartView.DataPoint].sharedDomain(for: [sent, received])
    #expect(domain == 0...4200)
  }

  @Test(arguments: [
    (Double.nan, Int16(-90), Int16(-110)), (-32.25, -90, -110), (32, -90, -110),
    (8, -257, -110), (8, 99, -110), (8, -90, -257), (8, -90, 99),
  ])
  func `one invalid signal excludes the whole status group`(snr: Double, rssi: Int16, noise: Int16) {
    let snapshot = NodeStatusSnapshotDTO(
      nodePublicKey: publicKey, lastSNR: snr, lastRSSI: rssi, noiseFloor: noise, sentFlood: 1200
    )
    #expect(charts(for: [snapshot]).snapshots.isEmpty)
  }

  @Test(arguments: [(-32.0, Int16(-256), Int16(98)), (31.75, 98, -256), (0, 0, 0)])
  func `driver boundaries keep high battery readings and large counters`(snr: Double, rssi: Int16, noise: Int16) {
    let snapshot = NodeStatusSnapshotDTO(
      nodePublicKey: publicKey, batteryMillivolts: 35000,
      lastSNR: snr, lastRSSI: rssi, noiseFloor: noise,
      uptimeSeconds: 1, sentFlood: .max
    )
    #expect(charts(for: [snapshot]).snapshots == [snapshot])
  }

  @Test
  func `missing signals and zero battery remain chartable`() {
    let snapshot = NodeStatusSnapshotDTO(nodePublicKey: publicKey, batteryMillivolts: 0, sentFlood: 10)
    #expect(charts(for: [snapshot]).snapshots == [snapshot])
  }

  @Test
  func `overview radio section stays hidden when all status samples are suspect`() {
    let viewModel = TelemetryHistoryOverviewViewModel()
    let valid = NodeStatusSnapshotDTO(nodePublicKey: publicKey, sentFlood: 1200)
    #expect(!viewModel.hasRadioData(in: [suspectSnapshot()]))
    #expect(viewModel.hasRadioData(in: [suspectSnapshot(), valid]))
  }

  private func suspectSnapshot() -> NodeStatusSnapshotDTO {
    NodeStatusSnapshotDTO(
      timestamp: start.addingTimeInterval(86400), nodePublicKey: publicKey,
      batteryMillivolts: 0, lastSNR: 2700, lastRSSI: 8500, noiseFloor: 8500,
      uptimeSeconds: 10_000_000, receiveErrors: 450_000_000,
      sentDirect: 400_000_000, sentFlood: 1_600_000_000,
      receivedDirect: 1_000_000_000, receivedFlood: 4100
    )
  }

  private func charts(for snapshots: [NodeStatusSnapshotDTO]) -> RadioMetricCharts<MetricChartView, PacketChartsGroup> {
    RadioMetricCharts(snapshots: snapshots, ocvArray: OCVPreset.liIon.ocvArray) { $0 } packetSection: { $0 }
  }
}
