import CoreLocation
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("TracePath map filter")
@MainActor
struct TracePathMapFilterTests {
  private static func makeContact(
    isFavorite: Bool,
    id: UUID = UUID(),
    publicKey: Data = Data(repeating: 0x11, count: 32)
  ) -> ContactDTO {
    ContactDTO(
      id: id,
      radioID: UUID(),
      publicKey: publicKey,
      name: "Node",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 37,
      longitude: -122,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: isFavorite,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
  }

  private static func makeDiscovered(
    id: UUID = UUID(),
    publicKey: Data = Data(repeating: 0x22, count: 32),
    latitude: Double = 38,
    longitude: Double = -122,
    type: ContactType = .repeater
  ) -> DiscoveredNodeDTO {
    DiscoveredNodeDTO(
      id: id,
      radioID: UUID(),
      publicKey: publicKey,
      name: "Discovered",
      typeRawValue: type.rawValue,
      lastHeard: Date(),
      lastAdvertTimestamp: 0,
      latitude: latitude,
      longitude: longitude,
      outPathLength: 0,
      outPath: Data(),
      inboundHopCount: nil,
      inboundHopAdvertTimestamp: nil
    )
  }

  @Test
  func `default seed showDiscovered true`() {
    #expect(MapFilterState.seed(for: .tracePath).showDiscovered == true)
  }

  @Test
  func `favorites hides non-favorite candidates but keeps path members`() {
    let pathMemberID = UUID()
    let favorite = Self.makeContact(isFavorite: true)
    let pathMember = Self.makeContact(isFavorite: false, id: pathMemberID)
    let other = Self.makeContact(isFavorite: false, publicKey: Data(repeating: 0x33, count: 32))
    var filter = MapFilterState.seed(for: .tracePath)
    filter.setFavoritesOnly(true)

    let visible = TracePathMapViewModel.visibleContactPins(
      candidates: [favorite, pathMember, other],
      pathMemberIDs: [pathMemberID],
      filter: filter
    )
    let ids = Set(visible.map(\.id))
    #expect(ids == Set([favorite.id, pathMemberID]))
  }

  @Test
  func `discovered on adds located discovered repeater pins deduped`() {
    let contactKey = Data(repeating: 0x44, count: 32)
    let contact = Self.makeContact(isFavorite: false, publicKey: contactKey)
    let discoveredDup = Self.makeDiscovered(publicKey: contactKey)
    let discoveredNew = Self.makeDiscovered(publicKey: Data(repeating: 0x55, count: 32))
    let chatDiscovered = Self.makeDiscovered(
      publicKey: Data(repeating: 0x66, count: 32),
      type: .chat
    )
    let filter = MapFilterState.seed(for: .tracePath)
    #expect(filter.effectiveShowDiscovered)

    let visible = TracePathMapViewModel.visibleDiscoveredPins(
      discovered: [discoveredDup, discoveredNew, chatDiscovered],
      contactKeys: [contact.publicKey],
      pathMemberIDs: [],
      filter: filter
    )
    #expect(Set(visible.map(\.id)) == Set([discoveredNew.id]))
  }

  @Test
  func `discovered off hides candidates but keeps path members`() {
    var filter = MapFilterState.seed(for: .tracePath)
    filter.setShowDiscovered(false)
    let pathMember = Self.makeDiscovered(id: UUID())
    let other = Self.makeDiscovered(publicKey: Data(repeating: 0x77, count: 32))
    let visible = TracePathMapViewModel.visibleDiscoveredPins(
      discovered: [pathMember, other],
      contactKeys: [],
      pathMemberIDs: [pathMember.id],
      filter: filter
    )
    #expect(visible.map(\.id) == [pathMember.id])
  }

  @Test
  func `favorites keeps discovered path members only`() {
    var filter = MapFilterState(showDiscovered: true)
    filter.setFavoritesOnly(true)
    #expect(filter.showDiscovered == true)
    #expect(filter.effectiveShowDiscovered == false)
    let pathMember = Self.makeDiscovered()
    let other = Self.makeDiscovered(publicKey: Data(repeating: 0x88, count: 32))
    let visible = TracePathMapViewModel.visibleDiscoveredPins(
      discovered: [pathMember, other],
      contactKeys: [],
      pathMemberIDs: [pathMember.id],
      filter: filter
    )
    #expect(visible.map(\.id) == [pathMember.id])
  }

  @Test
  func `invalid fix discovered not plotted`() {
    let bad = Self.makeDiscovered(latitude: 999, longitude: -122)
    let filter = MapFilterState.seed(for: .tracePath)
    let visible = TracePathMapViewModel.visibleDiscoveredPins(
      discovered: [bad],
      contactKeys: [],
      pathMemberIDs: [],
      filter: filter
    )
    #expect(visible.isEmpty)
  }

  @Test
  func `handleMapPointTap ignores unknown id`() {
    let mapVM = TracePathMapViewModel()
    let result = mapVM.handleMapPointTap(pointID: UUID())
    #expect(result == .ignored)
  }

  @Test
  func `handleMapPointTap adds contact hop then removes last hop`() {
    let contact = Self.makeContact(
      isFavorite: false,
      publicKey: Data(repeating: 0xC1, count: 32)
    )
    let traceVM = TracePathViewModel()
    traceVM.availableRepeaters = [contact]

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: nil)
    mapVM.applyFilter(MapFilterState.seed(for: .tracePath))
    mapVM.rebuildPathState()

    let added = mapVM.handleMapPointTap(pointID: contact.id)
    #expect(added == .added)
    #expect(traceVM.outboundPath.count == 1)
    #expect(mapVM.pathState[contact.id]?.inPath == true)
    #expect(mapVM.pathState[contact.id]?.isLastHop == true)

    let removed = mapVM.handleMapPointTap(pointID: contact.id)
    #expect(removed == .removed)
    #expect(traceVM.outboundPath.isEmpty)
  }

