import Foundation
@testable import MeshCore
import Testing

@Suite("Neighbours Parser")
struct NeighboursParserTests {
  @Test(arguments: [
    Data([0, 0, 0xFF, 0xFF]),
    Data([0xFF, 0xFF, 1, 0]) + Data(repeating: 0, count: 9)
  ])
  func `negative counts produce an empty response`(payload: Data) {
    let publicKeyPrefix = Data([1, 2, 3, 4])
    let tag = Data([5, 6, 7, 8])
    let response = NeighboursParser.parse(payload, publicKeyPrefix: publicKeyPrefix, tag: tag)

    #expect(response.totalCount == 0)
    #expect(response.neighbours.isEmpty)
    #expect(response.publicKeyPrefix == publicKeyPrefix)
    #expect(response.tag == tag)
  }
}
