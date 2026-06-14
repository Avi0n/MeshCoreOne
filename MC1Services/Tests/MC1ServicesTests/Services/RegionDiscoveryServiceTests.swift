import Foundation
import Testing
@testable import MC1Services

@Suite("RegionDiscoveryService query-target building")
struct RegionDiscoveryServiceTests {

    private let radioID = UUID()

    private func repeaterContact(_ pub: Data) -> ContactDTO {
        ContactDTO(
            id: UUID(),
            radioID: radioID,
            publicKey: pub,
            name: "Repeater",
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
            unreadCount: 0
        )
    }

    private func repeaterNode(_ pub: Data) -> DiscoveredNodeDTO {
        DiscoveredNodeDTO(
            id: UUID(),
            radioID: radioID,
            publicKey: pub,
            name: "Discovered",
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: Date(timeIntervalSince1970: 0),
            lastAdvertTimestamp: 0,
            latitude: 0,
            longitude: 0,
            outPathLength: 0,
            outPath: Data()
        )
    }

    @Test("Non-contact responders are queried when ad-hoc requests are supported")
    func includesNonContactNodesWhenSupported() {
        let contactKey = Data(repeating: 0x11, count: 32)
        let nonContactKey = Data(repeating: 0x22, count: 32)

        let targets = RegionDiscoveryService.buildRegionQueryTargets(
            responders: [contactKey, nonContactKey],
            contacts: [repeaterContact(contactKey)],
            discoveredNodes: [repeaterNode(nonContactKey)],
            supportsAdHocRequest: true
        )

        let keys = Set(targets.map(\.publicKey))
        #expect(keys == [contactKey, nonContactKey])
    }

    @Test("Non-contact responders are skipped when ad-hoc requests are unsupported")
    func excludesNonContactNodesWhenUnsupported() {
        let contactKey = Data(repeating: 0x11, count: 32)
        let nonContactKey = Data(repeating: 0x22, count: 32)

        let targets = RegionDiscoveryService.buildRegionQueryTargets(
            responders: [contactKey, nonContactKey],
            contacts: [repeaterContact(contactKey)],
            discoveredNodes: [repeaterNode(nonContactKey)],
            supportsAdHocRequest: false
        )

        let keys = Set(targets.map(\.publicKey))
        #expect(keys == [contactKey])
    }
}