  @Test
  func `handleMapPointTap ignores non-favorite under favorites filter`() {
    let nonFavorite = Self.makeContact(
      isFavorite: false,
      publicKey: Data(repeating: 0xC2, count: 32)
    )
    let favorite = Self.makeContact(
      isFavorite: true,
      publicKey: Data(repeating: 0xC3, count: 32)
    )
    let traceVM = TracePathViewModel()
    traceVM.availableRepeaters = [nonFavorite, favorite]

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: nil)
    var filter = MapFilterState.seed(for: .tracePath)
    filter.setFavoritesOnly(true)
    mapVM.applyFilter(filter)
    mapVM.rebuildPathState()

    #expect(mapVM.handleMapPointTap(pointID: nonFavorite.id) == .ignored)
    #expect(traceVM.outboundPath.isEmpty)

    let added = mapVM.handleMapPointTap(pointID: favorite.id)
    #expect(added == .added)
    #expect(traceVM.outboundPath.count == 1)
  }

  @Test
  func `exact known key without location does not bind hash prefix collision`() {
    // Full key A is known but unlocated; B shares the routing prefix and is located.
    // Must not fall through to B.
    let sharedPrefix: UInt8 = 0xAD
    var unlocatedKey = Data(repeating: 0x41, count: 32)
    unlocatedKey[0] = sharedPrefix
    var locatedCollisionKey = Data(repeating: 0x42, count: 32)
    locatedCollisionKey[0] = sharedPrefix

    let unlocated = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: unlocatedKey,
      name: "Unlocated",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )
    let collision = Self.makeContact(
      isFavorite: false,
      publicKey: locatedCollisionKey
    )
    let hop = PathHop(
      hashBytes: Data([sharedPrefix]),
      publicKey: unlocatedKey,
      resolvedName: unlocated.name
    )

    let traceVM = TracePathViewModel()
    traceVM.availableRepeaters = [unlocated, collision]
    traceVM.outboundPath = [hop]

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: nil)
    mapVM.applyFilter(MapFilterState.seed(for: .tracePath))
    mapVM.rebuildOverlays()

    #expect(mapVM.pathState[unlocated.id]?.inPath != true)
    #expect(mapVM.pathState[collision.id]?.inPath != true)
    #expect(mapVM.mapLines.isEmpty)
  }

  @Test
  func `handleMapPointTap adds discovered hop then rejects middle re-tap`() {
    let traceVM = TracePathViewModel()
    let node = Self.makeDiscovered(publicKey: Data(repeating: 0x99, count: 32))
    traceVM.discoveredRepeaters = [node]

    let mapVM = TracePathMapViewModel()
    let userLocation = CLLocation(latitude: 36.5, longitude: -122.5)
    mapVM.configure(traceViewModel: traceVM, userLocation: userLocation)
    mapVM.applyFilter(MapFilterState.seed(for: .tracePath))
    mapVM.rebuildPathState()

    #expect(mapVM.visibleDiscovered.contains { $0.id == node.id })

    let added = mapVM.handleMapPointTap(pointID: node.id)
    #expect(added == .added)
    #expect(traceVM.outboundPath.count == 1)
    #expect(mapVM.pathState[node.id]?.inPath == true)
    #expect(mapVM.pathState[node.id]?.isLastHop == true)
    // User location → first discovered hop must draw a path segment (not contact-only resolution).
    #expect(!mapVM.mapLines.isEmpty)
    let firstLine = mapVM.mapLines[0]
    #expect(abs(firstLine.coordinates[0].latitude - userLocation.coordinate.latitude) < 1e-9)
    #expect(abs(firstLine.coordinates[1].latitude - node.latitude) < 1e-9)

    // Second hop so first becomes middle
    let second = Self.makeDiscovered(
      publicKey: Data(repeating: 0x9A, count: 32),
      latitude: 39,
      longitude: -121
    )
    traceVM.discoveredRepeaters = [node, second]
    mapVM.rebuildPathState()
    let addedSecond = mapVM.handleMapPointTap(pointID: second.id)
    #expect(addedSecond == .added)
    #expect(traceVM.outboundPath.count == 2)
    #expect(mapVM.mapLines.count >= 2)

    let middle = mapVM.handleMapPointTap(pointID: node.id)
    #expect(middle == .rejectedMiddleHop)
    #expect(traceVM.outboundPath.count == 2)

    let removed = mapVM.handleMapPointTap(pointID: second.id)
    #expect(removed == .removed)
    #expect(traceVM.outboundPath.count == 1)
  }

  @Test
  func `applyFilter rebuilds discovered pins without loadContacts`() {
    let traceVM = TracePathViewModel()
    let node = Self.makeDiscovered()
    traceVM.discoveredRepeaters = [node]

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: nil)
    mapVM.applyFilter(MapFilterState.seed(for: .tracePath))
    mapVM.rebuildPathState()
    #expect(mapVM.mapPoints.contains { $0.id == node.id })

    var filter = MapFilterState.seed(for: .tracePath)
    filter.setShowDiscovered(false)
    mapVM.applyFilter(filter)
    #expect(!mapVM.mapPoints.contains { $0.id == node.id })
  }

  @Test
  func `exact full key prefers discovered over contact prefix collision`() {
    // Shared 1-byte routing prefix; full keys differ after the prefix.
    let sharedPrefix: UInt8 = 0xAB
    var contactKey = Data(repeating: 0x11, count: 32)
    contactKey[0] = sharedPrefix
    var discoveredKey = Data(repeating: 0x22, count: 32)
    discoveredKey[0] = sharedPrefix

    let contact = Self.makeContact(
      isFavorite: false,
      publicKey: contactKey
    )
    let discovered = Self.makeDiscovered(
      publicKey: discoveredKey,
      latitude: 40,
      longitude: -121
    )

    let hop = PathHop(
      hashBytes: Data([sharedPrefix]),
      publicKey: discoveredKey,
      resolvedName: discovered.name
    )
    let userLocation = CLLocation(latitude: 36.5, longitude: -122.5)

    let traceVM = TracePathViewModel()
    traceVM.availableRepeaters = [contact]
    traceVM.discoveredRepeaters = [discovered]
    traceVM.outboundPath = [hop]

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: userLocation)
    mapVM.applyFilter(MapFilterState.seed(for: .tracePath))
    mapVM.rebuildOverlays()

    #expect(mapVM.pathState[discovered.id]?.inPath == true)
    #expect(mapVM.pathState[contact.id]?.inPath != true)
    #expect(mapVM.mapPoints.contains { $0.id == discovered.id && $0.hopIndex == 1 })
    #expect(!mapVM.mapLines.isEmpty)
    #expect(abs(mapVM.mapLines[0].coordinates[1].latitude - discovered.latitude) < 1e-9)
  }

  @Test
  func `hash only prefix collision prefers contact over discovered`() {
    let sharedPrefix: UInt8 = 0xAC
    var contactKey = Data(repeating: 0x31, count: 32)
    contactKey[0] = sharedPrefix
    var discoveredKey = Data(repeating: 0x32, count: 32)
    discoveredKey[0] = sharedPrefix

    let contact = Self.makeContact(isFavorite: false, publicKey: contactKey)
    let discovered = Self.makeDiscovered(publicKey: discoveredKey, latitude: 41, longitude: -120)
    // No full key on the hop — only routing hash bytes.
    let hop = PathHop(
      hashBytes: Data([sharedPrefix]),
      publicKey: nil,
      resolvedName: nil
    )

    let traceVM = TracePathViewModel()
    traceVM.availableRepeaters = [contact]
    traceVM.discoveredRepeaters = [discovered]
    traceVM.outboundPath = [hop]

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: nil)
    mapVM.applyFilter(MapFilterState.seed(for: .tracePath))
    mapVM.rebuildOverlays()

    #expect(mapVM.pathState[contact.id]?.inPath == true)
    #expect(mapVM.pathState[discovered.id]?.inPath != true)
  }

  @Test
  func `centerOnAllRepeaters frames discovered-only visible set`() throws {
    let node = Self.makeDiscovered(latitude: 41.5, longitude: -123.25)
    let traceVM = TracePathViewModel()
    // No located contacts — only discovered pins drive the camera.
    traceVM.availableRepeaters = []
    traceVM.discoveredRepeaters = [node]

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: nil)
    mapVM.applyFilter(MapFilterState.seed(for: .tracePath))
    mapVM.rebuildPathState()
    #expect(mapVM.mapPoints.contains { $0.id == node.id })

    mapVM.centerOnAllRepeaters()
    let region = try #require(mapVM.cameraRegion)
    #expect(abs(region.center.latitude - node.latitude) < 1e-6)
    #expect(abs(region.center.longitude - node.longitude) < 1e-6)
    #expect(mapVM.hasInitiallyCenteredOnRepeaters)
  }

  @Test
  func `centerOnAllRepeaters under favorites excludes non-favorite far pin`() throws {
    let nearFavorite = Self.makeContact(
      isFavorite: true,
      publicKey: Data(repeating: 0xD1, count: 32)
    )
    // Far non-favorite must not expand the camera when Favorites is on.
    let farOther = ContactDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: 0xD2, count: 32),
      name: "Far",
      typeRawValue: ContactType.repeater.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: 50,
      longitude: -100,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0,
      ocvPreset: nil,
      customOCVArrayString: nil
    )

    let traceVM = TracePathViewModel()
    traceVM.availableRepeaters = [nearFavorite, farOther]
    traceVM.discoveredRepeaters = []

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: nil)
    var filter = MapFilterState.seed(for: .tracePath)
    filter.setFavoritesOnly(true)
    mapVM.applyFilter(filter)
    mapVM.rebuildPathState()
    #expect(mapVM.mapPoints.map(\.id) == [nearFavorite.id])

    mapVM.centerOnAllRepeaters()
    let region = try #require(mapVM.cameraRegion)
    #expect(abs(region.center.latitude - nearFavorite.latitude) < 1e-6)
    let halfLat = region.span.latitudeDelta / 2
    let halfLon = region.span.longitudeDelta / 2
    #expect(abs(farOther.latitude - region.center.latitude) > halfLat
      || abs(farOther.longitude - region.center.longitude) > halfLon)
  }

  @Test
  func `rebuildOverlays draws line after late discovered hop resolve`() {
    let node = Self.makeDiscovered(
      publicKey: Data(repeating: 0xCD, count: 32),
      latitude: 42,
      longitude: -120
    )
    let hop = PathHop(
      hashBytes: Data(node.publicKey.prefix(1)),
      publicKey: node.publicKey,
      resolvedName: node.name
    )
    let userLocation = CLLocation(latitude: 36.5, longitude: -122.5)

    let traceVM = TracePathViewModel()
    traceVM.outboundPath = [hop]
    // Contacts empty, discovered not loaded yet.
    traceVM.discoveredRepeaters = []

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: userLocation)
    mapVM.applyFilter(MapFilterState.seed(for: .tracePath))
    mapVM.rebuildOverlays()
    #expect(mapVM.mapLines.isEmpty)

    // Late fetch — same entry the view onChange uses.
    traceVM.discoveredRepeaters = [node]
    mapVM.handleNodeTablesChanged()
    #expect(mapVM.pathState[node.id]?.inPath == true)
    #expect(!mapVM.mapLines.isEmpty)
    let line = mapVM.mapLines[0]
    #expect(abs(line.coordinates[1].latitude - node.latitude) < 1e-9)
  }

  @Test
  func `rebuildOverlays re-applies SNR styles after successful result`() {
    let node = Self.makeDiscovered(
      publicKey: Data(repeating: 0xCE, count: 32),
      latitude: 43,
      longitude: -119
    )
    let hop = PathHop(
      hashBytes: Data(node.publicKey.prefix(1)),
      publicKey: node.publicKey,
      resolvedName: node.name
    )
    let userLocation = CLLocation(latitude: 36.5, longitude: -122.5)
    let pathSNR = -3.0

    let traceVM = TracePathViewModel()
    traceVM.availableRepeaters = []
    traceVM.discoveredRepeaters = [node]
    traceVM.outboundPath = [hop]
    // Result hops: start (0), path segment (1), end (2). pathIndex 0 → hopIndex 1.
    traceVM.result = TraceResult(
      hops: [
        TraceHop(
          hashBytes: nil,
          resolvedName: nil,
          snr: 0,
          isStartNode: true,
          isEndNode: false,
          latitude: nil,
          longitude: nil
        ),
        TraceHop(
          hashBytes: Data(node.publicKey.prefix(1)),
          resolvedName: node.name,
          snr: pathSNR,
          isStartNode: false,
          isEndNode: false,
          latitude: node.latitude,
          longitude: node.longitude
        ),
        TraceHop(
          hashBytes: nil,
          resolvedName: nil,
          snr: 0,
          isStartNode: false,
          isEndNode: true,
          latitude: nil,
          longitude: nil
        )
      ],
      durationMs: 100,
      success: true,
      errorMessage: nil,
      tracedPathBytes: [0xCE],
      hashSize: 1
    )

    let mapVM = TracePathMapViewModel()
    mapVM.configure(traceViewModel: traceVM, userLocation: userLocation)
    mapVM.applyFilter(MapFilterState.seed(for: .tracePath))
    mapVM.rebuildOverlays()

    #expect(!mapVM.mapLines.isEmpty)
    #expect(mapVM.mapLines[0].style == MapLine.LineStyle.forSNR(pathSNR))
    #expect(mapVM.mapLines[0].style != .traceUntraced)
    #expect(!mapVM.badgePoints.isEmpty)

    // Late table refresh must not wipe SNR styles after a successful trace.
    traceVM.discoveredRepeaters = [node]
    mapVM.handleNodeTablesChanged()
    #expect(mapVM.mapLines[0].style == MapLine.LineStyle.forSNR(pathSNR))
    #expect(!mapVM.badgePoints.isEmpty)
  }
}
