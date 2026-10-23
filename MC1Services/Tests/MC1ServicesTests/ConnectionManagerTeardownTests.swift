import Foundation
@testable import MC1Services
@testable import MeshCore
import Testing

@Suite("ConnectionManager teardown ownership")
@MainActor
struct ConnectionManagerTeardownTests {
  @Test(arguments: [false, true])
  func `stale sync schedulers leave the current recovery tasks intact`(channelRetry: Bool) async throws {
    let harness = try await TeardownHarness.make()
    let staleServices = try await ServiceContainer.forTesting(
      session: MeshCoreSession(transport: MockTransport()), radioID: harness.device.radioID
    )
    let resyncGate = TeardownGate()
    let channelGate = TeardownGate()
    let resync = Task { await resyncGate.wait() }
    let channel = Task { await channelGate.wait() }
    harness.manager.resyncTask = resync
    harness.manager.resyncAttemptCount = 2
    harness.manager.channelRetryTask = channel

    if channelRetry {
      harness.manager.scheduleChannelOnlyRetry(radioID: harness.device.radioID, services: staleServices, indices: [0])
    } else {
      harness.manager.startResyncLoop(radioID: harness.device.radioID, services: staleServices)
    }

    #expect(resync.isCancelled == false)
    #expect(channel.isCancelled == false)
    #expect(harness.manager.resyncAttemptCount == 2)
    harness.manager.resyncTask?.cancel()
    harness.manager.channelRetryTask?.cancel()
    resyncGate.release()
    channelGate.release()
    await resync.value
    await channel.value
    await staleServices.tearDown()
    await harness.finish(replacement: nil)
  }

  @Test(arguments: [false, true])
  func `a connection resumed after power-off cannot disconnect its shared replacement`(
    duringSync: Bool
  ) async throws {
    let harness = try await TeardownHarness.make()
    await harness.manager.disconnect()
    let ready = TeardownGate()
    harness.manager.onConnectionReady = {
      if duringSync, let services = harness.manager.services {
        await services.syncCoordinator.setSyncActivityCallbacks(
          onStarted: { await ready.wait() }, onEnded: { _ in }, onPhaseChanged: { _ in }
        )
      } else {
        await ready.wait()
      }
    }
    await harness.transport.setAnswerSetup(true)
    let firstID = UUID()
    let first = Task { try await harness.manager.connectViaBluetooth(peripheralID: firstID) }
    var replacement: Task<Void, Error>?
    let recoveryGate = TeardownGate()
    var replacementRecovery: Task<Void, Never>?
    do {
      try await waitUntil("first connection should park before sync") { ready.isWaiting }
      #expect(harness.manager.connectedDevice?.id == firstID)
      try await harness.manager.powerOffCurrentLink()
      await harness.transport.setAnswerSetup(false)
      let replacementID = firstID
      replacement = Task {
        try await harness.manager.connect(to: replacementID, forceFullSync: true, forceReconnect: true)
      }
      try await waitUntil("replacement should send app start") { await harness.transport.appStartCount == 3 }
      let currentSession = try #require(harness.manager.session)
      let recovery = Task { await recoveryGate.wait() }
      replacementRecovery = recovery
      harness.manager.resyncTask = recovery
      harness.manager.resyncAttemptCount = 2
      ready.release()
      _ = await first.result
      #expect(await harness.transport.isConnected)
      #expect(harness.manager.session === currentSession)
      #expect(harness.manager.connectingDeviceID == replacementID)
      #expect(harness.manager.connectionIntent == .wantsConnection(forceFullSync: true))
      #expect(recovery.isCancelled == false)
      #expect(harness.manager.resyncAttemptCount == 2)
      recoveryGate.release()
      await recovery.value
      await harness.finish(replacement: replacement)
    } catch {
      ready.release()
      recoveryGate.release()
      _ = await first.result
      await replacementRecovery?.value
      await harness.finish(replacement: replacement)
      throw error
    }
  }

