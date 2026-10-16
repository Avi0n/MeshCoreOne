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
    #expect(config.maxAttempts == 5)
    #expect(config.maxFloodAttempts == 1)
    #expect(config.floodAfter == 4)
    #expect(config.minTimeout == 0)
    #expect(config.triggerPathDiscoveryAfterFlood == true)
    #expect(config.ackGiveUpWindow == 30)
  }

  @Test
  func `MessageServiceConfig custom values`() {
    let config = MessageServiceConfig(
      floodFallbackOnRetry: false,
      maxAttempts: 3,
      maxFloodAttempts: 3,
      floodAfter: 1,
      minTimeout: 10.0,
      triggerPathDiscoveryAfterFlood: false
    )
    #expect(config.floodFallbackOnRetry == false)
    #expect(config.maxAttempts == 3)
    #expect(config.maxFloodAttempts == 3)
    #expect(config.floodAfter == 1)
    #expect(config.minTimeout == 10.0)
    #expect(config.triggerPathDiscoveryAfterFlood == false)
  }
}
