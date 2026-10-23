import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Connection link switch")
@MainActor
struct ConnectionLinkSwitchTests {
  @Test
  func `the live link does not start an attempt`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: nil)
    defer { harness.appState.shutdown() }

    await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.0.0.8")

    #expect(harness.appState.connectionLinkSwitch == nil)
    #expect(harness.manager.testWiFiConnectAttempts.isEmpty)
    #expect(harness.manager.testBluetoothConnectAttempts.isEmpty)
  }

  @Test
  func `the other link starts one attempt and keeps the check`() async throws {
    let peripheralID = UUID()
    let harness = try makeHarness(live: .bluetooth, peripheralID: peripheralID, wifiHost: nil)
    defer { harness.appState.shutdown() }
    let gate = LinkSwitchGate()
    harness.manager.testWiFiConnectHandler = { _, _ in
      await gate.enter()
      throw BLEError.connectionTimeout
    }

    let task = Task {
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")
    }
    await gate.waitUntilEntered()

    let record = try #require(harness.appState.connectionLinkSwitch)
    #expect(record.checked == .bluetooth)
    #expect(record.target == .wifi)
    #expect(record.bluetoothPeripheralID == peripheralID)
    #expect(record.wifi == ConnectionLinkWiFiDial(host: "10.0.0.8", port: ConnectionMethod.defaultWiFiPort))
    #expect(record.deviceID == harness.device.id)
    let attempt = try #require(harness.manager.testWiFiConnectAttempts.first)
    #expect(harness.manager.testWiFiConnectAttempts.count == 1)
    #expect(attempt.host == "10.0.0.8")
    #expect(attempt.port == ConnectionMethod.defaultWiFiPort)
    #expect(harness.manager.testBluetoothConnectAttempts.isEmpty)

    gate.release()
    await task.value
  }

  @Test
  func `a second start does not replace the current attempt`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: nil)
    defer { harness.appState.shutdown() }
    let gate = LinkSwitchGate()
    harness.manager.testWiFiConnectHandler = { _, _ in
      await gate.enter()
      throw BLEError.connectionTimeout
    }

    let task = Task {
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")
    }
    await gate.waitUntilEntered()
    let token = try #require(harness.appState.connectionLinkSwitch).token

    await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.9.9.9")
    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.9.9.9")

    let record = try #require(harness.appState.connectionLinkSwitch)
    #expect(record.token == token)
    #expect(record.target == .wifi)
    #expect(record.wifi?.host == "10.0.0.8")
    #expect(harness.manager.testWiFiConnectAttempts.count == 1)
    #expect(harness.manager.testBluetoothConnectAttempts.isEmpty)

    gate.release()
    await task.value
  }

  @Test
  func `a missing dial fact does not start`() async throws {
    let peripheralID = UUID()
    let wifiOnly = try makeHarness(live: .wifi, peripheralID: nil, wifiHost: "10.0.0.4", wifiPort: 4242)
    defer { wifiOnly.appState.shutdown() }
    await wifiOnly.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: nil)
    #expect(wifiOnly.appState.connectionLinkSwitch == nil)
    #expect(wifiOnly.manager.testBluetoothConnectAttempts.isEmpty)

    let bluetoothOnly = try makeHarness(live: .bluetooth, peripheralID: peripheralID, wifiHost: nil)
    defer { bluetoothOnly.appState.shutdown() }
    await bluetoothOnly.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: nil)
    await bluetoothOnly.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "   ")
    #expect(bluetoothOnly.appState.connectionLinkSwitch == nil)
    #expect(bluetoothOnly.manager.testWiFiConnectAttempts.isEmpty)

    let held = ConnectionLinkSwitch(
      radioID: UUID(),
      deviceID: UUID(),
      checked: .wifi,
      target: nil,
      token: UUID(),
      bluetoothPeripheralID: peripheralID,
      wifi: ConnectionLinkWiFiDial(host: "", port: ConnectionMethod.defaultWiFiPort)
    )
    let declined = ConnectionLinkSwitch.start(
      existing: held,
      target: .wifi,
      liveTransport: nil,
      radioID: nil,
      deviceID: nil,
      bluetoothPeripheralID: nil,
      wifi: ConnectionLinkWiFiDial(host: "10.9.9.9", port: ConnectionMethod.defaultWiFiPort),
      token: UUID()
    )
    #expect(declined == nil)
    #expect(held.wifi?.displayHost == nil)
  }

  @Test
  func `success for the current attempt on the same radio clears the record`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: nil)
    defer { harness.appState.shutdown() }
    harness.manager.testWiFiConnectHandler = { _, _ in
      harness.manager.setTestState(
        connectedDevice: .some(harness.device),
        currentTransportType: .wifi
      )
    }

    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

    #expect(harness.appState.connectionLinkSwitch == nil)
    await harness.appState.disconnect(reason: .userInitiated)
    #expect(harness.appState.connectionLinkSwitch == nil)
  }

  @Test
  func `a throw keeps the check and keeps the record`() async throws {
    let peripheralID = UUID()
    let harness = try makeHarness(
      live: .wifi,
      peripheralID: peripheralID,
      wifiHost: "radio.local",
      wifiPort: 4242
    )
    defer { harness.appState.shutdown() }

    await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.9.9.9")

    let record = try #require(harness.appState.connectionLinkSwitch)
    #expect(record.checked == .wifi)
    #expect(record.target == nil)
    #expect(record.wifi == ConnectionLinkWiFiDial(host: "radio.local", port: 4242))
    #expect(record.displayHost == "radio.local")
    #expect(harness.manager.testBluetoothConnectAttempts == [peripheralID])
    #expect(harness.manager.testWiFiConnectAttempts.isEmpty)
    #expect(harness.appState.connectionUI.showingConnectionFailedAlert)

    await harness.appState.disconnect(reason: .userInitiated)
    #expect(harness.appState.connectionLinkSwitch == nil)
  }

  @Test
  func `a retry dials the captured wifi host and non-default port`() async throws {
    let harness = try makeHarness(
      live: .wifi,
      peripheralID: UUID(),
      wifiHost: "radio.local",
      wifiPort: 4242
    )
    defer { harness.appState.shutdown() }
    harness.manager.testBluetoothConnectHandler = { _ in
      harness.manager.setTestState(
        connectedDevice: .some(nil),
        currentTransportType: .some(nil)
      )
      throw BLEError.connectionTimeout
    }

    await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.9.9.9")
    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.8.8.8")

    let attempt = try #require(harness.manager.testWiFiConnectAttempts.first)
    #expect(harness.manager.testWiFiConnectAttempts.count == 1)
    #expect(attempt.host == "radio.local")
    #expect(attempt.port == 4242)
  }

  @Test
  func `a stale completion writes nothing`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: nil)
    defer { harness.appState.shutdown() }
    let gate = LinkSwitchGate()
    harness.manager.testWiFiConnectHandler = { _, _ in
      await gate.enter()
      harness.manager.setTestState(
        connectedDevice: .some(harness.device),
        currentTransportType: .wifi
      )
    }

    let task = Task {
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")
    }
    await gate.waitUntilEntered()
    var replaced = try #require(harness.appState.connectionLinkSwitch)
    replaced.token = UUID()
    harness.appState.connectionLinkSwitch = replaced

    gate.release()
    await task.value

    let record = try #require(harness.appState.connectionLinkSwitch)
    #expect(record == replaced)
  }

  @Test
  func `a different radio drops the record`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: nil)
    defer { harness.appState.shutdown() }
    harness.manager.testWiFiConnectHandler = { _, _ in
      var other = harness.device
      other.radioID = UUID()
      harness.manager.setTestState(
        connectedDevice: .some(other),
        currentTransportType: .wifi
      )
      throw BLEError.connectionTimeout
    }

    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

    #expect(harness.appState.connectionLinkSwitch == nil)
  }

  @Test
  func `disconnect before the completion leaves the record clear`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: nil)
    defer { harness.appState.shutdown() }
    let gate = LinkSwitchGate()
    harness.manager.testWiFiConnectHandler = { _, _ in
      await gate.enter()
      harness.manager.setTestState(
        connectedDevice: .some(harness.device),
        currentTransportType: .wifi
      )
    }

    let task = Task {
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")
    }
    await gate.waitUntilEntered()
    let disconnect = Task {
      await harness.appState.disconnect(reason: .userInitiated)
    }
    await Task.yield()
    #expect(harness.appState.connectionLinkSwitch == nil)

    gate.release()
    await task.value
    await disconnect.value
    #expect(harness.appState.connectionLinkSwitch == nil)
  }

  @Test
  func `a nil live transport can start the checked link`() async throws {
    let peripheralID = UUID()
    let harness = try makeHarness(live: .bluetooth, peripheralID: peripheralID, wifiHost: nil)
    defer { harness.appState.shutdown() }
    harness.manager.testWiFiConnectHandler = { _, _ in
      throw BLEError.connectionTimeout
    }
    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")
    #expect(harness.appState.connectionLinkSwitch?.checked == .bluetooth)
    #expect(harness.appState.connectionLinkSwitch?.target == nil)

    harness.manager.setTestState(
      connectedDevice: .some(nil),
      currentTransportType: .some(nil)
    )
    let gate = LinkSwitchGate()
    harness.manager.testBluetoothConnectHandler = { _ in
      await gate.enter()
      throw BLEError.connectionTimeout
    }

    let task = Task {
      await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.9.9.9")
    }
    await gate.waitUntilEntered()

    let record = try #require(harness.appState.connectionLinkSwitch)
    #expect(record.checked == .bluetooth)
    #expect(record.target == .bluetooth)
    #expect(record.wifi?.host == "10.0.0.8")
    #expect(harness.manager.testBluetoothConnectAttempts == [peripheralID])

    gate.release()
    await task.value
    #expect(harness.appState.connectionLinkSwitch?.checked == .bluetooth)
    #expect(harness.appState.connectionLinkSwitch?.target == nil)
  }

  @Test
  func `a finished record clears once the radio is connected`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: nil)
    defer { harness.appState.shutdown() }
    harness.appState.connectionLinkSwitch = ConnectionLinkSwitch(
      radioID: harness.device.radioID,
      deviceID: harness.device.id,
      checked: .bluetooth,
      target: nil,
      token: UUID(),
      bluetoothPeripheralID: harness.device.bluetoothPeripheralID,
      wifi: ConnectionLinkWiFiDial(host: "10.0.0.8", port: ConnectionMethod.defaultWiFiPort)
    )

    await harness.appState.wireServicesIfConnected()

    #expect(harness.appState.connectionLinkSwitch == nil)
  }

  @Test
  func `an in-progress attempt keeps the record for the same radio`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: nil)
    defer { harness.appState.shutdown() }
    let record = ConnectionLinkSwitch(
      radioID: harness.device.radioID,
      deviceID: harness.device.id,
      checked: .bluetooth,
      target: .wifi,
      token: UUID(),
      bluetoothPeripheralID: harness.device.bluetoothPeripheralID,
      wifi: ConnectionLinkWiFiDial(host: "10.0.0.8", port: ConnectionMethod.defaultWiFiPort)
    )
    harness.appState.connectionLinkSwitch = record

    await harness.appState.wireServicesIfConnected()
    #expect(harness.appState.connectionLinkSwitch == record)

    var other = harness.device
    other.radioID = UUID()
    harness.manager.setTestState(connectedDevice: .some(other))
    await harness.appState.wireServicesIfConnected()
    #expect(harness.appState.connectionLinkSwitch == nil)
  }

  @Test
  func `a completed wifi session returns to its saved host and port`() async throws {
    let harness = try makeHarness(
      live: .wifi,
      peripheralID: UUID(),
      wifiHost: "radio.local",
      wifiPort: 4242
    )
    defer { harness.appState.shutdown() }
    harness.manager.testBluetoothConnectHandler = { _ in
      harness.manager.setTestState(
        connectedDevice: .some(harness.device),
        currentTransportType: .bluetooth
      )
    }

    await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.9.9.9")
    #expect(harness.appState.connectionLinkSwitch == nil)

    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.1.2.3")

    let attempt = try #require(harness.manager.testWiFiConnectAttempts.first)
    #expect(harness.manager.testWiFiConnectAttempts.count == 1)
    #expect(attempt.host == "radio.local")
    #expect(attempt.port == 4242)
    #expect(harness.appState.connectionUI.showingConnectionFailedAlert)
  }

  @Test
  func `a failed saved dial does not try the reported address`() async throws {
    let harness = try makeHarness(
      live: .bluetooth,
      peripheralID: UUID(),
      wifiHost: "radio.example",
      wifiPort: 4242
    )
    defer { harness.appState.shutdown() }

    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

    let attempt = try #require(harness.manager.testWiFiConnectAttempts.first)
    #expect(harness.manager.testWiFiConnectAttempts.count == 1)
    #expect(attempt.host == "radio.example")
    #expect(attempt.port == 4242)
    #expect(harness.manager.testBluetoothConnectAttempts.isEmpty)
    #expect(harness.appState.connectionUI.showingConnectionFailedAlert)
    #expect(harness.appState.connectionLinkSwitch?.wifi == ConnectionLinkWiFiDial(host: "radio.example", port: 4242))
  }

  @Test
  func `an unusable saved endpoint uses the reported address on the default port`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: "bad host", wifiPort: 4242)
    defer { harness.appState.shutdown() }

    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

    let attempt = try #require(harness.manager.testWiFiConnectAttempts.first)
    #expect(harness.manager.testWiFiConnectAttempts.count == 1)
    #expect(attempt.host == "10.0.0.8")
    #expect(attempt.port == ConnectionMethod.defaultWiFiPort)
  }

  @Test
  func `no usable wifi source does not dial`() async throws {
    let harness = try makeHarness(live: .bluetooth, peripheralID: UUID(), wifiHost: "bad host", wifiPort: 4242)
    defer { harness.appState.shutdown() }

    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "999.1.1.1")

    #expect(harness.appState.connectionLinkSwitch == nil)
    #expect(harness.manager.testWiFiConnectAttempts.isEmpty)
    #expect(!harness.appState.connectionUI.showingConnectionFailedAlert)
  }

  @Test(arguments: [TransportType.bluetooth, .wifi])
  func `a failed switch can recover through either captured link`(_ source: TransportType) async throws {
    let peripheralID = UUID()
    let capturedPort: UInt16 = 4242
    let harness = try makeHarness(
      live: source,
      peripheralID: peripheralID,
      wifiHost: "radio.local",
      wifiPort: capturedPort
    )
    defer { harness.appState.shutdown() }
    let target: TransportType = source == .bluetooth ? .wifi : .bluetooth
    let clearLive = {
      harness.manager.setTestState(
        connectedDevice: .some(nil),
        currentTransportType: .some(nil)
      )
    }
    harness.manager.testWiFiConnectHandler = { _, _ in
      clearLive()
      throw BLEError.connectionTimeout
    }
    harness.manager.testBluetoothConnectHandler = { _ in
      clearLive()
      throw BLEError.connectionTimeout
    }

    await harness.appState.beginLinkSwitch(to: target, snapshotWiFiHost: "10.9.9.9")

    var replaced = harness.device
    replaced.connectionMethods = [
      .bluetooth(peripheralUUID: UUID(), displayName: nil),
      .wifi(host: "other.example", port: 9, displayName: nil)
    ]
    harness.manager.setTestState(
      connectedDevice: .some(replaced),
      currentTransportType: .some(nil)
    )

    try expectRecoveryRow(harness, link: source, peripheralID: peripheralID, port: capturedPort)
    await harness.appState.beginLinkSwitch(to: source, snapshotWiFiHost: "10.8.8.8")
    try expectRecoveryRow(harness, link: target, peripheralID: peripheralID, port: capturedPort)
    await harness.appState.beginLinkSwitch(to: target, snapshotWiFiHost: "10.1.2.3")

    let record = try #require(harness.appState.connectionLinkSwitch)
    #expect(record.checked == source)
    #expect(record.bluetoothPeripheralID == peripheralID)
    #expect(record.wifi == ConnectionLinkWiFiDial(host: "radio.local", port: capturedPort))
    if source == .bluetooth {
      #expect(harness.manager.testBluetoothConnectAttempts == [peripheralID])
      #expect(harness.manager.testWiFiConnectAttempts.map(\.host) == ["radio.local", "radio.local"])
      #expect(harness.manager.testWiFiConnectAttempts.map(\.port) == [capturedPort, capturedPort])
    } else {
      #expect(harness.manager.testBluetoothConnectAttempts == [peripheralID, peripheralID])
      #expect(harness.manager.testWiFiConnectAttempts.map(\.host) == ["radio.local"])
      #expect(harness.manager.testWiFiConnectAttempts.map(\.port) == [capturedPort])
    }
  }

  @Test
  func `a held attempt keeps its endpoint when saved data and the snapshot change`() async throws {
    let harness = try makeHarness(
      live: .bluetooth,
      peripheralID: UUID(),
      wifiHost: "  radio.local  ",
      wifiPort: 4242
    )
    defer { harness.appState.shutdown() }
    let gate = LinkSwitchGate()
    harness.manager.testWiFiConnectHandler = { _, _ in
      await gate.enter()
      throw BLEError.connectionTimeout
    }

    let task = Task {
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")
    }
    await gate.waitUntilEntered()

    var replaced = harness.device
    replaced.connectionMethods = [
      .wifi(host: "other.example", port: 9, displayName: nil)
    ]
    harness.manager.setTestState(connectedDevice: .some(replaced))
    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.9.9.9")

    let running = try #require(harness.appState.connectionLinkSwitch)
    #expect(running.wifi == ConnectionLinkWiFiDial(host: "radio.local", port: 4242))
    #expect(harness.manager.testWiFiConnectAttempts.count == 1)

    gate.release()
    await task.value

    harness.manager.testWiFiConnectHandler = { _, _ in
      throw BLEError.connectionTimeout
    }
    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.8.8.8")
    #expect(harness.manager.testWiFiConnectAttempts.count == 2)
    #expect(harness.manager.testWiFiConnectAttempts.last?.host == "radio.local")
    #expect(harness.manager.testWiFiConnectAttempts.last?.port == 4242)
  }
}

