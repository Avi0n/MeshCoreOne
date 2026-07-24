import CoreLocation
import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

// MARK: - Test Helpers

private func makePublicKey(byte: UInt8 = 0xAB) -> Data {
  Data(repeating: byte, count: ProtocolLimits.publicKeySize)
}

private func makeNode(
  id: UUID = UUID(),
  radioID: UUID = UUID(),
  publicKey: Data? = nil,
  name: String = "Node",
  type: ContactType = .chat,
  lastHeard: Date = Date(timeIntervalSince1970: 1_700_000_000),
  lastAdvertTimestamp: UInt32 = 1_700_000_000,
  latitude: Double = 0,
  longitude: Double = 0,
  outPathLength: UInt8 = 0,
  outPath: Data = Data(),
  inboundHopCount: Int? = nil
) -> DiscoveredNodeDTO {
  DiscoveredNodeDTO(
    id: id,
    radioID: radioID,
    publicKey: publicKey ?? makePublicKey(),
    name: name,
    typeRawValue: type.rawValue,
    lastHeard: lastHeard,
    lastAdvertTimestamp: lastAdvertTimestamp,
    latitude: latitude,
    longitude: longitude,
    outPathLength: outPathLength,
    outPath: outPath,
    inboundHopCount: inboundHopCount,
    inboundHopAdvertTimestamp: nil
  )
}

// MARK: - DiscoveryViewModel Tests

@Suite("DiscoveryViewModel Tests")
@MainActor
struct DiscoveryViewModelTests {
  // MARK: - Name / Hex Search

