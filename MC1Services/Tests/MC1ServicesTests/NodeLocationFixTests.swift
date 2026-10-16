import CoreLocation
@testable import MC1Services
import MeshCore
import Testing

@Suite("NodeLocationFix Tests")
struct NodeLocationFixTests {
  private func gpsPoint(channel: UInt8, lat: Double, lon: Double) -> LPPDataPoint {
    LPPDataPoint(channel: channel, type: .gps, value: .gps(latitude: lat, longitude: lon, altitude: 0))
  }

  @Test
  func `Primary fix is the first valid GPS point`() {
    let points = [
      LPPDataPoint(channel: 1, type: .temperature, value: .float(21.5)),
      gpsPoint(channel: 2, lat: 37.7749, lon: -122.4194),
    ]
    let fix = NodeLocationFix.primaryFix(from: points)
    #expect(fix?.latitude == 37.7749)
    #expect(fix?.longitude == -122.4194)
  }

  @Test
  func `A (0,0) fix is dropped`() {
    let fix = NodeLocationFix.primaryFix(from: [gpsPoint(channel: 1, lat: 0, lon: 0)])
    #expect(fix == nil)
  }

  @Test
  func `An out-of-range fix is dropped`() {
    let fix = NodeLocationFix.primaryFix(from: [gpsPoint(channel: 1, lat: 999, lon: 999)])
    #expect(fix == nil)
  }

  @Test
  func `No GPS point yields no fix`() {
    let points = [LPPDataPoint(channel: 1, type: .temperature, value: .float(21.5))]
    #expect(NodeLocationFix.primaryFix(from: points) == nil)
  }
}