@MainActor
private final class LinkSwitchGate {
  private var entered = false
  private var enteredWaiter: CheckedContinuation<Void, Never>?
  private var releaseWaiter: CheckedContinuation<Void, Never>?

  func enter() async {
    entered = true
    enteredWaiter?.resume()
    enteredWaiter = nil
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      releaseWaiter = continuation
    }
  }

  func waitUntilEntered() async {
    if entered { return }
    await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
      if entered {
        continuation.resume()
        return
      }
      enteredWaiter = continuation
    }
  }

  func release() {
    releaseWaiter?.resume()
    releaseWaiter = nil
  }
}

@MainActor
private struct LinkSwitchHarness {
  let appState: AppState
  let device: DeviceDTO

  var manager: ConnectionManager {
    appState.connectionManager
  }
}

@MainActor
private func expectRecoveryRow(
  _ harness: LinkSwitchHarness,
  link: TransportType,
  peripheralID: UUID,
  port: UInt16
) throws {
  let record = try #require(harness.appState.connectionLinkSwitch)
  let device = harness.appState.connectedDevice
  let snapshot = ConnectionLinkRows.make(
    liveTransport: harness.manager.currentTransportType,
    savedPeripheralID: device?.bluetoothPeripheralID,
    wifiSupport: .supported,
    wifiAssociated: true,
    wifiDial: ConnectionLinkWiFiDial(host: "other.example", port: 9),
    activeSwitch: record
  )
  let row = try #require(snapshot.rows.first { $0.link == link })
  #expect(row.canSwitch)
  #expect(snapshot.checked == record.checked)
  #expect(snapshot.rows.first { $0.link == .wifi }?.address == "radio.local")
  #expect(record.bluetoothPeripheralID == peripheralID)
  #expect(record.wifi == ConnectionLinkWiFiDial(host: "radio.local", port: port))
}

