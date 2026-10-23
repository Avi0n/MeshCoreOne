import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("MapViewModel Advanced Filters")
@MainActor
struct MapViewModelAdvancedFilterTests {
  private static let now = Date(timeIntervalSince1970: 1_800_000_000)
  private static let hour: TimeInterval = 3600

  /// Mutable clock the view model reads through its injected `now` closure.
  private final class TestClock {
    var date: Date
    init(_ date: Date) {
      self.date = date
    }
  }

  private static func makeContact(
    radioID: UUID = UUID(),
    key: UInt8,
    type: ContactType = .repeater,
    heardAgo: TimeInterval?,
    outPathLength: UInt8 = PacketBuilder.floodPathSentinel,
    isFavorite: Bool = false
  ) -> ContactDTO {
    let heardStamp = heardAgo.map { UInt32(now.addingTimeInterval(-$0).timeIntervalSince1970) }
    return ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: Data(repeating: key, count: 32),
      name: "Node \(key)",
      typeRawValue: type.rawValue,
      flags: 0,
      outPathLength: outPathLength,
      outPath: Data(repeating: 0x11, count: 8),
      lastAdvertTimestamp: 0,
      latitude: 37,
      longitude: -122,
      lastModified: 0,
      lastHeardTimestamp: heardStamp,
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
    key: UInt8,
    type: ContactType = .repeater,
    heardAgo: TimeInterval,
    inboundHops: Int?
  ) -> DiscoveredNodeDTO {
    DiscoveredNodeDTO(
      id: UUID(),
      radioID: UUID(),
      publicKey: Data(repeating: key, count: 32),
      name: "Discovered \(key)",
      typeRawValue: type.rawValue,
      lastHeard: now.addingTimeInterval(-heardAgo),
      lastAdvertTimestamp: 0,
      latitude: 38,
      longitude: -122,
      outPathLength: PacketBuilder.floodPathSentinel,
      outPath: Data(),
      inboundHopCount: inboundHops,
      inboundHopAdvertTimestamp: nil
    )
  }

  private static func visible(
    contacts: [ContactDTO] = [],
    discovered: [DiscoveredNodeDTO] = [],
    hops: [Data: Int] = [:],
    filter: MapFilterState
  ) -> (contacts: Set<String>, discovered: Set<String>) {
    let result = MapViewModel.visiblePins(
      contacts: contacts,
      discovered: discovered,
      inboundHopByKey: hops,
      filter: filter,
      now: now
    )
    return (Set(result.contacts.map(\.name)), Set(result.discovered.map(\.name)))
  }

  // MARK: - Last Heard

  @Test
  func `max age keeps repeaters heard within two hours`() {
    let recent = Self.makeContact(key: 1, heardAgo: 30 * 60)
    let stale = Self.makeContact(key: 2, heardAgo: 3 * Self.hour)
    let result = Self.visible(
      contacts: [recent, stale],
      filter: MapFilterState(lastHeard: .within(MapDuration(2, .hours)))
    )
    #expect(result.contacts == ["Node 1"])
  }

  @Test
  func `min age keeps only quiet nodes`() {
    let recent = Self.makeContact(key: 1, heardAgo: 30 * 60)
    let stale = Self.makeContact(key: 2, heardAgo: 3 * 24 * Self.hour)
    let result = Self.visible(
      contacts: [recent, stale],
      filter: MapFilterState(lastHeard: MapLastHeardRange(minAge: MapDuration(1, .days)))
    )
    #expect(result.contacts == ["Node 2"])
  }

  @Test
  func `never heard contacts hide only while last heard is active`() {
    let never = Self.makeContact(key: 1, heardAgo: nil)
    #expect(Self.visible(contacts: [never], filter: MapFilterState()).contacts == ["Node 1"])
    let filtered = Self.visible(
      contacts: [never],
      filter: MapFilterState(lastHeard: .within(MapDuration(9999, .days)))
    )
    #expect(filtered.contacts.isEmpty)
  }

  @Test
  func `discovered nodes use their last heard date`() {
    let recent = Self.makeDiscovered(key: 1, heardAgo: 10 * 60, inboundHops: 1)
    let stale = Self.makeDiscovered(key: 2, heardAgo: 2 * Self.hour, inboundHops: 1)
    let result = Self.visible(
      discovered: [recent, stale],
      filter: MapFilterState(showDiscovered: true, lastHeard: .within(MapDuration(1, .hours)))
    )
    #expect(result.discovered == ["Discovered 1"])
  }

