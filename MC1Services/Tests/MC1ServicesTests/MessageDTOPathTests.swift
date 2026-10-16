import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("MessageDTO Path Hops")
struct MessageDTOPathTests {
  @Test
  func `Single-byte hops chunk into one (data, hex) pair each`() {
    let message = MessageDTO.testDirectMessage(
      pathLength: encodePathLen(hashSize: 1, hopCount: 3),
      pathNodes: Data([0x1A, 0x2B, 0x3C])
    )

    let hops = message.pathHops
    #expect(hops.map(\.data) == [Data([0x1A]), Data([0x2B]), Data([0x3C])])
    #expect(hops.map(\.hex) == ["1A", "2B", "3C"])
    #expect(message.pathNodesHex == ["1A", "2B", "3C"])
  }

  @Test
  func `Multi-byte hops chunk by the encoded hash size`() {
    let message = MessageDTO.testDirectMessage(
      pathLength: encodePathLen(hashSize: 2, hopCount: 2),
      pathNodes: Data([0x1A, 0x2B, 0x3C, 0x4D])
    )

    let hops = message.pathHops
    #expect(hops.map(\.data) == [Data([0x1A, 0x2B]), Data([0x3C, 0x4D])])
    #expect(hops.map(\.hex) == ["1A2B", "3C4D"])
  }

  @Test
  func `A trailing partial hop is kept as a short final chunk`() {
    let message = MessageDTO.testDirectMessage(
      pathLength: encodePathLen(hashSize: 2, hopCount: 1),
      pathNodes: Data([0x1A, 0x2B, 0x3C])
    )

    #expect(message.pathHops.map(\.hex) == ["1A2B", "3C"])
  }

  @Test
  func `A message with no path nodes has no hops`() {
    let message = MessageDTO.testDirectMessage(
      pathLength: encodePathLen(hashSize: 1, hopCount: 0),
      pathNodes: nil
    )

    #expect(message.pathHops.isEmpty)
    #expect(message.pathNodesHex.isEmpty)
  }
}