  @Test
  func `opportunistic BLE rebuild stands down during teardown`() async throws {
    let harness = try await TeardownHarness.make()
    let gate = TeardownGate()
    harness.manager.testConnectionTeardownHandler = { await gate.wait() }
    harness.manager.testLastConnectedDeviceID = harness.device.id
    await harness.stateMachine.setStubbedIsConnected(true)
    await harness.stateMachine.setStubbedConnectedDeviceID(harness.device.id)
    var rebuilt = false
    harness.manager.rebuildSessionForHealthCheckOverride = { _ in rebuilt = true }
    let disconnect = Task { await harness.manager.disconnect(reason: .resyncFailed) }
    do {
      try await waitUntil("transient disconnect should park in teardown") { gate.isWaiting }
      await harness.manager.checkBLEConnectionHealth()
      #expect(rebuilt == false)
      await #expect(throws: CancellationError.self) {
        try await harness.manager.rebuildSession(deviceID: harness.device.id)
      }
      #expect(harness.manager.connectionState == .disconnected)
      #expect(harness.manager.session == nil)
      gate.release()
      await disconnect.value
      await harness.finish(replacement: nil)
    } catch {
      gate.release()
      await disconnect.value
      await harness.finish(replacement: nil)
      throw error
    }
  }

  @Test
  func `cancelling a replacement parked behind teardown never connects`() async throws {
    let harness = try await TeardownHarness.make()
    let gate = TeardownGate()
    harness.manager.testConnectionTeardownHandler = { await gate.wait() }
    let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
    var replacement: Task<Void, Error>?
    do {
      try await waitUntil("power-off should park in teardown") { gate.isWaiting }
      let baseline = await harness.transport.connectCount
      let attempt = Task { try await harness.manager.connectViaBluetooth(peripheralID: UUID()) }
      replacement = attempt
      try await waitUntil("replacement should wait for teardown") {
        harness.manager.testConnectionTeardownWaiterCount == 1
      }
      attempt.cancel()
      gate.release()
      _ = await powerOff.result
      await #expect(throws: CancellationError.self) { try await attempt.value }
      #expect(await harness.transport.connectCount == baseline)
      #expect(harness.manager.testConnectionTeardownWaiterCount == 0)
      #expect(harness.manager.connectionTeardownCount == 0)
      await harness.finish(replacement: replacement)
    } catch {
      gate.release()
      _ = await powerOff.result
      await harness.finish(replacement: replacement)
      throw error
    }
  }

  @Test(arguments: [false, true])
  func `duplicate disconnects drain before a manual test override runs`(wifi: Bool) async throws {
    let harness = try await TeardownHarness.make()
    let gate = TeardownGate()
    harness.manager.testConnectionTeardownHandler = { await gate.wait() }
    let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
    var replacement: Task<Void, Error>?
    do {
      try await waitUntil("power-off should park in teardown") { gate.isWaiting }
      await harness.manager.disconnect()
      #expect(harness.manager.connectionTeardownCount == 1)
      var attempted = false
      harness.manager.testBluetoothConnectHandler = { _ in attempted = true }
      harness.manager.testWiFiConnectHandler = { _, _ in attempted = true }
      let attempt = Task {
        if wifi {
          try await harness.manager.connectViaWiFi(host: "radio.local", port: 4242)
        } else {
          try await harness.manager.connectViaBluetooth(peripheralID: UUID())
        }
      }
      replacement = attempt
      try await waitUntil("manual entry should wait for all teardown") {
        harness.manager.testConnectionTeardownWaiterCount == 1 || attempted
      }
      #expect(attempted == false)
      gate.release()
      _ = await powerOff.result
      try await attempt.value
      #expect(attempted)
      #expect(harness.manager.connectionTeardownCount == 0)
      #expect(harness.manager.testConnectionTeardownWaiterCount == 0)
      await harness.finish(replacement: replacement)
    } catch {
      gate.release()
      _ = await powerOff.result
      await harness.finish(replacement: replacement)
      throw error
    }
  }

  @Test
  func `power-off finishes shared transport teardown before a replacement connects`() async throws {
    let harness = try await TeardownHarness.make()
    let gate = TeardownGate()
    harness.manager.testConnectionTeardownHandler = { await gate.wait() }
    let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
    var replacement: Task<Void, Error>?
    do {
      try await waitUntil("power-off should park in teardown") { gate.isWaiting }
      let target = harness.device.id
      let baseline = await harness.transport.connectCount
      replacement = Task { try await harness.manager.connectViaBluetooth(peripheralID: target) }
      try await waitUntil("replacement should reach teardown or transport") {
        let connectCount = await harness.transport.connectCount
        return harness.manager.testConnectionTeardownWaiterCount > 0 || connectCount > baseline
      }
      #expect(await harness.transport.connectCount == baseline)

      gate.release()
      _ = await powerOff.result
      try await harness.waitForReplacementHandshake()
      #expect(await harness.transport.isConnected)
      #expect(await harness.transport.lastConnectFollowsLastDisconnect)
      #expect(harness.manager.connectionIntent.wantsConnection)
      #expect(harness.manager.session != nil)
      await harness.finish(replacement: replacement)
    } catch {
      gate.release()
      _ = await powerOff.result
      await harness.finish(replacement: replacement)
      throw error
    }
  }

  @Test
  func `an admitted replacement revokes settled cleanup before session publication`() async throws {
    let harness = try await TeardownHarness.make()
    let token = try await harness.manager.powerOffCurrentLink()
    #expect(harness.manager.powerOffOwnsCleanup(token))
    await harness.transport.holdConnect()
    let replacement = Task { try await harness.manager.connect(to: UUID(), forceReconnect: true) }
    do {
      try await waitUntil("replacement connect should park") { await harness.transport.connectIsWaiting }
      #expect(harness.manager.session == nil)
      #expect(harness.manager.powerOffOwnsCleanup(token) == false)
      await harness.transport.releaseConnect()
      try await harness.waitForReplacementHandshake()
      #expect(await harness.transport.isConnected)
      await harness.finish(replacement: replacement)
    } catch {
      await harness.transport.releaseConnect()
      await harness.finish(replacement: replacement)
      throw error
    }
  }

  @Test
  func `an admitted switch invalidates an unsettled power-off before transport connect`() async throws {
    let harness = try await TeardownHarness.make()
    await harness.transport.inner.holdNextSend()
    let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
    var replacement: Task<Void, Error>?
    do {
      try await waitUntil("power-off send should park") { await harness.transport.inner.sendIsWaiting }
      await harness.transport.holdConnect()
      replacement = Task { try await harness.manager.switchDevice(to: UUID()) }
      try await waitUntil("replacement switch should park") { await harness.transport.connectIsWaiting }
      await harness.transport.inner.releaseHeldSend()
      await #expect(throws: PowerOffSuperseded.self) { try await powerOff.value }
      #expect(harness.manager.connectionIntent.wantsConnection)
      await harness.transport.releaseConnect()
      await harness.finish(replacement: replacement)
    } catch {
      await harness.transport.inner.releaseHeldSend()
      await harness.transport.releaseConnect()
      _ = await powerOff.result
      await harness.finish(replacement: replacement)
      throw error
    }
  }

  @Test
  func `socket loss teardown finishes before a manual Bluetooth replacement`() async throws {
    let harness = try await TeardownHarness.make()
    let gate = TeardownGate()
    harness.manager.setTestState(currentTransportType: .wifi)
    harness.manager.testConnectionTeardownHandler = { await gate.wait() }
    harness.manager.testWiFiMaxReconnectDuration = .seconds(-1)
    let loss = Task {
      await harness.manager.handleWiFiDisconnection(error: WiFiTransportError.notConnected, remoteLossAt: nil)
    }
    var replacement: Task<Void, Error>?
    do {
      try await waitUntil("socket loss should park in teardown") { gate.isWaiting }
      let baseline = await harness.transport.connectCount
      replacement = Task { try await harness.manager.connectViaBluetooth(peripheralID: UUID()) }
      try await waitUntil("replacement should reach teardown or transport") {
        let connectCount = await harness.transport.connectCount
        return harness.manager.testConnectionTeardownWaiterCount > 0 || connectCount > baseline
      }
      #expect(await harness.transport.connectCount == baseline)
      harness.manager.testConnectionTeardownHandler = nil
      gate.release()
      await loss.value
      try await harness.waitForReplacementHandshake()
      #expect(await harness.transport.isConnected)
      #expect(harness.manager.connectionState == .connected)
      #expect(harness.manager.wifiReconnectTask == nil)
      await harness.finish(replacement: replacement)
    } catch {
      harness.manager.testConnectionTeardownHandler = nil
      gate.release()
      await loss.value
      await harness.finish(replacement: replacement)
      throw error
    }
  }
}

