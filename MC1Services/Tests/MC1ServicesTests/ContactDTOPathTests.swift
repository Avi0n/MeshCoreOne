import Testing
import Foundation
@testable import MC1Services
@testable import MeshCore

@Suite("ContactDTO Path Hops")
struct ContactDTOPathTests {

    @Test("Single-byte hops chunk into one (data, hex) pair each")
    func singleByteHops() {
        let contact = ContactDTO.testContact(
            outPathLength: encodePathLen(hashSize: 1, hopCount: 3),
            outPath: Data([0x1A, 0x2B, 0x3C])
        )

        let hops = contact.pathHops
        #expect(hops.map(\.data) == [Data([0x1A]), Data([0x2B]), Data([0x3C])])
        #expect(hops.map(\.hex) == ["1A", "2B", "3C"])
        #expect(contact.pathNodesHex == ["1A", "2B", "3C"])
    }

    @Test("Multi-byte hops chunk by the encoded hash size")
    func multiByteHops() {
        let contact = ContactDTO.testContact(
            outPathLength: encodePathLen(hashSize: 2, hopCount: 2),
            outPath: Data([0x1A, 0x2B, 0x3C, 0x4D])
        )

        let hops = contact.pathHops
        #expect(hops.map(\.data) == [Data([0x1A, 0x2B]), Data([0x3C, 0x4D])])
        #expect(hops.map(\.hex) == ["1A2B", "3C4D"])
    }

    @Test("Hops ignore bytes beyond the encoded path length")
    func ignoresTrailingBytes() {
        let contact = ContactDTO.testContact(
            outPathLength: encodePathLen(hashSize: 1, hopCount: 2),
            outPath: Data([0x1A, 0x2B, 0xFF, 0xFF])
        )

        #expect(contact.pathHops.map(\.hex) == ["1A", "2B"])
    }

    @Test("A flood-routed contact has no hops")
    func floodRoutedHasNoHops() {
        let contact = ContactDTO.testContact(
            outPathLength: PacketBuilder.floodPathSentinel,
            outPath: Data([0x1A, 0x2B])
        )

        #expect(contact.pathHops.isEmpty)
        #expect(contact.pathNodesHex.isEmpty)
    }

    @Test("Displayed hops use the out-path count when a route is set, ignoring inbound")
    func displayedHopsPrefersOutPath() {
        let contact = ContactDTO.testContact(
            outPathLength: encodePathLen(hashSize: 1, hopCount: 3),
            outPath: Data([0x1A, 0x2B, 0x3C])
        )

        #expect(contact.displayedHopCount(inboundHopCount: 5) == 3)
    }

    @Test("A set out-path of zero hops displays zero, not the inbound fallback")
    func displayedHopsOutPathZeroWins() {
        let contact = ContactDTO.testContact(
            outPathLength: encodePathLen(hashSize: 1, hopCount: 0)
        )

        #expect(!contact.isFloodRouted)
        #expect(contact.displayedHopCount(inboundHopCount: 5) == 0)
    }

    @Test("A flood-routed contact falls back to the inbound advert hops")
    func displayedHopsFloodFallsBackToInbound() {
        let contact = ContactDTO.testContact(outPathLength: PacketBuilder.floodPathSentinel)

        #expect(contact.displayedHopCount(inboundHopCount: 2) == 2)
    }

    @Test("An inbound zero is a real value, not nil")
    func displayedHopsInboundZeroIsPresent() {
        let contact = ContactDTO.testContact(outPathLength: PacketBuilder.floodPathSentinel)

        #expect(contact.displayedHopCount(inboundHopCount: 0) == 0)
    }

    @Test("Flood-routed with no inbound reception has no displayed hops")
    func displayedHopsFloodWithoutInboundIsNil() {
        let contact = ContactDTO.testContact(outPathLength: PacketBuilder.floodPathSentinel)

        #expect(contact.displayedHopCount(inboundHopCount: nil) == nil)
    }
}
