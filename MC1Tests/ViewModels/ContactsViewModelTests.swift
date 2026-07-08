import CoreLocation
import Foundation
@testable import MC1
@testable import MC1Services
import Testing

// MARK: - Test Helpers

private func createContact(
  radioID: UUID = UUID(),
  name: String = "TestContact",
  type: ContactType = .chat,
  isFavorite: Bool = false,
  isBlocked: Bool = false,
  lastAdvertTimestamp: UInt32 = 0,
  latitude: Double = 0,
  longitude: Double = 0,
  lastModified: UInt32 = 0,
  outPathLength: UInt8 = 0
) -> ContactDTO {
  ContactDTO(
    id: UUID(),
    radioID: radioID,
    publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
    name: name,
    typeRawValue: type.rawValue,
    flags: 0,
    outPathLength: outPathLength,
    outPath: Data(),
    lastAdvertTimestamp: lastAdvertTimestamp,
    latitude: latitude,
    longitude: longitude,
    lastModified: lastModified,
    nickname: nil,
    isBlocked: isBlocked,
    isMuted: false,
    isFavorite: isFavorite,
    lastMessageDate: nil,
    unreadCount: 0
  )
}

// MARK: - ContactsViewModel Tests

@Suite("ContactsViewModel Tests")
@MainActor
struct ContactsViewModelTests {
  // MARK: - Initial State

  @Test
  func `hasLoadedOnce starts false`() {
    let viewModel = ContactsViewModel()
    #expect(viewModel.hasLoadedOnce == false)
  }

  @Test
  func `isLoading starts false`() {
    let viewModel = ContactsViewModel()
    #expect(viewModel.isLoading == false)
  }

  @Test
  func `contacts starts empty`() {
    let viewModel = ContactsViewModel()
    #expect(viewModel.contacts.isEmpty)
  }

  // MARK: - Guard Behavior

  @Test
  func `loadContacts with nil dataStore returns early without setting hasLoadedOnce`() async {
    let viewModel = ContactsViewModel()
    await viewModel.loadContacts(radioID: UUID())

    #expect(viewModel.contacts.isEmpty)
    #expect(viewModel.hasLoadedOnce == false)
    #expect(viewModel.isLoading == false)
  }

  @Test
  func `syncContacts with nil contactService returns early`() async {
    let viewModel = ContactsViewModel()
    await viewModel.syncContacts(radioID: UUID())

    #expect(viewModel.isSyncing == false)
    #expect(viewModel.syncProgress == nil)
  }

  @Test
  func `toggleFavorite with nil contactService returns early`() async {
    let viewModel = ContactsViewModel()
    let contact = createContact(name: "Test")
    await viewModel.toggleFavorite(contact: contact)

    #expect(viewModel.togglingFavoriteID == nil)
    #expect(viewModel.errorMessage == nil)
  }

  // MARK: - hasFavorites