extension ConnectionManagerTeardownTests {
  @MainActor
  private struct TeardownHarness {
    let manager: ConnectionManager
    let transport: SharedTeardownTransport
    let device: DeviceDTO
    let stateMachine: MockBLEStateMachine
    let suiteName: String

    static func make() async throws -> TeardownHarness {
      let suiteName = "test.teardown.\(UUID().uuidString)"
      let defaults = try #require(UserDefaults(suiteName: suiteName))
      let container = try PersistenceStore.createContainer(inMemory: true)
      let transport = SharedTeardownTransport()
      let stateMachine = MockBLEStateMachine()
      let manager = ConnectionManager(
        modelContainer: container, defaults: defaults,
        stateMachine: stateMachine, transport: transport
      )
      let session = MeshCoreSession(transport: transport)
      let device = DeviceDTO.testDevice(firmwareVersion: 14)
      let services = try await ServiceContainer.forTesting(session: session, radioID: device.radioID)
      manager.setTestState(
        connectionState: .ready, services: services, session: session,
        connectedDevice: device, currentTransportType: .bluetooth, connectionIntent: .wantsConnection()
      )
      let start = Task { try await session.start() }
      try await waitUntil("session should send app start") { await transport.appStartCount == 1 }
      await transport.inner.simulateReceive(selfInfoPacket())
      try await start.value
      return TeardownHarness(
        manager: manager, transport: transport, device: device, stateMachine: stateMachine, suiteName: suiteName
      )
    }

