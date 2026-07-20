import CoreLocation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("LocationPathMapBuilder")
struct LocationPathMapBuilderTests {
  /// A single base captured once per test instance, so offsets produce exact gaps.
  /// Calling `Date()` per fixture would drift each timestamp by the call latency,
  /// pushing an intended-60-min gap microseconds over the connected-interval bound.
  private let base = Date()

  /// `NodeStatusSnapshotDTO.testSnapshot` lives in the MC1ServicesTests SwiftPM test target,
  /// which MC1Tests (an Xcode app-test target) does not link, so this builds fixtures directly
  /// from the DTO's real public initializer instead.
  private func snapshot(offsetHours: Int, lat: Double?, lon: Double?) -> NodeStatusSnapshotDTO {
    snapshot(offsetMinutes: Double(offsetHours) * 60, lat: lat, lon: lon)
  }

  private func snapshot(offsetMinutes: Double, lat: Double?, lon: Double?, alt: Double? = nil) -> NodeStatusSnapshotDTO {
    let timestamp = base.addingTimeInterval(offsetMinutes * 60)
    return NodeStatusSnapshotDTO(
      timestamp: timestamp,
      nodePublicKey: Data(repeating: 0xDD, count: 32),
      latitude: lat,
      longitude: lon,
      altitude: alt
    )
  }

  @Test
  func `Two valid fixes yield a trail segment, a dot, and the hero`() {
    let built = LocationPathMapBuilder.build(from: [
      snapshot(offsetHours: -2, lat: 37.0, lon: -122.0),
      snapshot(offsetHours: -1, lat: 37.1, lon: -122.1),
    ])
    #expect(built.lines.count == 1)
    #expect(built.lines.first?.coordinates.count == 2)
    #expect(built.lines.first?.style == .locationTrail)
    #expect(built.points.first?.pinStyle == .locationFix)
    #expect(built.points.last?.pinStyle == .locationFixLatest)
  }

  @Test
  func `A single valid fix yields a hero pin and no line`() {
    let built = LocationPathMapBuilder.build(from: [snapshot(offsetHours: -1, lat: 37.0, lon: -122.0)])
    #expect(built.lines.isEmpty)
    #expect(built.points.count == 1)
    #expect(built.points.first?.pinStyle == .locationFixLatest, "A lone report is the latest, so the hero pin")
  }

  @Test
  func `Snapshots without a valid fix are excluded`() {
    let built = LocationPathMapBuilder.build(from: [
      snapshot(offsetHours: -3, lat: nil, lon: nil),
      snapshot(offsetHours: -2, lat: 0, lon: 0),
      snapshot(offsetHours: -1, lat: 37.0, lon: -122.0),
    ])
    #expect(built.lines.isEmpty)
    #expect(built.points.count == 1)
  }

  @Test
  func `Empty input yields no path`() {
    let built = LocationPathMapBuilder.build(from: [])
    #expect(built.points.isEmpty)
    #expect(built.lines.isEmpty)
    #expect(built.reports.isEmpty)
  }

  @Test
  func `Trail preserves ascending input order`() {
    let built = LocationPathMapBuilder.build(from: [
      snapshot(offsetMinutes: -30, lat: 37.0, lon: -122.0),
      snapshot(offsetMinutes: -20, lat: 38.0, lon: -123.0),
      snapshot(offsetMinutes: -10, lat: 39.0, lon: -124.0),
    ])
    #expect(built.lines.count == 1)
    #expect(built.lines.first?.coordinates.first?.latitude == 37.0)
    #expect(built.lines.first?.coordinates.last?.latitude == 39.0)
  }

  // MARK: - Gap-break segmentation

