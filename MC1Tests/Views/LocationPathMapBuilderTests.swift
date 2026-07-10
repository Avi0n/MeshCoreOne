import CoreLocation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("LocationPathMapBuilder")
struct LocationPathMapBuilderTests {
  /// `NodeStatusSnapshotDTO.testSnapshot` lives in the MC1ServicesTests SwiftPM test target,
  /// which MC1Tests (an Xcode app-test target) does not link, so this builds fixtures directly
  /// from the DTO's real public initializer instead.
  private func snapshot(offsetHours: Int, lat: Double?, lon: Double?) -> NodeStatusSnapshotDTO {
    let timestamp = Date().addingTimeInterval(TimeInterval(offsetHours) * 3600)
    return NodeStatusSnapshotDTO(
      timestamp: timestamp,
      nodePublicKey: Data(repeating: 0xDD, count: 32),
      latitude: lat,
      longitude: lon
    )
  }

  @Test
  func `Two valid fixes yield a polyline and endpoint pins`() {
    let built = LocationPathMapBuilder.build(from: [
      snapshot(offsetHours: -2, lat: 37.0, lon: -122.0),
      snapshot(offsetHours: -1, lat: 37.1, lon: -122.1),
    ])
    #expect(built.line != nil)
    #expect(built.line?.coordinates.count == 2)
    #expect(built.points.first?.pinStyle == .pointA)
    #expect(built.points.last?.pinStyle == .pointB)
  }

  @Test
  func `A single valid fix yields a pin and no line`() {
    let built = LocationPathMapBuilder.build(from: [snapshot(offsetHours: -1, lat: 37.0, lon: -122.0)])
    #expect(built.line == nil)
    #expect(built.points.count == 1)
  }

  @Test
  func `Snapshots without a valid fix are excluded`() {
    let built = LocationPathMapBuilder.build(from: [
      snapshot(offsetHours: -3, lat: nil, lon: nil),
      snapshot(offsetHours: -2, lat: 0, lon: 0),
      snapshot(offsetHours: -1, lat: 37.0, lon: -122.0),
    ])
    #expect(built.line == nil)
    #expect(built.points.count == 1)
  }

  @Test
  func `Empty input yields no path`() {
    let built = LocationPathMapBuilder.build(from: [])
    #expect(built.points.isEmpty)
    #expect(built.line == nil)
  }

  @Test
  func `Polyline preserves ascending input order`() {
    let built = LocationPathMapBuilder.build(from: [
      snapshot(offsetHours: -3, lat: 37.0, lon: -122.0),
      snapshot(offsetHours: -2, lat: 38.0, lon: -123.0),
      snapshot(offsetHours: -1, lat: 39.0, lon: -124.0),
    ])
    #expect(built.line?.coordinates.first?.latitude == 37.0)
    #expect(built.line?.coordinates.last?.latitude == 39.0)
  }

  @Test
  func `Intermediate pins are decimated under a wide set`() {
    let snapshots = (0..<200).map { i in
      snapshot(offsetHours: -200 + i, lat: 37.0 + Double(i) * 0.001, lon: -122.0)
    }
    let built = LocationPathMapBuilder.build(from: snapshots)
    // 198 interior fixes decimated into a 58-pin budget (ceiling stride 4) plus the two
    // endpoints yields exactly 52; pinned so endpoints-only or over-cap both fail.
    #expect(built.points.count == 52)
    #expect(built.points.count <= LocationPathMapBuilder.maxPins)
    #expect(built.line?.coordinates.count == 200, "The polyline still uses every fix")
  }

  @Test
  func `latestFix returns the last valid fix after trailing invalid snapshots`() {
    let latest = LocationPathMapBuilder.latestFix(from: [
      snapshot(offsetHours: -3, lat: 37.0, lon: -122.0),
      snapshot(offsetHours: -2, lat: 38.0, lon: -123.0),
      snapshot(offsetHours: -1, lat: nil, lon: nil),
    ])
    #expect(latest?.latitude == 38.0)
    #expect(latest?.longitude == -123.0)
  }

  @Test
  func `latestFix returns nil when no snapshot has a valid fix`() {
    let latest = LocationPathMapBuilder.latestFix(from: [
      snapshot(offsetHours: -2, lat: nil, lon: nil),
      snapshot(offsetHours: -1, lat: 0, lon: 0),
    ])
    #expect(latest == nil)
  }

  @Test
  func `Exactly maxPins-plus-one fixes stay within the pin cap`() {
    let count = LocationPathMapBuilder.maxPins + 1
    let snapshots = (0..<count).map { i in
      snapshot(offsetHours: -count + i, lat: 37.0 + Double(i) * 0.001, lon: -122.0)
    }
    let built = LocationPathMapBuilder.build(from: snapshots)
    #expect(built.points.count <= LocationPathMapBuilder.maxPins)
    #expect(built.line?.coordinates.count == count, "The polyline still uses every fix")
  }
}
