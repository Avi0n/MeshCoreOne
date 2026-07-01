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
}