  @Test
  func `hasFavorites is false with no favorites`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Alice", isFavorite: false),
      createContact(radioID: deviceID, name: "Bob", isFavorite: false)
    ]
    #expect(viewModel.hasFavorites == false)
  }

  @Test
  func `hasFavorites is false when contacts empty`() {
    let viewModel = ContactsViewModel()
    #expect(viewModel.hasFavorites == false)
  }

  @Test
  func `hasFavorites is true when a favorite exists`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Alice", isFavorite: false),
      createContact(radioID: deviceID, name: "Bob", isFavorite: true)
    ]
    #expect(viewModel.hasFavorites == true)
  }

  // MARK: - Filtering by Segment

  @Test
  func `filteredContacts favorites segment returns only favorites`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Alice", isFavorite: true),
      createContact(radioID: deviceID, name: "Bob", isFavorite: false),
      createContact(radioID: deviceID, name: "Charlie", isFavorite: true)
    ]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .favorites,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(result.count == 2)
    let names = result.map(\.name)
    #expect(names.contains("Alice"))
    #expect(names.contains("Charlie"))
    #expect(!names.contains("Bob"))
  }

  @Test
  func `filteredContacts contacts segment returns only chat type`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Alice", type: .chat),
      createContact(radioID: deviceID, name: "Relay1", type: .repeater),
      createContact(radioID: deviceID, name: "Room1", type: .room)
    ]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(result.count == 1)
    #expect(result.first?.name == "Alice")
  }

  @Test
  func `filteredContacts repeaters segment returns only repeater type`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Alice", type: .chat),
      createContact(radioID: deviceID, name: "Relay1", type: .repeater),
      createContact(radioID: deviceID, name: "Relay2", type: .repeater),
      createContact(radioID: deviceID, name: "Room1", type: .room)
    ]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .repeaters,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(result.count == 2)
    #expect(result.allSatisfy { $0.type == .repeater })
  }

  @Test
  func `filteredContacts rooms segment returns only room type`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Alice", type: .chat),
      createContact(radioID: deviceID, name: "Relay1", type: .repeater),
      createContact(radioID: deviceID, name: "Room1", type: .room),
      createContact(radioID: deviceID, name: "Room2", type: .room)
    ]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .rooms,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(result.count == 2)
    #expect(result.allSatisfy { $0.type == .room })
  }

  // MARK: - Filtering by Search Text

  @Test
  func `filteredContacts with search text ignores segment and filters by name`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Alice", type: .chat),
      createContact(radioID: deviceID, name: "Relay-Alpha", type: .repeater),
      createContact(radioID: deviceID, name: "Bob", type: .chat)
    ]

    // Search for "al" should match Alice and Relay-Alpha, ignoring segment filter
    let result = viewModel.filteredContacts(
      searchText: "al",
      segment: .contacts,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(result.count == 2)
    let names = result.map(\.name)
    #expect(names.contains("Alice"))
    #expect(names.contains("Relay-Alpha"))
  }

  @Test
  func `filteredContacts with no matching search returns empty`() {
    let viewModel = ContactsViewModel()
    viewModel.contacts = [
      createContact(name: "Alice"),
      createContact(name: "Bob")
    ]

    let result = viewModel.filteredContacts(
      searchText: "zzz",
      segment: .contacts,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(result.isEmpty)
  }

  // MARK: - Sorting

  @Test
  func `filteredContacts sorted by name returns alphabetical order`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Charlie", type: .chat),
      createContact(radioID: deviceID, name: "Alice", type: .chat),
      createContact(radioID: deviceID, name: "Bob", type: .chat)
    ]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(result.map(\.name) == ["Alice", "Bob", "Charlie"])
  }

  @Test
  func `filteredContacts sorted by lastHeard returns most recent first`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Old", type: .chat, lastModified: 100),
      createContact(radioID: deviceID, name: "Recent", type: .chat, lastModified: 300),
      createContact(radioID: deviceID, name: "Middle", type: .chat, lastModified: 200)
    ]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .lastHeard,
      userLocation: nil
    )

    #expect(result.map(\.name) == ["Recent", "Middle", "Old"])
  }

  @Test
  func `filteredContacts sorted by distance falls back to name without location`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "Charlie", type: .chat),
      createContact(radioID: deviceID, name: "Alice", type: .chat)
    ]

    // No user location → falls back to name sort
    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .distance,
      userLocation: nil
    )

    #expect(result.map(\.name) == ["Alice", "Charlie"])
  }

  @Test
  func `filteredContacts sorted by distance with user location orders by proximity`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    // San Francisco
    let userLocation = CLLocation(latitude: 37.7749, longitude: -122.4194)

    viewModel.contacts = [
      // New York (~4100km away)
      createContact(radioID: deviceID, name: "FarAway", type: .chat, latitude: 40.7128, longitude: -74.0060),
      // Oakland (~13km away)
      createContact(radioID: deviceID, name: "Nearby", type: .chat, latitude: 37.8044, longitude: -122.2712)
    ]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .distance,
      userLocation: userLocation
    )

    #expect(result.first?.name == "Nearby")
    #expect(result.last?.name == "FarAway")
  }

  @Test
  func `filteredContacts sorted by hops returns fewest first with flood-routed last`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    let floodSentinel: UInt8 = 0xFF
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "ThreeHop", type: .chat, outPathLength: 3),
      createContact(radioID: deviceID, name: "Flood", type: .chat, outPathLength: floodSentinel),
      createContact(radioID: deviceID, name: "OneHop", type: .chat, outPathLength: 1)
    ]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .hops,
      userLocation: nil
    )

    #expect(result.map(\.name) == ["OneHop", "ThreeHop", "Flood"])
  }

  @Test
  func `filteredContacts sorted by hops breaks ties by distance, including the flood group`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    let floodSentinel: UInt8 = 0xFF
    let userLocation = CLLocation(latitude: 0, longitude: 0)
    viewModel.contacts = [
      createContact(radioID: deviceID, name: "FarFlood", type: .chat, latitude: 0, longitude: 5, outPathLength: floodSentinel),
      createContact(radioID: deviceID, name: "FarSameHop", type: .chat, latitude: 0, longitude: 1, outPathLength: 2),
      createContact(radioID: deviceID, name: "NearSameHop", type: .chat, latitude: 0, longitude: 0.1, outPathLength: 2),
      createContact(radioID: deviceID, name: "NearFlood", type: .chat, latitude: 0, longitude: 0.5, outPathLength: floodSentinel)
    ]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .hops,
      userLocation: userLocation
    )

    // Direct nodes first, nearest-first; flood group last, nearest-first.
    #expect(result.map(\.name) == ["NearSameHop", "FarSameHop", "NearFlood", "FarFlood"])
  }

  @Test
  func `filteredContacts sorted by hops orders a flood node by its known inbound hop count`() {
    let viewModel = ContactsViewModel()
    let deviceID = UUID()
    let floodSentinel: UInt8 = 0xFF
    let oneHop = createContact(radioID: deviceID, name: "OneHop", type: .chat, outPathLength: 1)
    let floodTwoInbound = createContact(radioID: deviceID, name: "FloodTwoInbound", type: .chat, outPathLength: floodSentinel)
    let threeHop = createContact(radioID: deviceID, name: "ThreeHop", type: .chat, outPathLength: 3)
    let floodUnknown = createContact(radioID: deviceID, name: "FloodUnknown", type: .chat, outPathLength: floodSentinel)
    viewModel.contacts = [floodUnknown, threeHop, floodTwoInbound, oneHop]

    // One flood node was heard two hops away via an advert; the other never was.
    viewModel.inboundHopByKey = [floodTwoInbound.publicKey: 2]

    let result = viewModel.filteredContacts(
      searchText: "",
      segment: .contacts,
      sortOrder: .hops,
      userLocation: nil
    )

    // The flood node interleaves by its inbound count; only the unknown flood node sorts last.
    #expect(result.map(\.name) == ["OneHop", "FloodTwoInbound", "ThreeHop", "FloodUnknown"])
  }
}