  // MARK: - Hops

  @Test
  func `flood routed contacts use inbound advert hops`() {
    let near = Self.makeContact(key: 1, heardAgo: 60)
    let far = Self.makeContact(key: 2, heardAgo: 60)
    let unknown = Self.makeContact(key: 3, heardAgo: 60)
    let hops = [near.publicKey: 0, far.publicKey: 4]
    let result = Self.visible(contacts: [near, far, unknown], hops: hops, filter: MapFilterState(hops: .direct))
    #expect(result.contacts == ["Node 1"])
  }

  @Test
  func `routed contacts use out path hops`() {
    let routed = Self.makeContact(key: 1, heardAgo: 60, outPathLength: 7)
    let result = Self.visible(
      contacts: [routed],
      hops: [routed.publicKey: 0],
      filter: MapFilterState(hops: MapHopRange(min: 6, max: 10))
    )
    #expect(result.contacts == ["Node 1"])
  }

  @Test
  func `hop range filters discovered nodes and hides unknown`() {
    let direct = Self.makeDiscovered(key: 1, heardAgo: 60, inboundHops: 0)
    let twoHop = Self.makeDiscovered(key: 2, heardAgo: 60, inboundHops: 2)
    let unknown = Self.makeDiscovered(key: 3, heardAgo: 60, inboundHops: nil)
    let result = Self.visible(
      discovered: [direct, twoHop, unknown],
      filter: MapFilterState(showDiscovered: true, hops: MapHopRange(max: 2))
    )
    #expect(result.discovered == ["Discovered 1", "Discovered 2"])
  }

  // MARK: - Combination

  @Test
  func `favorites mode still applies advanced filters`() {
    let favoriteRecent = Self.makeContact(key: 1, heardAgo: 60, isFavorite: true)
    let favoriteStale = Self.makeContact(key: 2, heardAgo: 5 * Self.hour, isFavorite: true)
    let otherRecent = Self.makeContact(key: 3, heardAgo: 60)
    let result = Self.visible(
      contacts: [favoriteRecent, favoriteStale, otherRecent],
      filter: MapFilterState(favoritesOnly: true, lastHeard: .within(MapDuration(1, .hours)))
    )
    #expect(result.contacts == ["Node 1"])
  }

  @Test
  func `type toggles and both dimensions combine with and`() {
    let match = Self.makeContact(key: 1, type: .repeater, heardAgo: 60)
    let wrongType = Self.makeContact(key: 2, type: .chat, heardAgo: 60)
    let tooFar = Self.makeContact(key: 3, type: .repeater, heardAgo: 60)
    let tooOld = Self.makeContact(key: 4, type: .repeater, heardAgo: 3 * Self.hour)
    let hops = [match.publicKey: 1, wrongType.publicKey: 1, tooFar.publicKey: 5, tooOld.publicKey: 1]
    let result = Self.visible(
      contacts: [match, wrongType, tooFar, tooOld],
      hops: hops,
      filter: MapFilterState(
        showChat: false,
        lastHeard: .within(MapDuration(2, .hours)),
        hops: MapHopRange(max: 2)
      )
    )
    #expect(result.contacts == ["Node 1"])
  }

  // MARK: - Clock

  @Test
  func `refreshTimeWindow ages pins out as the clock advances`() async throws {
    let radioID = UUID()
    let container = try PersistenceStore.createContainer(inMemory: true)
    let dataStore = PersistenceStore(modelContainer: container)
    let contact = Self.makeContact(radioID: radioID, key: 1, heardAgo: 30 * 60)
    try await dataStore.saveContact(contact)

    let clock = TestClock(Self.now)
    let viewModel = MapViewModel(now: { clock.date })
    viewModel.configure(dataStore: { dataStore }, radioID: { radioID })
    await viewModel.loadMapData(filter: MapFilterState(lastHeard: .within(MapDuration(1, .hours))))
    #expect(viewModel.visibleContacts.map(\.id) == [contact.id])

    clock.date = Self.now.addingTimeInterval(Self.hour)
    viewModel.refreshTimeWindow()
    #expect(viewModel.visibleContacts.isEmpty)
  }
}
