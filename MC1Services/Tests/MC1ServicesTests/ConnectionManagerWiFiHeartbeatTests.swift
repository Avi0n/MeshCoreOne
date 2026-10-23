import Foundation
@testable import MC1Services
@testable import MeshCore
import MeshCoreTestSupport
import Testing

@Suite("ConnectionManager WiFi heartbeat")
@MainActor
struct ConnectionManagerWiFiHeartbeatTests {
  @Test
  func `admitting a connection cancels an old unsubmitted power-off frame`() async throws {
    try await Harness.make().run { harness in
      await harness.gate.armIsConnected()
      let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("power-off should park before submission") { await harness.gate.isConnectedIsWaiting }
      harness.manager.testBluetoothConnectHandler = { _ in }
      try await harness.manager.connectViaBluetooth(peripheralID: UUID())
      await harness.gate.releaseIsConnected()
      await #expect(throws: PowerOffSuperseded.self) { try await powerOff.value }
      #expect(await harness.powerOffFrameCount() == 0)
      #expect(harness.manager.session === harness.session)
    }
  }

  @Test
  func `a stale socket callback leaves the replacement session and power-off evidence alone`() async throws {
    try await Harness.make().run { harness in
      let oldSocket = WiFiTransport()
      let currentSocket = WiFiTransport()
      harness.manager.wifiTransport = currentSocket
      await harness.inner.holdNextSend()
      let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("power-off send should park") { await harness.inner.sendIsWaiting }
      let currentServices = harness.manager.services
      await harness.manager.handleWiFiDisconnection(
        error: WiFiTransportError.notConnected,
        remoteLossAt: CFAbsoluteTimeGetCurrent(),
        source: oldSocket
      )
      #expect(harness.manager.services === currentServices)
      #expect(harness.manager.session === harness.session)
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.powerOffOperation?.remoteLossAfterSubmission == false)
      #expect(harness.manager.wifiReconnectTask == nil)

      await harness.inner.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
      await harness.inner.releaseHeldSend()
      await #expect(throws: SettingsServiceError.self) { try await powerOff.value }
    }
  }

  @Test
  func `power-off settling during socket teardown leaves recovery to the handler`() async throws {
    try await Harness.make().run { harness in
      let teardown = SocketLossTeardownGate()
      defer {
        harness.manager.testConnectionTeardownHandler = nil
        teardown.release()
      }
      try await harness.parkHeartbeat()
      try await harness.fireProbe()
      await harness.gate.armIsConnected()
      let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
      harness.requestClock.advance(by: harness.requestTimeout)
      try await waitUntil("power-off should park before submission") {
        await harness.gate.isConnectedIsWaiting
      }

      harness.manager.testConnectionTeardownHandler = { await teardown.wait() }
      await harness.inner.disconnect()
      let loss = Task {
        await harness.manager.handleWiFiDisconnection(
          error: WiFiTransportError.notConnected,
          remoteLossAt: CFAbsoluteTimeGetCurrent()
        )
      }
      try await waitUntil("socket loss should park in teardown") { teardown.isWaiting }
      #expect(harness.manager.connectionState == .connecting)
      await harness.gate.releaseIsConnected()
      await #expect(throws: Error.self) { try await powerOff.value }
      #expect(harness.manager.powerOffBlocksReconnect == false)
      #expect(harness.manager.wifiReconnectTask == nil)

      teardown.release()
      await loss.value
      try await waitUntil("handler should resume released WiFi recovery") {
        harness.manager.connectionState == .disconnected
          && harness.manager.currentTransportType == nil
      }
      #expect(await harness.powerOffFrameCount() == 0)
      #expect(harness.manager.connectionTeardownCount == 0)
    }
  }

  @Test
  func `socket loss before power-off submission resumes recovery`() async throws {
    try await Harness.make().run { harness in
      harness.manager.testWiFiMaxReconnectDuration = .seconds(30)
      try await harness.parkHeartbeat()
      try await harness.fireProbe()
      await harness.gate.armIsConnected()
      let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
      harness.requestClock.advance(by: harness.requestTimeout)
      try await waitUntil("power-off should park before submission") {
        await harness.gate.isConnectedIsWaiting
      }

      await harness.inner.disconnect()
      await harness.manager.handleWiFiDisconnection(
        error: WiFiTransportError.notConnected,
        remoteLossAt: CFAbsoluteTimeGetCurrent()
      )
      #expect(harness.manager.connectionState == .connecting)
      #expect(harness.manager.wifiReconnectTask == nil)
      await harness.gate.releaseIsConnected()
      await #expect(throws: Error.self) { try await powerOff.value }

      try await waitUntil("released recovery should enter the real WiFi retry loop") {
        harness.manager.wifiReconnectAttempt > 0
      }
      #expect(harness.manager.connectionIntent.wantsConnection)
      #expect(harness.manager.connectionState == .connecting)
      #expect(harness.manager.wifiReconnectTask != nil)
      harness.manager.testWiFiMaxReconnectDuration = .seconds(-1)

      try await waitUntil("failed power-off should release deferred WiFi recovery") {
        harness.manager.connectionState == .disconnected
          && harness.manager.currentTransportType == nil
      }
      #expect(await harness.powerOffFrameCount() == 0)
    }
  }

  @Test
  func `an inconclusive probe during power-off keeps the heartbeat loop`() async throws {
    try await Harness.make().run { harness in
      try await harness.parkHeartbeat()
      try await harness.fireProbe()
      await harness.gate.armIsConnected()

      let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
      harness.requestClock.advance(by: harness.requestTimeout)
      try await waitUntil("power-off should park before submission") {
        await harness.gate.isConnectedIsWaiting
      }
      try await waitUntil("heartbeat should sleep through the inconclusive probe") {
        harness.heartbeatClock.sleeperCount == 1
      }
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.powerOffBlocksReconnect)

      powerOff.cancel()
      _ = harness.manager.powerOffOperation?.gate.cancelIfUnsubmitted()
      await harness.gate.releaseIsConnected()
      _ = await powerOff.result
      try await waitUntil("cancelled power-off should settle") {
        harness.manager.powerOffBlocksReconnect == false
      }
      #expect(await harness.powerOffFrameCount() == 0)

      try await harness.fireProbe()
      harness.requestClock.advance(by: harness.requestTimeout)
      try await waitUntil("a later failed probe should tear the link down") {
        harness.manager.connectionState == .disconnected && harness.manager.session == nil
      }
      #expect(harness.heartbeatClock.sleeperCount == 0)
    }
  }

  @Test
  func `a rejected power-off leaves the heartbeat able to probe`() async throws {
    try await Harness.make().run { harness in
      try await harness.parkHeartbeat()
      await harness.inner.holdNextSend()
      let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("power-off write should park") { await harness.inner.sendIsWaiting }
      await harness.inner.simulateReceive(Data([ResponseCode.cliReply.rawValue]))
      await harness.inner.releaseHeldSend()

      await #expect(throws: SettingsServiceError.self) { try await powerOff.value }
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.connectionIntent.wantsConnection)
      try await harness.fireProbe()
    }
  }

  @Test
  func `successful power-off stops the heartbeat without reconnecting`() async throws {
    try await Harness.make().run { harness in
      try await harness.parkHeartbeat()
      try await harness.manager.powerOffCurrentLink()

      #expect(harness.manager.connectionIntent.isUserDisconnected)
      #expect(harness.manager.connectionState == .disconnected)
      #expect(harness.manager.wifiReconnectTask == nil)
      #expect(harness.heartbeatClock.sleeperCount == 0)
    }
  }

  @Test
  func `socket loss during power-off is not an inconclusive probe`() async throws {
    try await Harness.make().run { harness in
      try await harness.parkHeartbeat()
      await harness.inner.holdNextSend()
      let powerOff = Task { try await harness.manager.powerOffCurrentLink() }
      try await waitUntil("power-off write should park") { await harness.inner.sendIsWaiting }

      await harness.manager.handleWiFiDisconnection(
        error: WiFiTransportError.notConnected,
        remoteLossAt: CFAbsoluteTimeGetCurrent(),
        isHeartbeat: false
      )
      try await waitUntil("socket loss should stop the heartbeat") {
        harness.heartbeatClock.sleeperCount == 0
      }
      #expect(harness.manager.connectionState != .ready)

      await harness.inner.failHeldSend()
      await harness.inner.releaseHeldSend()
      _ = try await powerOff.value
      #expect(harness.manager.connectionIntent.isUserDisconnected)
      #expect(harness.manager.wifiReconnectTask == nil)
    }
  }

  @Test
  func `sync activity skips a probe and the next interval sends one`() async throws {
    try await Harness.make().run { harness in
      try await harness.parkHeartbeat()
      let baseline = await harness.getTimeCount()

      harness.manager.setTestState(connectionState: .syncing)
      try await harness.skipProbe()
      #expect(await harness.getTimeCount() == baseline)

      harness.manager.setTestState(connectionState: .ready)
      try await harness.fireProbe()
      #expect(await harness.getTimeCount() == baseline + 1)
      try await harness.acknowledgeProbe()

      let retry = parkedTask()
      harness.manager.resyncTask = retry
      try await harness.skipProbe()
      #expect(await harness.getTimeCount() == baseline + 1)
      retry.cancel()
      await retry.value
      harness.manager.resyncTask = nil

      let channelRetry = parkedTask()
      harness.manager.channelRetryTask = channelRetry
      try await harness.skipProbe()
      #expect(await harness.getTimeCount() == baseline + 1)
      channelRetry.cancel()
      await channelRetry.value
      harness.manager.channelRetryTask = nil

      try await harness.fireProbe()
      #expect(await harness.getTimeCount() == baseline + 2)
    }
  }

  @Test
  func `cancelling a probe leaves the link ready`() async throws {
    try await Harness.make().run { harness in
      try await harness.parkHeartbeat()
      try await harness.fireProbe()
      harness.manager.stopWiFiHeartbeat()
      harness.requestClock.advance(by: harness.requestTimeout)

      try await waitUntil("the cancelled probe should leave the clock") {
        harness.requestClock.sleeperCount == 0
      }
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.session != nil)
      #expect(harness.manager.wifiReconnectTask == nil)
      #expect(harness.heartbeatClock.sleeperCount == 0)
    }
  }

  @Test
  func `replacing the session during a probe keeps the replacement`() async throws {
    try await Harness.make().run { harness in
      try await harness.parkHeartbeat()
      try await harness.fireProbe()

      let replacement = MeshCoreSession(transport: MockTransport())
      let device = DeviceDTO.testDevice(firmwareVersion: 14)
      let services = try await ServiceContainer.forTesting(session: replacement, radioID: device.radioID)
      harness.manager.setTestState(
        connectionState: .ready,
        services: services,
        session: replacement,
        connectedDevice: device,
        currentTransportType: .wifi,
        connectionIntent: .wantsConnection()
      )
      harness.requestClock.advance(by: harness.requestTimeout)

      try await waitUntil("the old probe should finish") {
        harness.requestClock.sleeperCount == 0 && harness.heartbeatClock.sleeperCount == 0
      }
      #expect(harness.manager.session === replacement)
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.wifiReconnectTask == nil)
    }
  }

  @Test
  func `starting the heartbeat twice leaves one loop`() async throws {
    try await Harness.make().run { harness in
      try await harness.parkHeartbeat()
      let baseline = await harness.getTimeCount()
      harness.manager.startWiFiHeartbeat()
      try await harness.parkHeartbeat()
      await Task.yield()
      #expect(harness.heartbeatClock.sleeperCount == 1)

      try await harness.fireProbe()
      #expect(await harness.getTimeCount() == baseline + 1)
    }
  }
}

