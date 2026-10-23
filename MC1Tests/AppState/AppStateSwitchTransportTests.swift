import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("AppState transport switch")
@MainActor
struct AppStateSwitchTransportTests {
  @Test
  func `a timeout shows the connection failed alert`() async throws {
    let harness = try makeHarness()
    defer { harness.appState.shutdown() }
    harness.manager.testWiFiConnectHandler = { _, _ in
      throw BLEError.connectionTimeout
    }

    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

    #expect(harness.appState.connectionUI.showingConnectionFailedAlert)
    #expect(harness.appState.connectionUI.failedPairingDeviceID == nil)
    #expect(harness.appState.connectionLinkSwitch?.checked == .bluetooth)
    #expect(harness.appState.connectionLinkSwitch?.target == nil)
  }

  @Test
  func `an authentication failure records the passed device`() async throws {
    let harness = try makeHarness()
    defer { harness.appState.shutdown() }
    harness.manager.testWiFiConnectHandler = { _, _ in
      throw BLEError.authenticationFailed
    }

    await harness.appState.beginLinkSwitch(to: .wifi, snapshotWiFiHost: "10.0.0.8")

    #expect(harness.appState.connectionUI.failedPairingDeviceID == harness.device.id)
    #expect(harness.appState.connectionLinkSwitch?.checked == .bluetooth)
    #expect(harness.appState.connectionLinkSwitch?.target == nil)
  }

  @MainActor
  private struct Harness {
    let appState: AppState
    let device: DeviceDTO
    var manager: ConnectionManager {
      appState.connectionManager
    }
  }

  private func makeHarness() throws -> Harness {
    let suite = "test.switchtransport.\(UUID().uuidString)"
    let defaults = try #require(UserDefaults(suiteName: suite))
    defaults.removePersistentDomain(forName: suite)
    let appState = try AppState(
      modelContainer: PersistenceStore.createContainer(inMemory: true),
      isPlaceholder: true,
      defaults: defaults
    )
    let peripheralID = UUID()
    let device = DeviceDTO(
      id: UUID(),
      publicKey: Data(repeating: 0xB1, count: ProtocolLimits.publicKeySize),
      nodeName: "Relay",
      firmwareVersion: 11,
      firmwareVersionString: "1.15.0",
      manufacturerName: "Test",
      buildDate: "2025-01-01",
      maxContacts: 100,
      maxChannels: 8,
      frequency: 915_000,
      bandwidth: 250_000,
      spreadingFactor: 10,
      codingRate: 5,
      txPower: 20,
      maxTxPower: 20,
      latitude: 0,
      longitude: 0,
      blePin: 0,
      manualAddContacts: false,
      multiAcks: 2,
      telemetryModeBase: 2,
      telemetryModeLoc: 0,
      telemetryModeEnv: 0,
      advertLocationPolicy: 0,
      lastConnected: Date(),
      lastContactSync: 0,
      isActive: true,
      ocvPreset: nil,
      customOCVArrayString: nil,
      connectionMethods: [.bluetooth(peripheralUUID: peripheralID, displayName: nil)]
    )
    appState.connectionManager.setTestState(
      connectionState: .connected,
      connectedDevice: .some(device),
      currentTransportType: .bluetooth
    )
    appState.connectionManager.testWiFiConnectHandler = { _, _ in
      throw BLEError.connectionTimeout
    }
    return Harness(appState: appState, device: device)
  }
}
