import Foundation
@testable import MC1Services
import Testing

@Suite("WiFi give-up releases the captured transport")
@MainActor
struct ConnectionManagerWiFiGiveUpTests {
  @Test
  func `owned reconnect timeout closes the socket and a subsequent connection is usable`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(.wifi)
      env.manager.testWiFiMaxReconnectDuration = .seconds(-1)
      await env.wifi.simulateRemoteLoss()
      try await waitUntil("bounded recovery should retire the socket") {
        env.manager.connectionState == .disconnected && env.manager.wifiReconnectTask == nil
      }
      #expect(env.manager.wifiTransport == nil)
      #expect(env.manager.currentTransportType == nil)
      #expect(await env.wifi.isConnected == false)
      _ = try await env.connect(.bluetooth)
      #expect(env.manager.connectionState == .ready)
      #expect(env.manager.currentTransportType == .bluetooth)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test
  func `cooldown after a second socket loss also releases its own socket`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      env.manager.testWiFiReconnectCooldown = 10000
      env.manager.testWiFiMaxReconnectDuration = .seconds(-1)
      _ = try await env.connect(.wifi)
      await env.wifi.simulateRemoteLoss()
      try await waitUntil("first recovery should give up") { env.manager.connectionState == .disconnected }
      _ = try await env.connect(.wifi)
      await env.wifi.simulateRemoteLoss()
      try await waitUntil("cooldown should finish retirement") {
        env.manager.connectionState == .disconnected && env.manager.connectionTeardownCount == 0
      }
      #expect(env.manager.wifiTransport == nil)
      #expect(await env.wifi.isConnected == false)
    } catch { await env.close(); throw error }
    await env.close()
  }
}
