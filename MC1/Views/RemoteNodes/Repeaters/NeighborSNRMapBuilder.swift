import CoreLocation
import MapKit
import MC1Services

/// Builds the pins, lines, and camera region for the neighbor SNR map from data already in hand.
/// It is a pure builder rather than a view model: the content is a function of the session, its
/// neighbors, and the resolver inputs, with no per-connection dependency and nothing live to read.
/// `PlottedNeighbors` holds non-`Sendable` MapKit types, so it is built and consumed on `@MainActor`.
enum NeighborSNRMapBuilder {
  struct PlottedNeighbors {
    let points: [MapPoint]
    let lines: [MapLine]
    let region: MKCoordinateRegion?
    let unplottable: [UnplottableNeighbor]
  }

  /// A neighbor that could not be placed reliably (ambiguous match, no location, an invalid
  /// coordinate, or unresolved), carried with its resolved name and confidence for the list card.
  struct UnplottableNeighbor {
    let neighbor: NeighbourInfo
    let displayName: String
    let matchKind: NodeNameMatchKind
  }

  private static let lineOpacity = 1.0

  static func build(
    session: RemoteNodeSessionDTO,
    neighbors: [NeighbourInfo],
    contacts: [ContactDTO],
    discoveredNodes: [DiscoveredNodeDTO],
    userLocation: CLLocation?
  ) -> PlottedNeighbors {
    var points: [MapPoint] = []
    var lines: [MapLine] = []
    var unplottable: [UnplottableNeighbor] = []
    var plottedCoordinates: [CLLocationCoordinate2D] = []

    let centerCoordinate = session.coordinate
    if let centerCoordinate {
      points.append(MapPoint(
        id: UUID(),
        coordinate: centerCoordinate,
        pinStyle: .repeaterRingWhite,
        label: session.name,
        isClusterable: false,
        hopIndex: nil,
        badgeText: nil
      ))
      plottedCoordinates.append(centerCoordinate)
    }

    for (index, neighbor) in neighbors.enumerated() {
      guard let resolved = NeighborNameResolver.resolveLocated(
        for: neighbor.publicKeyPrefix,
        contacts: contacts,
        discoveredNodes: discoveredNodes,
        userLocation: userLocation
      ) else {
        unplottable.append(UnplottableNeighbor(
          neighbor: neighbor,
          displayName: NeighborNameResolver.fallbackName(for: neighbor.publicKeyPrefix),
          matchKind: .unresolved
        ))
        continue
      }

      // Only an exact identity match with a trustworthy coordinate is plotted; the validity
      // guard lives here because `DiscoveredNodeDTO.hasLocation` checks only non-(0,0) and
      // would otherwise admit an out-of-range point.
      guard resolved.matchKind == .exact,
            let coordinate = resolved.coordinate,
            isPlottable(coordinate) else {
        unplottable.append(UnplottableNeighbor(
          neighbor: neighbor,
          displayName: resolved.displayName,
          matchKind: resolved.matchKind
        ))
        continue
      }

      points.append(MapPoint(
        id: UUID(),
        coordinate: coordinate,
        pinStyle: .repeater,
        label: resolved.displayName,
        isClusterable: false,
        hopIndex: nil,
        badgeText: nil
      ))
      plottedCoordinates.append(coordinate)

      // A line and distance/SNR badge are defensible only when the center is also located;
      // without an anchor the neighbor pin still stands alone.
      guard let centerCoordinate else { continue }

      lines.append(MapLine(
        id: "neighbor-\(index)",
        coordinates: [centerCoordinate, coordinate],
        style: .forSNR(neighbor.snr),
        opacity: lineOpacity,
        pathIndex: nil
      ))

      points.append(MapLine.snrBadge(
        id: UUID(),
        from: centerCoordinate,
        to: coordinate,
        snr: neighbor.snr
      ))
    }

    return PlottedNeighbors(
      points: points,
      lines: lines,
      region: plottedCoordinates.boundingRegion(),
      unplottable: unplottable
    )
  }

  private static func isPlottable(_ coordinate: CLLocationCoordinate2D) -> Bool {
    CLLocationCoordinate2DIsValid(coordinate)
  }
}
