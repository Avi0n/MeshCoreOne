import Foundation
@testable import MC1Services
import Testing

/// Foreground health checks and Bluetooth power-on follow the saved transport.
/// Launch is the only path that falls back from WiFi to Bluetooth.
@Suite("Opportunistic Bluetooth reconnect follows the saved transport")
@MainActor
struct OpportunisticBluetoothStandDownTests {
  private func dualRow(id: UUID, wifiLast: Bool) -> DeviceDTO {
    let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: id, displayName: nil)
    let wifi = ConnectionMethod.wifi(host: "10.0.0.4", port: 5000, displayName: nil)
    return DeviceDTO.testDevice(id: id).copy {
      $0.connectionMethods = wifiLast ? [bluetooth, wifi] : [wifi, bluetooth]
    }
  }

  /// WiFi reconnect times out, then a foreground WiFi connect fails. The saved
  /// row and the intent to connect stay in place, and no transport is current.
  private func giveUpWiFiThenFailForegroundConnect(
    env: PairingTestEnvironment,
    device: DeviceDTO
  ) async throws {
    try await env.manager.persistenceStore.saveDevice(device)
    env.manager.persistConnection(deviceID: device.id, radioID: device.radioID, deviceName: device.nodeName)
    env.manager.testWiFiMaxReconnectDuration = .seconds(-1)
    env.manager.setTestState(
      connectionState: .ready,
      currentTransportType: .wifi,
      connectionIntent: .wantsConnection()
    )
    env.manager.wifiTransport = WiFiTransport()
    await env.manager.checkWiFiConnectionHealth()
    try await waitUntil("WiFi reconnect timeout should release the transport") {
      env.manager.wifiTransport == nil
        && env.manager.currentTransportType == nil
        && env.manager.wifiReconnectTask == nil
    }

    env.manager.testWiFiConnectHandler = { _, _ in
      throw WiFiTransportError.connectionFailed("down")
    }
    await #expect(throws: WiFiTransportError.self) {
      try await env.manager.connectViaWiFi(host: "10.0.0.4", port: 5000)
    }
    #expect(env.manager.currentTransportType == nil)
    #expect(env.manager.connectionIntent.wantsConnection)
  }

  @Test
  func `wifi last stands down the foreground check and bluetooth power on`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let peripheralID = UUID()
    let device = dualRow(id: peripheralID, wifiLast: true)
    try await giveUpWiFiThenFailForegroundConnect(env: env, device: device)
    await env.transport.setConnectError(BLEError.connectionFailed("down"))
    await env.manager.wireTransportHandlers()

    await env.manager.checkBLEConnectionHealth()
    let runsBefore = env.manager.testBluetoothPoweredOnHandlerRuns
    await env.stateMachine.simulateBluetoothPoweredOn()
    try await waitUntil("Bluetooth powered-on handler should finish") {
      env.manager.testBluetoothPoweredOnHandlerRuns > runsBefore
    }

    let dialed = await env.transport.connectInvocations
    #expect(dialed.isEmpty)
    let row = try await env.manager.persistenceStore.fetchDevice(id: peripheralID)
    #expect(row?.lastUsedConnectionMethod?.isWiFi == true)
  }

  @Test
  func `bluetooth last lets the powered-on handler dial the peripheral`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    let peripheralID = UUID()
    let device = dualRow(id: peripheralID, wifiLast: false)
    try await giveUpWiFiThenFailForegroundConnect(env: env, device: device)
    try await env.manager.persistenceStore.saveDevice(device)
    await env.transport.setConnectError(BLEError.connectionFailed("down"))
    // An inactive pairing session skips the accessory-list check, so the dial reaches the transport.
    env.accessorySetupKit.isSessionActive = false
    await env.manager.wireTransportHandlers()

    let runsBefore = env.manager.testBluetoothPoweredOnHandlerRuns
    await env.stateMachine.simulateBluetoothPoweredOn()
    try await waitUntil("Bluetooth powered-on handler should dial") {
      env.manager.testBluetoothPoweredOnHandlerRuns > runsBefore
    }

    let dialed = await env.transport.connectInvocations.compactMap(\.deviceID)
    #expect(dialed.contains(peripheralID))
  }
}
