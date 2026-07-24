import CoreLocation
import Foundation
import MapKit
@testable import MC1
@testable import MC1Services
import Testing

@Suite("NeighborSNRMapBuilder")
struct NeighborSNRMapBuilderTests {
  // MARK: - Fixtures

  private func makeSession(latitude: Double, longitude: Double, name: String = "Center") -> RemoteNodeSessionDTO {
    RemoteNodeSessionDTO(
      radioID: UUID(),
      publicKey: Data([0xC0, 0xC1, 0xC2, 0xC3, 0xC4, 0xC5] + Array(repeating: UInt8(0), count: 26)),
      name: name,
      role: .repeater,
      latitude: latitude,
      longitude: longitude
    )
  }

  private func makeContact(
    prefix: [UInt8],
    name: String,
    latitude: Double,
    longitude: Double,
    isFavorite: Bool = false
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(prefix + Array(repeating: UInt8(0), count: 32 - prefix.count)),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 100,
      latitude: latitude,
      longitude: longitude,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: isFavorite,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private func makeDiscoveredNode(prefix: [UInt8], name: String, latitude: Double, longitude: Double) -> DiscoveredNodeDTO {
    DiscoveredNodeDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(prefix + Array(repeating: UInt8(0), count: 32 - prefix.count)),
      name: name,
      typeRawValue: ContactType.repeater.rawValue,
      lastHeard: Date(),
      lastAdvertTimestamp: 100,
      latitude: latitude,
      longitude: longitude,
      outPathLength: 0,
      outPath: Data(),
      inboundHopCount: nil,
      inboundHopAdvertTimestamp: nil
    )
  }

  private func makeNeighbor(prefix: [UInt8], snr: Double = -3.0) -> NeighbourInfo {
    NeighbourInfo(publicKeyPrefix: Data(prefix), secondsAgo: 0, snr: snr)
  }

  private let exactPrefix: [UInt8] = [0xA1, 0xA2, 0xA3, 0xA4, 0xA5, 0xA6]
  private let secondExactPrefix: [UInt8] = [0xB1, 0xB2, 0xB3, 0xB4, 0xB5, 0xB6]

  /// `CLLocationCoordinate2D` is not `Equatable`; compare components within a tight epsilon.
  private func coordinatesMatch(_ lhs: CLLocationCoordinate2D, _ rhs: CLLocationCoordinate2D) -> Bool {
    abs(lhs.latitude - rhs.latitude) < 1e-9 && abs(lhs.longitude - rhs.longitude) < 1e-9
  }

  private func region(_ region: MKCoordinateRegion, brackets coordinate: CLLocationCoordinate2D) -> Bool {
    abs(coordinate.latitude - region.center.latitude) <= region.span.latitudeDelta / 2
      && abs(coordinate.longitude - region.center.longitude) <= region.span.longitudeDelta / 2
  }

  // MARK: - Plotting

