import CoreLocation
import Foundation
import MapKit
@testable import MC1
@testable import MC1Services
import Testing

@Suite("MapViewModel Discovered Pins")
@MainActor
struct MapViewModelDiscoveredTests {
  private static let contactLatitude = 37.0
  private static let contactLongitude = -122.0
  private static let discoveredLatitude = 38.0
  private static let discoveredLongitude = -122.0
  private static let farDiscoveredLatitude = 45.0
  private static let farDiscoveredLongitude = -100.0
  /// Outside valid latitude range; would crash MapLibre if plotted.
  private static let invalidLatitude = 999.0

  private static func makeLocatedContact(
    radioID: UUID,
    publicKey: Data = Data(repeating: 0xAA, count: 32),
    latitude: Double = contactLatitude,
    longitude: Double = contactLongitude
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: publicKey,
      name: "Located",
      typeRawValue: 0x01,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      lastAdvertTimestamp: 0,
      latitude: latitude,
      longitude: longitude,
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
  }

  private static func makeUnlocatedContact(
    radioID: UUID,
    publicKey: Data
  ) -> ContactDTO {
    makeLocatedContact(radioID: radioID, publicKey: publicKey, latitude: 0, longitude: 0)
  }

  private static func makeDiscoveredFrame(
    publicKey: Data = Data(repeating: 0xBB, count: 32),
    latitude: Double = discoveredLatitude,
    longitude: Double = discoveredLongitude,
    name: String = "Discovered",
    type: ContactType = .chat,
    typeRawValue: UInt8? = nil
  ) -> ContactFrame {
    ContactFrame(
      publicKey: publicKey,
      type: type,
      typeRawValue: typeRawValue ?? type.rawValue,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: name,
      lastAdvertTimestamp: 0,
      latitude: latitude,
      longitude: longitude,
      lastModified: 0
    )
  }

  private static func makeViewModel(
    dataStore: PersistenceStore,
    radioID: UUID
  ) -> MapViewModel {
    let viewModel = MapViewModel()
    viewModel.configure(dataStore: { dataStore }, radioID: { radioID })
    return viewModel
  }

