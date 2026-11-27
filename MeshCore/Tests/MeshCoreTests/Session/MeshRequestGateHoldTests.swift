import Foundation
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("Mesh request gate holds and admission", .timeLimit(.minutes(1)))
struct MeshRequestGateHoldTests {
  private let repeater = Data(repeating: 0x11, count: PacketBuilder.publicKeySize)
  private let other = Data(repeating: 0x22, count: PacketBuilder.publicKeySize)
  private let overallTimeout: TimeInterval = 40
  private let sameContactHold = Duration.seconds(1)
  private let strayVersion = Data("v1.18.0".utf8)
  private let loginOK = Data("OK".utf8)
  private let strayBattery: UInt16 = 0x1111
  private let realBattery: UInt16 = 3700
  private let fetchTimeout: TimeInterval = 5

  @Test
  func `stray owner info after a cancelled exchange is not a login failure`() async throws {
    let harness = try await StartedExchange.make(configuration: .init())
    let companion = FirmwarePendingSlotCompanion()
    try await leaveReplyInFlight(from: repeater, harness: harness, companion: companion)

    let login = Task { try await loginExchange(to: repeater, session: harness.session) }
    try await waitUntil("login is held or written") {
      await harness.isHeldOrWritten(.sendLogin, hold: sameContactHold)
    }
    await companion.ackNewCommands(on: harness.transport)
    await companion.deliver(from: repeater, body: strayVersion, on: harness.transport)
    #expect(await companion.loginFailCount == 0)
    guard await companion.loginFailCount == 0 else {
      login.cancel()
      await harness.session.stop()
      return
    }

    try await harness.waitUntilParked(for: sameContactHold)
    harness.clock.advance(by: sameContactHold)
    try await harness.ack(companion, code: .sendLogin)
    await companion.deliver(from: repeater, body: loginOK, on: harness.transport)
    let info = try await login.value
    #expect(info.publicKeyPrefix == repeater.prefix(6))
    #expect(await companion.loginFailCount == 0)
    let unhandled = await companion.unhandledPayloads
    #expect(unhandled.contains { $0.range(of: loginOK) != nil } == false)
    await harness.session.stop()
  }

  @Test
  func `stray status after a cancelled exchange is not the status reply`() async throws {
    let harness = try await StartedExchange.make(configuration: .init())
    let companion = FirmwarePendingSlotCompanion()
    try await leaveReplyInFlight(from: repeater, harness: harness, companion: companion)

    let status = Task {
      try await harness.session.requestStatus(from: repeater, priority: .interactive)
    }
    try await waitUntil("status is held or written") {
      await harness.isHeldOrWritten(.sendStatusRequest, hold: sameContactHold)
    }
    await companion.ackNewCommands(on: harness.transport)
    await companion.deliver(from: repeater, body: statusFields(battery: strayBattery), on: harness.transport)
    #expect(await companion.statusReplyCount == 0)
    guard await companion.statusReplyCount == 0 else {
      status.cancel()
      await harness.session.stop()
      return
    }

    try await harness.waitUntilParked(for: sameContactHold)
    harness.clock.advance(by: sameContactHold)
    try await harness.ack(companion, code: .sendStatusRequest)
    await companion.deliver(from: repeater, body: statusFields(battery: realBattery), on: harness.transport)
    let response = try await status.value
    #expect(response.battery == Int(realBattery))
    await harness.session.stop()
  }

  @Test
  func `a different contact is admitted during another contact's hold`() async throws {
    let harness = try await StartedExchange.make(configuration: .init())
    let companion = FirmwarePendingSlotCompanion()
    try await leaveReplyInFlight(from: repeater, harness: harness, companion: companion)

    let login = Task { try await loginExchange(to: other, session: harness.session) }
    try await waitUntil("login to the other contact is written") {
      await !harness.commands(.sendLogin).isEmpty
    }
    let sent = await harness.commands(.sendLogin)
    #expect(sent.first?.dropFirst().prefix(PacketBuilder.publicKeySize) == other)
    login.cancel()
    await harness.session.stop()
  }

  @Test
  func `background work does not take the gate ahead of an interactive login waiting out a hold`() async throws {
    let harness = try await StartedExchange.make(configuration: .init())
    let companion = FirmwarePendingSlotCompanion()
    try await leaveReplyInFlight(from: repeater, harness: harness, companion: companion)
    let binaryWritesBefore = await harness.commands(.binaryRequest).count

    let login = Task { try await loginExchange(to: repeater, session: harness.session) }
    try await waitUntil("login is held or written") {
      await harness.isHeldOrWritten(.sendLogin, hold: sameContactHold)
    }
    let background = Task {
      try await harness.session.requestOwnerInfo(from: other, priority: .background)
    }
    try await waitUntil("background exchange is queued or written") {
      let queued = await harness.session.meshRequestGate.queuedWaiterCount
      let written = await harness.commands(.binaryRequest).count
      return queued == 2 || written > binaryWritesBefore
    }
    #expect(await harness.commands(.binaryRequest).count == binaryWritesBefore)

    try await harness.waitUntilParked(for: sameContactHold)
    harness.clock.advance(by: sameContactHold)
    try await harness.ack(companion, code: .sendLogin)
    #expect(await harness.commands(.binaryRequest).count == binaryWritesBefore)
    await companion.deliver(from: repeater, body: loginOK, on: harness.transport)
    _ = try await login.value

    try await waitUntil("background exchange runs after the login") {
      await harness.commands(.binaryRequest).count == binaryWritesBefore + 1
    }
    background.cancel()
    await harness.session.stop()
  }

