import Testing
import Foundation
import CoreLocation
@testable import MC1
@testable import MC1Services

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
    lastModified: UInt32 = 0
) -> ContactDTO {
    ContactDTO(
        id: UUID(),
        radioID: radioID,
        publicKey: Data((0..<ProtocolLimits.publicKeySize).map { _ in UInt8.random(in: 0...255) }),
        name: name,
        typeRawValue: type.rawValue,
        flags: 0,
        outPathLength: 0,
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

    @Test("hasLoadedOnce starts false")
    func hasLoadedOnceStartsFalse() {
        let viewModel = ContactsViewModel()
        #expect(viewModel.hasLoadedOnce == false)
    }

    @Test("isLoading starts false")
    func isLoadingStartsFalse() {
        let viewModel = ContactsViewModel()
        #expect(viewModel.isLoading == false)
    }

    @Test("contacts starts empty")
    func contactsStartsEmpty() {
        let viewModel = ContactsViewModel()
        #expect(viewModel.contacts.isEmpty)
    }

    // MARK: - Guard Behavior

    @Test("loadContacts with nil dataStore returns early without setting hasLoadedOnce")
    func loadContactsNilDataStore() async {
        let viewModel = ContactsViewModel()
        await viewModel.loadContacts(radioID: UUID())

        #expect(viewModel.contacts.isEmpty)
        #expect(viewModel.hasLoadedOnce == false)
        #expect(viewModel.isLoading == false)
    }

    @Test("syncContacts with nil contactService returns early")
    func syncContactsNilService() async {
        let viewModel = ContactsViewModel()
        await viewModel.syncContacts(radioID: UUID())

        #expect(viewModel.isSyncing == false)
        #expect(viewModel.syncProgress == nil)
    }

    @Test("toggleFavorite with nil contactService returns early")
    func toggleFavoriteNilService() async {
        let viewModel = ContactsViewModel()
        let contact = createContact(name: "Test")
        await viewModel.toggleFavorite(contact: contact)

        #expect(viewModel.togglingFavoriteID == nil)
        #expect(viewModel.errorMessage == nil)
    }

    // MARK: - Filtering by Segment

    @Test("filteredContacts favorites segment returns only favorites")
    func filteredContactsFavoritesSegment() {
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

    @Test("filteredContacts contacts segment returns only chat type")
    func filteredContactsContactsSegment() {
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

    @Test("filteredContacts network segment returns repeaters and rooms")
    func filteredContactsNetworkSegment() {
        let viewModel = ContactsViewModel()
        let deviceID = UUID()
        viewModel.contacts = [
            createContact(radioID: deviceID, name: "Alice", type: .chat),
            createContact(radioID: deviceID, name: "Relay1", type: .repeater),
            createContact(radioID: deviceID, name: "Room1", type: .room)
        ]

        let result = viewModel.filteredContacts(
            searchText: "",
            segment: .network,
            sortOrder: .name,
            userLocation: nil
        )

        #expect(result.count == 2)
        let names = result.map(\.name)
        #expect(names.contains("Relay1"))
        #expect(names.contains("Room1"))
    }

    // MARK: - Filtering by Search Text

    @Test("filteredContacts with search text ignores segment and filters by name")
    func filteredContactsSearchTextIgnoresSegment() {
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

    @Test("filteredContacts with no matching search returns empty")
    func filteredContactsNoMatch() {
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

    @Test("filteredContacts sorted by name returns alphabetical order")
    func filteredContactsSortedByName() {
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

    @Test("filteredContacts sorted by lastHeard returns most recent first")
    func filteredContactsSortedByLastHeard() {
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

    @Test("filteredContacts sorted by distance falls back to name without location")
    func filteredContactsSortedByDistanceFallsBack() {
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

    @Test("filteredContacts sorted by distance with user location orders by proximity")
    func filteredContactsSortedByDistanceWithLocation() {
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
}
