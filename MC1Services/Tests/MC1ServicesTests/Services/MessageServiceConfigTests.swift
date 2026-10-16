import Foundation
@testable import MC1Services
import Testing

/// MessageServiceConfig guards against `maxAttempts > 5` via a precondition.
/// The firmware ACK hash masks the attempt index with `& 0x03`; a single
/// message's `ackCodes` set dedupes the attempt-4 wrap, so 5 (4 direct + 1
/// flood) stays unambiguous, and the cap then bounds mesh airtime. Swift
/// Testing has no precondition matcher, so the failing case is documented
/// inline rather than asserted at runtime; the boundary case is exercised here.
@Suite("MessageServiceConfig precondition")
struct MessageServiceConfigTests {
  @Test
  func `maxAttempts == 5 is accepted at the precondition boundary`() {
    let config = MessageServiceConfig(maxAttempts: 5)
    #expect(config.maxAttempts == 5)
  }

  @Test
  func `Default config respects the maxAttempts ceiling`() {
    let config = MessageServiceConfig()
    #expect(config.maxAttempts <= 5)
  }
}
