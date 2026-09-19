import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("ChannelGroup Tests")
struct ChannelGroupTests {
  private func snapshot(id: Int, entries: [TelemetrySnapshotEntry]) -> NodeStatusSnapshotDTO {
    NodeStatusSnapshotDTO(
      id: UUID(uuidString: String(format: "00000000-0000-0000-0000-%012d", id))!,
      nodePublicKey: Data(repeating: 1, count: 32),
      telemetryEntries: entries
    )
  }

  @Test
  func `single temperature on channel 1 stays one Temperature chart`() {
    let snapshots = [
      snapshot(id: 1, entries: [.init(channel: 1, type: "Temperature", value: 21)]),
    ]
    let groups = ChannelGroup.groups(from: snapshots)
    #expect(groups.count == 1)
    #expect(groups[0].charts.count == 1)
    #expect(groups[0].charts[0].title == L10n.RemoteNodes.RemoteNodes.Status.Sensor.temperature)
  }

  @Test
  func `two temperatures in one snapshot split into Temperature and MCU temperature`() {
    let snapshots = [
      snapshot(id: 1, entries: [
        .init(channel: 1, type: "Temperature", value: 21),
        .init(channel: 1, type: "Temperature", value: 38),
      ]),
    ]
    let groups = ChannelGroup.groups(from: snapshots)
    let titles = groups[0].charts.map(\.title)
    #expect(titles.contains(L10n.RemoteNodes.RemoteNodes.Status.Sensor.temperature))
    #expect(titles.contains(L10n.RemoteNodes.RemoteNodes.Status.Sensor.mcuTemperature))
  }

  @Test
  func `first temperature in a later snapshot is not MCU`() {
    let snapshots = [
      snapshot(id: 1, entries: [.init(channel: 1, type: "Temperature", value: 21)]),
      snapshot(id: 2, entries: [.init(channel: 1, type: "Temperature", value: 22)]),
    ]
    let groups = ChannelGroup.groups(from: snapshots)
    #expect(groups[0].charts.count == 1)
    #expect(groups[0].charts[0].dataPoints.count == 2)
    #expect(groups[0].charts[0].title == L10n.RemoteNodes.RemoteNodes.Status.Sensor.temperature)
  }
}
