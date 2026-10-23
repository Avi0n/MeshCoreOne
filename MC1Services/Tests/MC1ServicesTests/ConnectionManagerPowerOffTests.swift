import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("ConnectionManager power off")
@MainActor
struct ConnectionManagerPowerOffTests {
  @Test
  func `silence disconnects only the owned link`() async throws {
    let harness = try await Harness.make()
    try await harness.powerOff()
    #expect(harness.manager.connectionIntent.isUserDisconnected)
    #expect(harness.manager.connectionState == .disconnected)
  }

  @Test
  func `a CLI reply leaves the owned link connected`() async throws {
    let harness = try await Harness.make()
    await harness.transport.holdNextSend()
    let task = Task { try await harness.manager.powerOffCurrentLink() }
    try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }
    await harness.transport.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
    await harness.transport.releaseHeldSend()

    await #expect(throws: SettingsServiceError.self) { try await task.value }
    #expect(harness.manager.connectionIntent.wantsConnection)
    #expect(harness.manager.connectionState == .ready)
  }

  @Test
  func `send failure without link loss is not shutdown`() async throws {
    let harness = try await Harness.make()
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

  @Test
  func `remote loss after submission counts as shutdown when the write fails`() async throws {
    let harness = try await Harness.make()
    harness.manager.setTestState(currentTransportType: .wifi)
    await harness.transport.holdNextSend()
    let task = Task { try await harness.manager.powerOffCurrentLink() }
    try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }

    await harness.manager.handleWiFiDisconnection(
      error: WiFiTransportError.notConnected,
      remoteLossAt: CFAbsoluteTimeGetCurrent()
    )
    #expect(harness.manager.wifiReconnectTask == nil)
    await harness.transport.failHeldSend()
    await harness.transport.releaseHeldSend()

    _ = try await task.value
    #expect(harness.manager.connectionIntent.isUserDisconnected)
    #expect(harness.manager.wifiReconnectTask == nil)
  }

  @Test
  func `heartbeat failure does not tear down or prove shutdown`() async throws {
    let harness = try await Harness.make()
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

  @Test
  func `generic link loss without remote-loss evidence does not count as shutdown`() async throws {
    let harness = try await Harness.make()
    await harness.transport.holdNextSend()
    let task = Task { try await harness.manager.powerOffCurrentLink() }
    try await waitUntil("poweroff write should park") { await harness.transport.sendIsWaiting }

    await harness.manager.handleConnectionLoss(deviceID: harness.device.id, error: nil)
    #expect(harness.manager.reconnectionWatchdogTask == nil)
    await harness.transport.failHeldSend()
    await harness.transport.releaseHeldSend()

    await #expect(throws: Error.self) { try await task.value }
    #expect(!harness.manager.connectionIntent.isUserDisconnected)
    harness.manager.stopReconnectionWatchdog()
  }

  @Test
  func `early auto-reconnect and the watchdog stay suppressed during the attempt`() async throws {
    let harness = try await Harness.make()
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

  @Test
  func `a second power-off waits instead of sending twice`() async throws {
    let harness = try await Harness.make()
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

  @Test
  func `a replacement session is left connected when the old attempt finishes`() async throws {
    let harness = try await Harness.make()
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

  @Test
  func `cancelling before submission sends nothing`() async throws {
    let harness = try await Harness.make()
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

  @Test
  func `settled cleanup survives nil publication and dies on a replacement session`() async throws {
    let harness = try await Harness.make()

    let token = try await harness.manager.powerOffCurrentLink()
    #expect(harness.manager.powerOffOwnsCleanup(token))
    harness.manager.setTestState(session: MeshCoreSession?.none)
    #expect(harness.manager.powerOffOwnsCleanup(token))
    await harness.manager.disconnect(reason: .powerOff)
    #expect(harness.manager.powerOffOwnsCleanup(token))

    installReplacement(on: harness.manager, radioID: UUID())
    #expect(harness.manager.powerOffOwnsCleanup(token) == false)
  }

  @Test
  func `explicit disconnect revokes a settled cleanup token`() async throws {
    let harness = try await Harness.make()
    let token = try await harness.manager.powerOffCurrentLink()
    #expect(harness.manager.powerOffOwnsCleanup(token))

    await harness.manager.disconnect()

    #expect(harness.manager.powerOffOwnsCleanup(token) == false)
  }

  @Test
  func `a replacement revokes the shared cleanup token for every waiting caller`() async throws {
    let harness = try await Harness.make()
    let first = Task { try await harness.manager.powerOffCurrentLink() }
    let second = Task { try await harness.manager.powerOffCurrentLink() }

    let firstToken = try await first.value
    let secondToken = try await second.value
    #expect(firstToken == secondToken)
    #expect(harness.manager.powerOffOwnsCleanup(firstToken))

    installReplacement(on: harness.manager, radioID: UUID())
    #expect(harness.manager.powerOffOwnsCleanup(firstToken) == false)
    #expect(harness.manager.powerOffOwnsCleanup(secondToken) == false)
  }

  @Test
  func `a replacement before settlement revokes the shared cleanup token`() async throws {
    let harness = try await Harness.make()
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

@MainActor
private struct Harness {
  let manager: ConnectionManager
  let session: MeshCoreSession
  let transport: MockTransport
  let device: DeviceDTO

  static func make() async throws -> Harness {
    let (manager, _) = try ConnectionManager.createForTesting()
    let transport = MockTransport()
    let session = MeshCoreSession(
      transport: transport,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst")
    )
    let device = DeviceDTO.testDevice(firmwareVersion: 14)
    let services = try await ServiceContainer.forTesting(session: session, radioID: device.radioID)
    manager.setTestState(
      connectionState: .ready,
      services: services,
      session: session,
      connectedDevice: device,
      connectionIntent: .wantsConnection()
    )
    try await start(session, transport: transport)
    return Harness(manager: manager, session: session, transport: transport, device: device)
  }

  func powerOff() async throws {
    _ = try await manager.powerOffCurrentLink()
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
