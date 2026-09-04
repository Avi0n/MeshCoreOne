#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1
@testable import MC1Services
import SwiftData
import Testing

#if canImport(UIKit)
  @Suite("DangerZone Forget cancellation")
  @MainActor
  struct DangerZoneViewModelForgetTests {
    @Test
    func `declining Remove Accessory does not set errorMessage`() async throws {
      let container = try PersistenceStore.createContainer(inMemory: true)
      let suiteName = "test.\(UUID().uuidString)"
      let defaults = try #require(UserDefaults(suiteName: suiteName))
      defer { UserDefaults().removePersistentDomain(forName: suiteName) }

      let deviceID = UUID()
      let device = makeDevice(id: deviceID)
      let mockASK = MockAccessorySetupKitService()
      mockASK.isSessionActive = true
      mockASK.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")
      ])
      mockASK.removeAccessoryError = NSError(
        domain: ASError.errorDomain,
        code: ASError.Code.userCancelled.rawValue
      )

      let manager = ConnectionManager(
        modelContainer: container,
        defaults: defaults,
        transport: MockMeshTransport(),
        pairing: AccessorySetupPairingService(accessorySetupKit: mockASK)
      )
      manager.setTestState(
        connectionState: .connected,
        connectedDevice: device,
        currentTransportType: .bluetooth,
        connectionIntent: .wantsConnection()
      )

      let viewModel = DangerZoneViewModel()
      viewModel.configure(
        settingsService: { nil },
        connectedDevice: { device },
        connectionManager: manager
      )

      let dismissed = await viewModel.forgetDevice(deleteData: false)

      #expect(dismissed == false)
      #expect(viewModel.errorMessage == nil)
      #expect(manager.connectionState == .connected)
      #expect(manager.connectedDevice?.id == deviceID)
      #expect(manager.connectionIntent.wantsConnection)
    }

    private func makeDevice(id: UUID) -> DeviceDTO {
      DeviceDTO(
        id: id,
        radioID: id,
        publicKey: Data(repeating: 0x01, count: 32),
        nodeName: "TestDevice",
        firmwareVersion: 9,
        firmwareVersionString: "v1.13.0",
        manufacturerName: "TestMfg",
        buildDate: "01 Jan 2025",
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
        isActive: false,
        ocvPreset: nil,
        customOCVArrayString: nil,
        connectionMethods: [.bluetooth(peripheralUUID: id, displayName: "Radio")]
      )
    }
  }
#endif
