import Foundation
@testable import MeshCore
import Testing

@Suite("Region scope to FloodScope mapping")
struct FloodScopeMappingTests {
  @Test
  func `disabled scope key differs from any region scope key`() {
    let disabled = FloodScope.disabled.scopeKey()
    let region = FloodScope.region("Europe").scopeKey()

    #expect(disabled != region)
  }

  @Test
  func `different region names produce different scope keys`() {
    let europe = FloodScope.region("Europe").scopeKey()
    let uk = FloodScope.region("UK").scopeKey()

    #expect(europe != uk)
  }

  @Test
  func `setFloodScopeUnscoped emits sub-command 1 with no scope key`() {
    let data = PacketBuilder.setFloodScopeUnscoped()

    #expect(data == Data([CommandCode.setFloodScope.rawValue, 0x01]))
    #expect(data == Data([0x36, 0x01]))
  }

  @Test
  func `setFloodScope (sub-command 0) differs from the unscoped override`() {
    let zeroKey = PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey())
    let unscoped = PacketBuilder.setFloodScopeUnscoped()

    #expect(zeroKey != unscoped)
    #expect(zeroKey[1] == 0x00)
    #expect(unscoped[1] == 0x01)
  }
}