extension ConnectionManagerWiFiHeartbeatTests {
  @MainActor
  private final class SocketLossTeardownGate {
    private var continuation: CheckedContinuation<Void, Never>?
    private(set) var isWaiting = false

    func wait() async {
      isWaiting = true
      await withCheckedContinuation { continuation = $0 }
      isWaiting = false
    }

    func release() {
      let pending = continuation
      continuation = nil
      pending?.resume()
    }
  }
}

@MainActor
private struct Harness {
  let manager: ConnectionManager
  let session: MeshCoreSession
  let inner: MockTransport
  let gate: ProbeTransport
  let heartbeatClock: TestClock
  let requestClock: TestClock
  let requestTimeout: Duration = .seconds(10)

  static func make() async throws -> Harness {
    let (manager, _) = try ConnectionManager.createForTesting()
    let inner = MockTransport()
    let gate = ProbeTransport(inner: inner)
    let requestClock = TestClock()
    let session = MeshCoreSession(
      transport: gate,
      configuration: SessionConfiguration(defaultTimeout: 10, clientIdentifier: "MCTst"),
      clock: requestClock
    )
    let device = DeviceDTO.testDevice(firmwareVersion: 14)
    let services = try await ServiceContainer.forTesting(session: session, radioID: device.radioID)
    let heartbeatClock = TestClock()
    manager.testWiFiHeartbeatClock = heartbeatClock
    manager.testWiFiMaxReconnectDuration = .seconds(-1)
    manager.setTestState(
      connectionState: .ready,
      services: services,
      session: session,
      connectedDevice: device,
      currentTransportType: .wifi,
      connectionIntent: .wantsConnection()
    )
    try await start(session, transport: inner)
    try await waitUntil("session clock should be idle") { requestClock.sleeperCount == 0 }
    manager.startWiFiHeartbeat()
    return Harness(
      manager: manager,
      session: session,
      inner: inner,
      gate: gate,
      heartbeatClock: heartbeatClock,
      requestClock: requestClock
    )
  }

