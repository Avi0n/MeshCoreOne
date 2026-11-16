import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import os
import Testing

@Suite("ConnectionManager power off")
@MainActor
struct ConnectionManagerPowerOffTests {
  @Test
  func `silence disconnects only the owned link`() async throws {
    try await withPowerOffHarness(clock: TestClock()) { harness in
      try await harness.powerOff()
      #expect(harness.manager.connectionIntent.isUserDisconnected)
      #expect(harness.manager.connectionState == .disconnected)
    }
  }

  @Test
  func `a CLI reply leaves the owned link connected`() async throws {
    try await withPowerOffHarness { harness in
      await harness.transport.holdNextSend()
      let task = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }
      await harness.transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
      await harness.transport.releaseHeldSend()

      await #expect(throws: SettingsServiceError.self) { try await task.value }
      #expect(harness.manager.connectionIntent.wantsConnection)
      #expect(harness.manager.connectionState == .ready)
    }
  }

  @Test
  func `send failure without link loss is not shutdown`() async throws {
    try await withPowerOffHarness { harness in
      await harness.transport.holdNextSend()
      let task = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }
      let early = CFAbsoluteTimeGetCurrent() - 5
      harness.manager.notePowerOffRemoteLoss(deviceID: harness.device.id, eventTime: early)
      await harness.transport.failHeldSend()
      await harness.transport.releaseHeldSend()

      await #expect(throws: SettingsServiceError.self) { try await task.value }
      #expect(harness.manager.connectionIntent.wantsConnection)
    }
  }

  @Test
  func `remote loss after submission counts as shutdown when the write fails`() async throws {
    try await withPowerOffHarness(wifi: true) { harness in
      let socket = try #require(harness.wifi)
      await harness.transport.holdNextSend()
      let task = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }

      let loss = Task {
        await harness.manager.handleWiFiDisconnection(
          error: WiFiTransportError.notConnected,
          remoteLossAt: CFAbsoluteTimeGetCurrent(), source: socket
        )
      }
      do {
        try await waitUntil("socket loss must detach before its captured exchange drains") {
          harness.manager.currentConnection == nil
        }
        #expect(harness.manager.wifiReconnectTask == nil)
        await harness.transport.failHeldSend()
        await harness.transport.releaseHeldSend()
        await loss.value
        _ = try await task.value
        #expect(harness.manager.connectionIntent.isUserDisconnected)
        #expect(harness.manager.wifiReconnectTask == nil)
      } catch {
        await harness.transport.releaseHeldSend()
        _ = await loss.result
        _ = await task.result
        _ = await harness.manager.disconnect()
        throw error
      }
    }
  }

  @Test
  func `heartbeat failure does not tear down or prove shutdown`() async throws {
    try await withPowerOffHarness { harness in
      await harness.transport.holdNextSend()
      let task = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }

      await harness.manager.handleWiFiDisconnection(
        error: MeshCoreError.timeout,
        remoteLossAt: nil,
        isHeartbeat: true
      )
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.session != nil)

      await harness.transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
      await harness.transport.releaseHeldSend()
      await #expect(throws: SettingsServiceError.self) { try await task.value }
      #expect(harness.manager.connectionIntent.wantsConnection)
    }
  }

  @Test
  func `generic link loss without remote-loss evidence does not count as shutdown`() async throws {
    try await withPowerOffHarness { harness in
      await harness.transport.holdNextSend()
      let task = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }

      let origin = BLELifecycleOrigin(deviceID: harness.device.id, generation: 1, eventTime: CFAbsoluteTimeGetCurrent())
      let loss = Task { await harness.manager.handleBLELifecycleEvent(.disconnected(origin, error: nil)) }
      try await waitUntil("loss should detach publication before draining the held command") { harness.manager.connectionState == .disconnected }
      #expect(harness.manager.reconnectionWatchdogTask == nil)
      await harness.transport.failHeldSend()
      await harness.transport.releaseHeldSend()
      await loss.value

      await #expect(throws: Error.self) { try await task.value }
      #expect(!harness.manager.connectionIntent.isUserDisconnected)
      harness.manager.stopReconnectionWatchdog()
    }
  }

  @Test
  func `early auto-reconnect and the watchdog stay suppressed during the attempt`() async throws {
    try await withPowerOffHarness { harness in
      await harness.transport.holdNextSend()
      let task = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }

      #expect(harness.manager.powerOffSuppressesReconnect(for: harness.device.id))
      harness.manager.startReconnectionWatchdog()
      #expect(harness.manager.reconnectionWatchdogTask == nil)

      await harness.transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
      await harness.transport.releaseHeldSend()
      await #expect(throws: SettingsServiceError.self) { try await task.value }
      #expect(harness.manager.powerOffSuppressesReconnect(for: harness.device.id) == false)
    }
  }

  @Test
  func `a second power-off waits instead of sending twice`() async throws {
    try await withPowerOffHarness { harness in
      await harness.transport.holdNextSend()
      let first = Task { try await harness.manager.powerOffCurrentLink() }
      let second = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }
      await harness.transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
      await harness.transport.releaseHeldSend()

      await #expect(throws: SettingsServiceError.self) { try await first.value }
      await #expect(throws: SettingsServiceError.self) { try await second.value }
      let powerOffs = await harness.transport.sentData.filter {
        Data($0.dropFirst()) == Data(PacketBuilder.powerOffCommand.utf8)
      }
      #expect(powerOffs.count == 1)
    }
  }

  @Test
  func `a replacement session is left connected when the old attempt finishes`() async throws {
    try await withPowerOffHarness { harness in
      await harness.transport.holdNextSend()
      let task = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }

      let replacement = MeshCoreSession(transport: MockTransport())
      let replacementDevice = DeviceDTO.testDevice()
      let replacementServices = try await ServiceContainer.forTesting(session: replacement)
      harness.manager.setTestState(
        connectionState: .ready,
        services: replacementServices,
        session: replacement,
        connectedDevice: replacementDevice,
        connectionIntent: .wantsConnection()
      )
      await harness.transport.failHeldSend()
      await harness.transport.releaseHeldSend()

      await #expect(throws: PowerOffSuperseded.self) { try await task.value }
      #expect(harness.manager.session === replacement)
      #expect(harness.manager.connectedDevice?.id == replacementDevice.id)
      #expect(harness.manager.connectionIntent.wantsConnection)
      #expect(harness.manager.connectionState == .ready)
    }
  }

  @Test
  func `cancelling before submission sends nothing`() async throws {
    try await withPowerOffHarness { harness in
      await harness.transport.holdNextSend()
      let blocker = Task { try await harness.session.runCliCommand("board") }
      try await waitUntil("blocker write should park") { await harness.transport.sendIsWaiting }

      let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
      try await Task.sleep(for: .milliseconds(20))
      powerOff.cancel()
      await harness.transport.releaseHeldSend()
      await harness.transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
      _ = try? await blocker.value

      let outcome = await powerOff.result
      guard case .failure = outcome else {
        Issue.record("expected the queued power-off to fail")
        return
      }
      let powerOffs = await harness.transport.sentData.filter {
        Data($0.dropFirst()) == Data(PacketBuilder.powerOffCommand.utf8)
      }
      #expect(powerOffs.isEmpty)
      #expect(harness.manager.connectionIntent.wantsConnection)
    }
  }

  @Test
  func `settled cleanup survives nil publication and dies on a replacement session`() async throws {
    try await withPowerOffHarness(clock: TestClock()) { harness in
      let token = try await harness.awaitPowerOff()
      #expect(harness.manager.powerOffOwnsCleanup(token))
      harness.manager.setTestState(session: MeshCoreSession?.none)
      #expect(harness.manager.powerOffOwnsCleanup(token))
      await harness.manager.disconnect(reason: .powerOff)
      #expect(harness.manager.powerOffOwnsCleanup(token))

      installReplacement(on: harness.manager, radioID: UUID())
      #expect(harness.manager.powerOffOwnsCleanup(token) == false)
    }
  }

  @Test
  func `explicit disconnect revokes a settled cleanup token`() async throws {
    try await withPowerOffHarness(clock: TestClock()) { harness in
      let token = try await harness.awaitPowerOff()
      #expect(harness.manager.powerOffOwnsCleanup(token))

      await harness.manager.disconnect()

      #expect(harness.manager.powerOffOwnsCleanup(token) == false)
    }
  }

  @Test
  func `a replacement revokes the shared cleanup token for every waiting caller`() async throws {
    let clock = TestClock()
    try await withPowerOffHarness(clock: clock) { harness in
      let finished = OSAllocatedUnfairLock(initialState: 0)
      let first = Task {
        defer { finished.withLock { $0 += 1 } }
        return try await harness.manager.powerOffCurrentLink()
      }
      let second = Task {
        defer { finished.withLock { $0 += 1 } }
        return try await harness.manager.powerOffCurrentLink()
      }
      try await waitUntilClockAdvances(clock, timeout: .seconds(5), "power-off silence should elapse") {
        finished.withLock { $0 == 2 }
      }

      let firstToken = try await first.value
      let secondToken = try await second.value
      #expect(firstToken == secondToken)
      #expect(harness.manager.powerOffOwnsCleanup(firstToken))

      installReplacement(on: harness.manager, radioID: UUID())
      #expect(harness.manager.powerOffOwnsCleanup(firstToken) == false)
      #expect(harness.manager.powerOffOwnsCleanup(secondToken) == false)
    }
  }

  @Test
  func `a replacement before settlement revokes the shared cleanup token`() async throws {
    try await withPowerOffHarness { harness in
      await harness.transport.holdNextSend()
      let first = Task { try await harness.manager.powerOffCurrentLink() }
      let second = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }

      installReplacement(on: harness.manager, radioID: harness.device.radioID)
      await harness.transport.failHeldSend()
      await harness.transport.releaseHeldSend()

      await #expect(throws: PowerOffSuperseded.self) { try await first.value }
      await #expect(throws: PowerOffSuperseded.self) { try await second.value }
      #expect(harness.manager.powerOffCleanupToken == nil)
    }
  }
}

