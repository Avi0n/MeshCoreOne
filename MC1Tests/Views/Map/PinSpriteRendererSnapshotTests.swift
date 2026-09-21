@testable import MC1
import Testing
import UIKit

@Suite("PinSpriteRenderer snapshot sprites")
@MainActor
struct PinSpriteRendererSnapshotTests {
  @Test
  func `hop-badged repeater snapshot is larger than the base repeater pin`() {
    let hop = PinSpriteRenderer.snapshotSprite(named: "pin-repeater-hop-3")
    let base = PinSpriteRenderer.snapshotSprite(named: "pin-repeater")
    #expect(hop.size.width > 1)
    #expect(hop.size.height > 1)
    #expect(hop.size != base.size)
  }
}
