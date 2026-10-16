@testable import MC1
import SwiftUI
import Testing

/// `opaqueFill` stays `nil` on iOS 26+ even when a theme supplies a canvas, so
/// glass on the compose controls can sample the timeline.
@Suite("Chat input bar themed chrome")
struct ChatInputBarThemedChromeTests {
  @available(iOS 26, *)
  @Test
  func `default theme does not paint an opaque fill on Liquid Glass`() {
    #expect(ChatInputBarChrome.opaqueFill(themedCanvas: nil) == nil)
  }

  @available(iOS 26, *)
  @Test
  func `themed canvas does not paint an opaque fill on Liquid Glass`() {
    #expect(ChatInputBarChrome.opaqueFill(themedCanvas: Theme.ember.surfaces?.canvas) == nil)
  }
}
