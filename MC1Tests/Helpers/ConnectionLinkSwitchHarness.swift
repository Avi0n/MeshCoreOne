import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@MainActor
final class ConnectionLinkSwitchHarness {
  let appState: AppState
  let device: DeviceDTO
  let bluetooth: ConnectionTestRadioTransport
  let wifi: ConnectionTestRadioTransport
  let pairing: MockAccessorySetupKitService
  let initialBluetoothAttempts: Int
  let initialWiFiAttempts: Int
  /// Drives every session this harness's manager creates. `nil` keeps sessions on the real clock.
  let sessionClock: TestClock?
  private let defaults: UserDefaults
  private let suite: String
  private let radios: RadioObserver
  private var workers: [Task<Void, Never>] = []

  var manager: ConnectionManager {
    appState.connectionManager
  }

  private init(
    appState: AppState, device: DeviceDTO, bluetooth: ConnectionTestRadioTransport,
    wifi: ConnectionTestRadioTransport, pairing: MockAccessorySetupKitService,
    initialBluetoothAttempts: Int, initialWiFiAttempts: Int, sessionClock: TestClock?,
    radios: RadioObserver, defaults: UserDefaults, suite: String
  ) {
    self.appState = appState
    self.device = device
    self.bluetooth = bluetooth
    self.wifi = wifi
    self.pairing = pairing
    self.initialBluetoothAttempts = initialBluetoothAttempts
    self.initialWiFiAttempts = initialWiFiAttempts
    self.sessionClock = sessionClock
    self.radios = radios
    self.defaults = defaults
    self.suite = suite
  }

