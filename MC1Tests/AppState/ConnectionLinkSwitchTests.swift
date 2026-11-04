import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("Connection link switch")
@MainActor
struct ConnectionLinkSwitchTests {
  @Test
  func `the live link does not start an attempt`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.0.0.8")

      #expect(harness.appState.connectionLinkSwitch == nil)
      #expect(await harness.wifi.connectCount == 0)
      #expect(await harness.bluetooth.connectCount == harness.initialBluetoothAttempts)
      #expect(harness.manager.connectionState == .ready)
    }
  }

  @Test
  func `the other link starts one attempt and keeps the check`() async throws {
    let peripheralID = UUID()
    try await ConnectionLinkSwitchHarness.withHarness(peripheralID: peripheralID) { harness in
      await harness.wifi.holdConnect()
      let task = harness.startSwitch(to: .wifi)
      try await waitUntil("WiFi physical connect held") { await harness.wifi.connectIsHeld }

      let record = try #require(harness.appState.connectionLinkSwitch)
      #expect(record.checked == .bluetooth)
      #expect(record.target == .wifi)
      #expect(record.bluetoothPeripheralID == peripheralID)
      #expect(record.wifi == ConnectionLinkWiFiDial(host: "10.0.0.8", port: ConnectionMethod.defaultWiFiPort))
      #expect(record.deviceID == harness.device.id)
      #expect(await harness.wifi.connectCount == 1)
      #expect(await harness.wifi.connectionInfo?.host == "10.0.0.8")
      #expect(await harness.wifi.connectionInfo?.port == ConnectionMethod.defaultWiFiPort)
      #expect(await harness.bluetooth.connectCount == harness.initialBluetoothAttempts)

      await harness.wifi.releaseConnect()
      await task.value
    }
  }

  @Test
  func `a second start does not replace the current attempt`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      await harness.wifi.holdConnect()
      let task = harness.startSwitch(to: .wifi)
      try await waitUntil("WiFi physical connect held") { await harness.wifi.connectIsHeld }
      let token = try #require(harness.appState.connectionLinkSwitch).token

      await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.9.9.9")
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.9.9.9")

      let record = try #require(harness.appState.connectionLinkSwitch)
      #expect(record.token == token)
      #expect(record.target == .wifi)
      #expect(record.wifi?.host == "10.0.0.8")
      #expect(await harness.wifi.connectCount == 1)
      #expect(await harness.bluetooth.connectCount == harness.initialBluetoothAttempts)
      await harness.wifi.releaseConnect()
      await task.value
    }
  }

  @Test
  func `a missing dial fact does not start`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness(
      live: .wifi, peripheralID: nil, wifiHost: "10.0.0.4", wifiPort: 4242
    ) { harness in
      await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: nil)
      #expect(harness.appState.connectionLinkSwitch == nil)
      #expect(await harness.bluetooth.connectCount == 0)
    }
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: nil)
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "   ")
      #expect(harness.appState.connectionLinkSwitch == nil)
      #expect(await harness.wifi.connectCount == 0)
    }
    let held = ConnectionLinkSwitch(
      radioID: UUID(), deviceID: UUID(), checked: .wifi, target: nil, token: UUID(),
      bluetoothPeripheralID: UUID(), wifi: ConnectionLinkWiFiDial(host: "", port: ConnectionMethod.defaultWiFiPort)
    )
    let declined = ConnectionLinkSwitch.start(
      existing: held, target: .wifi, liveTransport: nil, radioID: nil, deviceID: nil,
      bluetoothPeripheralID: nil,
      wifi: ConnectionLinkWiFiDial(host: "10.9.9.9", port: ConnectionMethod.defaultWiFiPort), token: UUID()
    )
    #expect(declined == nil)
    #expect(held.wifi?.displayHost == nil)
  }

  @Test
  func `success for the current attempt on the same radio clears the record`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      await harness.wifi.setConnectError(nil)

      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

      #expect(harness.appState.connectionLinkSwitch == nil)
      #expect(harness.manager.currentTransportType == .wifi)
      #expect(harness.manager.connectionState == .ready)
      #expect(harness.manager.connectedDevice?.radioID == harness.device.radioID)
      #expect(harness.manager.services != nil)
      await harness.appState.disconnect(reason: .userInitiated)
      #expect(harness.appState.connectionLinkSwitch == nil)
    }
  }

  @Test
  func `a throw keeps the check and keeps the record`() async throws {
    let peripheralID = UUID()
    try await ConnectionLinkSwitchHarness.withHarness(
      live: .wifi, peripheralID: peripheralID, wifiHost: "radio.local", wifiPort: 4242
    ) { harness in
      await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.9.9.9")

      let record = try #require(harness.appState.connectionLinkSwitch)
      #expect(record.checked == .wifi)
      #expect(record.target == nil)
      #expect(record.wifi == ConnectionLinkWiFiDial(host: "radio.local", port: 4242))
      #expect(record.displayHost == "radio.local")
      #expect(await harness.bluetooth.connectCount == 1)
      #expect(await harness.wifi.connectCount == harness.initialWiFiAttempts)
      #expect(harness.appState.connectionUI.showingConnectionFailedAlert)
      #expect(harness.manager.currentTransportType == nil)
    }
  }

  @Test
  func `a stale completion does not finish a newer token`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      await harness.wifi.holdConnect()
      let task = harness.startSwitch(to: .wifi)
      try await waitUntil("WiFi physical connect held") { await harness.wifi.connectIsHeld }
      var replaced = try #require(harness.appState.connectionLinkSwitch)
      replaced.token = UUID()
      harness.appState.connectionLinkSwitch = replaced

      await harness.wifi.releaseConnect()
      await task.value

      #expect(harness.appState.connectionLinkSwitch == replaced)
    }
  }

  @Test
  func `a different radio drops the record`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness(foreignWiFiRadio: true) { harness in
      await harness.wifi.setConnectError(nil)

      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

      #expect(harness.appState.connectionLinkSwitch == nil)
      #expect(harness.manager.currentTransportType == .wifi)
      #expect(harness.manager.connectedDevice?.radioID != harness.device.radioID)
      #expect(harness.manager.connectionState == .ready)
    }
  }

  @Test
  func `disconnect before the completion leaves the record clear`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      await harness.wifi.setConnectError(nil)
      await harness.wifi.holdConnect()
      let task = harness.startSwitch(to: .wifi)
      try await waitUntil("WiFi physical connect held") { await harness.wifi.connectIsHeld }
      let disconnect = harness.startDisconnect()
      try await waitUntil("disconnect clears switch presentation") { harness.appState.connectionLinkSwitch == nil }

      await harness.wifi.releaseConnect()
      await task.value
      await disconnect.value

      #expect(harness.appState.connectionLinkSwitch == nil)
      #expect(harness.manager.connectionState == .disconnected)
      #expect(harness.manager.currentTransportType == nil)
      #expect(!harness.appState.connectionUI.showingConnectionFailedAlert)
    }
  }

  @Test
  func `a nil live transport can start the checked link`() async throws {
    let peripheralID = UUID()
    try await ConnectionLinkSwitchHarness.withHarness(peripheralID: peripheralID) { harness in
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")
      #expect(harness.manager.currentTransportType == nil)
      await harness.bluetooth.holdConnect()
      let task = harness.startSwitch(to: .bluetooth, host: "10.9.9.9")
      try await waitUntil("Bluetooth physical connect held") { await harness.bluetooth.connectIsHeld }

      let record = try #require(harness.appState.connectionLinkSwitch)
      #expect(record.checked == .bluetooth)
      #expect(record.target == .bluetooth)
      #expect(record.wifi?.host == "10.0.0.8")
      #expect(record.bluetoothPeripheralID == peripheralID)
      #expect(await harness.bluetooth.connectCount == harness.initialBluetoothAttempts + 1)
      await harness.bluetooth.releaseConnect()
      await task.value
      #expect(harness.appState.connectionLinkSwitch?.target == nil)
    }
  }

  @Test
  func `a finished record clears once the radio is connected`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness { harness in
      harness.appState.connectionLinkSwitch = ConnectionLinkSwitch(
        radioID: harness.device.radioID, deviceID: harness.device.id, checked: .bluetooth,
        target: nil, token: UUID(), bluetoothPeripheralID: harness.device.bluetoothPeripheralID,
        wifi: ConnectionLinkWiFiDial(host: "10.0.0.8", port: ConnectionMethod.defaultWiFiPort)
      )

      harness.appState.reconcileConnectionLinkSwitch()

      #expect(harness.appState.connectionLinkSwitch == nil)
    }
  }

  @Test
  func `an in-progress attempt keeps the record for the same radio`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness(foreignWiFiRadio: true) { harness in
      let record = ConnectionLinkSwitch(
        radioID: harness.device.radioID, deviceID: harness.device.id, checked: .bluetooth,
        target: .wifi, token: UUID(), bluetoothPeripheralID: harness.device.bluetoothPeripheralID,
        wifi: ConnectionLinkWiFiDial(host: "10.0.0.8", port: ConnectionMethod.defaultWiFiPort)
      )
      harness.appState.connectionLinkSwitch = record
      harness.appState.reconcileConnectionLinkSwitch()
      #expect(harness.appState.connectionLinkSwitch == record)

      await harness.wifi.setConnectError(nil)
      _ = try await harness.manager.connectViaWiFi(host: "10.0.0.8", port: ConnectionMethod.defaultWiFiPort)
      harness.appState.reconcileConnectionLinkSwitch()
      #expect(harness.appState.connectionLinkSwitch == nil)
    }
  }

  @Test
  func `a completed wifi session returns to its saved host and port`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness(
      live: .wifi, wifiHost: "radio.local", wifiPort: 4242
    ) { harness in
      await harness.bluetooth.setConnectError(nil)
      await harness.appState.beginLinkSwitch(to: .bluetooth, snapshotWiFiHost: "10.9.9.9")
      #expect(harness.appState.connectionLinkSwitch == nil)
      #expect(harness.manager.currentTransportType == .bluetooth)

      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.1.2.3")

      #expect(await harness.wifi.connectCount == harness.initialWiFiAttempts + 1)
      #expect(await harness.wifi.connectionInfo?.host == "radio.local")
      #expect(await harness.wifi.connectionInfo?.port == 4242)
      #expect(harness.appState.connectionUI.showingConnectionFailedAlert)
    }
  }

  @Test
  func `a failed saved dial does not try the reported address`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness(wifiHost: "radio.example", wifiPort: 4242) { harness in
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

      #expect(await harness.wifi.connectCount == 1)
      #expect(await harness.wifi.connectionInfo?.host == "radio.example")
      #expect(await harness.wifi.connectionInfo?.port == 4242)
      #expect(await harness.bluetooth.connectCount == harness.initialBluetoothAttempts)
      #expect(harness.appState.connectionUI.showingConnectionFailedAlert)
      #expect(harness.appState.connectionLinkSwitch?.wifi == ConnectionLinkWiFiDial(host: "radio.example", port: 4242))
    }
  }

  @Test
  func `an unusable saved endpoint uses the reported address on the default port`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness(wifiHost: "bad host", wifiPort: 4242) { harness in
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

      #expect(await harness.wifi.connectCount == 1)
      #expect(await harness.wifi.connectionInfo?.host == "10.0.0.8")
      #expect(await harness.wifi.connectionInfo?.port == ConnectionMethod.defaultWiFiPort)
    }
  }

  @Test
  func `no usable wifi source does not dial`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness(wifiHost: "bad host", wifiPort: 4242) { harness in
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "999.1.1.1")

      #expect(harness.appState.connectionLinkSwitch == nil)
      #expect(await harness.wifi.connectCount == 0)
      #expect(!harness.appState.connectionUI.showingConnectionFailedAlert)
    }
  }

  @Test(arguments: [TransportType.bluetooth, .wifi])
  func `a failed switch can recover through either captured link`(_ source: TransportType) async throws {
    let peripheralID = UUID()
    let port: UInt16 = 4242
    try await ConnectionLinkSwitchHarness.withHarness(
      live: source, peripheralID: peripheralID, wifiHost: "radio.local", wifiPort: port
    ) { harness in
      let target: TransportType = source == .bluetooth ? .wifi : .bluetooth
      await harness.appState.beginLinkSwitch(to: target, snapshotWiFiHost: "10.9.9.9")
      var replaced = try #require(try await harness.manager.persistenceStore.fetchDevice(id: harness.device.id))
      replaced.connectionMethods = [
        .bluetooth(peripheralUUID: UUID(), displayName: nil),
        .wifi(host: "other.example", port: 9, displayName: nil)
      ]
      try await harness.manager.persistenceStore.saveDevice(replaced)

      try harness.expectRecoveryRow(source, peripheralID: peripheralID, port: port)
      await harness.appState.beginLinkSwitch(to: source, snapshotWiFiHost: "10.8.8.8")
      try harness.expectRecoveryRow(target, peripheralID: peripheralID, port: port)
      await harness.appState.beginLinkSwitch(to: target, snapshotWiFiHost: "10.1.2.3")

      let record = try #require(harness.appState.connectionLinkSwitch)
      #expect(record.checked == source)
      #expect(record.bluetoothPeripheralID == peripheralID)
      #expect(record.wifi == ConnectionLinkWiFiDial(host: "radio.local", port: port))
      #expect(await harness.bluetooth.connectCount == harness.initialBluetoothAttempts + (source == .bluetooth ? 1 : 2))
      #expect(await harness.wifi.connectCount == harness.initialWiFiAttempts + (source == .bluetooth ? 2 : 1))
      #expect(await harness.wifi.connectionInfo?.host == "radio.local")
      #expect(await harness.wifi.connectionInfo?.port == port)
    }
  }

  @Test
  func `a held attempt keeps its endpoint when saved data and the snapshot change`() async throws {
    try await ConnectionLinkSwitchHarness.withHarness(wifiHost: "  radio.local  ", wifiPort: 4242) { harness in
      await harness.wifi.holdConnect()
      let task = harness.startSwitch(to: .wifi)
      try await waitUntil("WiFi physical connect held") { await harness.wifi.connectIsHeld }
      var replaced = try #require(try await harness.manager.persistenceStore.fetchDevice(id: harness.device.id))
      replaced.connectionMethods = [.wifi(host: "other.example", port: 9, displayName: nil)]
      try await harness.manager.persistenceStore.saveDevice(replaced)
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.9.9.9")

      #expect(harness.appState.connectionLinkSwitch?.wifi == ConnectionLinkWiFiDial(host: "radio.local", port: 4242))
      #expect(await harness.wifi.connectCount == 1)
      await harness.wifi.releaseConnect()
      await task.value
      await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.8.8.8")
      #expect(await harness.wifi.connectCount == 2)
      #expect(await harness.wifi.connectionInfo?.host == "radio.local")
      #expect(await harness.wifi.connectionInfo?.port == 4242)
    }
  }
}
