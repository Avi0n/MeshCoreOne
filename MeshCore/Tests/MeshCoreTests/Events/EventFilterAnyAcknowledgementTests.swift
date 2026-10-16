import Foundation
@testable import MeshCore
import Testing

@Suite("EventFilter.anyAcknowledgement")
struct EventFilterAnyAcknowledgementTests {
  @Test
  func `matches .acknowledgement regardless of code`() {
    let filter = EventFilter.anyAcknowledgement

    let a = MeshEvent.acknowledgement(code: Data([0x01, 0x02, 0x03, 0x04]), tripTime: 100)
    let b = MeshEvent.acknowledgement(code: Data([0xFF, 0xEE, 0xDD, 0xCC]), tripTime: nil)

    #expect(filter.matches(a))
    #expect(filter.matches(b))
  }

  @Test
  func `does not match non-acknowledgement events`() {
    let filter = EventFilter.anyAcknowledgement

    #expect(!filter.matches(.ok(value: nil)))
    #expect(!filter.matches(.error(code: 42)))
    #expect(!filter.matches(.advertisement(publicKey: Data([0xAA]))))
  }
}
