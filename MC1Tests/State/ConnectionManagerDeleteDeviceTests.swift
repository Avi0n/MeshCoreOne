#if canImport(UIKit)
  import AccessorySetupKit
#endif
import Foundation
@testable import MC1Services
import Testing

#if canImport(UIKit)
  @Suite("Connect Device Delete forgets ASK")
  @MainActor
  struct ConnectionManagerDeleteDeviceTests {
    @Test
    func `deleteDevice removes the ASK accessory then demotes the row`() async throws {
      let env = try makeEnvironment()
      defer { env.cleanup() }
      let deviceID = UUID()
      let publicKey = Data(repeating: 0xA1, count: 32)
      let device = makeDevice(id: deviceID, publicKey: publicKey)

      try await env.manager.persistenceStore.saveDevice(device)
      env.mockASK.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")
      ])

      try await env.manager.deleteDevice(id: deviceID)

      #expect(env.mockASK.removeAccessoryCallCount == 1)
      #expect(env.mockASK.lastRemovedDeviceID == deviceID)
      #expect(env.mockASK.accessory(for: deviceID) == nil)
      #expect(try await env.manager.persistenceStore.fetchDevice(id: deviceID) == nil)
      let ghost = try await env.manager.persistenceStore.fetchDevice(publicKey: publicKey)
      #expect(ghost != nil)
      #expect(ghost?.id != deviceID)
      #expect(ghost?.radioID == device.radioID)
      #expect(ghost?.connectionMethods.isEmpty == true)
    }

    @Test
    func `deleteDevice decline keeps the live radio connected`() async throws {
      let env = try makeEnvironment()
      defer { env.cleanup() }
      let deviceID = UUID()
      let device = makeDevice(id: deviceID)

      try await env.manager.persistenceStore.saveDevice(device)
      env.mockASK.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")
      ])
      env.mockASK.removeAccessoryError = NSError(
        domain: ASError.errorDomain,
        code: ASError.Code.userCancelled.rawValue
      )
      env.manager.setTestState(
        connectionState: .connected,
        connectedDevice: device,
        currentTransportType: .bluetooth,
        connectionIntent: .wantsConnection()
      )

      try await #expect {
        try await env.manager.deleteDevice(id: deviceID)
      } throws: { error in
        guard let pairingError = error as? DevicePairingError else { return false }
        if case .cancelled = pairingError { return true }
        return false
      }

      #expect(env.mockASK.removeAccessoryCallCount == 1)
      #expect(env.mockASK.accessory(for: deviceID) != nil)
      #expect(try await env.manager.persistenceStore.fetchDevice(id: deviceID)?.id == deviceID)
      #expect(env.manager.connectionState == .connected)
      #expect(env.manager.connectedDevice?.id == deviceID)
      #expect(env.manager.connectionIntent.wantsConnection)
    }

    @Test
    func `deleteDevice with no ASK accessory still demotes`() async throws {
      let env = try makeEnvironment()
      defer { env.cleanup() }
      let deviceID = UUID()
      let publicKey = Data(repeating: 0xA2, count: 32)
      let device = makeDevice(id: deviceID, publicKey: publicKey).copy {
        $0.connectionMethods = [.wifi(host: "10.0.0.2", port: 5000, displayName: "Home")]
      }

      try await env.manager.persistenceStore.saveDevice(device)

      try await env.manager.deleteDevice(id: deviceID)

      #expect(env.mockASK.removeAccessoryCallCount == 0)
      #expect(try await env.manager.persistenceStore.fetchDevice(id: deviceID) == nil)
      let ghost = try await env.manager.persistenceStore.fetchDevice(publicKey: publicKey)
      #expect(ghost?.connectionMethods.isEmpty == true)
    }

    @Test
    func `deleteDevice of the live radio disconnects and clears user intent`() async throws {
      let env = try makeEnvironment()
      defer { env.cleanup() }
      let deviceID = UUID()
      let device = makeDevice(id: deviceID)

      try await env.manager.persistenceStore.saveDevice(device)
      env.mockASK.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")
      ])
      env.manager.setTestState(
        connectionState: .connected,
        connectedDevice: device,
        currentTransportType: .bluetooth,
        connectionIntent: .wantsConnection()
      )

      try await env.manager.deleteDevice(id: deviceID)

      #expect(env.manager.connectedDevice == nil)
      #expect(env.manager.connectionIntent.isUserDisconnected)
    }

    @Test
    func `deleteDevice of a sibling radio does not disconnect the live radio`() async throws {
      let env = try makeEnvironment()
      defer { env.cleanup() }
      let liveID = UUID()
      let siblingID = UUID()
      let live = makeDevice(id: liveID, publicKey: Data(repeating: 0xB1, count: 32))
      let sibling = makeDevice(id: siblingID, publicKey: Data(repeating: 0xB2, count: 32))

      try await env.manager.persistenceStore.saveDevice(live)
      try await env.manager.persistenceStore.saveDevice(sibling)
      env.mockASK.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: liveID, displayName: "Live"),
        ASAccessory(bluetoothIdentifier: siblingID, displayName: "Sibling")
      ])
      env.manager.setTestState(
        connectionState: .connected,
        connectedDevice: live,
        currentTransportType: .bluetooth,
        connectionIntent: .wantsConnection(),
        isPairingFlowActive: true
      )

      try await env.manager.deleteDevice(id: siblingID)

      #expect(env.manager.connectedDevice?.id == liveID)
      #expect(env.manager.connectionIntent.wantsConnection)
      #expect(env.manager.isPairingFlowActive == true)
      #expect(env.mockASK.accessory(for: liveID) != nil)
      #expect(env.mockASK.accessory(for: siblingID) == nil)
    }

    @Test
    func `deleteDevice of an in-flight connect tears down the attempt`() async throws {
      let env = try makeEnvironment()
      defer { env.cleanup() }
      let deviceID = UUID()
      let device = makeDevice(id: deviceID)

      try await env.manager.persistenceStore.saveDevice(device)
      env.mockASK.setPairedAccessories([
        ASAccessory(bluetoothIdentifier: deviceID, displayName: "Radio")
      ])
      env.manager.setTestState(
        connectionState: .connecting,
        currentTransportType: .bluetooth,
        connectionIntent: .wantsConnection(),
        connectingDeviceID: deviceID
      )

      try await env.manager.deleteDevice(id: deviceID)

      #expect(env.manager.connectingDeviceID == nil)
      #expect(env.manager.connectionState == .disconnected)
      #expect(env.manager.connectionIntent.isUserDisconnected)
      #expect(env.mockASK.accessory(for: deviceID) == nil)
    }

    private struct Environment {
      let manager: ConnectionManager
      let mockASK: MockAccessorySetupKitService
      let cleanup: () -> Void
    }

    private func makeEnvironment() throws -> Environment {
      let container = try PersistenceStore.createContainer(inMemory: true)
      let suiteName = "test.\(UUID().uuidString)"
      let defaults = try #require(UserDefaults(suiteName: suiteName))
      let mockASK = MockAccessorySetupKitService()
      mockASK.isSessionActive = true
      let manager = ConnectionManager(
        modelContainer: container,
        defaults: defaults,
        transport: MockMeshTransport(),
        pairing: AccessorySetupPairingService(accessorySetupKit: mockASK)
      )
      return Environment(
        manager: manager,
        mockASK: mockASK,
        cleanup: { UserDefaults().removePersistentDomain(forName: suiteName) }
      )
    }

    private func makeDevice(
      id: UUID,
      publicKey: Data = Data(repeating: 0x01, count: 32)
    ) -> DeviceDTO {
      DeviceDTO(
        id: id,
        radioID: id,
        publicKey: publicKey,
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