    func waitForReplacementHandshake() async throws {
      try await waitUntil("replacement should send app start") { await transport.appStartCount == 2 }
    }

    func finish(replacement: Task<Void, Error>?) async {
      manager.testConnectionTeardownHandler = nil
      await transport.releaseConnect()
      await transport.inner.releaseHeldSend()
      replacement?.cancel()
      await manager.disconnect()
      _ = await replacement?.result
      UserDefaults().removePersistentDomain(forName: suiteName)
    }

    nonisolated static func selfInfoPacket() -> Data {
      var packet = Data([ResponseCode.selfInfo.rawValue, 1, 22, 22])
      packet.append(Data(repeating: 0x01, count: 32))
      packet.append(Data(repeating: 0, count: 12))
      for value: UInt32 in [915_000, 125_000] {
        packet.append(withUnsafeBytes(of: value.littleEndian) { Data($0) })
      }
      packet.append(contentsOf: [7, 5])
      return packet
    }
  }

  @MainActor
  private final class TeardownGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private var isOpen = false
    private(set) var isWaiting = false

    func wait() async {
      guard !isOpen, !isWaiting else { return }
      isWaiting = true
      await withCheckedContinuation { continuation = $0 }
      isWaiting = false
    }

    func release() {
      isOpen = true
      let pending = continuation
      continuation = nil
      pending?.resume()
    }
  }

  private actor SharedTeardownTransport: iOSMeshTransport {
    let inner = MockTransport()
    private var events: [String] = []
    private var shouldHoldConnect = false
    private var connectContinuation: CheckedContinuation<Void, Never>?
    private var answerSetup = false
    private(set) var connectIsWaiting = false
    private(set) var connectCount = 0

    var isConnected: Bool {
      get async { await inner.isConnected }
    }

    var receivedData: AsyncStream<Data> {
      get async { await inner.receivedData }
    }

    var appStartCount: Int {
      get async { await inner.sentData.filter { $0.first == CommandCode.appStart.rawValue }.count }
    }

    var lastConnectFollowsLastDisconnect: Bool {
      guard let connect = events.lastIndex(of: "connect"), let disconnect = events.lastIndex(of: "disconnect") else { return false }
      return connect > disconnect
    }

    func connect() async throws {
      connectCount += 1
      if shouldHoldConnect {
        connectIsWaiting = true
        await withCheckedContinuation { connectContinuation = $0 }
        connectIsWaiting = false
      }
      events.append("connect")
      try await inner.connect()
    }

    func disconnect() async {
      events.append("disconnect")
      await inner.disconnect()
    }

    func send(_ data: Data) async throws {
      try await inner.send(data)
      guard answerSetup else { return }
      switch data.first {
      case CommandCode.appStart.rawValue:
        await inner.simulateReceive(TeardownHarness.selfInfoPacket())
      case CommandCode.deviceQuery.rawValue:
        var response = Data([ResponseCode.deviceInfo.rawValue, 14, 1, 1])
        response.append(Data(repeating: 0, count: 76))
        await inner.simulateReceive(response)
      case CommandCode.getAutoAddConfig.rawValue:
        await inner.simulateReceive(Data([ResponseCode.autoAddConfig.rawValue, 0]))
      case CommandCode.runCliCommand.rawValue:
        break
      default:
        await inner.simulateReceive(Data([ResponseCode.error.rawValue, 1]))
      }
    }

    func setDeviceID(_ id: UUID) {}
    func switchDevice(to deviceID: UUID) async throws {
      await disconnect()
      try await connect()
    }

    func setDisconnectionHandler(_ handler: @escaping @Sendable (UUID, Error?) -> Void) {}
    func setReconnectionHandler(_ handler: @escaping @Sendable (UUID) -> Void) {}
    func refreshDataStream() {}

    func holdConnect() {
      shouldHoldConnect = true
    }

    func setAnswerSetup(_ answer: Bool) {
      answerSetup = answer
    }

    func releaseConnect() {
      shouldHoldConnect = false
      let pending = connectContinuation
      connectContinuation = nil
      pending?.resume()
    }
  }
}
