import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("DeviceSelectionFilter Tests")
struct DeviceSelectionFilterTests {
  @Test
  func `WiFi-capable device is shown regardless of ASK registration`() {
    let device = makeDevice(connectionMethods: [
      .wifi(host: "10.0.0.2", port: 5000, displayName: "Home")
    ])

    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: []))
  }

  @Test
  func `BLE device registered with AccessorySetupKit is shown`() {
    let id = UUID()
    let device = makeDevice(
      id: id,
      connectionMethods: [.bluetooth(peripheralUUID: id, displayName: "Radio")]
    )

    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [id]))
  }

  @Test
  func `BLE device missing from ASK is hidden (imported shadow)`() {
    let device = makeDevice(connectionMethods: [
      .bluetooth(peripheralUUID: UUID(), displayName: "Radio")
    ])

    #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [UUID()]))
  }

  @Test
  func `Device with empty connection methods and no ASK entry is hidden`() {
    let device = makeDevice(connectionMethods: [])

    #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: []))
  }

  @Test
  func `Device with empty connection methods is shown when ASK knows its id`() {
    let id = UUID()
    let device = makeDevice(id: id, connectionMethods: [])

    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [id]))
  }

  @Test
  func `WiFi+BLE device is shown when its BLE id is missing from ASK`() {
    let device = makeDevice(connectionMethods: [
      .wifi(host: "10.0.0.2", port: 5000, displayName: "Home"),
      .bluetooth(peripheralUUID: UUID(), displayName: "Radio")
    ])

    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [UUID()]))
  }

  @Test
  func `macOS: BLE device with a stored bluetooth method is shown (no ASK registry)`() {
    let device = makeDevice(connectionMethods: [
      .bluetooth(peripheralUUID: UUID(), displayName: "Radio")
    ])

    // On macOS pairedAccessoryIDs is always empty; the stored method is the signal.
    #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [], hasSystemPairingRegistry: false))
  }

  @Test
  func `macOS: ghost/shadow with no bluetooth method is hidden`() {
    let device = makeDevice(connectionMethods: [])

    #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [], hasSystemPairingRegistry: false))
  }

  @Test
  func `macOS: a method-less device is hidden even when its id is in the registry set`() {
    let id = UUID()
    let device = makeDevice(id: id, connectionMethods: [])

    // Without a system pairing registry the stored method is the only signal, so a
    // populated pairedAccessoryIDs must not flip a method-less device to connectable.
    #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [id], hasSystemPairingRegistry: false))
  }

  private func makeDevice(
    id: UUID = UUID(),
    connectionMethods: [ConnectionMethod]
  ) -> DeviceDTO {
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
      connectionMethods: connectionMethods
    )
  }
}
