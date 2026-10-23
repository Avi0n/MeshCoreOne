import Foundation
@testable import MC1Services
import Testing

/// A WiFi give-up has to drop `wifiTransport`. `disconnect()` closes that socket
/// whenever it is set, including after a later Bluetooth session.
@Suite("WiFi give-up releases the transport")
@MainActor
struct ConnectionManagerWiFiGiveUpTests {
  private func armWiFiSession(_ manager: ConnectionManager) {
    manager.testWiFiMaxReconnectDuration = .seconds(-1)
    manager.setTestState(
      connectionState: .ready,
      currentTransportType: .wifi,
      connectionIntent: .wantsConnection()
    )
    manager.wifiTransport = WiFiTransport()
  }

  @Test
  func `reconnect timeout clears the wifi transport`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    armWiFiSession(env.manager)

    await env.manager.checkWiFiConnectionHealth()

    try await waitUntil("WiFi reconnect timeout should release the transport") {
      env.manager.wifiTransport == nil && env.manager.wifiReconnectTask == nil
    }
    #expect(env.manager.currentTransportType == nil)
    #expect(env.manager.connectionState == .disconnected)
  }

  @Test
  func `reconnect cooldown clears the wifi transport`() async throws {
    let env = try ConnectionManager.createForPairingTesting()
    defer { env.cleanup() }
    env.manager.testWiFiReconnectCooldown = 10000
    armWiFiSession(env.manager)

    await env.manager.checkWiFiConnectionHealth()
    try await waitUntil("first WiFi reconnect should finish") {
      env.manager.wifiTransport == nil && env.manager.wifiReconnectTask == nil
    }

    armWiFiSession(env.manager)
    await env.manager.checkWiFiConnectionHealth()

    try await waitUntil("cooldown suppression should release the transport") {
      env.manager.wifiTransport == nil && env.manager.currentTransportType == nil
    }
    #expect(env.manager.connectionState == .disconnected)
  }
}
