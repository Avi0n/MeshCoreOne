import CoreLocation
import MC1Services

/// Turns time-filtered snapshots into the pins and dashed trail for the location
/// history map. Pure: a function of the snapshots passed in.
///
/// Precondition: `snapshots` are in ascending timestamp order (the fetch and the
/// history filter both preserve it), so the builder never re-sorts.
enum LocationPathMapBuilder {
  /// Cap on plotted pins for the inline preview, where dense sprites are noise.
  /// The full-screen map opts out (`decimatePins: false`) so every listed report
  /// is a tappable pin. The trail always uses every fix; only pins are decimated.
  static let maxPins = 60

  /// Reports farther apart in time than this are not joined by a trail segment: a
  /// node silent this long didn't travel a straight line between the two fixes, so
  /// bridging them would draw a route that never happened. ~4× the nominal cadence.
  static let maxConnectedInterval: TimeInterval = 60 * 60

  /// A fix's report detail, surfaced in the tap callout. `id` is the source
  /// snapshot's id, so a row tap can resolve which plotted point to auto-select.
  struct LocationReport: Equatable {
    let id: UUID
    let timestamp: Date
    let altitude: Double?
  }

  struct PlottedPath {
    let points: [MapPoint]
    /// Time-ordered trail split into segments at each long silence; empty for a
    /// single fix. Flattened into one shape collection at render, so per-segment
    /// `MapLine`s are cheap.
    let lines: [MapLine]
    /// Pin id → its report, for the tap callout.
    let reports: [UUID: LocationReport]
  }

  private typealias Fix = (snapshot: NodeStatusSnapshotDTO, coordinate: CLLocationCoordinate2D)

  static func build(from snapshots: [NodeStatusSnapshotDTO], decimatePins: Bool = true) -> PlottedPath {
    let fixes: [Fix] = snapshots.compactMap { snapshot in
      guard let coordinate = snapshot.validCoordinate else { return nil }
      return (snapshot, coordinate)
    }

    guard !fixes.isEmpty else { return PlottedPath(points: [], lines: [], reports: [:]) }

    var reports: [UUID: LocationReport] = [:]
    func makePin(_ fix: Fix, _ style: MapPoint.PinStyle, recencyBucket: Int? = nil) -> MapPoint {
      let point = pin(at: fix.coordinate, style: style, recencyBucket: recencyBucket)
      reports[point.id] = LocationReport(
        id: fix.snapshot.id,
        timestamp: fix.snapshot.timestamp,
        altitude: fix.snapshot.altitude
      )
      return point
    }

    // One fix: a lone hero pin, no degenerate one-length trail.
    guard fixes.count >= 2 else {
      return PlottedPath(points: [makePin(fixes[0], .locationFixLatest)], lines: [], reports: reports)
    }

    let points = pins(for: fixes, decimate: decimatePins, makePin: makePin)
    return PlottedPath(points: points, lines: segments(for: fixes), reports: reports)
  }

  /// The most recent valid fix. Snapshots are ascending, so the last valid one is
  /// the latest fix; scans from the end to avoid building the full path.
  static func latestFix(from snapshots: [NodeStatusSnapshotDTO]) -> CLLocationCoordinate2D? {
    snapshots.reversed().lazy.compactMap(\.validCoordinate).first
  }

  /// Every sampled fix is a subtle dot (`.locationFix`) graded by recency; only the
  /// latest is the emphasized hero (`.locationFixLatest`). Decimated by stride for the
  /// inline preview; kept whole for the full-screen map so every report stays tappable.
  private static func pins(for fixes: [Fix], decimate: Bool, makePin: (Fix, MapPoint.PinStyle, Int?) -> MapPoint) -> [MapPoint] {
    // The dot fixes are every fix but the latest (which becomes the hero). Collect
    // them first so recency is bucketed by rank among the dots actually plotted:
    // every trail then spans the full ramp regardless of length or decimation.
    // Absolute-time bucketing would collapse the ramp on short or bursty tracks.
    var dotFixes = [fixes[0]]
    let interior = Array(fixes[1..<(fixes.count - 1)])
    if !interior.isEmpty {
      let step: Int
      if decimate {
        // Reserve two slots for the first dot and the hero pin appended separately.
        // Ceiling division so the interior count never exceeds the budget: a floor
        // stride can undercount and spill total pins past maxPins.
        let interiorBudget = maxPins - 2
        step = max(1, (interior.count + interiorBudget - 1) / interiorBudget)
      } else {
        step = 1
      }
      for index in stride(from: 0, to: interior.count, by: step) {
        dotFixes.append(interior[index])
      }
    }

    let lastDot = dotFixes.count - 1
    var points = dotFixes.enumerated().map { position, fix in
      makePin(fix, .locationFix, bucket(position: position, of: lastDot))
    }
    points.append(makePin(fixes[fixes.count - 1], .locationFixLatest, nil))
    return points
  }

  /// Maps a dot's rank (0 = oldest) to a recency bucket, spreading the plotted dots
  /// evenly across the full ramp so the oldest maps to bucket 0 and the newest dot to
  /// the last bucket. `lastDot` is the highest rank; 0 when only one dot is plotted.
  private static func bucket(position: Int, of lastDot: Int) -> Int {
    guard lastDot > 0 else { return 0 }
    let recency = Double(position) / Double(lastDot)
    return Int((recency * Double(PinSpriteRenderer.recencyBucketCount - 1)).rounded())
  }

  /// Splits the ascending fixes into trail segments, breaking wherever two
  /// consecutive reports are more than `maxConnectedInterval` apart. A run of a
  /// single fix contributes no segment (a one-point line is degenerate).
  private static func segments(for fixes: [Fix]) -> [MapLine] {
    var lines: [MapLine] = []
    var run: [CLLocationCoordinate2D] = [fixes[0].coordinate]

    func closeRun() {
      guard run.count >= 2 else { return }
      lines.append(MapLine(
        id: "location-trail-\(lines.count)",
        coordinates: run,
        style: .locationTrail,
        opacity: 1.0
      ))
    }

    for index in 1..<fixes.count {
      let gap = fixes[index].snapshot.timestamp.timeIntervalSince(fixes[index - 1].snapshot.timestamp)
      if gap > maxConnectedInterval {
        closeRun()
        run = [fixes[index].coordinate]
      } else {
        run.append(fixes[index].coordinate)
      }
    }
    closeRun()
    return lines
  }

  /// `recencyBucket` rides in `hopIndex` (the point's generic integer channel, as the
  /// repeater-hop styles use it) and drives the dot's per-bucket sprite name.
  private static func pin(at coordinate: CLLocationCoordinate2D, style: MapPoint.PinStyle, recencyBucket: Int? = nil) -> MapPoint {
    MapPoint(
      id: UUID(),
      coordinate: coordinate,
      pinStyle: style,
      label: nil,
      isClusterable: false,
      hopIndex: recencyBucket,
      badgeText: nil
    )
  }
}
