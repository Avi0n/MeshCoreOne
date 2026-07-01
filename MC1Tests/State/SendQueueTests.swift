import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("SendQueue")
struct SendQueueTests {
  /// A trivial envelope used in tests where the payload is just an
  /// identifier the assertions can compare against.
  private struct Marker: Equatable {
    let id: Int
  }

  @Test
  func `Serial draining: enqueueing N envelopes triggers N sends in FIFO order`() async {
    actor Collector {
      var observed: [Int] = []
      func record(_ value: Int) {
        observed.append(value)
      }

      func snapshot() -> [Int] {
        observed
      }
    }
    let collector = Collector()
    let drainSignal = AsyncStream.makeStream(of: Void.self)

    let queue = SendQueue<Marker>(
      send: { envelope in
        await collector.record(envelope.id)
      },
      onError: { _, _ in },
      onDrain: { _ in
        drainSignal.continuation.yield(())
        drainSignal.continuation.finish()
      }
    )

    for id in 1...5 {
      await queue.enqueue(Marker(id: id))
    }

    var iterator = drainSignal.stream.makeAsyncIterator()
    _ = await iterator.next()

    let observed = await collector.snapshot()
    let count = await queue.count
    #expect(observed == [1, 2, 3, 4, 5])
    #expect(count == 0)
  }

  @Test
  func `CancellationError requeues at the front and the drain auto-respawns`() async {
    // The drain's `catch is CancellationError` branch re-inserts the
    // in-flight envelope at index 0, then `taskCompleted` respawns
    // because pending is non-empty. The retried send succeeds (the
    // latch flips after the first throw), and the envelope drains
    // without an external nudge.
    actor Tracker {
      var sendCalls = 0
      var succeededIDs: [Int] = []
      func recordSend() -> Bool {
        sendCalls += 1
        return sendCalls == 1
      }

      func recordSuccess(_ id: Int) {
        succeededIDs.append(id)
      }

      func snapshot() -> (Int, [Int]) {
        (sendCalls, succeededIDs)
      }
    }
    let tracker = Tracker()
    let drainSignal = AsyncStream.makeStream(of: Void.self)

    let queue = SendQueue<Marker>(
      send: { envelope in
        let throwThisTime = await tracker.recordSend()
        if throwThisTime { throw CancellationError() }
        await tracker.recordSuccess(envelope.id)
      },
      onError: { _, _ in },
      onDrain: { _ in
        drainSignal.continuation.yield(())
        drainSignal.continuation.finish()
      }
    )

    await queue.enqueue(Marker(id: 42))

    var iterator = drainSignal.stream.makeAsyncIterator()
    _ = await iterator.next()

    let (sendCalls, succeeded) = await tracker.snapshot()
    #expect(sendCalls == 2, "Envelope is attempted twice: once cancelled, once successful")
    #expect(succeeded == [42], "The auto-respawn retry delivers the envelope")

    let finalCount = await queue.count
    #expect(finalCount == 0)
  }

  @Test
  func `Non-cancellation error fires onError and the drain continues`() async {
    struct Boom: Error {}
    actor Collector {
      var sent: [Int] = []
      var errors: [Int] = []
      func recordSend(_ id: Int) {
        sent.append(id)
      }

      func recordError(_ id: Int) {
        errors.append(id)
      }

      func sentSnapshot() -> [Int] {
        sent
      }

      func errorSnapshot() -> [Int] {
        errors
      }
    }
    let collector = Collector()
    let drainSignal = AsyncStream.makeStream(of: Void.self)

    let queue = SendQueue<Marker>(
      send: { envelope in
        if envelope.id == 2 {
          throw Boom()
        }
        await collector.recordSend(envelope.id)
      },
      onError: { _, envelope in
        await collector.recordError(envelope.id)
      },
      onDrain: { _ in
        drainSignal.continuation.yield(())
        drainSignal.continuation.finish()
      }
    )

    for id in 1...3 {
      await queue.enqueue(Marker(id: id))
    }

    var iterator = drainSignal.stream.makeAsyncIterator()
    _ = await iterator.next()

    let sent = await collector.sentSnapshot()
    let errored = await collector.errorSnapshot()
    #expect(sent == [1, 3], "Drain must continue past the failing envelope")
    #expect(errored == [2])
  }