  static func make(
    live: TransportType = .bluetooth,
    peripheralID: UUID? = UUID(),
    wifiHost: String? = nil,
    wifiPort: UInt16 = ConnectionMethod.defaultWiFiPort,
    foreignWiFiRadio: Bool = false,
    sessionClock: TestClock? = nil
  ) async throws -> ConnectionLinkSwitchHarness {
    let suite = "test.linkswitch.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    let container = try PersistenceStore.createContainer(inMemory: true)
    let publicKey = Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize)
    let bluetooth = ConnectionTestRadioTransport(publicKey: publicKey)
    let wifi = ConnectionTestRadioTransport(
      publicKey: foreignWiFiRadio ? Data(repeating: 0xB2, count: ProtocolLimits.publicKeySize) : publicKey
    )
    let pairing = MockAccessorySetupKitService()
    pairing.isSessionActive = false
    let manager = ConnectionManager(
      modelContainer: container, defaults: defaults, stateMachine: MockBLEStateMachine(),
      transport: bluetooth, pairing: AccessorySetupPairingService(accessorySetupKit: pairing),
      makeWiFiTransport: { wifi }
    )
    var methods: [ConnectionMethod] = []
    if let peripheralID { methods.append(.bluetooth(peripheralUUID: peripheralID, displayName: nil)) }
    if let wifiHost { methods.append(.wifi(host: wifiHost, port: wifiPort, displayName: nil)) }
    let seed = makeDevice(id: peripheralID ?? UUID(), publicKey: publicKey, methods: methods)
    try await manager.persistenceStore.saveDevice(seed)
    let appState = AppState(
      modelContainer: container, isPlaceholder: true, defaults: defaults, injectedConnectionManager: manager
    )
    manager.sessionClockForTesting = sessionClock
    let radios = RadioObserver(manager: manager, bluetooth: bluetooth, wifi: wifi, live: live)
    do {
      try await advancing(sessionClock, radios: radios) {
        switch live {
        case .bluetooth:
          let id = try #require(peripheralID)
          _ = try await manager.connect(to: id)
        case .wifi:
          _ = try await manager.connectViaWiFi(host: wifiHost ?? "10.0.0.4", port: wifiPort)
        }
      }
      let device = try #require(manager.connectedDevice)
      #expect(manager.connectionState == .ready)
      #expect(manager.services != nil)
      await bluetooth.setConnectError(BLEError.bluetoothUnavailable)
      await wifi.setConnectError(BLEError.connectionTimeout)
      return await ConnectionLinkSwitchHarness(
        appState: appState, device: device, bluetooth: bluetooth, wifi: wifi, pairing: pairing,
        initialBluetoothAttempts: bluetooth.connectCount,
        initialWiFiAttempts: wifi.connectCount, sessionClock: sessionClock, radios: radios,
        defaults: defaults, suite: suite
      )
    } catch {
      await bluetooth.releaseAllGates()
      await wifi.releaseAllGates()
      _ = try? await advancing(sessionClock, radios: radios) { await manager.disconnect(reason: .userInitiated) }
      appState.shutdown()
      defaults.removePersistentDomain(forName: suite)
      throw error
    }
  }

  func startSwitch(to target: TransportType, host: String? = "10.0.0.8") -> Task<Void, Never> {
    let worker = Task { await self.appState.beginLinkSwitch(to: target, snapshotWiFiHost: host) }
    workers.append(worker)
    return worker
  }

  func startDisconnect() -> Task<Void, Never> {
    let worker = Task { await self.appState.disconnect(reason: .userInitiated) }
    workers.append(worker)
    return worker
  }

  func close() async {
    await bluetooth.releaseAllGates()
    await wifi.releaseAllGates()
    for worker in workers {
      await worker.value
    }
    workers.removeAll()
    // Retirement drains in-flight exchanges, and an unanswered one ends only on its session-clock timeout.
    do {
      try await advancingClock { await self.appState.disconnect(reason: .userInitiated) }
    } catch {
      Issue.record("Clocked disconnect did not finish: \(error)")
    }
    appState.shutdown()
    defaults.removePersistentDomain(forName: suite)
    #expect(manager.services == nil)
    #expect(await bluetooth.isConnected == false)
    #expect(await wifi.isConnected == false)
  }

  func expectRecoveryRow(_ link: TransportType, peripheralID: UUID, port: UInt16) throws {
    let record = try #require(appState.connectionLinkSwitch)
    let snapshot = ConnectionLinkRows.make(
      liveTransport: manager.currentTransportType,
      savedPeripheralID: appState.connectedDevice?.bluetoothPeripheralID,
      wifiSupport: .supported, wifiAssociated: true,
      wifiDial: ConnectionLinkWiFiDial(host: "other.example", port: 9), activeSwitch: record
    )
    #expect(try #require(snapshot.rows.first { $0.link == link }).canSwitch)
    #expect(snapshot.checked == record.checked)
    #expect(snapshot.rows.first { $0.link == .wifi }?.address == "radio.local")
    #expect(record.bluetoothPeripheralID == peripheralID)
    #expect(record.wifi == ConnectionLinkWiFiDial(host: "radio.local", port: port))
  }

  static func withHarness(
    live: TransportType = .bluetooth,
    peripheralID: UUID? = UUID(),
    wifiHost: String? = nil,
    wifiPort: UInt16 = ConnectionMethod.defaultWiFiPort,
    foreignWiFiRadio: Bool = false,
    body: (ConnectionLinkSwitchHarness) async throws -> Void
  ) async throws {
    let harness = try await make(
      live: live, peripheralID: peripheralID, wifiHost: wifiHost,
      wifiPort: wifiPort, foreignWiFiRadio: foreignWiFiRadio
    )
    do {
      try await body(harness)
      await harness.close()
    } catch {
      await harness.close()
      throw error
    }
  }

  /// Runs `work`, letting parked session timeouts elapse once their replies can no longer arrive.
  /// Connects, imports, and disconnects in a clocked test go through here so sync and drain sleeps cannot park forever.
  func advancingClock<T: Sendable>(_ work: @escaping @MainActor @Sendable () async throws -> T) async throws -> T {
    try await Self.advancing(sessionClock, radios: radios, work)
  }

  /// Polls `condition` while letting parked session timeouts elapse once their replies can no longer arrive.
  func waitAdvancingClock(
    _ message: String = "clocked session work should finish",
    until condition: @escaping @MainActor () async -> Bool
  ) async throws {
    guard let sessionClock else {
      try await waitUntil(timeout: Timing.realClockWait, message, condition: condition)
      return
    }
    try await advanceParkedMatchedSessionClock(sessionClock, message, observation: { [radios] in
      await radios.observation()
    }, until: condition)
  }

  private static func advancing<T: Sendable>(
    _ clock: TestClock?,
    radios: RadioObserver,
    _ work: @escaping @MainActor @Sendable () async throws -> T
  ) async throws -> T {
    guard let clock else { return try await work() }
    return try await resultAdvancingMatchedSessionClock(clock, observation: { await radios.observation() }, work)
  }

  private enum Timing {
    static let realClockWait: Duration = .seconds(35)
  }

  /// Observes the radio that sent most recently, since that is where the newest session waiter is parked.
  @MainActor
  private final class RadioObserver {
    private let manager: ConnectionManager
    private let bluetooth: ConnectionTestRadioTransport
    private let wifi: ConnectionTestRadioTransport
    private var current: TransportType
    private var sentCounts: (bluetooth: Int, wifi: Int) = (0, 0)

    init(
      manager: ConnectionManager,
      bluetooth: ConnectionTestRadioTransport,
      wifi: ConnectionTestRadioTransport,
      live: TransportType
    ) {
      self.manager = manager
      self.bluetooth = bluetooth
      self.wifi = wifi
      current = live
    }

    func observation() async -> SessionClockObservation {
      let counts = await (bluetooth: bluetooth.sentData.count, wifi: wifi.sentData.count)
      let bluetoothSent = counts.bluetooth > sentCounts.bluetooth
      let wifiSent = counts.wifi > sentCounts.wifi
      sentCounts = counts
      if bluetoothSent != wifiSent {
        current = bluetoothSent ? .bluetooth : .wifi
      } else if bluetoothSent, let live = manager.currentTransportType {
        current = live
      }
      let radio = current == .wifi ? wifi : bluetooth
      return await sessionClockObservation(radio: radio, session: manager.session)
    }
  }

  private static func makeDevice(id: UUID, publicKey: Data, methods: [ConnectionMethod]) -> DeviceDTO {
    DeviceDTO(
      id: id, radioID: UUID(), publicKey: publicKey, nodeName: "Relay", firmwareVersion: 14,
      firmwareVersionString: "1.18.0", manufacturerName: "Test", buildDate: "2025-01-01",
      maxContacts: 100, maxChannels: 1, frequency: 915_000, bandwidth: 250_000,
      spreadingFactor: 10, codingRate: 5, txPower: 20, maxTxPower: 20, latitude: 0, longitude: 0,
      blePin: 0, manualAddContacts: false, multiAcks: 2, telemetryModeBase: 2, telemetryModeLoc: 0,
      telemetryModeEnv: 0, advertLocationPolicy: 0, lastConnected: Date(), lastContactSync: 0,
      isActive: true, ocvPreset: nil, customOCVArrayString: nil, connectionMethods: methods
    )
  }
}