  @Test
  func `load without discovered shows only contacts`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let contact = Self.makeLocatedContact(radioID: radioID)
    try await dataStore.saveContact(contact)
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: false)

    #expect(viewModel.mapPoints.map(\.id) == [contact.id])
    #expect(viewModel.discoveredWithLocation.isEmpty)
  }

  @Test
  func `load with discovered unions located nodes`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let contact = Self.makeLocatedContact(radioID: radioID)
    try await dataStore.saveContact(contact)
    let discovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    ).node

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: true)

    let pointIDs = Set(viewModel.mapPoints.map(\.id))
    #expect(pointIDs == Set([contact.id, discovered.id]))
  }

  @Test
  func `load with discovered dedupes by public key`() async throws {
    let radioID = UUID()
    let sharedKey = Data(repeating: 0xCC, count: 32)
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let contact = Self.makeLocatedContact(radioID: radioID, publicKey: sharedKey)
    try await dataStore.saveContact(contact)
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame(publicKey: sharedKey, latitude: Self.farDiscoveredLatitude)
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: true)

    #expect(viewModel.mapPoints.map(\.id) == [contact.id])
    #expect(viewModel.discoveredWithLocation.isEmpty)
  }

  @Test
  func `load with discovered dedupes unlocated contact public key`() async throws {
    let radioID = UUID()
    let sharedKey = Data(repeating: 0xDD, count: 32)
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeUnlocatedContact(radioID: radioID, publicKey: sharedKey))
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame(publicKey: sharedKey)
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: true)

    #expect(viewModel.contactsWithLocation.isEmpty)
    #expect(viewModel.discoveredWithLocation.isEmpty)
    #expect(viewModel.mapPoints.isEmpty)
  }

  @Test
  func `center on all includes discovered when enabled`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame(
        latitude: Self.farDiscoveredLatitude,
        longitude: Self.farDiscoveredLongitude
      )
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)

    await viewModel.loadMapData(includeDiscovered: true)
    viewModel.centerOnAllContacts()
    let unionRegion = try #require(viewModel.cameraRegion)
    let unionMinLat = unionRegion.center.latitude - unionRegion.span.latitudeDelta / 2
    let unionMaxLat = unionRegion.center.latitude + unionRegion.span.latitudeDelta / 2
    #expect(unionMinLat <= Self.contactLatitude)
    #expect(unionMaxLat >= Self.farDiscoveredLatitude)

    await viewModel.loadMapData(includeDiscovered: false)
    viewModel.centerOnAllContacts()
    let contactsOnly = try #require(viewModel.cameraRegion)
    let contactsMaxLat = contactsOnly.center.latitude + contactsOnly.span.latitudeDelta / 2
    #expect(contactsMaxLat < Self.farDiscoveredLatitude)
  }

  @Test
  func `center on all enabled when only discovered located`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: true)

    #expect(viewModel.hasPinsForCenterAll)
    #expect(viewModel.contactsWithLocation.isEmpty)
    viewModel.centerOnAllContacts()
    let region = try #require(viewModel.cameraRegion)
    #expect(region.center.latitude == Self.discoveredLatitude)
    #expect(region.center.longitude == Self.discoveredLongitude)
  }

  @Test
  func `center on all disabled when only discovered and include false`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: false)

    #expect(!viewModel.hasPinsForCenterAll)
    viewModel.centerOnAllContacts()
    #expect(viewModel.cameraRegion == nil)
  }

  @Test
  func `load with include discovered false clears prior discovered pins`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let contact = Self.makeLocatedContact(radioID: radioID)
    try await dataStore.saveContact(contact)
    let discovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    ).node

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: true)
    #expect(viewModel.mapPoints.contains { $0.id == discovered.id })

    await viewModel.loadMapData(includeDiscovered: false)
    #expect(viewModel.discoveredWithLocation.isEmpty)
    #expect(!viewModel.mapPoints.contains { $0.id == discovered.id })
    #expect(viewModel.mapPoints.map(\.id) == [contact.id])
  }

  @Test
  func `load with nil providers clears pins and error`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    )

    let viewModel = MapViewModel()
    viewModel.configure(dataStore: { dataStore }, radioID: { radioID })
    await viewModel.loadMapData(includeDiscovered: true)
    #expect(!viewModel.mapPoints.isEmpty)
    viewModel.errorMessage = "stale"

    viewModel.configure(dataStore: { nil }, radioID: { nil })
    await viewModel.loadMapData(includeDiscovered: true)

    #expect(viewModel.contactsWithLocation.isEmpty)
    #expect(viewModel.discoveredWithLocation.isEmpty)
    #expect(viewModel.mapPoints.isEmpty)
    #expect(viewModel.errorMessage == nil)
    #expect(!viewModel.isLoading)
  }

  @Test
  func `discovered without location is omitted`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame(latitude: 0, longitude: 0)
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: true)

    #expect(viewModel.discoveredWithLocation.isEmpty)
    #expect(viewModel.mapPoints.isEmpty)
    #expect(!viewModel.hasPinsForCenterAll)
  }

  @Test
  func `discovered with invalid coordinate is omitted`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame(latitude: Self.invalidLatitude, longitude: Self.discoveredLongitude)
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: true)

    #expect(viewModel.discoveredWithLocation.isEmpty)
    #expect(viewModel.mapPoints.isEmpty)
  }

  @Test
  func `schedule coalesced reload uses trailing include flag`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    viewModel.scheduleCoalescedReload(includeDiscovered: true)
    viewModel.scheduleCoalescedReload(includeDiscovered: false)

    // Debounce is 50ms; wait for fire + fetch.
    try await Task.sleep(for: .milliseconds(200))

    #expect(viewModel.discoveredWithLocation.isEmpty)
    #expect(viewModel.contactsWithLocation.count == 1)
    #expect(viewModel.mapPoints.map(\.id) == viewModel.contactsWithLocation.map(\.id))
  }

  @Test
  func `newer load wins over older include discovered true`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let contact = Self.makeLocatedContact(radioID: radioID)
    try await dataStore.saveContact(contact)
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)

    async let older: Void = viewModel.loadMapData(includeDiscovered: true, showsLoadingChrome: true)
    await Task.yield()
    await viewModel.loadMapData(includeDiscovered: false, showsLoadingChrome: true)
    await older

    #expect(viewModel.discoveredWithLocation.isEmpty)
    #expect(viewModel.contactsWithLocation.count == 1)
    #expect(viewModel.contactsWithLocation.first?.id == contact.id)
    #expect(viewModel.mapPoints.map(\.id) == [contact.id])
    #expect(!viewModel.isLoading)
  }

  @Test
  func `lookup helpers resolve contact and discovered`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let contact = Self.makeLocatedContact(radioID: radioID)
    try await dataStore.saveContact(contact)
    let discovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    ).node

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(includeDiscovered: true)

    #expect(viewModel.contact(forPointID: contact.id)?.id == contact.id)
    #expect(viewModel.discovered(forPointID: discovered.id)?.id == discovered.id)
    #expect(viewModel.contact(forPointID: discovered.id) == nil)
    #expect(viewModel.discovered(forPointID: contact.id) == nil)
    #expect(viewModel.contact(forPointID: UUID()) == nil)
    #expect(viewModel.discovered(forPointID: UUID()) == nil)
  }

  @Test
  func `make contact frame passes fields including type raw value`() {
    let publicKey = Data(repeating: 0xEE, count: 32)
    let customTypeRaw: UInt8 = 0x7F
    let outPath = Data([0x01, 0x02, 0x03])
    // Build the DTO directly: store upsert may normalize typeRawValue to ContactType.rawValue.
    let discovered = DiscoveredNodeDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: publicKey,
      name: "FrameNode",
      typeRawValue: customTypeRaw,
      lastHeard: Date(timeIntervalSince1970: 100),
      lastAdvertTimestamp: 42,
      latitude: Self.discoveredLatitude,
      longitude: Self.discoveredLongitude,
      outPathLength: 3,
      outPath: outPath,
      inboundHopCount: nil,
      inboundHopAdvertTimestamp: nil
    )

    let lastModified: UInt32 = 99
    let frame = discovered.makeContactFrame(lastModified: lastModified)

    #expect(frame.publicKey == publicKey)
    #expect(frame.type == discovered.nodeType)
    #expect(frame.typeRawValue == customTypeRaw)
    #expect(frame.flags == 0)
    #expect(frame.outPathLength == 3)
    #expect(frame.outPath == outPath)
    #expect(frame.name == "FrameNode")
    #expect(frame.lastAdvertTimestamp == 42)
    #expect(frame.latitude == Self.discoveredLatitude)
    #expect(frame.longitude == Self.discoveredLongitude)
    #expect(frame.lastModified == lastModified)
  }
}