  func run(_ body: (Harness) async throws -> Void) async throws {
    do {
      try await body(self)
      await finish()
    } catch {
      await finish()
      throw error
    }
  }

  func parkHeartbeat() async throws {
    try await waitUntil("heartbeat should park") { heartbeatClock.sleeperCount == 1 }
  }

  func fireProbe() async throws {
    let baseline = await getTimeCount()
    heartbeatClock.advance(by: ConnectionManager.wifiHeartbeatInterval)
    try await waitUntil("heartbeat should send getTime") { await getTimeCount() == baseline + 1 }
  }

  /// Completes the in-flight getTime so the loop is parked before the next interval.
  func acknowledgeProbe() async throws {
    await inner.simulateReceive(Self.currentTimePacket())
    try await parkHeartbeat()
  }

  func skipProbe() async throws {
    try await parkHeartbeat()
    heartbeatClock.advance(by: ConnectionManager.wifiHeartbeatInterval)
    try await parkHeartbeat()
  }

  func getTimeCount() async -> Int {
    await inner.sentData.filter { $0 == PacketBuilder.getTime() }.count
  }

  func powerOffFrameCount() async -> Int {
    await inner.sentData.filter {
      Data($0.dropFirst()) == Data(PacketBuilder.powerOffCommand.utf8)
    }.count
  }

