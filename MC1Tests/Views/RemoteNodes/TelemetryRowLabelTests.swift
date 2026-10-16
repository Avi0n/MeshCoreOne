import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Telemetry row labels")
struct TelemetryRowLabelTests {
  @Test
  func `equal temperatures on one channel still label the second as MCU`() {
    let ambient = LPPDataPoint(channel: 1, type: .temperature, value: .float(21))
    let mcu = LPPDataPoint(channel: 1, type: .temperature, value: .float(21))
    let points = [ambient, mcu]
    #expect(
      telemetryLabel(for: ambient, at: 0, in: points)
        == L10n.RemoteNodes.RemoteNodes.Status.Sensor.temperature
    )
    #expect(
      telemetryLabel(for: mcu, at: 1, in: points)
        == L10n.RemoteNodes.RemoteNodes.Status.Sensor.mcuTemperature
    )
  }
}
