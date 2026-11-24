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
  func `appState gates battery refresh loop on scene phase`() {
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

    appState.scenePhaseOverride = .background
    appState.batteryMonitor.startRefreshLoop(services: services, device: device)
    #expect(!appState.batteryMonitor.isRefreshLoopActiveForTesting)

    appState.scenePhaseOverride = .active
    appState.batteryMonitor.startRefreshLoop(services: services, device: device)
    #expect(appState.batteryMonitor.isRefreshLoopActiveForTesting)

    appState.batteryMonitor.stop()
    #expect(!appState.batteryMonitor.isRefreshLoopActiveForTesting)
  }

  @Test
  func `fetchDeviceBattery fires onBatteryChanged only when percentage changes`() async throws {
    let appState = AppState()
    defer { appState.shutdown() }
    let radioID = UUID()
    let radio = ConnectionTestRadioTransport()
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    let services = ServiceContainer(
      session: session,
      dataStore: appState.connectionManager.persistenceStore,
      radioID: radioID
    )
    let device = DeviceDTO(
      from: Device(radioID: radioID, publicKey: Data(repeating: 0x14, count: 32), nodeName: "BatteryRadio")
    )
    appState.connectionManager.persistConnection(deviceID: device.id, radioID: radioID, deviceName: "BatteryRadio")
    appState.connectionManager.setTestState(services: services, session: session, connectedDevice: device)

    var notifications: [Int] = []
    let ocv = appState.batteryMonitor.activeBatteryOCVArray(for: device)
    appState.batteryMonitor.onBatteryChanged = { battery in
      notifications.append(battery.percentage(using: ocv))
    }

    await appState.batteryMonitor.fetchDeviceBattery(services: services, device: device)
    #expect(notifications.count == 1)

    await appState.batteryMonitor.fetchDeviceBattery(services: services, device: device)
    #expect(notifications.count == 1)

    await radio.setResponsePackets(for: .getBattery, packets: [Data([ResponseCode.battery.rawValue, 0x68, 0x10])])
    await appState.batteryMonitor.fetchDeviceBattery(services: services, device: device)
    #expect(notifications.count == 2)
    #expect(notifications.last == 100)
  }

  @Test
  func `fetchBatteryIfOverdue backs off after a failed BLE read`() async throws {
    let appState = AppState()
    defer { appState.shutdown() }
    let radioID = UUID()
    let radio = ConnectionTestRadioTransport()
    let session = MeshCoreSession(transport: radio)
    try await session.start()
    let services = ServiceContainer(
      session: session,
      dataStore: appState.connectionManager.persistenceStore,
      radioID: radioID
    )
    let device = DeviceDTO(
      from: Device(radioID: radioID, publicKey: Data(repeating: 0x15, count: 32), nodeName: "BackoffRadio")
    )
    appState.connectionManager.persistConnection(deviceID: device.id, radioID: radioID, deviceName: "BackoffRadio")
    appState.connectionManager.setTestState(services: services, session: session, connectedDevice: device)

    let t0 = Date.now
    await radio.setSendError(MeshCoreError.notConnected, for: .getBattery)
    await appState.batteryMonitor.fetchBatteryIfOverdue(services: services, device: device, now: t0)
    let failedCount = await radio.failedSendCount
    let initialSentCount = await radio.sentData.count { $0.first == CommandCode.getBattery.rawValue }
    #expect(failedCount == 1)
    #expect(initialSentCount == 0)

    await radio.setSendError(nil, for: .getBattery)
    await appState.batteryMonitor.fetchBatteryIfOverdue(
      services: services,
      device: device,
      now: t0.addingTimeInterval(30)
    )
    let duringBackoffCount = await radio.sentData.count { $0.first == CommandCode.getBattery.rawValue }
    #expect(duringBackoffCount == 0)

    await appState.batteryMonitor.fetchBatteryIfOverdue(
      services: services,
      device: device,
      now: t0.addingTimeInterval(BatteryMonitor.backgroundFailureBackoffSeconds + 1)
    )
    let afterBackoffCount = await radio.sentData.count { $0.first == CommandCode.getBattery.rawValue }
    #expect(afterBackoffCount == 1)
  }
}
