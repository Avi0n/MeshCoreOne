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
}
