import Foundation
@testable import MC1Services
import Testing

@Suite("Launch last-used transport")
@MainActor
struct ConnectionManagerLaunchTransportTests {
  @Test
  func `bluetooth then wifi falls back only within the admitted launch request`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let device = savedDevice(env, wifiLast: false)
    try await env.manager.persistenceStore.saveDevice(device)
    env.manager.testLastConnectedDeviceID = device.id
    await env.ble.setConnectError(BLEError.authenticationFailed)
    await env.manager.reconnectLastUsedTransport(device: device, lastDeviceID: device.id)
    #expect(await env.wifi.connectCount == 1)
    #expect(env.manager.currentTransportType == .wifi)
    #expect(!env.manager.isReconnectionWatchdogRunning)
    await env.close()
  }

  @Test
  func `wifi then bluetooth dials the recorded peripheral after owned wifi failure`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let device = savedDevice(env, wifiLast: true)
    try await env.manager.persistenceStore.saveDevice(device)
    await env.wifi.setConnectError(BLEError.connectionFailed("unreachable"))
    await env.manager.reconnectLastUsedTransport(device: device, lastDeviceID: device.id)
    #expect(await env.ble.connectCount == 1)
    #expect(env.manager.currentTransportType == .bluetooth)
    #expect(env.manager.connectedDevice?.id == env.deviceID)
    await env.close()
  }

  @Test
  func `wifi only failure never dials bluetooth or starts its watchdog`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let device = DeviceDTO.testDevice().copy { $0.connectionMethods = [.wifi(host: "radio.test", port: 5123, displayName: nil)] }
    await env.wifi.setConnectError(BLEError.connectionFailed("unreachable"))
    await env.manager.reconnectLastUsedTransport(device: device, lastDeviceID: device.id)
    #expect(await env.ble.connectCount == 0)
    #expect(!env.manager.isReconnectionWatchdogRunning)
    await env.close()
  }

  @Test
  func `a row with no methods dials its Device ID`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let device = DeviceDTO.testDevice(id: env.deviceID)
    await env.manager.reconnectLastUsedTransport(device: device, lastDeviceID: device.id)
    #expect(await env.ble.connectCount == 1)
    #expect(env.manager.connectedDevice?.id == device.id)
    await env.close()
  }

  @Test
  func `foreground wifi recovery follows the saved last method`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    let device = savedDevice(env, wifiLast: true)
    try await env.manager.persistenceStore.saveDevice(device)
    env.manager.testLastConnectedDeviceID = device.id
    env.manager.setTestState(connectionIntent: .wantsConnection())
    await env.manager.checkWiFiConnectionHealth()
    #expect(await env.wifi.connectCount == 1)
    #expect(env.manager.connectionState == .ready)
    await env.close()
  }

  @Test
  func `switching to bluetooth closes the captured wifi socket before dialing`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(.wifi)
      await env.ble.holdConnect()
      let switchTask = Task { try await env.connect(.bluetooth) }
      do {
        try await waitUntil("BLE should reach its real dial") { await env.ble.connectIsHeld }
        #expect(await env.wifi.isConnected == false)
        #expect(env.manager.services == nil)
        await env.ble.releaseConnect()
        let result = try await switchTask.value
        #expect(env.manager.isCurrent(result))
      } catch {
        await env.ble.releaseAllGates()
        switchTask.cancel()
        _ = await switchTask.result
        throw error
      }
    } catch { await env.close(); throw error }
    await env.close()
  }

  private func savedDevice(_ env: ConnectionManagerRadioTestHarness, wifiLast: Bool) -> DeviceDTO {
    let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: env.deviceID, displayName: nil)
    let wifi = ConnectionMethod.wifi(host: "radio.test", port: 5123, displayName: nil)
    return DeviceDTO.testDevice(id: env.deviceID).copy { $0.connectionMethods = wifiLast ? [bluetooth, wifi] : [wifi, bluetooth] }
  }
}