  func finish() async {
    await gate.releaseIsConnected()
    await inner.releaseHeldSend()
    manager.resyncTask?.cancel()
    manager.channelRetryTask?.cancel()
    manager.resyncTask = nil
    manager.channelRetryTask = nil
    manager.stopWiFiHeartbeat()
    heartbeatClock.advance(by: .seconds(120))
    requestClock.advance(by: .seconds(60))
    await manager.disconnect()
    await session.stop()
    let drained = await (try? waitUntil("heartbeat clocks should drain") {
      heartbeatClock.sleeperCount == 0 && requestClock.sleeperCount == 0
    }) != nil
    #expect(drained)
  }

  private static func start(_ session: MeshCoreSession, transport: MockTransport) async throws {
    let startTask = Task { try await session.start() }
    try await waitUntil("session should send app start") { await transport.sentData.count == 1 }
    await transport.simulateReceive(selfInfoPacket())
    try await startTask.value
  }

  private static func currentTimePacket() -> Data {
    var packet = Data([ResponseCode.currentTime.rawValue])
    let seconds = UInt32(currentTimeFixture.timeIntervalSince1970)
    packet.append(contentsOf: withUnsafeBytes(of: seconds.littleEndian) { Array($0) })
    return packet
  }

  private static let currentTimeFixture = Date(timeIntervalSince1970: 1_700_000_000)

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

private func parkedTask() -> Task<Void, Never> {
  Task {
    do {
      try await Task.sleep(for: .seconds(60))
    } catch {}
  }
}

private actor ProbeTransport: MeshTransport {
  private let inner: MockTransport
  private var parkNextIsConnected = false
  private var continuation: CheckedContinuation<Void, Never>?
  private(set) var isConnectedIsWaiting = false

  init(inner: MockTransport) {
    self.inner = inner
  }

  func armIsConnected() {
    parkNextIsConnected = true
  }

  func releaseIsConnected() {
    parkNextIsConnected = false
    isConnectedIsWaiting = false
    let continuation = continuation
    self.continuation = nil
    continuation?.resume()
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

  var isConnected: Bool {
    get async {
      if parkNextIsConnected {
        parkNextIsConnected = false
        await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
          self.continuation = continuation
          self.isConnectedIsWaiting = true
        }
      }
      return await inner.isConnected
    }
  }

  var receivedData: AsyncStream<Data> {
    get async { await inner.receivedData }
  }
}
