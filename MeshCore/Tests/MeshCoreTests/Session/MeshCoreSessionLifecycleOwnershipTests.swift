import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("MeshCoreSession lifecycle ownership")
struct MeshCoreSessionLifecycleOwnershipTests {
  @Test
  func `stopping during transport connect prevents a late startup handshake`() async throws {
    let transport = HeldConnectTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 0.05, clientIdentifier: "MCTst")
    )
    let starting = Task {
      try await session.start(disconnectTransportOnFailure: false)
    }
    try await waitUntil { await transport.connectIsWaiting }

    await session.stop(disconnectTransport: false)
    await transport.releaseConnect()
    await #expect(throws: CancellationError.self) {
      try await starting.value
    }

    #expect(await transport.sentData.isEmpty)
    #expect(await session.sessionIsRunning == false)
    #expect(await transport.disconnectCount == 0)
    await transport.disconnect()
  }

  @Test
  func `a later default stop still closes an independently owned transport`() async throws {
    let transport = MockTransport()
    try await transport.connect()
    let session = MeshCoreSession(transport: transport)
    await session.stop(disconnectTransport: false)
    #expect(await transport.isConnected)
    await session.stop()
    #expect(await transport.isConnected == false)
  }

  @Test(arguments: [false, true])
  func `owned retirement drains a cancelled caller exchange and held physical completion`(replyBeforeCompletion: Bool) async throws {
    let physical = MockTransport()
    let binding = SessionTransportBinding()
    let session = MeshCoreSession(
      transport: SessionBoundTransport(transport: physical, binding: binding),
      configuration: SessionConfiguration(defaultTimeout: 1, clientIdentifier: "MCTst")
    )
    try await start(session, physical: physical, expectedSends: 1)
    await physical.holdNextSend()
    let caller = Task { try await session.factoryReset() }
    try await waitUntil { await physical.sendIsWaiting }
    caller.cancel()
    await #expect(throws: CancellationError.self) { try await caller.value }

    binding.revoke()
    let stopped = CallTracker()
    let retirement = Task {
      await session.stop(disconnectTransport: false, drainExchanges: true)
      await binding.waitForQuiescence()
      await session.waitForQuiescence()
      stopped.markCalled()
    }
    try await waitUntil { await !session.sessionIsRunning }
    #expect(!stopped.wasCalled)
    if replyBeforeCompletion { await physical.simulateOK() }
    await physical.releaseHeldSend()
    await binding.waitForQuiescence()
    if !replyBeforeCompletion {
      #expect(!stopped.wasCalled)
      await physical.simulateOK()
    }
    await retirement.value
    #expect(stopped.wasCalled)
    #expect(await physical.isConnected)

    await physical.disconnect()
    let replacement = MeshCoreSession(transport: physical)
    try await start(replacement, physical: physical, expectedSends: 3)
    let probe = Task { try await replacement.factoryReset() }
    try await waitUntil { await physical.sentData.count == 4 }
    await physical.simulateOK()
    try await probe.value
    await replacement.stop()
    await replacement.waitForQuiescence()
  }

  private func start(_ session: MeshCoreSession, physical: MockTransport, expectedSends: Int) async throws {
    let starting = Task { try await session.start(disconnectTransportOnFailure: false) }
    try await waitUntil { await physical.sentData.count == expectedSends }
    await physical.simulateReceive(selfInfoPacket())
    try await starting.value
  }

  private func selfInfoPacket() -> Data {
    var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
    packet.append(Data(repeating: 1, count: 32))
    packet.append(Data(repeating: 0, count: 12))
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    packet.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    packet.append(contentsOf: [7, 5])
    packet.append(contentsOf: "Test".utf8)
    return packet
  }
}

private actor HeldConnectTransport: MeshTransport {
  private let base = MockTransport()
  private var connectContinuation: CheckedContinuation<Void, Never>?
  private(set) var disconnectCount = 0
  var connectIsWaiting: Bool {
    connectContinuation != nil
  }

  var sentData: [Data] {
    get async { await base.sentData }
  }

  var receivedData: AsyncStream<Data> {
    get async { await base.receivedData }
  }

  var isConnected: Bool {
    get async { await base.isConnected }
  }

  func connect() async throws {
    await withCheckedContinuation { connectContinuation = $0 }
    try await base.connect()
  }

  func releaseConnect() {
    connectContinuation?.resume()
    connectContinuation = nil
  }

  func disconnect() async {
    disconnectCount += 1
    await base.disconnect()
  }

  func send(_ data: Data) async throws {
    try await base.send(data)
  }
}