@MainActor
private struct Harness {
  let manager: ConnectionManager
  let session: MeshCoreSession
  let transport: MockTransport
  let device: DeviceDTO
  let wifi: PowerOffSocket?
  let services: ServiceContainer
  let clock: TestClock?
  let suiteName: String

  static func make(wifi: Bool = false, clock: TestClock? = nil) async throws -> Harness {
    let suiteName = "test.poweroff.\(UUID())"
    let defaults = try #require(UserDefaults(suiteName: suiteName))
    let (manager, _) = try ConnectionManager.createForTesting(defaults: defaults)
    let transport = MockTransport()
    let socket = wifi ? PowerOffSocket(transport: transport) : nil
    let physical: any MeshTransport = if let socket { socket } else { transport }
    let session = MeshCoreSession(
      transport: physical,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst"),
      clock: clock
    )
    let device = DeviceDTO.testDevice(firmwareVersion: 14)
    let services = try await ServiceContainer.forTesting(session: session, radioID: device.radioID)
    manager.setTestState(
      connectionState: .ready,
      services: services,
      session: session,
      connectedDevice: device,
      currentTransportType: wifi ? .wifi : .bluetooth,
      wifiTransport: socket,
      connectionIntent: .wantsConnection()
    )
    try await start(session, transport: transport)
    return Harness(
      manager: manager, session: session, transport: transport, device: device, wifi: socket,
      services: services, clock: clock, suiteName: suiteName
    )
  }

