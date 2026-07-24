import CoreLocation
import CryptoKit
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

  /// Role namespaces so center / neighbor / badge pins keep stable ids across rebuilds.
  enum PinRole: UInt8, Sendable {
    case center = 0
    case neighbor = 1
    case badge = 2
  }

  static func build(
    session: RemoteNodeSessionDTO,
    neighbors: [NeighbourInfo],
    contacts: [ContactDTO],
    discoveredNodes: [DiscoveredNodeDTO],
    userLocation: CLLocation?,
    filter: MapFilterState
  ) -> PlottedNeighbors {
    let filter = filter.sanitized(for: .neighborSNR)
    let effectiveContacts: [ContactDTO] = if filter.favoritesOnly {
      contacts.filter(\.isFavorite)
    } else {
      contacts
    }
    let effectiveDiscovered: [DiscoveredNodeDTO] = if filter.effectiveShowDiscovered {
      discoveredNodes
    } else {
      []
    }

    var points: [MapPoint] = []
    var lines: [MapLine] = []
    var unplottable: [UnplottableNeighbor] = []
    var plottedCoordinates: [CLLocationCoordinate2D] = []

    let centerCoordinate = session.coordinate
    if let centerCoordinate {
      points.append(MapPoint(
        id: stableID(role: .center, key: session.publicKey),
        coordinate: centerCoordinate,
        pinStyle: .repeaterRingWhite,
        label: session.name,
        isClusterable: false,
        hopIndex: nil,
        badgeText: nil
      ))
      plottedCoordinates.append(centerCoordinate)
    }

    for neighbor in neighbors {
      guard let resolved = NeighborNameResolver.resolveLocated(
        for: neighbor.publicKeyPrefix,
        contacts: effectiveContacts,
        discoveredNodes: effectiveDiscovered,
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

      let identityKey = neighbor.publicKeyPrefix
      points.append(MapPoint(
        id: stableID(role: .neighbor, key: identityKey),
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
        id: stableLineID(key: identityKey),
        coordinates: [centerCoordinate, coordinate],
        style: .forSNR(neighbor.snr),
        opacity: lineOpacity,
        pathIndex: nil
      ))

      points.append(MapLine.snrBadge(
        id: stableID(role: .badge, key: identityKey),
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
    coordinate.isValidFix
  }

  /// Deterministic UUID from role namespace + identity key bytes (SHA-256 prefix).
  static func stableID(role: PinRole, key: Data) -> UUID {
    var material = Data([role.rawValue])
    material.append(key)
    let hash = SHA256.hash(data: material)
    let bytes = Array(hash)
    return UUID(uuid: (
      bytes[0], bytes[1], bytes[2], bytes[3],
      bytes[4], bytes[5], bytes[6], bytes[7],
      bytes[8], bytes[9], bytes[10], bytes[11],
      bytes[12], bytes[13], bytes[14], bytes[15]
    ))
  }

  /// Line id stable across filter rebuilds (hex of neighbor-role stable UUID).
  private static func stableLineID(key: Data) -> String {
    "neighbor-\(stableID(role: .neighbor, key: key).uuidString)"
  }
}