  @Test
  func `center located plus exact located neighbor draws pins, an SNR line, and a badge`() throws {
    let centerCoordinate = CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0)
    let neighborCoordinate = CLLocationCoordinate2D(latitude: 37.1, longitude: -122.1)
    let session = makeSession(latitude: centerCoordinate.latitude, longitude: centerCoordinate.longitude)
    let contact = makeContact(
      prefix: exactPrefix,
      name: "Ridge",
      latitude: neighborCoordinate.latitude,
      longitude: neighborCoordinate.longitude
    )
    let neighbor = makeNeighbor(prefix: exactPrefix)

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [contact],
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState()
    )

    #expect(result.points.count(where: { $0.pinStyle == .repeaterRingWhite }) == 1)
    #expect(result.points.count(where: { $0.pinStyle == .repeater }) == 1)
    #expect(result.points.count(where: { $0.pinStyle == .badge }) == 1)
    #expect(result.unplottable.isEmpty)

    let line = try #require(result.lines.first)
    #expect(result.lines.count == 1)
    // The fixture SNR of -3.0 must drive a non-default style, and the link must run from the
    // center to the neighbor in that order.
    #expect(line.style == .forSNR(neighbor.snr))
    #expect(line.style == .traceMedium)
    #expect(line.coordinates.count == 2)
    #expect(coordinatesMatch(line.coordinates[0], centerCoordinate))
    #expect(coordinatesMatch(line.coordinates[1], neighborCoordinate))

    let region = try #require(result.region)
    #expect(self.region(region, brackets: centerCoordinate))
    #expect(self.region(region, brackets: neighborCoordinate))
  }

  @Test
  func `center plus two located neighbors draws a ring, two repeater pins, two badges, and two lines`() throws {
    let centerCoordinate = CLLocationCoordinate2D(latitude: 37.0, longitude: -122.0)
    let firstCoordinate = CLLocationCoordinate2D(latitude: 37.1, longitude: -122.1)
    let secondCoordinate = CLLocationCoordinate2D(latitude: 37.2, longitude: -121.9)
    let session = makeSession(latitude: centerCoordinate.latitude, longitude: centerCoordinate.longitude)
    let contacts = [
      makeContact(prefix: exactPrefix, name: "Ridge", latitude: firstCoordinate.latitude, longitude: firstCoordinate.longitude),
      makeContact(prefix: secondExactPrefix, name: "Valley", latitude: secondCoordinate.latitude, longitude: secondCoordinate.longitude)
    ]
    let neighbors = [makeNeighbor(prefix: exactPrefix), makeNeighbor(prefix: secondExactPrefix)]

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: neighbors,
      contacts: contacts,
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState()
    )

    #expect(result.points.count(where: { $0.pinStyle == .repeaterRingWhite }) == 1)
    #expect(result.points.count(where: { $0.pinStyle == .repeater }) == 2)
    #expect(result.points.count(where: { $0.pinStyle == .badge }) == 2)
    #expect(result.lines.count == 2)
    #expect(result.unplottable.isEmpty)

    let region = try #require(result.region)
    #expect(self.region(region, brackets: centerCoordinate))
    #expect(self.region(region, brackets: firstCoordinate))
    #expect(self.region(region, brackets: secondCoordinate))
  }

  @Test
  func `center not located draws neighbor pin without line or badge`() {
    let session = makeSession(latitude: 0, longitude: 0)
    let contact = makeContact(prefix: exactPrefix, name: "Ridge", latitude: 37.1, longitude: -122.1)
    let neighbor = makeNeighbor(prefix: exactPrefix)

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [contact],
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState()
    )

    #expect(result.points.filter { $0.pinStyle == .repeaterRingWhite }.isEmpty)
    #expect(result.points.count(where: { $0.pinStyle == .repeater }) == 1)
    #expect(result.points.filter { $0.pinStyle == .badge }.isEmpty)
    #expect(result.lines.isEmpty)
    #expect(result.unplottable.isEmpty)
  }

  @Test
  func `ambiguous fallback neighbor is not plotted and is listed as fallback`() {
    // A sub-6-byte prefix that collides across a contact and a discovered node forces the
    // resolver's refinement gate to return `.fallback`; production 6-byte prefixes never do.
    let session = makeSession(latitude: 0, longitude: 0)
    let contact = makeContact(prefix: [0xAB, 0xCD], name: "Saved", latitude: 37.1, longitude: -122.1)
    let node = makeDiscoveredNode(prefix: [0xAB, 0xEF], name: "Advert", latitude: 38.0, longitude: -123.0)
    let neighbor = makeNeighbor(prefix: [0xAB])

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [contact],
      discoveredNodes: [node],
      userLocation: nil,
      filter: MapFilterState(showDiscovered: true)
    )

    #expect(result.points.filter { $0.pinStyle == .repeater }.isEmpty)
    #expect(result.lines.isEmpty)
    #expect(result.unplottable.count == 1)
    #expect(result.unplottable.first?.matchKind == .fallback)
  }

  @Test
  func `exact neighbor without a location is not plotted`() {
    let session = makeSession(latitude: 37.0, longitude: -122.0)
    let contact = makeContact(prefix: exactPrefix, name: "No GPS", latitude: 0, longitude: 0)
    let neighbor = makeNeighbor(prefix: exactPrefix)

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [contact],
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState()
    )

    #expect(result.points.filter { $0.pinStyle == .repeater }.isEmpty)
    #expect(result.unplottable.count == 1)
    #expect(result.unplottable.first?.matchKind == .exact)
    #expect(result.unplottable.first?.displayName == "No GPS")
  }

  @Test
  func `exact neighbor with an out-of-range coordinate is rejected by the builder guard`() {
    // `DiscoveredNodeDTO.hasLocation` only checks non-(0,0), so an out-of-range latitude reaches
    // the builder; its own validity guard must reject it.
    let session = makeSession(latitude: 37.0, longitude: -122.0)
    let node = makeDiscoveredNode(prefix: exactPrefix, name: "Bad GPS", latitude: 200.0, longitude: -122.1)
    let neighbor = makeNeighbor(prefix: exactPrefix)

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [],
      discoveredNodes: [node],
      userLocation: nil,
      filter: MapFilterState(showDiscovered: true)
    )

    #expect(result.points.filter { $0.pinStyle == .repeater }.isEmpty)
    #expect(result.unplottable.count == 1)
    #expect(result.unplottable.first?.matchKind == .exact)
  }

  @Test
  func `unresolved neighbor falls back to a hex name and is listed`() {
    let session = makeSession(latitude: 37.0, longitude: -122.0)
    let neighbor = makeNeighbor(prefix: [0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01])

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [],
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState()
    )

    #expect(result.points.filter { $0.pinStyle == .repeater }.isEmpty)
    #expect(result.unplottable.count == 1)
    #expect(result.unplottable.first?.matchKind == .unresolved)
    #expect(result.unplottable.first?.displayName == NeighborNameResolver.fallbackName(for: neighbor.publicKeyPrefix))
  }

  @Test
  func `all neighbors unplottable keeps only the center pin and lists them all`() {
    let session = makeSession(latitude: 37.0, longitude: -122.0)
    let neighbors = [
      makeNeighbor(prefix: [0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01]),
      makeNeighbor(prefix: [0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x02])
    ]

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: neighbors,
      contacts: [],
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState()
    )

    #expect(result.points.count == 1)
    #expect(result.points.first?.pinStyle == .repeaterRingWhite)
    #expect(result.lines.isEmpty)
    #expect(result.region != nil)
    #expect(result.unplottable.count == 2)
  }

  @Test
  func `nothing plotted yields a nil region`() {
    let session = makeSession(latitude: 0, longitude: 0)
    let neighbor = makeNeighbor(prefix: [0xDE, 0xAD, 0xBE, 0xEF, 0x00, 0x01])

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [],
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState()
    )

    #expect(result.points.isEmpty)
    #expect(result.region == nil)
    #expect(result.unplottable.count == 1)
  }

  // MARK: - Filter

  @Test
  func `favorites only keeps session pin and drops non-favorite neighbor`() {
    let session = makeSession(latitude: 37.0, longitude: -122.0)
    let favorite = makeContact(
      prefix: exactPrefix,
      name: "Fav",
      latitude: 37.1,
      longitude: -122.1,
      isFavorite: true
    )
    let other = makeContact(
      prefix: secondExactPrefix,
      name: "Other",
      latitude: 37.2,
      longitude: -121.9,
      isFavorite: false
    )
    let neighbors = [makeNeighbor(prefix: exactPrefix), makeNeighbor(prefix: secondExactPrefix)]

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: neighbors,
      contacts: [favorite, other],
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState(favoritesOnly: true)
    )

    #expect(result.points.count(where: { $0.pinStyle == .repeaterRingWhite }) == 1)
    #expect(result.points.count(where: { $0.pinStyle == .repeater }) == 1)
    #expect(result.points.contains { $0.pinStyle == .repeater && $0.label == "Fav" })
    #expect(result.unplottable.count == 1)
    // Non-favorite is excluded from the contact pool, so resolution falls back to hex.
    #expect(result.unplottable.contains {
      $0.matchKind == .unresolved
        && $0.neighbor.publicKeyPrefix == Data(secondExactPrefix)
    })
  }

  @Test
  func `discovered off drops discovered-only exact neighbor into unplottable`() {
    let session = makeSession(latitude: 37.0, longitude: -122.0)
    let discovered = makeDiscoveredNode(
      prefix: exactPrefix,
      name: "Heard",
      latitude: 37.1,
      longitude: -122.1
    )
    let neighbor = makeNeighbor(prefix: exactPrefix)

    let withD = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [],
      discoveredNodes: [discovered],
      userLocation: nil,
      filter: MapFilterState(showDiscovered: true)
    )
    #expect(withD.points.count(where: { $0.pinStyle == .repeater }) == 1)

    let withoutD = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [],
      discoveredNodes: [discovered],
      userLocation: nil,
      filter: MapFilterState(showDiscovered: false)
    )
    #expect(withoutD.points.count(where: { $0.pinStyle == .repeater }) == 0)
    #expect(withoutD.points.count(where: { $0.pinStyle == .repeaterRingWhite }) == 1)
    #expect(withoutD.unplottable.count == 1)
  }

  @Test
  func `same inputs twice produce equal points under MapPoint equality`() {
    let session = makeSession(latitude: 37.0, longitude: -122.0)
    let contact = makeContact(prefix: exactPrefix, name: "Ridge", latitude: 37.1, longitude: -122.1)
    let neighbor = makeNeighbor(prefix: exactPrefix)

    let first = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [contact],
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState()
    )
    let second = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [contact],
      discoveredNodes: [],
      userLocation: nil,
      filter: MapFilterState()
    )
    #expect(first.points == second.points)
    #expect(first.lines.map(\.id) == second.lines.map(\.id))
  }

  @Test
  func `favorites with discovered true still drops discovered-only neighbor`() {
    let session = makeSession(latitude: 37.0, longitude: -122.0)
    let discovered = makeDiscoveredNode(
      prefix: exactPrefix,
      name: "Heard",
      latitude: 37.1,
      longitude: -122.1
    )
    let neighbor = makeNeighbor(prefix: exactPrefix)

    let result = NeighborSNRMapBuilder.build(
      session: session,
      neighbors: [neighbor],
      contacts: [],
      discoveredNodes: [discovered],
      userLocation: nil,
      filter: MapFilterState(favoritesOnly: true, showDiscovered: true)
    )

    #expect(result.points.count(where: { $0.pinStyle == .repeaterRingWhite }) == 1)
    #expect(result.points.count(where: { $0.pinStyle == .repeater }) == 0)
    #expect(result.unplottable.count == 1)
  }

  @Test
  func `stableID namespaces separate roles for same key`() {
    let key = Data(exactPrefix)
    let center = NeighborSNRMapBuilder.stableID(role: .center, key: key)
    let neighbor = NeighborSNRMapBuilder.stableID(role: .neighbor, key: key)
    let badge = NeighborSNRMapBuilder.stableID(role: .badge, key: key)
    #expect(center == NeighborSNRMapBuilder.stableID(role: .center, key: key))
    #expect(center != neighbor)
    #expect(neighbor != badge)
    #expect(center != badge)
  }

  // MARK: - SNR bucketing

  /// The 0.0 and -6.0 cases sit on `SNRQuality`'s strict-greater thresholds, so they catch a
  /// `>` to `>=` regression that the interior values would not.
  @Test(arguments: [
    (snr: 7.0 as Double?, style: MapLine.LineStyle.traceGood),
    (snr: 3.0, style: .traceGood),
    (snr: 0.0, style: .traceMedium),
    (snr: -3.0, style: .traceMedium),
    (snr: -6.0, style: .traceWeak),
    (snr: -10.0, style: .traceWeak),
    (snr: nil, style: .traceUntraced)
  ])
  func `SNR buckets map to the expected trace line style`(snr: Double?, style: MapLine.LineStyle) {
    #expect(MapLine.LineStyle.forSNR(snr) == style)
  }

  @Test
  func `SNR badge midpoint stays between coordinates that straddle the antimeridian`() {
    let west = CLLocationCoordinate2D(latitude: 0, longitude: 179)
    let east = CLLocationCoordinate2D(latitude: 0, longitude: -179)

    let badge = MapLine.snrBadge(id: UUID(), from: west, to: east, snr: -3.0)

    // The midpoint must land on the date line near ±180, not on the opposite hemisphere near 0.
    #expect(abs(abs(badge.coordinate.longitude) - 180) < 0.0001)
  }
}
