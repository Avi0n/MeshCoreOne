import Foundation
@testable import MeshCore
import Testing

@Suite("MeshCoreSession.events(filter:)")
struct EventDispatcherFilteredSubscriptionTests {
  @Test
  func `filtered subscription receives only matching events`() async {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)

    let stream = await session.events(filter: .anyAcknowledgement)

    await session.dispatchForTesting(.advertisement(publicKey: Data([0x01])))
    await session.dispatchForTesting(.acknowledgement(code: Data([0x10, 0x20, 0x30, 0x40]), tripTime: 500))
    await session.dispatchForTesting(.advertisement(publicKey: Data([0x02])))

    var iterator = stream.makeAsyncIterator()
    let first = await iterator.next()

    guard case let .acknowledgement(code, tripTime) = first else {
      Issue.record("Expected acknowledgement, got \(String(describing: first))")
      return
    }
    #expect(code == Data([0x10, 0x20, 0x30, 0x40]))
    #expect(tripTime == 500)
  }

  @Test
  func `filtered subscription survives flood of non-matching events`() async {
    let transport = MockTransport()
    let session = MeshCoreSession(transport: transport)

    let stream = await session.events(filter: .anyAcknowledgement)

    // 500 non-matching events is 5x the 100-slot buffer cap — enough to
    // overflow an unfiltered subscription.
    for i in 0..<500 {
      await session.dispatchForTesting(.advertisement(publicKey: Data([UInt8(i % 256)])))
    }

    let expectedCode = Data([0xAB, 0xCD, 0xEF, 0x12])
    await session.dispatchForTesting(.acknowledgement(code: expectedCode, tripTime: 1234))

    var iterator = stream.makeAsyncIterator()
    let event = await iterator.next()

    guard case let .acknowledgement(code, tripTime) = event else {
      Issue.record("filter not applied — iterator yielded a non-ACK event first. Got: \(String(describing: event))")
      return
    }
    #expect(code == expectedCode)
    #expect(tripTime == 1234)
  }
}
