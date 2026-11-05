import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("MessageService Tests")
struct MessageServiceTests {
  // MARK: - MessageServiceConfig Tests

  @Test
  func `MessageServiceConfig default values`() {
    let config = MessageServiceConfig.default
    #expect(config.floodFallbackOnRetry == true)
    #expect(config.minTimeout == 0)
    #expect(config.triggerPathDiscoveryAfterFlood == true)
    #expect(config.ackGiveUpWindow == 30)
  }

  @Test
  func `MessageServiceConfig custom values`() {
    let config = MessageServiceConfig(
      floodFallbackOnRetry: false,
      minTimeout: 10.0,
      triggerPathDiscoveryAfterFlood: false
    )
    #expect(config.floodFallbackOnRetry == false)
    #expect(config.minTimeout == 10.0)
    #expect(config.triggerPathDiscoveryAfterFlood == false)
  }
}
