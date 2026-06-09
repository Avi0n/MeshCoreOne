import CoreLocation
import Foundation
import Testing
@testable import MC1
@testable import MC1Services

@Suite("RepeaterResolver")
struct RepeaterResolverTests {

    private func createRepeater(
        prefix: UInt8,
        secondByte: UInt8,
        name: String,
        lastAdvertTimestamp: UInt32,
        latitude: Double,
        longitude: Double
    ) -> ContactDTO {
        ContactDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data([prefix, secondByte] + Array(repeating: UInt8(0), count: 30)),
            name: name,
            typeRawValue: ContactType.repeater.rawValue,
            flags: 0,
            outPathLength: 0,
            outPath: Data(),
            lastAdvertTimestamp: lastAdvertTimestamp,
            latitude: latitude,
            longitude: longitude,
            lastModified: 0,
            nickname: nil,
            isBlocked: false,
            isMuted: false,
            isFavorite: false,
            lastMessageDate: nil,
            unreadCount: 0
        )
    }

    @Test("prefers closest repeater when location available")
    func prefersClosestWithLocation() {
        let repeaterA = createRepeater(
            prefix: 0x3F,
            secondByte: 0x01,
            name: "Near",
            lastAdvertTimestamp: 10,
            latitude: 37.0,
            longitude: -122.0
        )
        let repeaterB = createRepeater(
            prefix: 0x3F,
            secondByte: 0x02,
            name: "Far",
            lastAdvertTimestamp: 200,
            latitude: 38.0,
            longitude: -123.0
        )

        let userLocation = CLLocation(latitude: 37.0005, longitude: -122.0005)
        let match = RepeaterResolver.bestMatch(for: Data([0x3F]), in: [repeaterA, repeaterB], userLocation: userLocation)

        #expect(match?.displayName == "Near")
    }

    @Test("exact match with full public key ignores proximity/recency")
    func exactMatchWithFullPublicKey() {
        let repeaterA = createRepeater(
            prefix: 0x3F,
            secondByte: 0x01,
            name: "Target",
            lastAdvertTimestamp: 10,
            latitude: 38.0,
            longitude: -123.0
        )
        let repeaterB = createRepeater(
            prefix: 0x3F,
            secondByte: 0x02,
            name: "Closer and Newer",
            lastAdvertTimestamp: 200,
            latitude: 37.0,
            longitude: -122.0
        )

        let userLocation = CLLocation(latitude: 37.0005, longitude: -122.0005)
        // PathHop with full key of repeaterA - should match exactly despite repeaterB being closer/newer
        let hop = PathHop(hashBytes: Data([0x3F]), publicKey: repeaterA.publicKey, resolvedName: "Target")
        let match = RepeaterResolver.bestMatch(for: hop, in: [repeaterA, repeaterB], userLocation: userLocation)

        #expect(match?.displayName == "Target")
    }

    @Test("PathHop without public key falls back to proximity/recency")
    func pathHopWithoutKeyFallsBackToProximity() {
        let repeaterA = createRepeater(
            prefix: 0x3F,
            secondByte: 0x01,
            name: "Far",
            lastAdvertTimestamp: 10,
            latitude: 38.0,
            longitude: -123.0
        )
        let repeaterB = createRepeater(
            prefix: 0x3F,
            secondByte: 0x02,
            name: "Near",
            lastAdvertTimestamp: 200,
            latitude: 37.0,
            longitude: -122.0
        )

        let userLocation = CLLocation(latitude: 37.0005, longitude: -122.0005)
        // PathHop with nil publicKey - should fall back to proximity match
        let hop = PathHop(hashBytes: Data([0x3F]), resolvedName: nil)
        let match = RepeaterResolver.bestMatch(for: hop, in: [repeaterA, repeaterB], userLocation: userLocation)

        #expect(match?.displayName == "Near")
    }

    @Test("PathHop with deleted contact key falls back to hash byte match")
    func pathHopWithDeletedContactFallsBack() {
        let repeaterA = createRepeater(
            prefix: 0x3F,
            secondByte: 0x01,
            name: "Only Match",
            lastAdvertTimestamp: 10,
            latitude: 0,
            longitude: 0
        )

        // PathHop has a key that doesn't match any current repeater (contact was deleted)
        let deletedKey = Data([0x3F, 0xFF] + Array(repeating: UInt8(0), count: 30))
        let hop = PathHop(hashBytes: Data([0x3F]), publicKey: deletedKey, resolvedName: "Deleted")
        let match = RepeaterResolver.bestMatch(for: hop, in: [repeaterA], userLocation: nil)

        // Falls back to hash byte match
        #expect(match?.displayName == "Only Match")
    }

    // MARK: - DiscoveredNodeDTO Tests

    private func createDiscoveredNode(
        prefix: UInt8,
        secondByte: UInt8,
        name: String,
        lastAdvertTimestamp: UInt32,
        lastHeard: Date = Date(),
        latitude: Double,
        longitude: Double
    ) -> DiscoveredNodeDTO {
        DiscoveredNodeDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data([prefix, secondByte] + Array(repeating: UInt8(0), count: 30)),
            name: name,
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: lastHeard,
            lastAdvertTimestamp: lastAdvertTimestamp,
            latitude: latitude,
            longitude: longitude,
            outPathLength: 0,
            outPath: Data()
        )
    }

    @Test("prefers closest discovered node when location available")
    func prefersClosestDiscoveredNodeWithLocation() {
        let nodeA = createDiscoveredNode(
            prefix: 0x3F,
            secondByte: 0x01,
            name: "Near Node",
            lastAdvertTimestamp: 10,
            latitude: 37.0,
            longitude: -122.0
        )
        let nodeB = createDiscoveredNode(
            prefix: 0x3F,
            secondByte: 0x02,
            name: "Far Node",
            lastAdvertTimestamp: 200,
            latitude: 38.0,
            longitude: -123.0
        )

        let userLocation = CLLocation(latitude: 37.0005, longitude: -122.0005)
        let match = RepeaterResolver.bestMatch(for: Data([0x3F]), in: [nodeA, nodeB], userLocation: userLocation)

        #expect(match?.name == "Near Node")
    }

    @Test("prefers most recent discovered node without location")
    func prefersMostRecentDiscoveredNodeWithoutLocation() {
        let nodeA = createDiscoveredNode(
            prefix: 0x3F,
            secondByte: 0x01,
            name: "Older Node",
            lastAdvertTimestamp: 10,
            latitude: 0,
            longitude: 0
        )
        let nodeB = createDiscoveredNode(
            prefix: 0x3F,
            secondByte: 0x02,
            name: "Newer Node",
            lastAdvertTimestamp: 200,
            latitude: 0,
            longitude: 0
        )

        let match = RepeaterResolver.bestMatch(for: Data([0x3F]), in: [nodeA, nodeB], userLocation: nil)

        #expect(match?.name == "Newer Node")
    }

    @Test("exact match with full public key for discovered node PathHop variant")
    func exactMatchDiscoveredNodePathHop() {
        let nodeA = createDiscoveredNode(
            prefix: 0x3F,
            secondByte: 0x01,
            name: "Target Node",
            lastAdvertTimestamp: 10,
            latitude: 38.0,
            longitude: -123.0
        )
        let nodeB = createDiscoveredNode(
            prefix: 0x3F,
            secondByte: 0x02,
            name: "Closer and Newer",
            lastAdvertTimestamp: 200,
            latitude: 37.0,
            longitude: -122.0
        )

        let userLocation = CLLocation(latitude: 37.0005, longitude: -122.0005)
        let hop = PathHop(hashBytes: Data([0x3F]), publicKey: nodeA.publicKey, resolvedName: "Target Node")
        let match = RepeaterResolver.bestMatch(for: hop, in: [nodeA, nodeB], userLocation: userLocation)

        #expect(match?.name == "Target Node")
    }

    // MARK: - ContactDTO Tests

    @Test("prefers most recent when location unavailable")
    func prefersMostRecentWithoutLocation() {
        let repeaterA = createRepeater(
            prefix: 0x3F,
            secondByte: 0x01,
            name: "Older",
            lastAdvertTimestamp: 10,
            latitude: 0,
            longitude: 0
        )
        let repeaterB = createRepeater(
            prefix: 0x3F,
            secondByte: 0x02,
            name: "Newer",
            lastAdvertTimestamp: 200,
            latitude: 0,
            longitude: 0
        )

        let match = RepeaterResolver.bestMatch(for: Data([0x3F]), in: [repeaterA, repeaterB], userLocation: nil)

        #expect(match?.displayName == "Newer")
    }

    @Test("neighbor name resolver uses discovered node when contact is absent")
    func neighborNameResolverUsesDiscoveredNode() {
        let node = DiscoveredNodeDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data([0xAB, 0xCD, 0xEF] + Array(repeating: UInt8(0), count: 29)),
            name: "Ridge Repeater",
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: Date(),
            lastAdvertTimestamp: 100,
            latitude: 0,
            longitude: 0,
            outPathLength: 0,
            outPath: Data()
        )

        let name = NeighborNameResolver.resolveName(
            for: Data([0xAB, 0xCD]),
            contacts: [],
            discoveredNodes: [node],
            userLocation: nil
        )

        #expect(name == "Ridge Repeater")
    }

    @Test("neighbor name resolver marks unique short discovered prefix as exact")
    func neighborNameResolverMarksUniqueShortDiscoveredPrefixAsExact() {
        let node = DiscoveredNodeDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data([0xAB, 0xCD, 0xEF] + Array(repeating: UInt8(0), count: 29)),
            name: "Ridge Repeater",
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: Date(),
            lastAdvertTimestamp: 100,
            latitude: 0,
            longitude: 0,
            outPathLength: 0,
            outPath: Data()
        )

        let result = NeighborNameResolver.resolve(
            for: Data([0xAB, 0xCD]),
            contacts: [],
            discoveredNodes: [node],
            userLocation: nil
        )

        #expect(result?.displayName == "Ridge Repeater")
        #expect(result?.matchKind == .exact)
    }

    @Test("neighbor name resolver marks full prefix contact match as exact")
    func neighborNameResolverMarksFullPrefixContactMatchAsExact() {
        let contact = createRepeater(
            prefix: 0xAB,
            secondByte: 0xCD,
            name: "Saved Repeater",
            lastAdvertTimestamp: 10,
            latitude: 0,
            longitude: 0
        )

        let result = NeighborNameResolver.resolve(
            for: contact.publicKeyPrefix,
            contacts: [contact],
            discoveredNodes: [],
            userLocation: nil
        )

        #expect(result?.displayName == "Saved Repeater")
        #expect(result?.matchKind == .exact)
    }

    @Test("neighbor name resolver prefers contacts over discovered nodes")
    func neighborNameResolverPrefersContacts() {
        let contact = createRepeater(
            prefix: 0xAB,
            secondByte: 0xCD,
            name: "Saved Repeater",
            lastAdvertTimestamp: 10,
            latitude: 0,
            longitude: 0
        )
        let node = DiscoveredNodeDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: contact.publicKey,
            name: "Advert Repeater",
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: Date(),
            lastAdvertTimestamp: 200,
            latitude: 0,
            longitude: 0,
            outPathLength: 0,
            outPath: Data()
        )

        let name = NeighborNameResolver.resolveName(
            for: Data([0xAB, 0xCD]),
            contacts: [contact],
            discoveredNodes: [node],
            userLocation: nil
        )

        #expect(name == "Saved Repeater")
    }

    @Test("neighbor name resolver marks cross-source short prefix ambiguity as fallback")
    func neighborNameResolverMarksCrossSourceShortPrefixAmbiguityAsFallback() {
        let contact = createRepeater(
            prefix: 0xAB,
            secondByte: 0xCD,
            name: "Saved Repeater",
            lastAdvertTimestamp: 10,
            latitude: 0,
            longitude: 0
        )
        let node = DiscoveredNodeDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data([0xAB, 0xEF] + Array(repeating: UInt8(0), count: 30)),
            name: "Advert Repeater",
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: Date(),
            lastAdvertTimestamp: 200,
            latitude: 0,
            longitude: 0,
            outPathLength: 0,
            outPath: Data()
        )

        let result = NeighborNameResolver.resolve(
            for: Data([0xAB]),
            contacts: [contact],
            discoveredNodes: [node],
            userLocation: nil
        )

        #expect(result?.displayName == "Saved Repeater")
        #expect(result?.matchKind == .fallback)
    }

    @Test("neighbor name resolver disambiguates short discovered prefixes by recency")
    func neighborNameResolverDisambiguatesShortPrefixesByRecency() {
        let older = DiscoveredNodeDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data([0xAB, 0xCD, 0x01] + Array(repeating: UInt8(0), count: 29)),
            name: "Older Repeater",
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: Date(timeIntervalSince1970: 1_000),
            lastAdvertTimestamp: 100,
            latitude: 0,
            longitude: 0,
            outPathLength: 0,
            outPath: Data()
        )
        let newer = DiscoveredNodeDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data([0xAB, 0xCD, 0x02] + Array(repeating: UInt8(0), count: 29)),
            name: "Newer Repeater",
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: Date(timeIntervalSince1970: 2_000),
            lastAdvertTimestamp: 200,
            latitude: 0,
            longitude: 0,
            outPathLength: 0,
            outPath: Data()
        )

        let name = NeighborNameResolver.resolveName(
            for: Data([0xAB, 0xCD]),
            contacts: [],
            discoveredNodes: [older, newer],
            userLocation: nil
        )

        #expect(name == "Newer Repeater")
    }

    @Test("neighbor name resolver marks ambiguous short discovered prefix as fallback")
    func neighborNameResolverMarksAmbiguousShortDiscoveredPrefixAsFallback() {
        let older = DiscoveredNodeDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data([0xAB, 0xCD, 0x01] + Array(repeating: UInt8(0), count: 29)),
            name: "Older Repeater",
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: Date(timeIntervalSince1970: 1_000),
            lastAdvertTimestamp: 100,
            latitude: 0,
            longitude: 0,
            outPathLength: 0,
            outPath: Data()
        )
        let newer = DiscoveredNodeDTO(
            id: UUID(),
            radioID: UUID(),
            publicKey: Data([0xAB, 0xCD, 0x02] + Array(repeating: UInt8(0), count: 29)),
            name: "Newer Repeater",
            typeRawValue: ContactType.repeater.rawValue,
            lastHeard: Date(timeIntervalSince1970: 2_000),
            lastAdvertTimestamp: 200,
            latitude: 0,
            longitude: 0,
            outPathLength: 0,
            outPath: Data()
        )

        let result = NeighborNameResolver.resolve(
            for: Data([0xAB, 0xCD]),
            contacts: [],
            discoveredNodes: [older, newer],
            userLocation: nil
        )

        #expect(result?.displayName == "Newer Repeater")
        #expect(result?.matchKind == .fallback)
    }
}
