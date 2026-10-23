import Foundation
@testable import MeshCore
import Testing

@Suite("v1.18 session methods")
struct V118SessionMethodsTests {
  @Test
  func `stopping during power-off connection check prevents submission`() async throws {
    let transport = ConnectedReadGateTransport()
    let session = MeshCoreSession(transport: transport)
    try await startSession(session, transport: transport.inner)
    await transport.arm()
    let powerOff = Task { await session.exchangePowerOff() }
    do {
      try await waitUntil("power-off should wait for the connection check") { await transport.isWaiting }
      await session.stop(disconnectTransport: false)
      await transport.release()
      #expect(await powerOff.value == .notSubmitted(.notRunning))
      #expect(await transport.inner.sentData.count == 1)
      #expect(await transport.isConnected)
      await transport.disconnect()
    } catch {
      await transport.release()
      _ = await powerOff.value
      await session.stop()
      throw error
    }
  }

  @Test
  func `runCliCommand returns UTF-8 reply`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)

    let task = Task {
      try await session.runCliCommand("get cad")
    }

    try await waitUntil("command should be sent") {
      await transport.sentData.count == 2
    }

    let sent = await transport.sentData[1]
    #expect(sent[0] == 0x42)
    #expect(Data(sent.dropFirst()) == Data("get cad".utf8))

    var reply = Data([0x1D])
    reply.append(contentsOf: "Unknown command".utf8)
    await transport.simulateReceive(reply)

    let text = try await task.value
    #expect(text == "Unknown command")
    await session.stop()
  }

  @Test
  func `runCliCommand propagates unsupported as deviceError`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)

    let task = Task {
      try await session.runCliCommand("get cad")
    }
    try await waitUntil("command should be sent") {
      await transport.sentData.count == 2
    }
    await transport.simulateError(code: 1)

    let err = await #expect(throws: MeshCoreError.self) {
      try await task.value
    }
    guard case let .deviceError(code)? = err else {
      Issue.record("Expected deviceError, got \(String(describing: err))")
      await session.stop()
      return
    }
    #expect(code == 1)
    await session.stop()
  }

  @Test
  func `runCliCommand rejects a one-byte command without sending`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)
    let sentAfterStart = await transport.sentData.count

    let err = await #expect(throws: MeshCoreError.self) {
      try await session.runCliCommand("x")
    }
    guard case .invalidInput? = err else {
      Issue.record("Expected invalidInput, got \(String(describing: err))")
      await session.stop()
      return
    }
    let sentCount = await transport.sentData.count
    #expect(sentCount == sentAfterStart)
    await session.stop()
  }

  @Test
  func `runCliCommand rejects empty command without sending`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)
    let sentAfterStart = await transport.sentData.count

    let err = await #expect(throws: MeshCoreError.self) {
      try await session.runCliCommand("   ")
    }
    guard case .invalidInput? = err else {
      Issue.record("Expected invalidInput, got \(String(describing: err))")
      await session.stop()
      return
    }
    let sentCount = await transport.sentData.count
    #expect(sentCount == sentAfterStart)
    await session.stop()
  }

  @Test
  func `runCliCommand rejects oversized command without sending`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)
    let sentAfterStart = await transport.sentData.count
    let oversized = String(repeating: "a", count: PacketBuilder.runCliCommandMaxUTF8Bytes + 1)

    let err = await #expect(throws: MeshCoreError.self) {
      try await session.runCliCommand(oversized)
    }
    guard case .invalidInput? = err else {
      Issue.record("Expected invalidInput, got \(String(describing: err))")
      await session.stop()
      return
    }
    let sentCount = await transport.sentData.count
    #expect(sentCount == sentAfterStart)
    await session.stop()
  }

  @Test
  func `runCliCommand sends a max-length command`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)
    let command = String(repeating: "a", count: PacketBuilder.runCliCommandMaxUTF8Bytes)

    let task = Task {
      try await session.runCliCommand(command)
    }
    try await waitUntil("command should be sent") {
      await transport.sentData.count == 2
    }
    let sent = await transport.sentData[1]
    #expect(sent.count == PacketBuilder.maxFrameSize)
    #expect(sent[0] == 0x42)
    #expect(Data(sent.dropFirst()) == Data(command.utf8))

    var reply = Data([0x1D])
    reply.append(contentsOf: "OK".utf8)
    await transport.simulateReceive(reply)
    _ = try await task.value
    await session.stop()
  }

  @Test
  func `runCliCommand sends the line verbatim`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)

    let commands = ["set wifi.pwd ", "set wifi.ssid Field Net "]
    for (offset, command) in commands.enumerated() {
      let task = Task {
        try await session.runCliCommand(command)
      }
      let sentIndex = offset + 1
      try await waitUntil("command should be sent") {
        await transport.sentData.count == sentIndex + 1
      }
      let sent = await transport.sentData[sentIndex]
      #expect(Data(sent.dropFirst()) == Data(command.utf8))
      if offset == 0 {
        #expect(sent.last == 0x20)
      }

      var reply = Data([0x1D])
      reply.append(contentsOf: "OK".utf8)
      await transport.simulateReceive(reply)
      _ = try await task.value
    }
    await session.stop()
  }

  @Test
  func `powerOff sends poweroff and returns false when the board replies`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)

    let task = Task { try await session.powerOff() }
    try await waitUntil("poweroff should be sent") {
      await transport.sentData.count == 2
    }
    let sent = await transport.sentData[1]
    #expect(sent.first == 0x42)
    #expect(Data(sent.dropFirst()) == Data(PacketBuilder.powerOffCommand.utf8))

    await transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
    let poweredOff = try await task.value
    #expect(poweredOff == false)
    await session.stop()
  }

  @Test
  func `powerOff returns true when the reply window expires`() async throws {
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)

    let poweredOff = try await session.powerOff()
    let sent = await transport.sentData[1]
    #expect(sent.first == 0x42)
    #expect(Data(sent.dropFirst()) == Data(PacketBuilder.powerOffCommand.utf8))
    #expect(poweredOff == true)
    await session.stop()
  }

  @Test
  func `powerOff exchange keeps an empty CLI reply as still on`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    await transport.holdNextSend()

    let task = Task { await session.exchangePowerOff() }
    try await waitUntil("poweroff write should park") { await transport.sendIsWaiting }
    await transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
    await transport.releaseHeldSend()

    #expect(await task.value == .submitted(.stillOn("")))
    await session.stop()
  }

  @Test
  func `powerOff exchange keeps a CLI reply that arrives before a failed write`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    await transport.holdNextSend()

    let task = Task { await session.exchangePowerOff() }
    try await waitUntil("poweroff write should park") { await transport.sendIsWaiting }
    var reply = Data([ResponseCode.cliReply.rawValue])
    reply.append(contentsOf: "busy".utf8)
    await transport.simulateReceive(reply)
    await transport.failHeldSend()
    await transport.releaseHeldSend()

    #expect(await task.value == .submitted(.stillOn("busy")))
    await session.stop()
  }

  @Test
  func `powerOff exchange keeps a firmware error that arrives before a failed write`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    await transport.holdNextSend()

    let task = Task { await session.exchangePowerOff() }
    try await waitUntil("poweroff write should park") { await transport.sendIsWaiting }
    await transport.simulateError(code: 1)
    await transport.failHeldSend()
    await transport.releaseHeldSend()

    #expect(await task.value == .submitted(.rejected(1)))
    await session.stop()
  }

  @Test
  func `powerOff exchange reports send failure when nothing was buffered`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    await transport.holdNextSend()

    let task = Task { await session.exchangePowerOff() }
    try await waitUntil("poweroff write should park") { await transport.sendIsWaiting }
    await transport.failHeldSend()
    await transport.releaseHeldSend()

    #expect(await task.value == .submitted(.sendFailed))
    await session.stop()
  }

  @Test
  func `powerOff exchange reports a stopped session instead of silence`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    await transport.holdNextSend()

    let task = Task { await session.exchangePowerOff() }
    try await waitUntil("poweroff write should park") { await transport.sendIsWaiting }
    await transport.releaseHeldSend()
    await session.stop()

    #expect(await task.value == .submitted(.stopped))
  }

  @Test
  func `powerOff exchange does not submit when the session is stopped or disconnected`() async throws {
    let idle = MeshCoreSession(transport: MockTransport())
    #expect(await idle.exchangePowerOff() == .notSubmitted(.notRunning))

    let transport = MockTransport()
    let session = try await startedSession(transport)
    await transport.disconnect()
    let result = await session.exchangePowerOff()
    guard case .notSubmitted = result else {
      Issue.record("expected no submission, got \(result)")
      return
    }
  }

  @Test
  func `powerOff exchange sends nothing when cancelled while queued`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    await transport.holdNextSend()

    let blocker = Task { try await session.runCliCommand("board") }
    try await waitUntil("blocker write should park") { await transport.sendIsWaiting }

    let gate = PowerOffSubmissionGate()
    let powerOff = Task { await session.exchangePowerOff(gate: gate) }
    try await Task.sleep(for: .milliseconds(50))
    #expect(gate.cancelIfUnsubmitted())

    await transport.releaseHeldSend()
    await transport.simulateReceive(Data([ResponseCode.cliReply.rawValue, 0x4F, 0x4B]))
    _ = try await blocker.value

    #expect(await powerOff.value == .notSubmitted(.cancelled))
    let sent = await transport.sentData
    #expect(sent.count == 2)
    #expect(sent.contains { Data($0.dropFirst()) == Data(PacketBuilder.powerOffCommand.utf8) } == false)
    await session.stop()
  }

  @Test
  func `powerOff exchange still finishes when cancel loses the submission race`() async throws {
    let transport = MockTransport()
    let session = try await startedSession(transport)
    await transport.holdNextSend()
    let gate = PowerOffSubmissionGate()

    let task = Task { await session.exchangePowerOff(gate: gate) }
    try await waitUntil("poweroff write should park") { await transport.sendIsWaiting }
    #expect(gate.cancelIfUnsubmitted() == false)
    await transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
    await transport.releaseHeldSend()

    #expect(await task.value == .submitted(.stillOn("")))
    await session.stop()
  }

  private func startedSession(_ transport: MockTransport) async throws -> MeshCoreSession {
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    try await startSession(session, transport: transport)
    return session
  }

  private actor ConnectedReadGateTransport: MeshTransport {
    let inner = MockTransport()
    private var armed = false
    private var continuation: CheckedContinuation<Void, Never>?
    private(set) var isWaiting = false

    var receivedData: AsyncStream<Data> {
      get async { await inner.receivedData }
    }

    var isConnected: Bool {
      get async {
        if armed {
          armed = false
          isWaiting = true
          await withCheckedContinuation { continuation = $0 }
          isWaiting = false
        }
        return await inner.isConnected
      }
    }

    func connect() async throws {
      try await inner.connect()
    }

    func disconnect() async {
      await inner.disconnect()
    }

    func send(_ data: Data) async throws {
      try await inner.send(data)
    }

    func arm() {
      armed = true
    }

    func release() {
      let pending = continuation
      continuation = nil
      pending?.resume()
    }
  }
}

private func startSession(
  _ session: MeshCoreSession,
  transport: MockTransport
) async throws {
  let startTask = Task { try await session.start() }
  try await waitUntil("transport should send appStart before session starts") {
    await transport.sentData.count == 1
  }
  await transport.simulateReceive(makeSelfInfoPacket())
  try await startTask.value
}

private func makeSelfInfoPacket() -> Data {
  var payload = Data([ResponseCode.selfInfo.rawValue])
  payload.append(1) // adv type
  payload.append(UInt8(bitPattern: 22)) // tx power
  payload.append(UInt8(bitPattern: 22)) // max tx power
  payload.append(Data(repeating: 0x01, count: 32)) // pubkey
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lat
  payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) }) // lon
  payload.append(0) // multi acks
  payload.append(0) // adv loc policy
  payload.append(0) // telemetry mode
  payload.append(0) // manual add
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) }) // freq
  payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) }) // bw
  payload.append(11) // sf
  payload.append(5) // cr
  return payload
}
