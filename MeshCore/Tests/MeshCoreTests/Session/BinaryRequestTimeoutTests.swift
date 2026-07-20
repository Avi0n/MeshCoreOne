import Foundation
@testable import MeshCore
import Testing

@Suite("Binary request timeout")
struct BinaryRequestTimeoutTests {
  @Test
  func `short firmware estimates are floored for flood-return replies`() {
    // A 2-hop direct estimate (4346ms -> 8.7s doubled) starved a status reply
    // that returned via flood; the floor must win over short estimates.
    let configuration = SessionConfiguration()
    #expect(configuration.binaryRequestTimeout(suggestedTimeoutMs: 4346)
      == configuration.binaryRequestMinimumTimeout)
    #expect(configuration.binaryRequestTimeout(suggestedTimeoutMs: 0)
      == configuration.binaryRequestMinimumTimeout)
  }

  @Test
  func `long firmware estimates extend beyond the floor`() {
    #expect(SessionConfiguration().binaryRequestTimeout(suggestedTimeoutMs: 10000) == 20.0)
  }

  @Test
  func `a zero floor leaves the firmware estimate in charge`() {
    let configuration = SessionConfiguration(binaryRequestMinimumTimeout: 0)
    #expect(configuration.binaryRequestTimeout(suggestedTimeoutMs: 4346) == 8.692)
  }
}