  @Test
  func `onDrain fires exactly once per drain pass`() async {
    // Block the first send call on a barrier so the test can enqueue
    // envelopes 2 and 3 while drain is parked. Without the barrier,
    // a synchronous send closure lets T1 finish draining envelope 1
    // (and fire onDrain) before the test thread submits enqueues 2
    // and 3 to the actor — exact ordering is up to the scheduler.
    actor State {
      var firstSendSeen = false
      var sent: [Int] = []
      func markFirstSend() -> Bool {
        let isFirst = !firstSendSeen
        firstSendSeen = true
        return isFirst
      }

      func recordSent(_ id: Int) {
        sent.append(id)
      }

      func snapshot() -> [Int] {
        sent
      }
    }
    actor DrainCounter {
      var count = 0
      func bump() {
        count += 1
      }

      func snapshot() -> Int {
        count
      }
    }
    let state = State()
    let counter = DrainCounter()
    let drainSignal = AsyncStream.makeStream(of: Void.self)
    let firstSendReached = AsyncStream.makeStream(of: Void.self)
    let releaseFirstSend = AsyncStream.makeStream(of: Void.self)

    let queue = SendQueue<Marker>(
      send: { envelope in
        if await state.markFirstSend() {
          firstSendReached.continuation.yield(())
          firstSendReached.continuation.finish()
          var releaseIter = releaseFirstSend.stream.makeAsyncIterator()
          _ = await releaseIter.next()
        }
        await state.recordSent(envelope.id)
      },
      onError: { _, _ in },
      onDrain: { _ in
        await counter.bump()
        drainSignal.continuation.yield(())
      }
    )

    await queue.enqueue(Marker(id: 1))

    // Park here until drain has popped envelope 1 and entered send.
    var firstIter = firstSendReached.stream.makeAsyncIterator()
    _ = await firstIter.next()

    await queue.enqueue(Marker(id: 2))
    await queue.enqueue(Marker(id: 3))

    releaseFirstSend.continuation.yield(())
    releaseFirstSend.continuation.finish()

    var iterator = drainSignal.stream.makeAsyncIterator()
    _ = await iterator.next()
    await queue.awaitDrainCompletion()
    drainSignal.continuation.finish()

    let sent = await state.snapshot()
    let observed = await counter.snapshot()
    #expect(sent == [1, 2, 3])
    #expect(observed == 1, "All three envelopes drain in one pass; onDrain fires once")
  }

  @Test
  func `onDrain receives the most recent non-cancellation error from the drain pass`() async {
    struct Boom: Error, Equatable {
      let id: Int
    }
    actor ErrorBox {
      var value: Error?
      func record(_ error: Error?) {
        value = error
      }

      func snapshot() -> Error? {
        value
      }
    }
    let box = ErrorBox()
    let drainSignal = AsyncStream.makeStream(of: Void.self)

    let queue = SendQueue<Marker>(
      send: { envelope in
        throw Boom(id: envelope.id)
      },
      onError: { _, _ in },
      onDrain: { lastError in
        await box.record(lastError)
        drainSignal.continuation.yield(())
        drainSignal.continuation.finish()
      }
    )

    await queue.enqueue(Marker(id: 1))
    await queue.enqueue(Marker(id: 2))
    await queue.enqueue(Marker(id: 3))

    var iterator = drainSignal.stream.makeAsyncIterator()
    _ = await iterator.next()

    let recorded = await box.snapshot()
    // Last-error-wins: the third envelope's Boom should reach onDrain.
    #expect((recorded as? Boom) == Boom(id: 3))
  }

  @Test
  func `onDrain receives nil when no envelope failed during the drain pass`() async {
    actor ErrorBox {
      var value: Error?
      var didFire = false
      func record(_ error: Error?) {
        value = error; didFire = true
      }

      func snapshot() -> (Error?, Bool) {
        (value, didFire)
      }
    }
    let box = ErrorBox()
    let drainSignal = AsyncStream.makeStream(of: Void.self)

    let queue = SendQueue<Marker>(
      send: { _ in },
      onError: { _, _ in },
      onDrain: { lastError in
        await box.record(lastError)
        drainSignal.continuation.yield(())
        drainSignal.continuation.finish()
      }
    )

    await queue.enqueue(Marker(id: 1))

    var iterator = drainSignal.stream.makeAsyncIterator()
    _ = await iterator.next()

    let (recorded, fired) = await box.snapshot()
    #expect(fired)
    #expect(recorded == nil)
  }