  @Test
  func `A long silence splits the trail into two segments`() {
    // Two 10-min-apart runs separated by a 140-min gap (> the 60-min threshold).
    let built = LocationPathMapBuilder.build(from: [
      snapshot(offsetMinutes: -200, lat: 37.0, lon: -122.0),
      snapshot(offsetMinutes: -190, lat: 37.1, lon: -122.0),
      snapshot(offsetMinutes: -50, lat: 38.0, lon: -123.0),
      snapshot(offsetMinutes: -40, lat: 38.1, lon: -123.0),
    ])
    #expect(built.lines.count == 2, "No phantom segment bridges the silence")
    #expect(built.lines.allSatisfy { $0.coordinates.count == 2 })
    #expect(built.lines.map(\.id).count == Set(built.lines.map(\.id)).count, "Segment ids are unique")
  }

  @Test
  func `Fixes within the connected interval stay one segment`() {
    let built = LocationPathMapBuilder.build(from: (0..<5).map { i in
      snapshot(offsetMinutes: Double(-60 + i * 15), lat: 37.0 + Double(i) * 0.01, lon: -122.0)
    })
    #expect(built.lines.count == 1, "15-min cadence never exceeds the 60-min threshold")
    #expect(built.lines.first?.coordinates.count == 5)
  }

  @Test
  func `A lone fix on the far side of a gap contributes no segment`() {
    // Run of two, a gap, then a single fix: the singleton makes no degenerate line.
    let built = LocationPathMapBuilder.build(from: [
      snapshot(offsetMinutes: -200, lat: 37.0, lon: -122.0),
      snapshot(offsetMinutes: -190, lat: 37.1, lon: -122.0),
      snapshot(offsetMinutes: -10, lat: 38.0, lon: -123.0),
    ])
    #expect(built.lines.count == 1)
    #expect(built.lines.first?.coordinates.count == 2)
  }

  // MARK: - Report side table

  @Test
  func `Every pin maps to a report carrying its timestamp and altitude`() {
    let snapshots = [
      snapshot(offsetMinutes: -30, lat: 37.0, lon: -122.0, alt: 10),
      snapshot(offsetMinutes: -15, lat: 37.1, lon: -122.1, alt: nil),
      snapshot(offsetMinutes: -1, lat: 37.2, lon: -122.2, alt: 42),
    ]
    let built = LocationPathMapBuilder.build(from: snapshots)
    #expect(built.reports.count == built.points.count)
    for point in built.points {
      #expect(built.reports[point.id] != nil)
    }
    // Each report carries its source snapshot's id, the key a row tap resolves to
    // auto-select the point. Every snapshot id appears exactly once.
    let reportedIDs = Set(built.reports.values.map(\.id))
    #expect(reportedIDs == Set(snapshots.map(\.id)))
    // The latest (hero) pin carries the last report's altitude and snapshot id.
    if let heroID = built.points.last?.id {
      #expect(built.reports[heroID]?.altitude == 42)
      #expect(built.reports[heroID]?.id == snapshots.last?.id)
    }
  }

  // MARK: - Decimation

  @Test
  func `Intermediate pins are decimated under a wide set`() {
    let snapshots = (0..<200).map { i in
      snapshot(offsetMinutes: Double(-200 + i) * 10, lat: 37.0 + Double(i) * 0.001, lon: -122.0)
    }
    let built = LocationPathMapBuilder.build(from: snapshots)
    // 198 interior fixes decimated into a 58-pin budget (ceiling stride 4) plus the two
    // endpoints yields exactly 52; pinned so endpoints-only or over-cap both fail.
    #expect(built.points.count == 52)
    #expect(built.points.count <= LocationPathMapBuilder.maxPins)
    // 10-min spacing keeps every fix connected, so the trail is one segment of all 200.
    #expect(built.lines.first?.coordinates.count == 200, "The trail still uses every fix")
  }

  @Test
  func `Decimation off keeps a pin for every fix`() {
    let snapshots = (0..<200).map { i in
      snapshot(offsetMinutes: Double(-200 + i) * 10, lat: 37.0 + Double(i) * 0.001, lon: -122.0)
    }
    let built = LocationPathMapBuilder.build(from: snapshots, decimatePins: false)
    #expect(built.points.count == 200, "The full-screen map keeps every report tappable")
    #expect(built.reports.count == 200)
  }

