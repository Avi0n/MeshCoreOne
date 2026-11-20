import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Channel flood scope lifetime")
struct ChannelFloodScopeLifetimeTests {
  private static let timestamp = Date(timeIntervalSince1970: 1_700_000_000)
  private static let channel: UInt8 = 1
  private static let namedScope = FloodScope.region("Alpha")
  private static let contactKey = Data(repeating: 0x11, count: 32)
  private static let commandTimeout: TimeInterval = 5
  private static let restoreCases: [(scope: ResolvedFloodScope, command: QueuedCommand)] = [
    (.scope(namedScope), .login),
    (.scope(namedScope), .directMessage),
    (.scope(namedScope), .telemetry),
    (.unscoped, .login)
  ]

  @Test(arguments: restoreCases)
  func `scoped send restores the baseline before a queued contact command`(
    testCase: (scope: ResolvedFloodScope, command: QueuedCommand)
  ) async throws {
    let (scope, command) = testCase
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    let setupFrame = switch scope {
    case .unscoped: PacketBuilder.setFloodScopeUnscoped()
    case let .scope(floodScope): Self.scopeKey(floodScope)
    }
    let send = harness.send(scope: scope)
    try await harness.ack(setupFrame)
    try await harness.ack(Self.channelMessage)
    let queued = command.start(harness.session)
    try await harness.waitForWaiters(1)
    #expect(await harness.transport.sentData.allSatisfy { $0.first != command.code })
    try await harness.ack(Self.baselineReset)
    try await send.value
    try await harness.expectFrame(command.code)
    await harness.transport.simulateReceive(Self.messageSent)
    _ = try await queued.value
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)
  }

  @Test
  func `a zero-key setup is the baseline and has no trailing reset`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    let send = harness.send(scope: .scope(.disabled))
    try await harness.ack(Self.baselineReset)
    let queued = QueuedCommand.login.start(harness.session)
    try await harness.waitForWaiters(1)
    #expect(await harness.transport.sentData.allSatisfy { $0.first != CommandCode.sendLogin.rawValue })
    let scopeFramesBeforeSend = await harness.transport.sentData.filter { $0.first == CommandCode.setFloodScope.rawValue }
    #expect(scopeFramesBeforeSend == [Self.baselineReset])
    try await harness.ack(Self.channelMessage)
    try await send.value
    try await harness.expectFrame(CommandCode.sendLogin.rawValue)
    let scopeFrames = await harness.transport.sentData.filter { $0.first == CommandCode.setFloodScope.rawValue }
    #expect(scopeFrames == [Self.baselineReset])
    await harness.transport.simulateReceive(Self.messageSent)
    _ = try await queued.value
  }

  @Test
  func `a raw channel send does not change flood scope`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    let send = Task {
      try await harness.session.sendChannelMessage(channel: Self.channel, text: "hello", timestamp: Self.timestamp)
    }
    try await harness.ack(Self.channelMessage)
    try await send.value
    #expect(await harness.transport.sentData.allSatisfy { $0.first != CommandCode.setFloodScope.rawValue })
  }

  @Test
  func `each baseline send writes the scope command before the channel message`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    let first = harness.send(scope: .scope(.disabled))
    try await harness.ack(Self.baselineReset)
    try await harness.ack(Self.channelMessage)
    try await first.value

    let second = harness.send(scope: .scope(.disabled))
    try await harness.ack(Self.baselineReset)
    try await harness.ack(Self.channelMessage)
    try await second.value

    let scopeFrames = await harness.transport.sentData.filter { $0.first == CommandCode.setFloodScope.rawValue }
    #expect(scopeFrames == [Self.baselineReset, Self.baselineReset])
    #expect(await Self.channelSendCount(harness.transport.sentData) == 2)
  }

  @Test
  func `cancellation during reset keeps the accepted send when the reset is confirmed`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    let send = harness.send(scope: .scope(Self.namedScope))
    try await harness.ack(Self.scopeKey(Self.namedScope))
    try await harness.ack(Self.channelMessage)
    try await harness.waitForFrames(4)
    #expect(await harness.transport.sentData[3] == Self.baselineReset)
    send.cancel()
    let queued = QueuedCommand.telemetry.start(harness.session)
    try await harness.waitForWaiters(1)
    #expect(await harness.transport.sentData.count == 4)
    try await harness.ack(Self.baselineReset)
    try await send.value
    try await harness.expectFrame(CommandCode.getSelfTelemetry.rawValue)
    await harness.transport.simulateReceive(Self.messageSent)
    _ = try await queued.value
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)
  }

  @Test
  func `a rejected channel send restores an active override`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    let send = harness.send(scope: .scope(Self.namedScope))
    try await harness.ack(Self.scopeKey(Self.namedScope))
    try await harness.waitForFrames(3)
    let rejected: UInt8 = 9
    await harness.transport.simulateError(code: rejected)
    try await harness.waitForFrames(4)
    #expect(await harness.transport.sentData[3] == Self.baselineReset)
    try await harness.ack(Self.baselineReset)
    let error = await #expect(throws: MeshCoreError.self) { try await send.value }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected the channel rejection, got \(String(describing: error))")
      return
    }
    #expect(code == rejected)
    let queued = QueuedCommand.login.start(harness.session)
    try await harness.expectFrame(CommandCode.sendLogin.rawValue)
    await harness.transport.simulateReceive(Self.messageSent)
    _ = try await queued.value
  }

  @Test
  func `reset rejection preserves the accepted send and the next command runs`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    let send = harness.send(scope: .unscoped)
    try await harness.ack(PacketBuilder.setFloodScopeUnscoped())
    try await harness.ack(Self.channelMessage)
    try await harness.waitForFrames(4)
    await harness.transport.simulateError(code: 3)
    try await send.value
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)
    let queued = QueuedCommand.directMessage.start(harness.session)
    try await harness.finish(queued, code: CommandCode.sendMessage.rawValue)
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)
  }

  @Test
  func `reset timeout preserves the accepted send and a late OK before the next submission does not prove it`() async throws {
    let harness = try await Harness.started(clock: true)
    defer { Task { await harness.session.stop() } }
    let send = harness.send(scope: .scope(Self.namedScope))
    try await harness.ack(Self.scopeKey(Self.namedScope))
    try await harness.ack(Self.channelMessage)
    try await harness.waitForFrames(4)
    try await harness.advanceCommandTimeout()
    try await harness.waitUntilSubscribers(0)
    await harness.transport.simulateOK()
    try await send.value
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)

    await harness.transport.holdNextSendBeforeSubmission()
    let rename = Task { try await harness.session.setName("Relay") }
    try await waitUntil("name write should park before submission") {
      await harness.transport.submissionIsWaiting
    }
    try await harness.waitUntilSubscribers(1)
    await harness.session.dispatchForTesting(.ok(value: nil))
    await harness.transport.releaseHeldSubmission()
    try await harness.advanceCommandTimeout()
    await #expect {
      try await rename.value
    } throws: { error in
      guard let error = error as? MeshCoreError, case .timeout = error else { return false }
      return true
    }
    #expect(await harness.transport.sentData.contains { $0 == PacketBuilder.setName("Relay") })
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)
    let scopeFrames = await harness.transport.sentData.filter { $0.first == CommandCode.setFloodScope.rawValue }
    #expect(scopeFrames.count == 2)
  }

  @Test
  func `channel command timeout skips the clear and the next command runs`() async throws {
    let harness = try await Harness.started(clock: true)
    defer { Task { await harness.session.stop() } }
    let send = harness.send(scope: .scope(Self.namedScope))
    try await harness.ack(Self.scopeKey(Self.namedScope))
    try await harness.waitForFrames(3)
    let queued = QueuedCommand.telemetry.start(harness.session)
    try await harness.waitForWaiters(1)
    try await harness.advanceCommandTimeout()
    await #expect {
      try await send.value
    } throws: { error in
      guard let error = error as? MeshCoreError, case .timeout = error else { return false }
      return true
    }
    await harness.transport.simulateOK()
    #expect(await harness.transport.sentData.allSatisfy { $0 != Self.baselineReset })
    try await harness.finish(queued, code: CommandCode.getSelfTelemetry.rawValue)
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)
  }

  @Test
  func `preexisting response uncertainty does not block the next channel send`() async throws {
    let harness = try await Harness.started(clock: true)
    defer { Task { await harness.session.stop() } }
    let stuck = Task { try await harness.session.setFloodScope(.region("Gamma")) }
    try await harness.waitForFrames(2)
    try await harness.advanceCommandTimeout()
    await #expect {
      try await stuck.value
    } throws: { error in
      guard let error = error as? MeshCoreError, case .timeout = error else { return false }
      return true
    }

    let send = harness.send(scope: .scope(Self.namedScope))
    try await harness.ack(Self.scopeKey(Self.namedScope))
    try await harness.ack(Self.channelMessage)
    try await harness.ack(Self.baselineReset)
    try await send.value
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)
  }

  @Test
  func `an OK buffered before write completion confirms the send and still restores`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    let send = harness.send(scope: .scope(Self.namedScope))
    try await harness.waitForFrames(2)
    await harness.transport.holdNextSend()
    try await harness.ack(Self.scopeKey(Self.namedScope))
    try await waitUntil("channel write should park") { await harness.transport.sendIsWaiting }
    try await harness.waitUntilSubscribers(1)
    await harness.session.dispatchForTesting(.ok(value: nil))
    await harness.transport.releaseHeldSend()
    try await harness.ack(Self.baselineReset)
    try await send.value
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)
  }

  @Test
  func `a failed write keeps a buffered channel OK and does not start a reset`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    let send = harness.send(scope: .scope(Self.namedScope))
    try await harness.waitForFrames(2)
    await harness.transport.holdNextSend()
    await harness.transport.failHeldSend()
    try await harness.ack(Self.scopeKey(Self.namedScope))
    try await waitUntil("channel write should park") { await harness.transport.sendIsWaiting }
    try await harness.waitUntilSubscribers(1)
    await harness.session.dispatchForTesting(.ok(value: nil))
    await harness.transport.releaseHeldSend()
    try await send.value
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)
    #expect(await harness.transport.sentData.allSatisfy { $0 != Self.baselineReset })
    let queued = QueuedCommand.telemetry.start(harness.session)
    try await harness.finish(queued, code: CommandCode.getSelfTelemetry.rawValue)
  }

  @Test(arguments: [true, false])
  func `a buffered rejection takes precedence over a failed scope or channel write`(duringSetup: Bool) async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    if duringSetup {
      await harness.transport.holdNextSend()
    }
    let send = harness.send(scope: .scope(Self.namedScope))
    if !duringSetup {
      try await harness.waitForFrames(2)
      await harness.transport.holdNextSend()
      try await harness.ack(Self.scopeKey(Self.namedScope))
    }
    try await waitUntil("scope or channel write should park") { await harness.transport.sendIsWaiting }
    await harness.transport.failHeldSend()
    let queued = QueuedCommand.login.start(harness.session)
    try await harness.waitForWaiters(1)
    try await harness.waitUntilSubscribers(1)
    let rejectedCode = ErrorCode.badState.rawValue
    await harness.session.dispatchForTesting(.error(code: rejectedCode))
    await harness.transport.releaseHeldSend()

    let error = await #expect(throws: MeshCoreError.self) { try await send.value }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected the radio rejection, got \(String(describing: error))")
      return
    }
    #expect(code == rejectedCode)
    try await harness.finish(queued, code: CommandCode.sendLogin.rawValue)
    let frames = await harness.transport.sentData
    #expect(Self.channelSendCount(frames) == (duringSetup ? 0 : 1))
    #expect(frames.allSatisfy { $0 != Self.baselineReset })
  }

  @Test
  func `a buffered setup OK with a failed write does not send the channel message`() async throws {
    let harness = try await Harness.started()
    defer { Task { await harness.session.stop() } }
    await harness.transport.holdNextSend()
    let send = harness.send(scope: .scope(Self.namedScope))
    try await waitUntil("scope write should park") { await harness.transport.sendIsWaiting }
    await harness.transport.failHeldSend()
    let queued = QueuedCommand.login.start(harness.session)
    try await harness.waitForWaiters(1)
    try await harness.waitUntilSubscribers(1)
    await harness.session.dispatchForTesting(.ok(value: nil))
    await harness.transport.releaseHeldSend()

    let error = await #expect(throws: MeshTransportError.self) { try await send.value }
    guard case .sendFailed? = error else {
      Issue.record("Expected the write failure, got \(String(describing: error))")
      return
    }
    try await harness.finish(queued, code: CommandCode.sendLogin.rawValue)
    let frames = await harness.transport.sentData
    #expect(Self.channelSendCount(frames) == 0)
    #expect(frames.allSatisfy { $0 != Self.baselineReset })
  }

  @Test
  func `a reply delivered before submission does not prove the write`() async throws {
    let harness = try await Harness.started(clock: true)
    defer { Task { await harness.session.stop() } }
    await harness.transport.holdNextSendBeforeSubmission()
    let send = harness.send(scope: .scope(Self.namedScope))
    try await waitUntil("scope write should park before submission") {
      await harness.transport.submissionIsWaiting
    }
    try await harness.waitUntilSubscribers(1)
    await harness.session.dispatchForTesting(.ok(value: nil))
    await harness.transport.releaseHeldSubmission()
    try await harness.advanceCommandTimeout()
    await #expect {
      try await send.value
    } throws: { error in
      guard let error = error as? MeshCoreError, case .timeout = error else { return false }
      return true
    }
    let frames = await harness.transport.sentData
    #expect(Self.channelSendCount(frames) == 0)
    #expect(frames.allSatisfy { $0 != Self.baselineReset })
    let queued = QueuedCommand.login.start(harness.session)
    try await harness.finish(queued, code: CommandCode.sendLogin.rawValue)
  }

  @Test
  func `a late OK after the next submission proves that command and leaves the channel send Sent`() async throws {
    let harness = try await Harness.started(clock: true)
    defer { Task { await harness.session.stop() } }
    let send = harness.send(scope: .scope(Self.namedScope))
    try await harness.ack(Self.scopeKey(Self.namedScope))
    try await harness.ack(Self.channelMessage)
    try await harness.waitForFrames(4)
    #expect(await harness.transport.sentData[3] == Self.baselineReset)
    try await harness.advanceCommandTimeout()
    try await send.value
    #expect(await Self.channelSendCount(harness.transport.sentData) == 1)

    let rename = Task { try await harness.session.setName("Relay") }
    try await harness.waitForFrames(5)
    #expect(await harness.transport.sentData[4] == PacketBuilder.setName("Relay"))
    await harness.transport.simulateOK()
    try await rename.value
    let frames = await harness.transport.sentData
    #expect(Self.channelSendCount(frames) == 1)
    #expect(frames.filter { $0.first == CommandCode.setFloodScope.rawValue }.count == 2)
  }

  @Test
  func `a late error after direct-message submission fails that send and a bare OK does not`() async throws {
    let harness = try await Harness.started(clock: true)
    defer { Task { await harness.session.stop() } }
    await harness.transport.holdNextSendBeforeSubmission()
    let send = Task {
      try await harness.session.sendMessage(to: Self.contactKey, text: "dm", timestamp: Self.timestamp)
    }
    try await waitUntil("direct message should park before submission") {
      await harness.transport.submissionIsWaiting
    }
    try await harness.waitUntilSubscribers(1)
    await harness.session.dispatchForTesting(.error(code: 4))
    await harness.transport.releaseHeldSubmission()
    try await waitUntil("the direct message should wait for its own reply") {
      harness.clock?.sleeperCount == 1
    }
    await harness.session.dispatchForTesting(.ok(value: nil))
    #expect(harness.clock?.sleeperCount == 1)
    await harness.session.dispatchForTesting(.error(code: 9))
    let error = await #expect(throws: MeshCoreError.self) { try await send.value }
    guard case let .deviceError(code)? = error else {
      Issue.record("Expected the late error, got \(String(describing: error))")
      return
    }
    #expect(code == 9)
  }

  private static func scopeKey(_ scope: FloodScope) -> Data {
    PacketBuilder.setFloodScope(scope.scopeKey())
  }

  private static var baselineReset: Data {
    PacketBuilder.setFloodScope(FloodScope.disabled.scopeKey())
  }

  private static var channelMessage: Data {
    PacketBuilder.sendChannelMessage(channel: channel, text: "hello", timestamp: timestamp)
  }

  private static var messageSent: Data {
    Data([ResponseCode.messageSent.rawValue, 1, 2, 3, 4, 5, 50, 0, 0, 0])
  }

  private static func channelSendCount(_ frames: [Data]) -> Int {
    frames.filter { $0.first == CommandCode.sendChannelMessage.rawValue }.count
  }

  enum QueuedCommand {
    case login, directMessage, telemetry

    var code: UInt8 {
      switch self {
      case .login: CommandCode.sendLogin.rawValue
      case .directMessage: CommandCode.sendMessage.rawValue
      case .telemetry: CommandCode.getSelfTelemetry.rawValue
      }
    }

    func start(_ session: MeshCoreSession) -> Task<Void, Error> {
      let key = ChannelFloodScopeLifetimeTests.contactKey
      let timestamp = ChannelFloodScopeLifetimeTests.timestamp
      return Task {
        switch self {
        case .login:
          _ = try await session.sendLogin(to: key, password: "room")
        case .directMessage:
          _ = try await session.sendMessage(to: key, text: "dm", timestamp: timestamp)
        case .telemetry:
          _ = try await session.sendTelemetryRequest(to: key)
        }
      }
    }
  }

  private struct Harness {
    let session: MeshCoreSession
    let transport: MockTransport
    let clock: TestClock?

    static func started(clock useClock: Bool = false) async throws -> Harness {
      let transport = MockTransport()
      let clock = useClock ? TestClock() : nil
      let session = MeshCoreSession(
        transport: transport,
        configuration: SessionConfiguration(defaultTimeout: commandTimeout, clientIdentifier: "MCTst"),
        clock: clock
      )
      let harness = Harness(session: session, transport: transport, clock: clock)
      let start = Task { try await session.start() }
      try await harness.waitForFrames(1)
      await transport.simulateReceive(selfInfo)
      try await start.value
      return harness
    }

    func send(scope: ResolvedFloodScope) -> Task<Void, Error> {
      Task {
        try await session.sendChannelMessage(
          channel: ChannelFloodScopeLifetimeTests.channel, text: "hello",
          timestamp: ChannelFloodScopeLifetimeTests.timestamp, floodScope: scope
        )
      }
    }

    func ack(_ expected: Data) async throws {
      try await waitUntil("Expected \(expected.count)-byte frame") {
        await transport.sentData.last == expected
      }
      try await waitUntilSubscribers(1)
      await transport.simulateOK()
    }

    func expectFrame(_ code: UInt8) async throws {
      try await waitUntil("Expected command \(code)") {
        await transport.sentData.contains { $0.first == code }
      }
    }

    func waitForFrames(_ count: Int) async throws {
      try await waitUntil("Expected \(count) frames") { await transport.sentData.count >= count }
    }

    func waitForWaiters(_ count: Int) async throws {
      try await waitUntil("Expected \(count) waiting commands") {
        await session.requestResponseSerializer.waiterCountForTest == count
      }
    }

    func waitUntilSubscribers(_ count: Int) async throws {
      try await waitUntil("Expected \(count) event subscribers") {
        await session.subscriberCountForTest == count
      }
    }

    func advanceCommandTimeout() async throws {
      let clock = try #require(clock)
      try await waitUntil("command timeout should park on the test clock") { clock.sleeperCount == 1 }
      clock.advance(by: .seconds(ChannelFloodScopeLifetimeTests.commandTimeout))
    }

    func finish(_ queued: Task<Void, Error>, code: UInt8) async throws {
      try await expectFrame(code)
      await transport.simulateReceive(ChannelFloodScopeLifetimeTests.messageSent)
      _ = try await queued.value
    }
  }
}

private let selfInfo: Data = {
  var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
  packet.append(Data(repeating: 1, count: 32))
  packet.append(Data(repeating: 0, count: 12))
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(915_000).littleEndian) { Array($0) })
  packet.append(contentsOf: withUnsafeBytes(of: UInt32(125_000).littleEndian) { Array($0) })
  packet.append(contentsOf: [7, 5])
  packet.append(contentsOf: "Test".utf8)
  return packet
}()
