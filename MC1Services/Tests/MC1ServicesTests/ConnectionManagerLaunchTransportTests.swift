import Foundation
@testable import MC1Services
import MeshCore
import Testing

/// Launch reconnect on a simulator build returns before this path. These tests call
/// `reconnectLastUsedTransport`, which `activate()` uses on device.
@Suite("Launch last-used transport")
@MainActor
struct ConnectionManagerLaunchTransportTests {
  private func makeEnvironment() throws -> PairingTestEnvironment {
    let env = try ConnectionManager.createForPairingTesting()
    env.accessorySetupKit.isSessionActive = false
    return env
  }

  @Test
  func `bluetooth then wifi falls back to wifi when bluetooth throws and skips the watchdog`() async throws {
    let env = try makeEnvironment()
    defer { env.cleanup() }
    let peripheralID = UUID()
    let device = DeviceDTO.testDevice().copy {
      $0.connectionMethods = [
        .wifi(host: "10.0.0.8", port: 5000, displayName: nil),
        .bluetooth(peripheralUUID: peripheralID, displayName: nil)
      ]
    }
    try await env.manager.persistenceStore.saveDevice(device)
    env.manager.testLastConnectedDeviceID = device.id
    env.manager.testWiFiConnectHandler = { _, _ in }
    await env.transport.setConnectError(BLEError.connectionFailed("down"))

    await env.manager.reconnectLastUsedTransport(device: device, lastDeviceID: device.id)

    #expect(env.manager.testWiFiConnectAttempts.count == 1)
    #expect(env.manager.testWiFiConnectAttempts.first?.host == "10.0.0.8")
    #expect(env.manager.isReconnectionWatchdogRunning == false)
    env.manager.stopReconnectionWatchdog()
  }

  @Test
  func `wifi then bluetooth dials the peripheral when wifi throws`() async throws {
    let env = try makeEnvironment()
    defer { env.cleanup() }
    let peripheralID = UUID()
    let device = DeviceDTO.testDevice().copy {
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: peripheralID, displayName: nil),
        .wifi(host: "10.0.0.8", port: 5000, displayName: nil)
      ]
    }
    env.manager.testWiFiConnectHandler = { _, _ in throw BLEError.connectionFailed("wifi") }
    await env.transport.setConnectError(BLEError.connectionFailed("down"))

    await env.manager.reconnectLastUsedTransport(device: device, lastDeviceID: device.id)

    let dialed = await env.transport.connectInvocations.compactMap(\.deviceID)
    #expect(dialed.isEmpty == false)
    #expect(dialed.allSatisfy { $0 == peripheralID })
    #expect(dialed.contains(device.id) == false)
    env.manager.stopReconnectionWatchdog()
  }

  @Test
  func `wifi only does not dial bluetooth or start the watchdog`() async throws {
    let env = try makeEnvironment()
    defer { env.cleanup() }
    let device = DeviceDTO.testDevice().copy {
      $0.connectionMethods = [.wifi(host: "10.0.0.8", port: 5000, displayName: nil)]
    }
    env.manager.testWiFiConnectHandler = { _, _ in throw BLEError.connectionFailed("wifi") }

    await env.manager.reconnectLastUsedTransport(device: device, lastDeviceID: device.id)

    let dialed = await env.transport.connectInvocations
    #expect(dialed.isEmpty)
    #expect(env.manager.isReconnectionWatchdogRunning == false)
  }

  @Test
  func `no methods dials the device id`() async throws {
    let env = try makeEnvironment()
    defer { env.cleanup() }
    let device = DeviceDTO.testDevice()
    await env.transport.setConnectError(BLEError.connectionFailed("down"))

    await env.manager.reconnectLastUsedTransport(device: device, lastDeviceID: device.id)

    let dialed = await env.transport.connectInvocations.compactMap(\.deviceID)
    #expect(dialed.isEmpty == false)
    #expect(dialed.allSatisfy { $0 == device.id })
    env.manager.stopReconnectionWatchdog()
  }

  @Test
  func `foreground health check reconnects wifi only when it was last used`() async throws {
    let env = try makeEnvironment()
    defer { env.cleanup() }
    let wifiLast = DeviceDTO.testDevice().copy {
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: UUID(), displayName: nil),
        .wifi(host: "10.0.0.9", port: 5000, displayName: nil)
      ]
    }
    try await env.manager.persistenceStore.saveDevice(wifiLast)
    env.manager.testLastConnectedDeviceID = wifiLast.id
    env.manager.setTestState(connectionState: .disconnected, connectionIntent: .wantsConnection())
    env.manager.testWiFiConnectHandler = { _, _ in }

    await env.manager.checkWiFiConnectionHealth()
    #expect(env.manager.testWiFiConnectAttempts.count == 1)

    let bluetoothLast = wifiLast.copy {
      $0.connectionMethods = [
        .wifi(host: "10.0.0.9", port: 5000, displayName: nil),
        .bluetooth(peripheralUUID: UUID(), displayName: nil)
      ]
    }
    try await env.manager.persistenceStore.saveDevice(bluetoothLast)
    env.manager.testWiFiConnectAttempts = []
    await env.manager.checkWiFiConnectionHealth()
    #expect(env.manager.testWiFiConnectAttempts.isEmpty)
  }

  @Test
  func `bluetooth connect prep keeps the intent to connect`() async throws {
    let env = try makeEnvironment()
    defer { env.cleanup() }
    env.manager.setTestState(
      connectionState: .connected,
      currentTransportType: .some(.wifi),
      connectionIntent: .wantsConnection()
    )
    env.manager.wifiTransport = WiFiTransport()

    await env.manager.disconnect(reason: .bluetoothConnectPrep)

    #expect(env.manager.connectionIntent.wantsConnection)
    #expect(env.manager.connectionState == .disconnected)
    #expect(env.manager.wifiTransport == nil)
  }

  @Test
  func `connect via bluetooth drops wifi before dialing the peripheral`() async throws {
    let env = try makeEnvironment()
    defer { env.cleanup() }
    let peripheralID = UUID()
    env.manager.setTestState(
      connectionState: .connected,
      currentTransportType: .some(.wifi),
      connectionIntent: .wantsConnection()
    )
    env.manager.wifiTransport = WiFiTransport()
    await env.transport.setConnectError(BLEError.connectionFailed("down"))

    await #expect(throws: BLEError.self) {
      try await env.manager.connectViaBluetooth(peripheralID: peripheralID)
    }

    let dialed = await env.transport.connectInvocations.compactMap(\.deviceID)
    #expect(env.manager.wifiTransport == nil)
    #expect(dialed.first == peripheralID)
    #expect(env.manager.connectionIntent.wantsConnection)
  }
}