  @Test
  func `filteredNodes matches name case-insensitively via localizedStandardContains`() {
    let viewModel = DiscoveryViewModel()
    viewModel.discoveredNodes = [
      makeNode(name: "Alpha Repeater"),
      makeNode(name: "Beta Chat"),
    ]

    let result = viewModel.filteredNodes(
      searchText: "alpha",
      segment: .all,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(result.map(\.name) == ["Alpha Repeater"])
  }

  @Test
  func `filteredNodes matches hex prefix case-insensitively`() {
    // Key starts with 0x00, 0xAA → lowercase hex prefix "00aa"
    var keyBytes = [UInt8](repeating: 0, count: ProtocolLimits.publicKeySize)
    keyBytes[0] = 0x00
    keyBytes[1] = 0xAA
    let key = Data(keyBytes)
    let viewModel = DiscoveryViewModel()
    viewModel.discoveredNodes = [
      makeNode(publicKey: key, name: "HexNode"),
      makeNode(publicKey: makePublicKey(byte: 0xFF), name: "Other"),
    ]

    let upper = viewModel.filteredNodes(
      searchText: "00AA",
      segment: .all,
      sortOrder: .name,
      userLocation: nil
    )
    let lower = viewModel.filteredNodes(
      searchText: "00aa",
      segment: .all,
      sortOrder: .name,
      userLocation: nil
    )
    let mixed = viewModel.filteredNodes(
      searchText: "00Aa",
      segment: .all,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(upper.map(\.name) == ["HexNode"])
    #expect(lower.map(\.name) == ["HexNode"])
    #expect(mixed.map(\.name) == ["HexNode"])
  }

  @Test
  func `filteredNodes ignores segment while searching`() {
    let viewModel = DiscoveryViewModel()
    viewModel.discoveredNodes = [
      makeNode(name: "ChatOne", type: .chat),
      makeNode(name: "RepeaterOne", type: .repeater),
    ]

    let result = viewModel.filteredNodes(
      searchText: "One",
      segment: .contacts,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(Set(result.map(\.name)) == Set(["ChatOne", "RepeaterOne"]))
  }

  // MARK: - Segments

  @Test
  func `filteredNodes applies segment filter when not searching`() {
    let viewModel = DiscoveryViewModel()
    viewModel.discoveredNodes = [
      makeNode(name: "Chat", type: .chat),
      makeNode(name: "Repeater", type: .repeater),
      makeNode(name: "Room", type: .room),
    ]

    let contacts = viewModel.filteredNodes(
      searchText: "",
      segment: .contacts,
      sortOrder: .name,
      userLocation: nil
    )
    let repeaters = viewModel.filteredNodes(
      searchText: "",
      segment: .repeaters,
      sortOrder: .name,
      userLocation: nil
    )
    let rooms = viewModel.filteredNodes(
      searchText: "",
      segment: .rooms,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(contacts.map(\.name) == ["Chat"])
    #expect(repeaters.map(\.name) == ["Repeater"])
    #expect(rooms.map(\.name) == ["Room"])
  }

  // MARK: - Sort Orders

  @Test
  func `lastHeard sort uses receiver lastHeard not lastAdvertTimestamp`() {
    let viewModel = DiscoveryViewModel()
    let oldHeard = Date(timeIntervalSince1970: 1_000_000)
    let recentHeard = Date(timeIntervalSince1970: 2_000_000)
    // Future sender clock on the stale-heard node — must not pin it to the top.
    let futureAdvert: UInt32 = 4_000_000_000
    let oldAdvert: UInt32 = 1_000_000

    let futureSender = makeNode(
      name: "FutureSender",
      lastHeard: oldHeard,
      lastAdvertTimestamp: futureAdvert
    )
    let recentlyHeard = makeNode(
      name: "RecentlyHeard",
      lastHeard: recentHeard,
      lastAdvertTimestamp: oldAdvert
    )
    viewModel.discoveredNodes = [futureSender, recentlyHeard]

    let result = viewModel.filteredNodes(
      searchText: "",
      segment: .all,
      sortOrder: .lastHeard,
      userLocation: nil
    )

    #expect(result.map(\.name) == ["RecentlyHeard", "FutureSender"])
  }

  @Test
  func `name sort is locale ascending`() {
    let viewModel = DiscoveryViewModel()
    viewModel.discoveredNodes = [
      makeNode(name: "Charlie"),
      makeNode(name: "Alice"),
      makeNode(name: "Bob"),
    ]

    let result = viewModel.filteredNodes(
      searchText: "",
      segment: .all,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(result.map(\.name) == ["Alice", "Bob", "Charlie"])
  }

  @Test
  func `distance sort places nearer located nodes first`() {
    let viewModel = DiscoveryViewModel()
    let user = CLLocation(latitude: 37.0, longitude: -122.0)
    viewModel.discoveredNodes = [
      makeNode(name: "Far", latitude: 38.0, longitude: -122.0),
      makeNode(name: "Near", latitude: 37.01, longitude: -122.0),
      makeNode(name: "Unlocated"),
    ]

    let result = viewModel.filteredNodes(
      searchText: "",
      segment: .all,
      sortOrder: .distance,
      userLocation: user
    )

    #expect(result.map(\.name) == ["Near", "Far", "Unlocated"])
  }

  @Test
  func `hops sort orders by hop count then distance`() {
    let viewModel = DiscoveryViewModel()
    let user = CLLocation(latitude: 37.0, longitude: -122.0)
    // outPathLength encodes hop count in lower 6 bits when not flood.
    viewModel.discoveredNodes = [
      makeNode(name: "ThreeHop", latitude: 37.01, longitude: -122.0, outPathLength: 3, outPath: Data([1, 2, 3])),
      makeNode(name: "OneHopNear", latitude: 37.01, longitude: -122.0, outPathLength: 1, outPath: Data([1])),
      makeNode(name: "OneHopFar", latitude: 38.0, longitude: -122.0, outPathLength: 1, outPath: Data([1])),
    ]

    let result = viewModel.filteredNodes(
      searchText: "",
      segment: .all,
      sortOrder: .hops,
      userLocation: user
    )

    #expect(result.map(\.name) == ["OneHopNear", "OneHopFar", "ThreeHop"])
  }

  // MARK: - visibleNodes state

  @Test
  func `updateVisibleNodes stores result in visibleNodes`() {
    let viewModel = DiscoveryViewModel()
    viewModel.discoveredNodes = [
      makeNode(name: "Alpha"),
      makeNode(name: "Beta"),
    ]

    viewModel.updateVisibleNodes(
      searchText: "alp",
      segment: .all,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(viewModel.visibleNodes.map(\.name) == ["Alpha"])

    viewModel.updateVisibleNodes(
      searchText: "bet",
      segment: .all,
      sortOrder: .name,
      userLocation: nil
    )

    #expect(viewModel.visibleNodes.map(\.name) == ["Beta"])
  }

  @Test
  func `deleteDiscoveredNode reapplies visibleNodes without re-calling updateVisibleNodes`() async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let store = PersistenceStore(modelContainer: container)
    let radioID = UUID()

    let keepFrame = ContactFrame(
      publicKey: makePublicKey(byte: 0x11),
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Keep",
      lastAdvertTimestamp: 1_700_000_000,
      latitude: 0,
      longitude: 0,
      lastModified: 1_700_000_000
    )
    let dropFrame = ContactFrame(
      publicKey: makePublicKey(byte: 0x22),
      type: .chat,
      flags: 0,
      outPathLength: 0,
      outPath: Data(),
      name: "Drop",
      lastAdvertTimestamp: 1_700_000_000,
      latitude: 0,
      longitude: 0,
      lastModified: 1_700_000_000
    )
    _ = try await store.upsertDiscoveredNode(radioID: radioID, from: keepFrame)
    let (dropNode, _) = try await store.upsertDiscoveredNode(radioID: radioID, from: dropFrame)

    let viewModel = DiscoveryViewModel()
    viewModel.configure(
      dataStore: { store },
      radioID: { radioID }
    )
    await viewModel.loadDiscoveredNodes()
    viewModel.updateVisibleNodes(
      searchText: "",
      segment: .all,
      sortOrder: .name,
      userLocation: nil
    )
    #expect(viewModel.visibleNodes.count == 2)

    // deleteDiscoveredNode must call applyFilter itself — no second updateVisibleNodes.
    await viewModel.deleteDiscoveredNode(dropNode)

    #expect(viewModel.discoveredNodes.map(\.name) == ["Keep"])
    #expect(viewModel.visibleNodes.map(\.name) == ["Keep"])
  }
}