@MainActor
private func makeHarness(
  live: TransportType,
  peripheralID: UUID?,
  wifiHost: String?,
  wifiPort: UInt16 = ConnectionMethod.defaultWiFiPort
) throws -> LinkSwitchHarness {
  let suite = "test.linkswitch.\(UUID().uuidString)"
  let defaults = try #require(UserDefaults(suiteName: suite))
  defaults.removePersistentDomain(forName: suite)
  let appState = try AppState(
    modelContainer: PersistenceStore.createContainer(inMemory: true),
    isPlaceholder: true,
    defaults: defaults
  )
  var methods: [ConnectionMethod] = []
  if let peripheralID {
    methods.append(.bluetooth(peripheralUUID: peripheralID, displayName: nil))
  }
  if let wifiHost {
    methods.append(.wifi(host: wifiHost, port: wifiPort, displayName: nil))
  }
  let device = makeLinkDevice(methods: methods)
  appState.connectionManager.setTestState(
    connectionState: .connected,
    connectedDevice: .some(device),
    currentTransportType: live
  )
  appState.connectionManager.testWiFiConnectHandler = { _, _ in
    throw BLEError.connectionTimeout
  }
  appState.connectionManager.testBluetoothConnectHandler = { _ in
    throw BLEError.connectionTimeout
  }
  return LinkSwitchHarness(appState: appState, device: device)
}

private func makeLinkDevice(methods: [ConnectionMethod]) -> DeviceDTO {
  DeviceDTO(
    id: UUID(),
    publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize),
    nodeName: "Relay",
    firmwareVersion: 11,
    firmwareVersionString: "1.15.0",
    manufacturerName: "Test",
    buildDate: "2025-01-01",
    maxContacts: 100,
    maxChannels: 8,
    frequency: 915_000,
    bandwidth: 250_000,
    spreadingFactor: 10,
    codingRate: 5,
    txPower: 20,
    maxTxPower: 20,
    latitude: 0,
    longitude: 0,
    blePin: 0,
    manualAddContacts: false,
    multiAcks: 2,
    telemetryModeBase: 2,
    telemetryModeLoc: 0,
    telemetryModeEnv: 0,
    advertLocationPolicy: 0,
    lastConnected: Date(),
    lastContactSync: 0,
    isActive: true,
    ocvPreset: nil,
    customOCVArrayString: nil,
    connectionMethods: methods
  )
}
