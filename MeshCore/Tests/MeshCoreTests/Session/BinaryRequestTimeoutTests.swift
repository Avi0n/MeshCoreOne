import Foundation
@testable import MeshCore
import Testing

@Suite("Binary request timeout")
struct BinaryRequestTimeoutTests {
  @Test
  func `defaults use a 40 second overall budget and 1 second retransmit floor`() {
    let configuration = SessionConfiguration()
    #expect(configuration.binaryRequestOverallTimeout == 40.0)
    #expect(configuration.binaryRequestRetransmitInterval == 1.0)
    #expect(SessionConfiguration.binaryRetransmitRTTHeadroom == 2.0)
  }

  @Test
  func `configuration accepts custom overall and nil retransmit disables in exchange resends`() {
    let configuration = SessionConfiguration(
      binaryRequestOverallTimeout: 0.05,
      binaryRequestRetransmitInterval: nil
    )
    #expect(configuration.binaryRequestOverallTimeout == 0.05)
    #expect(configuration.binaryRequestRetransmitInterval == nil)
  }
}
