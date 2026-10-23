import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import os
import Testing

@Suite("MeshCoreSession connection state", .timeLimit(.minutes(1)))
struct ConnectionStateTests {
  @Test
  func `initial start emits connecting then connected`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "Test")
    )

    var iterator = await session.connectionState.makeAsyncIterator()
    #expect(await iterator.next() == .disconnected)

    let startTask = Task {
      try await session.start()
    }

    try await waitUntil("transport should have sent appStart") {
      await transport.sentData.count >= 1
    }

    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value

    #expect(await iterator.next() == .connecting)
    #expect(await iterator.next() == .connected)

    await session.stop()
  }

  @Test
  func `reconnect start emits reconnecting then connected`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "Test")
    )

    var iterator = await session.connectionState.makeAsyncIterator()
    #expect(await iterator.next() == .disconnected)

    let startTask = Task {
      try await session.start(reconnectingAttempt: 1)
    }

    try await waitUntil("transport should have sent appStart") {
      await transport.sentData.count >= 1
    }

    await transport.simulateReceive(makeSelfInfoPacket())
    try await startTask.value

    #expect(await iterator.next() == .reconnecting(attempt: 1))
    #expect(await iterator.next() == .connected)

    await session.stop()
  }

  @Test
  func `failed appStart unwinds start so it can be retried`() async throws {
    let transport = MockTransport()
    let clock = TestClock()
    let handshakeTimeout: TimeInterval = 5
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: handshakeTimeout, clientIdentifier: "Test"),
      clock: clock
    )

    let observedStates = OSAllocatedUnfairLock<[ConnectionState]>(initialState: [])
    let observerTask = Task {
      for await state in await session.connectionState {
        observedStates.withLock { $0.append(state) }
      }
    }
    do {
      try await waitUntil("subscriber should see the initial state") {
        observedStates.withLock { $0.first == .disconnected }
      }

      // No selfInfo response arrives; only advancing virtual time expires appStart.
      let firstStart = Task { try await session.start() }
      try await waitUntil("appStart timeout should park on the injected clock") {
        clock.sleeperCount == 1
      }
      clock.advance(by: .seconds(handshakeTimeout))
      await #expect {
        try await firstStart.value
      } throws: { error in
        guard let error = error as? MeshCoreError, case .timeout = error else { return false }
        return true
      }

      try await waitUntil("a failed appStart should publish .failed") {
        observedStates.withLock { states in
          if case .failed = states.last { return true }
          return false
        }
      }
      let states = observedStates.withLock { $0 }
      #expect(states.contains(.connecting))
      #expect(states.contains(.connected))
      #expect(await session.currentSelfInfo == nil)

      let retry = Task { try await session.start() }
      try await waitUntil("retry should send a fresh appStart") {
        await transport.sentData.count == 2
      }
      await transport.simulateReceive(makeSelfInfoPacket())
      try await retry.value
      #expect(await session.currentSelfInfo?.name == "TestNode")
      await session.stop()
    } catch {
      await session.stop()
      observerTask.cancel()
      await observerTask.value
      throw error
    }

    observerTask.cancel()
    await observerTask.value
  }

  @Test
  func `getContact rejects short public key before sending`() async {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "Test")
    )

    await #expect(throws: MeshCoreError.self) {
      _ = try await session.getContact(publicKey: Data(repeating: 0xAA, count: 31))
    }

    #expect(await transport.sentData.isEmpty)
  }

  @Test
  func `requestStatus rejects short public key before sending`() async {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "Test")
    )

    await #expect(throws: MeshCoreError.self) {
      _ = try await session.requestStatus(from: Data(repeating: 0xBB, count: 31))
    }

    #expect(await transport.sentData.isEmpty)
  }

  @Test
  func `setPathHashMode rejects reserved mode before sending`() async {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "Test")
    )

    await #expect(throws: MeshCoreError.self) {
      try await session.setPathHashMode(3)
    }

    #expect(await transport.sentData.isEmpty)
  }

  private func makeSelfInfoPacket(name: String = "TestNode") -> Data {
    var payload = Data()
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
    payload.append(7)
    payload.append(5)
    payload.append(contentsOf: name.utf8)

    var packet = Data([ResponseCode.selfInfo.rawValue])
    packet.append(payload)
    return packet
  }
}
