import Foundation
@testable import MC1Services
import Testing

@Suite("Opportunistic reconnect follows request authority")
@MainActor
struct OpportunisticBluetoothStandDownTests {
  @Test(arguments: [false, true])
  func `admitted wifi remains authoritative after recovery gives up`(bluetoothLast: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      _ = try await env.connect(.wifi)
      let saved = try #require(env.manager.connectedDevice)
      env.manager.testWiFiMaxReconnectDuration = .seconds(-1)
      await env.wifi.simulateRemoteLoss()
      try await waitUntil("wifi should finish bounded recovery") { env.manager.connectionState == .disconnected }
      let requestID = env.manager.connectionRequestID
      let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: env.deviceID, displayName: nil)
      let wifi = ConnectionMethod.wifi(host: "radio.test", port: 5123, displayName: nil)
      let updated = saved.copy { $0.connectionMethods = bluetoothLast ? [wifi, bluetooth] : [bluetooth, wifi] }
      try await env.manager.persistenceStore.saveDevice(updated)
      env.manager.testLastConnectedDeviceID = saved.id
      await env.manager.checkBLEConnectionHealth()
      await env.manager.handleBluetoothPoweredOn()
      #expect(await env.ble.connectCount == 0)
      #expect(env.manager.connectionRequestID == requestID)
      #expect(env.manager.connectionState == .disconnected)
      #expect(env.manager.desiredRequest?.target == .wifi(host: "radio.test", port: 5123))
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [false, true], [false, true])
  func `without a request foreground and power on use saved last method`(bluetoothLast: Bool, powerOn: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: env.deviceID, displayName: nil)
      let wifi = ConnectionMethod.wifi(host: "radio.test", port: 5123, displayName: nil)
      let saved = DeviceDTO.testDevice(id: env.deviceID, publicKey: env.ble.publicKey, isActive: false).copy {
        $0.connectionMethods = bluetoothLast ? [wifi, bluetooth] : [bluetooth, wifi]
      }
      try await env.manager.persistenceStore.saveDevice(saved)
      env.manager.testLastConnectedDeviceID = saved.id
      env.manager.setTestState(connectionIntent: .wantsConnection())
      #expect(env.manager.connectionRequestID == nil)
      if powerOn {
        await env.manager.wireTransportHandlers()
        let handled = env.manager.testBluetoothPoweredOnHandlerRuns
        await env.stateMachine.simulateBluetoothPoweredOn()
        try await waitUntil("installed power callback should settle") { env.manager.testBluetoothPoweredOnHandlerRuns == handled + 1 }
      } else {
        await env.manager.appDidBecomeActive()
      }
      #expect(await env.ble.connectCount == (bluetoothLast ? 1 : 0))
      if bluetoothLast {
        #expect(env.manager.currentTransportType == .bluetooth)
        let probed = try await env.manager.session?.getTime() != nil
        #expect(probed)
      } else {
        #expect(env.manager.connectionState == .disconnected)
        #expect(env.manager.connectionRequestID == nil)
      }
    } catch { await env.close(); throw error }
    await env.close()
  }
}
