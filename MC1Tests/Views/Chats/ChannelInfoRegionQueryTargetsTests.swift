import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("ChannelInfoSheet region query targets")
struct ChannelInfoRegionQueryTargetsTests {
  private static let radioID = UUID()
  private static let keyA = Data(repeating: 0x0A, count: 32)
  private static let keyB = Data(repeating: 0x0B, count: 32)
  private static let keyC = Data(repeating: 0x0C, count: 32)
  private static let keyD = Data(repeating: 0x0D, count: 32)
  private static let keyE = Data(repeating: 0x0E, count: 32)

  @Test
  func `queries responders present only in the discovered-nodes table`() {
    let responders: Set<Data> = [Self.keyA]
    let targets = RegionDiscoveryService.buildRegionQueryTargets(
      responders: responders,
      contacts: [],
      discoveredNodes: [Self.makeDiscoveredNode(publicKey: Self.keyA, type: .repeater)],
      supportsAdHocRequest: true
    )

    #expect(targets.count == 1)
    #expect(targets.first?.publicKey == Self.keyA)
  }

  @Test
  func `prefers contact record when both sources have the responder`() {
    let responders: Set<Data> = [Self.keyA]
    let targets = RegionDiscoveryService.buildRegionQueryTargets(
      responders: responders,
      contacts: [Self.makeContact(publicKey: Self.keyA, type: .repeater, name: "from-contact")],
      discoveredNodes: [Self.makeDiscoveredNode(publicKey: Self.keyA, type: .repeater, name: "from-discovery")],
      supportsAdHocRequest: true
    )

    #expect(targets.count == 1)
    #expect(targets.first?.advertisedName == "from-contact")
  }

  @Test
  func `unions contacts and discovered nodes without duplication`() {
    let responders: Set<Data> = [Self.keyA, Self.keyB, Self.keyC]
    let targets = RegionDiscoveryService.buildRegionQueryTargets(
      responders: responders,
      contacts: [Self.makeContact(publicKey: Self.keyA, type: .repeater)],
      discoveredNodes: [
        Self.makeDiscoveredNode(publicKey: Self.keyA, type: .repeater),
        Self.makeDiscoveredNode(publicKey: Self.keyB, type: .repeater),
        Self.makeDiscoveredNode(publicKey: Self.keyC, type: .repeater)
      ],
      supportsAdHocRequest: true
    )

    let keys = Set(targets.map(\.publicKey))
    #expect(keys == responders)
  }

  @Test
  func `excludes non-repeater types from both pools`() {
    let responders: Set<Data> = [Self.keyA, Self.keyB, Self.keyC, Self.keyD]
    let targets = RegionDiscoveryService.buildRegionQueryTargets(
      responders: responders,
      contacts: [
        Self.makeContact(publicKey: Self.keyA, type: .chat),
        Self.makeContact(publicKey: Self.keyB, type: .repeater)
      ],
      discoveredNodes: [
        Self.makeDiscoveredNode(publicKey: Self.keyC, type: .room),
        Self.makeDiscoveredNode(publicKey: Self.keyD, type: .repeater)
      ],
      supportsAdHocRequest: true
    )

    let keys = Set(targets.map(\.publicKey))
    #expect(keys == [Self.keyB, Self.keyD])
  }

  @Test
  func `drops responders that are in neither pool`() {
    let responders: Set<Data> = [Self.keyA, Self.keyE]
    let targets = RegionDiscoveryService.buildRegionQueryTargets(
      responders: responders,
      contacts: [Self.makeContact(publicKey: Self.keyA, type: .repeater)],
      discoveredNodes: [],
      supportsAdHocRequest: true
    )

    #expect(targets.map(\.publicKey) == [Self.keyA])
  }

  @Test
  func `drops non-responders even if they are repeater contacts`() {
    let responders: Set<Data> = [Self.keyA]
    let targets = RegionDiscoveryService.buildRegionQueryTargets(
      responders: responders,
      contacts: [
        Self.makeContact(publicKey: Self.keyA, type: .repeater),
        Self.makeContact(publicKey: Self.keyB, type: .repeater)
      ],
      discoveredNodes: [],
      supportsAdHocRequest: true
    )

    #expect(targets.map(\.publicKey) == [Self.keyA])
  }

  @Test
  func `forwards outPath bytes from contact source`() {
    let contactPath = Data([0x11, 0x22, 0x33])
    let responders: Set<Data> = [Self.keyA]
    let targets = RegionDiscoveryService.buildRegionQueryTargets(
      responders: responders,
      contacts: [
        Self.makeContact(
          publicKey: Self.keyA,
          type: .repeater,
          outPathLength: UInt8(contactPath.count),
          outPath: contactPath
        )
      ],
      discoveredNodes: [],
      supportsAdHocRequest: true
    )

    #expect(targets.first?.outPathLength == UInt8(contactPath.count))
    #expect(targets.first?.outPath == contactPath)
  }

  @Test
  func `forwards outPath bytes from discovered-node source`() {
    let nodePath = Data([0xAA, 0xBB])
    let responders: Set<Data> = [Self.keyA]
    let targets = RegionDiscoveryService.buildRegionQueryTargets(
      responders: responders,
      contacts: [],
      discoveredNodes: [
        Self.makeDiscoveredNode(
          publicKey: Self.keyA,
          type: .repeater,
          outPathLength: UInt8(nodePath.count),
          outPath: nodePath
        )
      ],
      supportsAdHocRequest: true
    )

    #expect(targets.first?.outPathLength == UInt8(nodePath.count))
    #expect(targets.first?.outPath == nodePath)
  }

  @Test
  func `uses contact outPath when both sources have routing data`() {
    let contactPath = Data([0x11, 0x22, 0x33])
    let nodePath = Data([0xAA, 0xBB])
    let responders: Set<Data> = [Self.keyA]
    let targets = RegionDiscoveryService.buildRegionQueryTargets(
      responders: responders,
      contacts: [
        Self.makeContact(
          publicKey: Self.keyA,
          type: .repeater,
          outPathLength: UInt8(contactPath.count),
          outPath: contactPath
        )
      ],
      discoveredNodes: [
        Self.makeDiscoveredNode(
          publicKey: Self.keyA,
          type: .repeater,
          outPathLength: UInt8(nodePath.count),
          outPath: nodePath
        )
      ],
      supportsAdHocRequest: true
    )

    #expect(targets.first?.outPathLength == UInt8(contactPath.count))
    #expect(targets.first?.outPath == contactPath)
  }

  // MARK: - Fixtures

  private static func makeContact(
    publicKey: Data,
    type: ContactType,
    name: String = "contact",
    outPathLength: UInt8 = 0xFF,
    outPath: Data = Data()
  ) -> ContactDTO {
    ContactDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: publicKey,
      name: name,
      typeRawValue: type.rawValue,
      flags: 0,
      outPathLength: outPathLength,
      outPath: outPath,
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      lastModified: 0,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
  }

  private static func makeDiscoveredNode(
    publicKey: Data,
    type: ContactType,
    name: String = "discovered",
    outPathLength: UInt8 = 0xFF,
    outPath: Data = Data()
  ) -> DiscoveredNodeDTO {
    DiscoveredNodeDTO(
      id: UUID(),
      radioID: radioID,
      publicKey: publicKey,
      name: name,
      typeRawValue: type.rawValue,
      lastHeard: Date(),
      lastAdvertTimestamp: 0,
      latitude: 0,
      longitude: 0,
      outPathLength: outPathLength,
      outPath: outPath,
      inboundHopCount: nil,
      inboundHopAdvertTimestamp: nil
    )
  }
}