  func close() async {
    await transport.releaseHeldSend()
    _ = await manager.disconnect()
    await session.stop(disconnectTransport: false, drainExchanges: true)
    await session.waitForQuiescence()
    await services.tearDown()
    await transport.disconnect()
    UserDefaults().removePersistentDomain(forName: suiteName)
    #expect(await session.subscriberCountForTest == 0)
  }

  func powerOff() async throws {
    _ = try await awaitPowerOff()
  }

  func awaitPowerOff() async throws -> UUID {
    guard let clock else { return try await manager.powerOffCurrentLink() }
    let slot = OSAllocatedUnfairLock<Result<UUID, Error>?>(initialState: nil)
    let task = Task { @MainActor in
      let outcome: Result<UUID, Error>
      do { outcome = try await .success(manager.powerOffCurrentLink()) } catch { outcome = .failure(error) }
      slot.withLock { $0 = outcome }
    }
    try await waitUntilClockAdvances(clock, timeout: .seconds(5), "power-off silence should elapse") {
      slot.withLock { $0 != nil }
    }
    task.cancel()
    return try slot.withLock { try $0!.get() }
  }

  private static func start(_ session: MeshCoreSession, transport: MockTransport) async throws {
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") { await transport.sentData.count == 1 }
    await transport.simulateReceive(selfInfoPacket())
    try await startTask.value
  }

  private static func selfInfoPacket() -> Data {
    var payload = Data([ResponseCode.selfInfo.rawValue])
    payload.append(1)
    payload.append(UInt8(bitPattern: 22))
    payload.append(UInt8(bitPattern: 22))
    payload.append(Data(repeating: 0x01, count: 32))
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: Int32(0).littleEndian) { Array($0) })
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(0)
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(869_525).littleEndian) { Array($0) })
    payload.append(contentsOf: withUnsafeBytes(of: UInt32(250_000).littleEndian) { Array($0) })
    payload.append(11)
    payload.append(5)
    return payload
  }
}

@MainActor
private func withPowerOffHarness(
  wifi: Bool = false,
  clock: TestClock? = nil,
  _ operation: @MainActor (Harness) async throws -> Void
) async throws {
  let harness = try await Harness.make(wifi: wifi, clock: clock)
  do { try await operation(harness) } catch { await harness.close(); throw error }
  await harness.close()
}

@MainActor
private func installReplacement(on manager: ConnectionManager, radioID: UUID) {
  let replacement = MeshCoreSession(transport: MockTransport())
  let device = DeviceDTO.testDevice(radioID: radioID, firmwareVersion: 14)
  manager.setTestState(
    connectionState: .ready,
    session: replacement,
    connectedDevice: device,
    connectionIntent: .wantsConnection()
  )
}

private actor PowerOffSocket: WiFiMeshTransport {
  let transport: MockTransport
  var connectionInfo: (host: String, port: UInt16)? {
    ("radio.test", 5123)
  }

  var receivedData: AsyncStream<Data> {
    get async { await transport.receivedData }
  }

  var isConnected: Bool {
    get async { await transport.isConnected }
  }

  init(transport: MockTransport) {
    self.transport = transport
  }

  func connect() async throws {
    try await transport.connect()
  }

  func disconnect() async {
    await transport.disconnect()
  }

  func send(_ data: Data) async throws {
    try await transport.send(data)
  }

  func setConnectionInfo(host: String, port: UInt16) {}
  func setDisconnectionHandler(_ handler: @escaping @Sendable (Error?, CFAbsoluteTime) -> Void) {}
}