  @Test
  func `keep-alive waits at the gate without holding the host slot`() async throws {
    let harness = try await StartedExchange.make(configuration: .init())
    let companion = FirmwarePendingSlotCompanion()
    let background = Task {
      try await harness.session.requestOwnerInfo(from: repeater, priority: .background)
    }
    try await harness.ack(companion, code: .binaryRequest)
    try await harness.waitUntilParked(for: sameContactHold)

    let keepAlive = Task {
      try await harness.session.sendKeepAlive(to: other, syncSince: { 0 }, beforeSend: {})
    }
    try await waitUntil("keep-alive is queued or written") {
      let queued = await harness.session.meshRequestGate.queuedWaiterCount
      let written = await harness.keepAliveWrites(to: other)
      return queued == 1 || !written.isEmpty
    }
    #expect(await harness.keepAliveWrites(to: other).isEmpty)

    try await fetchNoMessages(harness)

    harness.clock.advance(by: sameContactHold)
    try await harness.ack(companion, code: .binaryRequest, count: 2)
    try await harness.waitUntilParked(for: .seconds(overallTimeout) - sameContactHold)
    harness.clock.advance(by: .seconds(overallTimeout))
    let backgroundError = await #expect(throws: MeshCoreError.self) { try await background.value }
    guard case .timeout = backgroundError else {
      Issue.record("Background exchange should time out when its budget ends")
      keepAlive.cancel()
      await harness.session.stop()
      return
    }

    try await waitUntil("keep-alive is written") {
      await !harness.keepAliveWrites(to: other).isEmpty
    }
    await companion.ackNewCommands(on: harness.transport)
    _ = try await keepAlive.value
    #expect(await harness.keepAliveWrites(to: other).count == 1)
    #expect(await !harness.session.meshRequestGate.hasActiveHold(for: other.prefix(4)))

    try await fetchNoMessages(harness)
    await harness.session.stop()
  }

  private func leaveReplyInFlight(
    from contact: Data,
    harness: StartedExchange,
    companion: FirmwarePendingSlotCompanion
  ) async throws {
    let background = Task {
      try await harness.session.requestOwnerInfo(from: contact, priority: .background)
    }
    try await harness.ack(companion, code: .binaryRequest)
    try await harness.waitUntilParked(for: sameContactHold)
    background.cancel()
    await #expect(throws: CancellationError.self) { try await background.value }
  }

  private func loginExchange(to contact: Data, session: MeshCoreSession) async throws -> LoginInfo {
    let result = try await session.login(
      to: contact,
      password: "pw",
      priority: .interactive,
      budget: { _ in overallTimeout }
    )
    return try #require(result.info)
  }

  private func fetchNoMessages(_ harness: StartedExchange) async throws {
    let writesBefore = await harness.commands(.getMessage).count
    let fetch = Task { try await harness.session.getMessage(timeout: fetchTimeout) }
    try await waitUntil("getMessage is written") {
      await harness.commands(.getMessage).count == writesBefore + 1
    }
    await harness.transport.simulateReceive(Data([ResponseCode.noMoreMessages.rawValue]))
    let result = try await fetch.value
    guard case .noMoreMessages = result else {
      Issue.record("Expected no more messages, got \(result)")
      return
    }
  }

  private func statusFields(battery: UInt16) -> Data {
    var fields = Data(repeating: 0, count: Self.statusFieldCount)
    fields[0] = UInt8(battery & 0x00FF)
    fields[1] = UInt8(battery >> 8)
    return fields
  }

  private static let statusFieldCount = 51
}

private struct StartedExchange {
  let session: MeshCoreSession
  let transport: MockTransport
  let clock: TestClock

  static func make(configuration: SessionConfiguration) async throws -> StartedExchange {
    let transport = MockTransport()
    let clock = TestClock()
    let session = MeshCoreSession(transport: transport, configuration: configuration, clock: clock)
    let start = Task { try await session.start() }
    try await waitUntil("app start is written") {
      await transport.sentData.contains { $0.first == CommandCode.appStart.rawValue }
    }
    await transport.simulateReceive(
      Data([ResponseCode.selfInfo.rawValue, 1, 22, 22]) + Data(repeating: 0, count: Self.selfInfoPadding)
    )
    try await start.value
    await transport.clearSentData()
    return StartedExchange(session: session, transport: transport, clock: clock)
  }

  func commands(_ code: CommandCode) async -> [Data] {
    await transport.sentData.filter { $0.first == code.rawValue }
  }

  func isHeldOrWritten(_ code: CommandCode, hold: Duration) async -> Bool {
    let queued = await session.meshRequestGate.queuedWaiterCount
    let written = await commands(code)
    let parkedOnHold = clock.sleeperCount == 1 && clock.nextSleepDuration == hold
    return queued == 1 || parkedOnHold || !written.isEmpty
  }

  func keepAliveWrites(to contact: Data) async -> [Data] {
    await commands(.binaryRequest).filter { frame in
      frame.dropFirst().prefix(PacketBuilder.publicKeySize) == contact
        && frame.dropFirst(1 + PacketBuilder.publicKeySize).first == BinaryRequestType.keepAlive.rawValue
    }
  }

  func ack(_ companion: FirmwarePendingSlotCompanion, code: CommandCode, count: Int = 1) async throws {
    try await waitUntil("command \(code) is written (\(count))") {
      await commands(code).count >= count
    }
    await companion.ackNewCommands(on: transport)
  }

  func waitUntilParked(for duration: Duration) async throws {
    try await waitUntil("clock parked for \(duration)") {
      clock.sleeperCount == 1 && clock.nextSleepDuration == duration
    }
  }

  private static let selfInfoPadding = 54
}
