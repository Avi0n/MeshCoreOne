import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Session transport binding")
struct SessionTransportBindingTests {
  @Test
  func `revocation rejects every queued physical submission and receive access`() async throws {
    let physical = GatedPhysicalTransport()
    let binding = SessionTransportBinding()
    let transport = SessionBoundTransport(transport: physical, binding: binding)
    let writing = Task { try await transport.send(Data([1])) }
    while await !physical.sendIsWaiting {
      await Task.yield()
    }

    binding.revoke()
    let settled = CallTracker()
    let joining = Task {
      await binding.waitForQuiescence()
      settled.markCalled()
    }
    try await waitUntil("binding join should register or return") {
      binding.quiescenceWaiterCountForTesting > 0 || settled.wasCalled
    }
    #expect(!settled.wasCalled)
    await physical.releaseSend()
    await #expect(throws: CancellationError.self) { try await writing.value }
    await joining.value

    await #expect(throws: CancellationError.self) { try await transport.connect() }
    await #expect(throws: CancellationError.self) { try await transport.sendWithoutResponse(Data([2])) }
    var iterator = await transport.receivedData.makeAsyncIterator()
    #expect(await iterator.next() == nil)
    #expect(await physical.submissions.isEmpty)

    let replacement = SessionBoundTransport(transport: physical, binding: SessionTransportBinding())
    try await replacement.sendWithoutResponse(Data([3]))
    #expect(await physical.submissions == [Data([3])])
  }

  @Test
  func `serializer quiescence waits for exchange after its caller is cancelled`() async throws {
    let serializer = RequestResponseSerializer()
    let gate = ExchangeGate()
    let caller = Task {
      try await serializer.withSerialization { await gate.wait() }
    }
    while await !gate.isWaiting {
      await Task.yield()
    }
    caller.cancel()
    await #expect(throws: CancellationError.self) { try await caller.value }

    let settled = CallTracker()
    let joining = Task {
      await serializer.waitForQuiescence()
      settled.markCalled()
    }
    try await waitUntil("serializer join should register or return") {
      await serializer.quiescenceWaiterCountForTesting > 0 || settled.wasCalled
    }
    #expect(!settled.wasCalled)
    await gate.release()
    await joining.value
    #expect(settled.wasCalled)
    let probe = try await serializer.withSerialization { true }
    #expect(probe)
  }
}

private actor GatedPhysicalTransport: MeshTransport {
  private var waiter: CheckedContinuation<Void, Never>?
  private(set) var submissions: [Data] = []
  var sendIsWaiting: Bool {
    waiter != nil
  }

  var isConnected: Bool {
    true
  }

  var receivedData: AsyncStream<Data> {
    AsyncStream { $0.finish() }
  }

  func connect() {}
  func disconnect() {}
  func send(_ data: Data) {
    submissions.append(data)
  }

  func send(_ data: Data, sessionBinding: SessionTransportBinding) async throws {
    await withCheckedContinuation { waiter = $0 }
    try sessionBinding.performIfActive { submissions.append(data) }
  }

  func releaseSend() {
    waiter?.resume()
    waiter = nil
  }
}

private actor ExchangeGate {
  private var waiter: CheckedContinuation<Void, Never>?
  var isWaiting: Bool {
    waiter != nil
  }

  func wait() async {
    await withCheckedContinuation { waiter = $0 }
  }

  func release() {
    waiter?.resume()
    waiter = nil
  }
}
