import Foundation
@testable import MC1Services
import Testing

@Suite("EventBroadcaster Tests")
struct EventBroadcasterTests {
  @Test
  func `two subscribers both receive every event in yield order`() async {
    let broadcaster = EventBroadcaster<Int>()
    let streamA = broadcaster.subscribe()
    let streamB = broadcaster.subscribe()
    let events = [1, 2, 3, 4, 5]

    for value in events {
      broadcaster.yield(value)
    }
    broadcaster.finish()

    var receivedA: [Int] = []
    for await value in streamA {
      receivedA.append(value)
    }
    var receivedB: [Int] = []
    for await value in streamB {
      receivedB.append(value)
    }

    #expect(receivedA == events)
    #expect(receivedB == events)
  }

  @Test
  func `a cancelled subscriber is pruned and does not affect its sibling`() async {
    let broadcaster = EventBroadcaster<Int>()
    let doomed = broadcaster.subscribe()
    let survivor = broadcaster.subscribe()
    #expect(broadcaster.subscriberCount == 2)

    let consumer = Task {
      for await _ in doomed {}
    }
    consumer.cancel()
    await consumer.value
    #expect(broadcaster.subscriberCount == 1)

    broadcaster.yield(7)
    broadcaster.finish()

    var received: [Int] = []
    for await value in survivor {
      received.append(value)
    }
    #expect(received == [7])
  }

  @Test
  func `finish ends every subscriber's for-await loop`() async {
    let broadcaster = EventBroadcaster<Int>()
    let streamA = broadcaster.subscribe()
    let streamB = broadcaster.subscribe()

    let consumerA = Task {
      for await _ in streamA {}
      return true
    }
    let consumerB = Task {
      for await _ in streamB {}
      return true
    }

    broadcaster.finish()

    #expect(await consumerA.value)
    #expect(await consumerB.value)
    #expect(broadcaster.subscriberCount == 0)
  }

  @Test
  func `an event yielded immediately after subscribe is never dropped`() async {
    let broadcaster = EventBroadcaster<String>()
    let stream = broadcaster.subscribe()
    broadcaster.yield("first")
    broadcaster.finish()

    var received: [String] = []
    for await value in stream {
      received.append(value)
    }
    #expect(received == ["first"])
  }

  @Test
  func `subscribing after finish returns a stream that completes immediately`() async {
    let broadcaster = EventBroadcaster<Int>()
    broadcaster.finish()

    let stream = broadcaster.subscribe()
    var received: [Int] = []
    for await value in stream {
      received.append(value)
    }
    #expect(received.isEmpty)
  }

  @Test
  func `yield after finish reaches no subscriber`() {
    let broadcaster = EventBroadcaster<Int>()
    broadcaster.finish()
    broadcaster.yield(42)
    #expect(broadcaster.subscriberCount == 0)
  }
}
