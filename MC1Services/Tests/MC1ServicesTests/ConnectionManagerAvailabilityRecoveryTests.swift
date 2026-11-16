import Foundation
@testable import MC1Services
import Testing

@Suite("Bluetooth availability and launch recovery")
@MainActor
struct ConnectionManagerAvailabilityRecoveryTests {
  @Test(arguments: [BLEError.bluetoothPoweredOff, .bluetoothUnavailable, .bluetoothUnauthorized], [false, true])
  func `availability recovery within cooldown installs a working session`(error: BLEError, dualTransport: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      await env.manager.wireTransportHandlers()
      let saved = savedDevice(env, wifiLast: dualTransport ? false : nil)
      try await env.manager.persistenceStore.saveDevice(saved)
      await env.wifi.setConnectError(WiFiTransportError.notConnected)
      await env.ble.setConnectError(error)
      await env.manager.reconnectLastUsedTransport(device: saved, lastDeviceID: saved.id)
      try #require(env.manager.connectionState == .disconnected)
      #expect(await env.ble.connectCount == 1)
      #expect(await env.wifi.connectCount == (dualTransport ? 1 : 0))
      #expect(env.manager.isReconnectionWatchdogRunning)
      #expect(env.manager.shouldAllowConnection(force: false), "Bluetooth availability must not consume the retry cooldown")

      let failedRequestID = env.manager.connectionRequestID
      await env.ble.setConnectError(nil)
      let handled = env.manager.testBluetoothPoweredOnHandlerRuns
      await env.stateMachine.simulateBluetoothPoweredOn()
      try await waitUntil("installed power-on callback must finish its recovery request") {
        env.manager.testBluetoothPoweredOnHandlerRuns == handled + 1
      }

      #expect(env.manager.connectionRequestID != failedRequestID)
      #expect(await env.ble.connectCount == 2)
      #expect(env.manager.connectionState == .ready)
      #expect(env.manager.connectedDevice?.radioID == saved.radioID)
      let session = try #require(env.manager.session)
      #expect(try await session.getTime().timeIntervalSince1970 > 0)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test
  func `failed owned power-on recovery keeps a watchdog that can exchange frames`() async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      await env.manager.wireTransportHandlers()
      let saved = savedDevice(env, wifiLast: nil)
      try await env.manager.persistenceStore.saveDevice(saved)
      await env.ble.setConnectError(BLEError.deviceNotFound)
      await env.manager.reconnectLastUsedTransport(device: saved, lastDeviceID: saved.id)
      try #require(env.manager.isReconnectionWatchdogRunning)
      env.manager.stopReconnectionWatchdog()
      env.manager.setCircuitBreakerOpenForTesting(since: .distantPast)
      env.manager.testWatchdogInitialDelay = .milliseconds(1)

      let failedLaunchRequestID = env.manager.connectionRequestID
      var failedPowerOnRequestID: UUID?
      env.manager.onConnectionLost = { receipt in
        env.manager.onConnectionLost = nil
        failedPowerOnRequestID = receipt.requestID
        #expect(env.manager.connectionRequestID == receipt.requestID)
        #expect(receipt.requestID != failedLaunchRequestID)
        #expect(await env.ble.connectCount == 2)
        #expect(env.manager.connectionState == .disconnected)
        #expect(!env.manager.isReconnectionWatchdogRunning)
        await env.ble.setConnectError(nil)
        env.manager.setCircuitBreakerOpenForTesting(since: .distantPast)
      }
      let handled = env.manager.testBluetoothPoweredOnHandlerRuns
      await env.stateMachine.simulateBluetoothPoweredOn()
      try await waitUntil(timeout: .seconds(3), "owned power-on failure must re-arm a watchdog that establishes a usable session") {
        env.manager.connectionState == .ready
      }

      let exhaustedRequestID = try #require(failedPowerOnRequestID)
      #expect(env.manager.testBluetoothPoweredOnHandlerRuns == handled + 1)
      #expect(env.manager.connectionRequestID != exhaustedRequestID)
      #expect(await env.ble.connectCount == 3)
      #expect(env.manager.connectedDevice?.radioID == saved.radioID)
      let session = try #require(env.manager.session)
      #expect(try await session.getTime().timeIntervalSince1970 > 0)
    } catch { await env.close(); throw error }
    await env.close()
  }

  @Test(arguments: [false, true])
  func `exhausted dual-transport launch retains Bluetooth watchdog recovery`(wifiLast: Bool) async throws {
    let env = try ConnectionManagerRadioTestHarness()
    do {
      let saved = savedDevice(env, wifiLast: wifiLast)
      try await env.manager.persistenceStore.saveDevice(saved)
      await env.ble.setConnectError(BLEError.deviceNotFound)
      await env.wifi.setConnectError(WiFiTransportError.notConnected)
      env.manager.testWatchdogInitialDelay = .milliseconds(1)
      var failedRequestID: UUID?
      env.manager.onConnectionLost = { receipt in
        env.manager.onConnectionLost = nil
        failedRequestID = receipt.requestID
        let bluetooth = ConnectionRequest.Target.bluetooth(env.deviceID)
        let wifi = ConnectionRequest.Target.wifi(host: "radio.test", port: 5123)
        #expect(env.manager.desiredRequest?.id == receipt.requestID)
        #expect(env.manager.desiredRequest?.targets == (wifiLast ? [wifi, bluetooth] : [bluetooth, wifi]))
        #expect(env.manager.desiredRequest?.target == bluetooth, "An exhausted launch must retain the Bluetooth target its watchdog can retry")
        #expect(await env.ble.connectCount == 1)
        #expect(await env.wifi.connectCount == 1)
        #expect(env.manager.connectionState == .disconnected)
        await env.ble.setConnectError(nil)
        env.manager.setCircuitBreakerOpenForTesting(since: .distantPast)
      }
      await env.manager.reconnectLastUsedTransport(device: saved, lastDeviceID: saved.id)
      try await waitUntil(timeout: .seconds(3), "launch watchdog must recover Bluetooth after both transports fail") {
        env.manager.connectionState == .ready
      }

      let exhaustedRequestID = try #require(failedRequestID)
      #expect(await env.ble.connectCount == 2)
      #expect(await env.wifi.connectCount == 1)
      #expect(env.manager.connectionRequestID != exhaustedRequestID)
      #expect(env.manager.currentTransportType == .bluetooth)
      #expect(env.manager.connectedDevice?.radioID == saved.radioID)
      let session = try #require(env.manager.session)
      #expect(try await session.getTime().timeIntervalSince1970 > 0)
    } catch { await env.close(); throw error }
    await env.close()
  }

  private func savedDevice(_ env: ConnectionManagerRadioTestHarness, wifiLast: Bool?) -> DeviceDTO {
    let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: env.deviceID, displayName: nil)
    let wifi = ConnectionMethod.wifi(host: "radio.test", port: 5123, displayName: nil)
    return DeviceDTO.testDevice(id: env.deviceID, publicKey: env.ble.publicKey, isActive: false).copy {
      if let wifiLast {
        $0.connectionMethods = wifiLast ? [bluetooth, wifi] : [wifi, bluetooth]
      } else {
        $0.connectionMethods = [bluetooth]
      }
    }
  }
}
