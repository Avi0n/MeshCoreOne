import Foundation
import MeshCore
import Testing
@testable import MC1
@testable import MC1Services

@Suite("ChannelInfoSheet region query targets")
struct ChannelInfoRegionQueryTargetsTests {

    private static let radioID = UUID()
    private static let keyA = Data(repeating: 0x0A, count: 32)
    private static let keyB = Data(repeating: 0x0B, count: 32)
    private static let keyC = Data(repeating: 0x0C, count: 32)
    private static let keyD = Data(repeating: 0x0D, count: 32)
    private static let keyE = Data(repeating: 0x0E, count: 32)

    @Test("queries responders present only in the discovered-nodes table")
    func includesDiscoveredNodeResponders() {
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

    @Test("prefers contact record when both sources have the responder")
    func prefersContactOverDiscoveredNode() {
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

    @Test("unions contacts and discovered nodes without duplication")
    func unionsBothPoolsDeduped() {
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

        let keys = Set(targets.map { $0.publicKey })
        #expect(keys == responders)
    }

    @Test("excludes non-repeater types from both pools")
    func excludesNonRepeaters() {
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

        let keys = Set(targets.map { $0.publicKey })
        #expect(keys == [Self.keyB, Self.keyD])
    }

    @Test("drops responders that are in neither pool")
    func dropsUnknownResponders() {
        let responders: Set<Data> = [Self.keyA, Self.keyE]
        let targets = RegionDiscoveryService.buildRegionQueryTargets(
            responders: responders,
            contacts: [Self.makeContact(publicKey: Self.keyA, type: .repeater)],
            discoveredNodes: [],
            supportsAdHocRequest: true
        )

        #expect(targets.map(\.publicKey) == [Self.keyA])
    }

    @Test("drops non-responders even if they are repeater contacts")
    func dropsNonResponders() {
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

    @Test("forwards outPath bytes from contact source")
    func forwardsContactRoutingData() {
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

    @Test("forwards outPath bytes from discovered-node source")
    func forwardsDiscoveredNodeRoutingData() {
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

    @Test("uses contact outPath when both sources have routing data")
    func contactRoutingWinsOverDiscoveredNode() {
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