  @Test
  func `drain's outer loop processes envelopes enqueued during onDrain`() async {
    // The outer `while !pending.isEmpty` re-check after onDrain is what
    // catches an enqueue that lands during the onDrain await. Park
    // the first onDrain on a barrier, enqueue a follow-up envelope
    // from outside, release. The same drain task should pop the new
    // envelope and fire onDrain a second time.
    actor State {
      var sent: [Int] = []
      var onDrainCalls = 0
      func recordSend(_ id: Int) {
        sent.append(id)
      }

      func bump() -> Int {
        onDrainCalls += 1
        return onDrainCalls
      }

      func snapshot() -> (sent: [Int], onDrainCalls: Int) {
        (sent, onDrainCalls)
      }
    }
    let state = State()
    let firstOnDrainReached = AsyncStream.makeStream(of: Void.self)
    let releaseFirstOnDrain = AsyncStream.makeStream(of: Void.self)
    let secondOnDrainFired = AsyncStream.makeStream(of: Void.self)

    let queue = SendQueue<Marker>(
      send: { envelope in
        await state.recordSend(envelope.id)
      },
      onError: { _, _ in },
      onDrain: { _ in
        let callNumber = await state.bump()
        if callNumber == 1 {
          firstOnDrainReached.continuation.yield(())
          firstOnDrainReached.continuation.finish()
          var releaseIter = releaseFirstOnDrain.stream.makeAsyncIterator()
          _ = await releaseIter.next()
        } else {
          secondOnDrainFired.continuation.yield(())
          secondOnDrainFired.continuation.finish()
        }
      }
    )

    await queue.enqueue(Marker(id: 1))

    // Park here until the first onDrain has fired and is blocked.
    var firstIter = firstOnDrainReached.stream.makeAsyncIterator()
    _ = await firstIter.next()

    // Enqueue 2 while onDrain is parked. The outer loop must pick it up.
    await queue.enqueue(Marker(id: 2))

    releaseFirstOnDrain.continuation.yield(())
    releaseFirstOnDrain.continuation.finish()

    var secondIter = secondOnDrainFired.stream.makeAsyncIterator()
    _ = await secondIter.next()
    await queue.awaitDrainCompletion()

    let snapshot = await state.snapshot()
    #expect(snapshot.sent == [1, 2])
    #expect(snapshot.onDrainCalls == 2, "Outer loop produces a second onDrain after the re-enqueued envelope drains")
  }

  @Test
  func `Drain task captures actor strongly: drain completes after only external ref drops`() async {
    // Drop the local strong ref to the queue while the drain's send
    // closure is parked. Because the drain Task captures the actor
    // strongly, the actor stays alive until drain completes; the
    // send closure resumes after release and observably runs to
    // completion.
    actor State {
      var sendCompleted = false
      func markCompleted() {
        sendCompleted = true
      }

      func snapshot() -> Bool {
        sendCompleted
      }
    }
    let state = State()
    let sendReached = AsyncStream.makeStream(of: Void.self)
    let releaseSend = AsyncStream.makeStream(of: Void.self)

    do {
      let queue = SendQueue<Marker>(
        send: { _ in
          sendReached.continuation.yield(())
          sendReached.continuation.finish()
          var releaseIter = releaseSend.stream.makeAsyncIterator()
          _ = await releaseIter.next()
          await state.markCompleted()
        },
        onError: { _, _ in },
        onDrain: { _ in }
      )
      await queue.enqueue(Marker(id: 1))

      var iter = sendReached.stream.makeAsyncIterator()
      _ = await iter.next()
      // Local `queue` strong ref drops at the end of this `do` block.
    }

    releaseSend.continuation.yield(())
    releaseSend.continuation.finish()

    // Poll for send completion. The drain Task is the sole remaining
    // strong ref to the actor; if strong-self capture works, send
    // resumes and runs to completion.
    var completed = false
    for _ in 0..<200 {
      if await state.snapshot() {
        completed = true
        break
      }
      try? await Task.sleep(nanoseconds: 5_000_000)
    }

    #expect(completed, "Send must complete even after the only external strong reference was released")
  }
}
