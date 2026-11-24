import Foundation
@testable import MC1
@testable import MC1Services
import MeshCore
import Testing

@Suite("Battery Monitoring Tests")
@MainActor
struct BatteryMonitoringTests {
  // MARK: - Default State

  @Test
  func `deviceBattery is nil by default`() {
    let appState = AppState()
    #expect(appState.batteryMonitor.deviceBattery == nil)
  }

  @Test
  func `activeBatteryOCVArray returns liIon default when no device connected`() {
    let appState = AppState()
    #expect(appState.batteryMonitor.activeBatteryOCVArray(for: appState.connectedDevice) == OCVPreset.liIon.ocvArray)
  }

  // MARK: - fetchDeviceBattery

  @Test
  func `fetchDeviceBattery is no-op when services is nil`() async {
    let appState = AppState()

    await appState.batteryMonitor.fetchDeviceBattery(services: appState.services, device: appState.connectedDevice)

    #expect(appState.batteryMonitor.deviceBattery == nil)
  }

  @Test
  func `fetchDeviceBattery does not crash when called on fresh state`() async {
    let appState = AppState()
    #expect(appState.services == nil)

    // Should not throw or crash
    await appState.batteryMonitor.fetchDeviceBattery(services: appState.services, device: appState.connectedDevice)
    #expect(appState.batteryMonitor.deviceBattery == nil)
  }

  // MARK: - Battery State Observation

  @Test
  func `deviceBattery can be set directly for testing`() {
    let appState = AppState()
    let battery = BatteryInfo(level: 3700)

    appState.batteryMonitor.deviceBattery = battery

    #expect(appState.batteryMonitor.deviceBattery == battery)
    #expect(appState.batteryMonitor.deviceBattery?.level == 3700)
  }

  @Test
  func `deviceBattery can be cleared`() {
    let appState = AppState()
    appState.batteryMonitor.deviceBattery = BatteryInfo(level: 3700)

    appState.batteryMonitor.deviceBattery = nil

    #expect(appState.batteryMonitor.deviceBattery == nil)
  }

  @Test
  func `appState gates battery refresh loop on foreground override`() {
    let appState = AppState()
    defer { appState.shutdown() }
    let radioID = UUID()
    let session = MeshCoreSession(transport: MockTransport())
    let services = ServiceContainer(
      session: session,
      dataStore: appState.connectionManager.persistenceStore,
      radioID: radioID
    )
    let device = DeviceDTO(
      from: Device(radioID: radioID, publicKey: Data(repeating: 0x13, count: 32), nodeName: "ForegroundRadio")
    )
    appState.connectionManager.persistConnection(deviceID: device.id, radioID: radioID, deviceName: "ForegroundRadio")
    appState.connectionManager.setTestState(services: services, session: session, connectedDevice: device)

    appState.isInForegroundOverride = false
    appState.batteryMonitor.startRefreshLoop(services: services, device: device)
    #expect(!appState.batteryMonitor.isRefreshLoopActiveForTesting)

    appState.isInForegroundOverride = true
    appState.batteryMonitor.startRefreshLoop(services: services, device: device)
    #expect(appState.batteryMonitor.isRefreshLoopActiveForTesting)

    appState.batteryMonitor.stop()
    #expect(!appState.batteryMonitor.isRefreshLoopActiveForTesting)
  }
}
