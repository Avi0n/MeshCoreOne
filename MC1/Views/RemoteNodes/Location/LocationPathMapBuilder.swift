import CoreLocation
import MC1Services

/// Turns time-filtered snapshots into the pins and polyline for the location
/// history map. Pure: a function of the snapshots passed in.
///
/// Precondition: `snapshots` are in ascending timestamp order (the fetch and the
/// history filter both preserve it), so the builder never re-sorts.
enum LocationPathMapBuilder {
  /// Cap on plotted pins. The polyline still uses every fix; only the pin sprites
  /// are decimated, since a wide range can retain one fix per 15-minute window.
  static let maxPins = 60

  struct PlottedPath {
    let points: [MapPoint]
    let line: MapLine?
  }

  static func build(from snapshots: [NodeStatusSnapshotDTO]) -> PlottedPath {
    let coordinates = snapshots.compactMap(\.validCoordinate)

    guard !coordinates.isEmpty else { return PlottedPath(points: [], line: nil) }

    // One fix: a lone pin, no degenerate one-length polyline (matches MessagePathMapView).
    guard coordinates.count >= 2 else {
      return PlottedPath(points: [pin(at: coordinates[0], style: .droppedPin)], line: nil)
    }

    let line = MapLine(
      id: "location-path",
      coordinates: coordinates,
      style: .messagePath,
      opacity: 1.0
    )
    return PlottedPath(points: pins(for: coordinates), line: line)
  }

  /// The most recent valid fix. Snapshots are ascending, so the last valid one is
  /// the latest fix; scans from the end to avoid building the full path.
  static func latestFix(from snapshots: [NodeStatusSnapshotDTO]) -> CLLocationCoordinate2D? {
    snapshots.reversed().lazy.compactMap(\.validCoordinate).first
  }

  /// Start (`.pointA`) and latest (`.pointB`) emphasized; intermediate fixes as
  /// subtle dots, decimated by stride so a wide range stays legible.
  private static func pins(for coordinates: [CLLocationCoordinate2D]) -> [MapPoint] {
    var points = [pin(at: coordinates[0], style: .pointA)]

    let interior = Array(coordinates[1..<(coordinates.count - 1)])
    if !interior.isEmpty {
      // Reserve two slots for the start (.pointA) and end (.pointB) pins appended
      // separately, so interior decimation is bounded to the remaining budget.
      // Ceiling division so the interior count never exceeds it: a floor stride
      // can undercount and let the total pins spill past `maxPins`.
      let interiorBudget = maxPins - 2
      let step = max(1, (interior.count + interiorBudget - 1) / interiorBudget)
      for index in stride(from: 0, to: interior.count, by: step) {
        points.append(pin(at: interior[index], style: .droppedPin))
      }
    }

    points.append(pin(at: coordinates[coordinates.count - 1], style: .pointB))
    return points
  }

  private static func pin(at coordinate: CLLocationCoordinate2D, style: MapPoint.PinStyle) -> MapPoint {
    MapPoint(
      id: UUID(),
      coordinate: coordinate,
      pinStyle: style,
      label: nil,
      isClusterable: false,
      hopIndex: nil,
      badgeText: nil
    )
  }
}
