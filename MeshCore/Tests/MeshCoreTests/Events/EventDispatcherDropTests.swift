import Foundation
@testable import MeshCore
import Testing

@Suite("EventDispatcher drop observability")
struct EventDispatcherDropTests {
  @Test
  func `droppedEventCount increments when a slow consumer overflows the buffer`() async {
    let dispatcher = EventDispatcher()
    let (_, stream) = await dispatcher.subscribeTracked(filter: nil)

    // Don't drain `stream` — we want the buffer to fill.
    for i in 0..<200 {
      await dispatcher.dispatch(.advertisement(publicKey: Data([UInt8(i % 256)])))
    }

    // 100-slot buffer → 100 drops expected.
    #expect(await dispatcher.droppedEventCount >= 100)

    // Keep the stream alive through the dispatch loop; going out of scope
    // here triggers onTermination → removeSubscription.
    _ = stream
  }
}
