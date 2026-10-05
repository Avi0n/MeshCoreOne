import Foundation
@testable import MC1
import Testing

@Suite("MessagePathPreviewMap")
struct MessagePathPreviewMapTests {
  @Test
  @MainActor
  func `overlay exposes distance and incomplete without hops`() {
    let map = MessagePathPreviewMap(
      image: nil,
      didFail: false,
      totalPathDistance: 12400,
      isDistanceIncomplete: true,
      onExpand: {},
      onRetry: {}
    )
    let distance = Measurement(value: 12400, unit: UnitLength.meters)
      .formatted(.measurement(width: .abbreviated, usage: .road))
    let texts = map.distanceBanner.spokenTexts
    #expect(texts.contains { $0.contains(distance) || $0 == distance })
    #expect(texts.contains(L10n.Chats.Chats.Path.Distance.incomplete))
    #expect(map.expandAccessibilityLabel == L10n.Chats.Chats.Path.Accessibility.viewOnMap)
    #expect(map.previewShowsHopCount == false)
    #expect(!texts.contains(L10n.Contacts.Contacts.Trace.Map.hops(0)))
  }
}