  @Test
  func `Only the latest fix is the hero; every earlier fix is a dot`() {
    let snapshots = (0..<5).map { i in
      snapshot(offsetMinutes: Double(-50 + i * 10), lat: 37.0 + Double(i) * 0.01, lon: -122.0)
    }
    let built = LocationPathMapBuilder.build(from: snapshots, decimatePins: false)
    #expect(built.points.dropLast().allSatisfy { $0.pinStyle == .locationFix })
    #expect(built.points.last?.pinStyle == .locationFixLatest)
  }

  // MARK: - Recency grading

  @Test
  func `Dots are graded oldest to newest across the full recency ramp`() {
    // Nine fixes → eight dots + the hero. Rank binning spreads the dots across every
    // recency bucket, with the oldest at 0 and the newest dot at the last bucket.
    let snapshots = (0..<9).map { i in
      snapshot(offsetMinutes: Double(-80 + i * 10), lat: 37.0 + Double(i) * 0.01, lon: -122.0)
    }
    let built = LocationPathMapBuilder.build(from: snapshots, decimatePins: false)
    let buckets = built.points.dropLast().compactMap(\.hopIndex) // every point but the hero

    #expect(buckets.first == 0, "The oldest fix is the coolest bucket")
    #expect(buckets.last == PinSpriteRenderer.recencyBucketCount - 1, "The newest dot is the hottest bucket")
    #expect(buckets == buckets.sorted(), "Recency rises monotonically toward the present")
    #expect(Set(buckets).count == PinSpriteRenderer.recencyBucketCount, "Every bucket in the ramp is used")
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

  // MARK: - Seeded simulator track

  @Test
  func `The seeded demo track plots as a gap-broken trail with altitudes`() {
    // The simulator seeds this exact array (ascending time), so building it here
    // proves the demo screen renders the intended map, not just that the code compiles.
    let seeded = MockDataProvider.nodeStatusSnapshots
    let built = LocationPathMapBuilder.build(from: seeded, decimatePins: false)

    #expect(built.points.count == seeded.count, "Every seeded fix is a tappable pin on the full map")
    #expect(built.lines.count == 5, "Four dated outings, with today's split by its mid-run pause, draw as five segments")
    #expect(built.lines.allSatisfy { $0.style == .locationTrail })
    #expect(built.points.last?.pinStyle == .locationFixLatest, "The newest fix is the hero pin")
    // One seeded fix reports no altitude; the rest do.
    let altitudes = seeded.map(\.altitude)
    #expect(altitudes.contains(where: { $0 == nil }))
    #expect(altitudes.count(where: { $0 != nil }) == seeded.count - 1)
  }

  @Test
  func `The seeded track spans time-filter bands so each range shows a distinct count`() {
    // Mirrors how the History screens narrow snapshots by the picker's cutoff
    // (timestamp >= start). Each successive range must reveal strictly more fixes,
    // so switching the filter visibly changes the rows and the plotted points.
    let seeded = MockDataProvider.nodeStatusSnapshots
    let now = Date()
    func countWithin(days: Int?) -> Int {
      guard let days else { return seeded.count }
      let cutoff = now.addingTimeInterval(-Double(days) * 24 * 60 * 60)
      return seeded.count(where: { $0.timestamp >= cutoff })
    }
    let week = countWithin(days: 7)
    let month = countWithin(days: 30)
    let threeMonths = countWithin(days: 90)
    let all = countWithin(days: nil)
    #expect(week < month, "The 14-day outing appears only once the range reaches a month")
    #expect(month < threeMonths, "The 55-day outing appears only under 3 months or wider")
    #expect(threeMonths < all, "The 180-day outing appears only under the all-time range")
  }

  @Test
  func `latestFix returns nil when no snapshot has a valid fix`() {
    let latest = LocationPathMapBuilder.latestFix(from: [
      snapshot(offsetHours: -2, lat: nil, lon: nil),
      snapshot(offsetHours: -1, lat: 0, lon: 0),
    ])
    #expect(latest == nil)
  }
}
