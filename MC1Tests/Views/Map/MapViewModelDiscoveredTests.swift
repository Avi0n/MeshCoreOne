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
    longitude: Double = contactLongitude,
    type: ContactType = .chat,
    isFavorite: Bool = false,
    name: String = "Located"
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: publicKey,
      name: name,
      typeRawValue: type.rawValue,
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
      isFavorite: isFavorite,
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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: false))

    #expect(viewModel.mapPoints.map(\.id) == [contact.id])
    #expect(viewModel.visibleDiscovered.isEmpty)
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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))

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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))

    #expect(viewModel.mapPoints.map(\.id) == [contact.id])
    #expect(viewModel.visibleDiscovered.isEmpty)
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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))

    #expect(viewModel.visibleContacts.isEmpty)
    #expect(viewModel.visibleDiscovered.isEmpty)
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

    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))
    viewModel.centerOnAllContacts()
    let unionRegion = try #require(viewModel.cameraRegion)
    let unionMinLat = unionRegion.center.latitude - unionRegion.span.latitudeDelta / 2
    let unionMaxLat = unionRegion.center.latitude + unionRegion.span.latitudeDelta / 2
    #expect(unionMinLat <= Self.contactLatitude)
    #expect(unionMaxLat >= Self.farDiscoveredLatitude)

    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: false))
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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))

    #expect(viewModel.hasPinsForCenterAll)
    #expect(viewModel.visibleContacts.isEmpty)
    viewModel.centerOnAllContacts()
    let region = try #require(viewModel.cameraRegion)
    #expect(region.center.latitude == Self.discoveredLatitude)
    #expect(region.center.longitude == Self.discoveredLongitude)
  }

  @Test
  func `center on all disabled when only discovered and showDiscovered false`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: false))

    #expect(!viewModel.hasPinsForCenterAll)
    viewModel.centerOnAllContacts()
    #expect(viewModel.cameraRegion == nil)
  }

  @Test
  func `load with filter discovered false clears prior discovered pins`() async throws {
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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))
    #expect(viewModel.mapPoints.contains { $0.id == discovered.id })

    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: false))
    #expect(viewModel.visibleDiscovered.isEmpty)
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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))
    #expect(!viewModel.mapPoints.isEmpty)
    viewModel.errorMessage = "stale"

    viewModel.configure(dataStore: { nil }, radioID: { nil })
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))

    #expect(viewModel.visibleContacts.isEmpty)
    #expect(viewModel.visibleDiscovered.isEmpty)
    #expect(viewModel.mapPoints.isEmpty)
    #expect(viewModel.errorMessage == nil)
    #expect(!viewModel.isLoading)
    #expect(!viewModel.hasCompletedInitialLoad)
    #expect(viewModel.allLocatedContacts.isEmpty)
    #expect(viewModel.allLocatedDiscovered.isEmpty)

    // Warm filter after clear must not resurrect pins without a new load.
    viewModel.applyFilter(MapFilterState(showDiscovered: true))
    #expect(viewModel.mapPoints.isEmpty)
    #expect(!viewModel.hasCompletedInitialLoad)
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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))

    #expect(viewModel.visibleDiscovered.isEmpty)
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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))

    #expect(viewModel.visibleDiscovered.isEmpty)
    #expect(viewModel.mapPoints.isEmpty)
  }

  @Test
  func `schedule coalesced reload uses trailing filter state`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    viewModel.scheduleCoalescedReload(filter: MapFilterState(showDiscovered: true))
    viewModel.scheduleCoalescedReload(filter: MapFilterState(showDiscovered: false))

    // Debounce is 50ms; wait for fire + fetch.
    try await Task.sleep(for: .milliseconds(200))

    #expect(viewModel.visibleDiscovered.isEmpty)
    #expect(viewModel.visibleContacts.count == 1)
    #expect(viewModel.mapPoints.map(\.id) == viewModel.visibleContacts.map(\.id))
  }

  @Test
  func `newer load wins over older filter with discovered true`() async throws {
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

    async let older: Void = viewModel.loadMapData(filter: MapFilterState(showDiscovered: true), showsLoadingChrome: true)
    // Wait until the first load latches a generation so the second call is strictly newer.
    while viewModel.loadGenerationForTesting == 0 {
      await Task.yield()
    }
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: false), showsLoadingChrome: true)
    await older

    #expect(viewModel.visibleDiscovered.isEmpty)
    #expect(viewModel.visibleContacts.count == 1)
    #expect(viewModel.visibleContacts.first?.id == contact.id)
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
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))

    #expect(viewModel.contact(forPointID: contact.id)?.id == contact.id)
    #expect(viewModel.discovered(forPointID: discovered.id)?.id == discovered.id)
    #expect(viewModel.contact(forPointID: discovered.id) == nil)
    #expect(viewModel.discovered(forPointID: contact.id) == nil)
    #expect(viewModel.contact(forPointID: UUID()) == nil)
    #expect(viewModel.discovered(forPointID: UUID()) == nil)
  }

  @Test
  func `lookup helpers return nil for filter-hidden pins still in caches`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let chat = Self.makeLocatedContact(radioID: radioID, type: .chat, name: "Chat")
    let repeater = Self.makeLocatedContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xB1, count: 32),
      type: .repeater,
      name: "Repeater"
    )
    try await dataStore.saveContact(chat)
    try await dataStore.saveContact(repeater)
    let discovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame(type: .chat)
    ).node

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))
    #expect(viewModel.contact(forPointID: chat.id) != nil)
    #expect(viewModel.discovered(forPointID: discovered.id) != nil)

    viewModel.applyFilter(MapFilterState(
      showDiscovered: false,
      showChat: false,
      showRepeater: true,
      showRoom: true
    ))
    #expect(viewModel.contact(forPointID: chat.id) == nil)
    #expect(viewModel.discovered(forPointID: discovered.id) == nil)
    #expect(viewModel.contact(forPointID: repeater.id)?.id == repeater.id)
    #expect(viewModel.allLocatedContacts.contains { $0.id == chat.id })
    #expect(viewModel.allLocatedDiscovered.contains { $0.id == discovered.id })
  }

  @Test
  func `centerOnAllContacts under type filter excludes hidden far pin`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let nearRepeater = Self.makeLocatedContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xB2, count: 32),
      latitude: Self.contactLatitude,
      longitude: Self.contactLongitude,
      type: .repeater,
      name: "Near"
    )
    let farChat = Self.makeLocatedContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xB3, count: 32),
      latitude: Self.farDiscoveredLatitude,
      longitude: Self.farDiscoveredLongitude,
      type: .chat,
      name: "Far"
    )
    try await dataStore.saveContact(nearRepeater)
    try await dataStore.saveContact(farChat)

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState(
      showChat: false,
      showRepeater: true,
      showRoom: true
    ))
    viewModel.centerOnAllContacts()
    let region = try #require(viewModel.cameraRegion)
    #expect(abs(region.center.latitude - nearRepeater.latitude) < 1e-6)
    let halfLat = region.span.latitudeDelta / 2
    let halfLon = region.span.longitudeDelta / 2
    #expect(
      abs(farChat.latitude - region.center.latitude) > halfLat
        || abs(farChat.longitude - region.center.longitude) > halfLon
    )
  }

  @Test
  func `centerOnAllContacts under favorites excludes non-favorite far pin`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let nearFavorite = Self.makeLocatedContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xB4, count: 32),
      latitude: Self.contactLatitude,
      longitude: Self.contactLongitude,
      type: .chat,
      isFavorite: true,
      name: "Fav"
    )
    let farOther = Self.makeLocatedContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xB5, count: 32),
      latitude: Self.farDiscoveredLatitude,
      longitude: Self.farDiscoveredLongitude,
      type: .chat,
      isFavorite: false,
      name: "Far"
    )
    try await dataStore.saveContact(nearFavorite)
    try await dataStore.saveContact(farOther)

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState(favoritesOnly: true))
    viewModel.centerOnAllContacts()
    let region = try #require(viewModel.cameraRegion)
    #expect(abs(region.center.latitude - nearFavorite.latitude) < 1e-6)
    let halfLat = region.span.latitudeDelta / 2
    let halfLon = region.span.longitudeDelta / 2
    #expect(
      abs(farOther.latitude - region.center.latitude) > halfLat
        || abs(farOther.longitude - region.center.longitude) > halfLon
    )
  }

  @Test
  func `load failure latches pending filter and rebuilds pins`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let chat = Self.makeLocatedContact(radioID: radioID, type: .chat, name: "Chat")
    let repeater = Self.makeLocatedContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xB6, count: 32),
      type: .repeater,
      name: "Repeater"
    )
    try await dataStore.saveContact(chat)
    try await dataStore.saveContact(repeater)

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState())
    #expect(viewModel.mapPoints.count == 2)
    #expect(viewModel.errorMessage == nil)

    viewModel.simulateLoadFailureForTesting = true
    await viewModel.loadMapData(filter: MapFilterState(
      showChat: false,
      showRepeater: true,
      showRoom: true
    ))
    #expect(viewModel.errorMessage != nil)
    let ids = Set(viewModel.mapPoints.map(\.id))
    #expect(ids == Set([repeater.id]))
    #expect(!ids.contains(chat.id))
  }

  @Test
  func `types off hide matching contact pins`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let chat = Self.makeLocatedContact(radioID: radioID, type: .chat, name: "Chat")
    let repeater = Self.makeLocatedContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xAB, count: 32),
      type: .repeater,
      name: "Repeater"
    )
    try await dataStore.saveContact(chat)
    try await dataStore.saveContact(repeater)

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState())
    viewModel.applyFilter(MapFilterState(showChat: false, showRepeater: true, showRoom: true))

    let ids = Set(viewModel.mapPoints.map(\.id))
    #expect(ids.contains(repeater.id))
    #expect(!ids.contains(chat.id))
  }

  @Test
  func `favorites only plots favorite contacts and no discovered`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let favorite = Self.makeLocatedContact(
      radioID: radioID,
      type: .chat,
      isFavorite: true,
      name: "Fav"
    )
    let other = Self.makeLocatedContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xAC, count: 32),
      type: .repeater,
      isFavorite: false,
      name: "Other"
    )
    try await dataStore.saveContact(favorite)
    try await dataStore.saveContact(other)
    let discovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    ).node

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))
    #expect(viewModel.mapPoints.contains { $0.id == discovered.id })

    viewModel.applyFilter(MapFilterState(favoritesOnly: true, showDiscovered: true))
    let ids = Set(viewModel.mapPoints.map(\.id))
    #expect(ids == Set([favorite.id]))
    #expect(viewModel.visibleDiscovered.isEmpty)
  }

  @Test
  func `favorites off restores type and discovered pin set`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let contact = Self.makeLocatedContact(radioID: radioID, isFavorite: true)
    try await dataStore.saveContact(contact)
    let discovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    ).node

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    let base = MapFilterState(showDiscovered: true, showChat: true, showRepeater: false, showRoom: true)
    await viewModel.loadMapData(filter: base)
    let before = Set(viewModel.mapPoints.map(\.id))

    var withFavorites = base
    withFavorites.setFavoritesOnly(true)
    viewModel.applyFilter(withFavorites)
    #expect(viewModel.visibleDiscovered.isEmpty)

    withFavorites.setFavoritesOnly(false)
    viewModel.applyFilter(withFavorites)
    #expect(Set(viewModel.mapPoints.map(\.id)) == before)
    #expect(viewModel.mapPoints.contains { $0.id == discovered.id })
  }

  @Test
  func `dropped pin survives filter change`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState())
    viewModel.focusOnCoordinate(CLLocationCoordinate2D(latitude: 10, longitude: 20))
    #expect(viewModel.mapPoints.contains { $0.pinStyle == .droppedPin })

    viewModel.applyFilter(MapFilterState(showChat: false, showRepeater: true, showRoom: true))
    #expect(viewModel.mapPoints.contains { $0.pinStyle == .droppedPin })
  }

  @Test
  func `coalesced reload latches full filter favorites with discovered storage`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID, isFavorite: true))
    _ = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    )

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    viewModel.scheduleCoalescedReload(
      filter: MapFilterState(favoritesOnly: true, showDiscovered: true)
    )
    try await Task.sleep(for: .milliseconds(200))

    #expect(viewModel.visibleDiscovered.isEmpty)
    let allFavorite = viewModel.visibleContacts.allSatisfy(\.isFavorite)
    #expect(allFavorite)
  }

  @Test
  func `applyFilter after load does not require second fetch for type toggle`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let chat = Self.makeLocatedContact(radioID: radioID, type: .chat)
    let room = Self.makeLocatedContact(
      radioID: radioID,
      publicKey: Data(repeating: 0xAD, count: 32),
      type: .room
    )
    try await dataStore.saveContact(chat)
    try await dataStore.saveContact(room)

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState())
    #expect(viewModel.allLocatedContacts.count == 2)

    viewModel.applyFilter(MapFilterState(showChat: false, showRepeater: true, showRoom: true))
    #expect(viewModel.allLocatedContacts.count == 2)
    #expect(viewModel.visibleContacts.map(\.id) == [room.id])
  }

  @Test
  func `warm applyFilter turns discovered on from unfiltered cache without reload`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))
    let discovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    ).node

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: false))
    #expect(viewModel.hasCompletedInitialLoad)
    #expect(!viewModel.allLocatedDiscovered.isEmpty)
    #expect(viewModel.visibleDiscovered.isEmpty)
    #expect(!viewModel.mapPoints.contains { $0.id == discovered.id })

    viewModel.applyFilter(MapFilterState(showDiscovered: true))
    #expect(viewModel.visibleDiscovered.map(\.id) == [discovered.id])
    #expect(viewModel.mapPoints.contains { $0.id == discovered.id })
  }

  @Test
  func `type filter hides matching discovered node types`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let chatDiscovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame(
        publicKey: Data(repeating: 0xB1, count: 32),
        type: .chat
      )
    ).node
    let repeaterDiscovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame(
        publicKey: Data(repeating: 0xB2, count: 32),
        latitude: Self.farDiscoveredLatitude,
        type: .repeater
      )
    ).node

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: true))
    viewModel.applyFilter(MapFilterState(
      showDiscovered: true,
      showChat: false,
      showRepeater: true,
      showRoom: true
    ))

    let ids = Set(viewModel.mapPoints.map(\.id))
    #expect(ids.contains(repeaterDiscovered.id))
    #expect(!ids.contains(chatDiscovered.id))
  }

  @Test
  func `scheduleFilterChange warm latches pending over coalesced reload`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))
    let discovered = try await dataStore.upsertDiscoveredNode(
      radioID: radioID,
      from: Self.makeDiscoveredFrame()
    ).node

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    await viewModel.loadMapData(filter: MapFilterState(showDiscovered: false))
    #expect(viewModel.hasCompletedInitialLoad)

    // Coalesced full reload with discovered off, then warm flip on before debounce fires.
    viewModel.scheduleCoalescedReload(filter: MapFilterState(showDiscovered: false))
    viewModel.scheduleFilterChange(MapFilterState(showDiscovered: true))
    try await Task.sleep(for: .milliseconds(200))

    #expect(viewModel.mapPoints.contains { $0.id == discovered.id })
    #expect(!viewModel.visibleDiscovered.isEmpty)
  }

  @Test
  func `scheduleFilterChange cold path loads before warm apply`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    try await dataStore.saveContact(Self.makeLocatedContact(radioID: radioID))

    let viewModel = Self.makeViewModel(dataStore: dataStore, radioID: radioID)
    #expect(!viewModel.hasCompletedInitialLoad)
    viewModel.scheduleFilterChange(MapFilterState())
    try await Task.sleep(for: .milliseconds(200))
    #expect(viewModel.hasCompletedInitialLoad)
    #expect(viewModel.visibleContacts.count == 1)
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
