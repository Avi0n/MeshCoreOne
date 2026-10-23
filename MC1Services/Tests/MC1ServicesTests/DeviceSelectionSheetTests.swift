import Foundation
@testable import MC1Services
import SwiftData
import Testing

/// The device picker dials `DeviceDTO.savedDeviceConnectTarget`.
@Suite("Device selection sheet transport")
struct DeviceSelectionSheetTests {
  @Test(arguments: [false, true])
  @MainActor
  func `persisted legacy methods ignore appended backup WiFi when choosing the last transport`(
    bluetoothWasLast: Bool
  ) async throws {
    let container = try PersistenceStore.createContainer(inMemory: true)
    let context = ModelContext(container)
    let peripheralID = UUID()
    let wifi = ConnectionMethod.wifi(host: "10.0.0.4", port: 5000, displayName: "Local")
    let bluetooth = ConnectionMethod.bluetooth(peripheralUUID: peripheralID, displayName: nil)
    let backupWiFi = ConnectionMethod.wifi(host: "10.0.0.99", port: 5000, displayName: "Backup")
    let original = DeviceDTO.testDevice(id: peripheralID).copy {
      $0.connectionMethods = bluetoothWasLast ? [wifi, bluetooth, backupWiFi] : [bluetooth, wifi, backupWiFi]
    }
    context.insert(Device(dto: original))
    try context.save()
    let store = PersistenceStore(modelContainer: container)

    let device = try #require(await store.fetchDevices().first)

    #expect(device.connectionMethods == original.connectionMethods)
    #expect(device.id == original.id)
    #expect(device.radioID == original.radioID)
    #expect(device.publicKey == original.publicKey)
    #expect(device.wifiConnectionMethod == wifi)
    #expect(device.bluetoothPeripheralID == peripheralID)
    #expect(device.lastUsedConnectionMethod == (bluetoothWasLast ? bluetooth : wifi))
    #expect(device.savedDeviceConnectTarget == (bluetoothWasLast
        ? .bluetooth(peripheralID: peripheralID)
        : .wifi(host: "10.0.0.4", port: 5000)))
  }

  @Test
  func `bluetooth then wifi dials wifi`() {
    let device = DeviceDTO.testDevice().copy {
      $0.connectionMethods = [
        .bluetooth(peripheralUUID: UUID(), displayName: nil),
        .wifi(host: "10.0.0.4", port: 5000, displayName: nil)
      ]
    }
    #expect(device.savedDeviceConnectTarget == .wifi(host: "10.0.0.4", port: 5000))
  }

  @Test
  func `wifi then bluetooth dials the peripheral and the id is that peripheral`() {
    let peripheralID = UUID()
    let device = DeviceDTO.testDevice(id: peripheralID).copy {
      $0.connectionMethods = [
        .wifi(host: "10.0.0.4", port: 5000, displayName: nil),
        .bluetooth(peripheralUUID: peripheralID, displayName: nil)
      ]
    }
    #expect(device.id == peripheralID)
    #expect(device.savedDeviceConnectTarget == .bluetooth(peripheralID: peripheralID))
  }

  @Test
  func `wifi only dials wifi`() {
    let device = DeviceDTO.testDevice().copy {
      $0.connectionMethods = [.wifi(host: "10.1.1.1", port: 5000, displayName: nil)]
    }
    #expect(device.savedDeviceConnectTarget == .wifi(host: "10.1.1.1", port: 5000))
  }

  @Test
  func `no methods dials the device id`() {
    let device = DeviceDTO.testDevice()
    #expect(device.connectionMethods.isEmpty)
    #expect(device.savedDeviceConnectTarget == .bluetooth(peripheralID: device.id))
  }
}
