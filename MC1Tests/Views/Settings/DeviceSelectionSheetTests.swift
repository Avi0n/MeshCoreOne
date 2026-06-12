import Foundation
import Testing
@testable import MC1Services

@testable import MC1

@Suite("DeviceSelectionFilter Tests")
struct DeviceSelectionFilterTests {

    @Test("WiFi-capable device is shown regardless of ASK registration")
    func wifiCapableDeviceShown() {
        let device = makeDevice(connectionMethods: [
            .wifi(host: "10.0.0.2", port: 5000, displayName: "Home")
        ])

        #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: []))
    }

    @Test("BLE device registered with AccessorySetupKit is shown")
    func bleDeviceRegisteredInASKShown() {
        let id = UUID()
        let device = makeDevice(
            id: id,
            connectionMethods: [.bluetooth(peripheralUUID: id, displayName: "Radio")]
        )

        #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [id]))
    }

    @Test("BLE device missing from ASK is hidden (imported shadow)")
    func bleDeviceNotInASKHidden() {
        let device = makeDevice(connectionMethods: [
            .bluetooth(peripheralUUID: UUID(), displayName: "Radio")
        ])

        #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [UUID()]))
    }

    @Test("Device with empty connection methods and no ASK entry is hidden")
    func ghostWithStrippedMethodsHidden() {
        let device = makeDevice(connectionMethods: [])

        #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: []))
    }

    @Test("Device with empty connection methods is shown when ASK knows its id")
    func legacyDeviceInASKShown() {
        let id = UUID()
        let device = makeDevice(id: id, connectionMethods: [])

        #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [id]))
    }

    @Test("WiFi+BLE device is shown when its BLE id is missing from ASK")
    func wifiPlusBLEShownWhenBLENotInASK() {
        let device = makeDevice(connectionMethods: [
            .wifi(host: "10.0.0.2", port: 5000, displayName: "Home"),
            .bluetooth(peripheralUUID: UUID(), displayName: "Radio")
        ])

        #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [UUID()]))
    }

    @Test("macOS: BLE device with a stored bluetooth method is shown (no ASK registry)")
    func macBLEDeviceWithMethodShown() {
        let device = makeDevice(connectionMethods: [
            .bluetooth(peripheralUUID: UUID(), displayName: "Radio")
        ])

        // On macOS pairedAccessoryIDs is always empty; the stored method is the signal.
        #expect(DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [], hasSystemPairingRegistry: false))
    }

    @Test("macOS: ghost/shadow with no bluetooth method is hidden")
    func macGhostWithoutMethodHidden() {
        let device = makeDevice(connectionMethods: [])

        #expect(!DeviceSelectionFilter.isConnectable(device, pairedAccessoryIDs: [], hasSystemPairingRegistry: false))
    }

    @Test("macOS: a method-less device is hidden even when its id is in the registry set")
    func macMethodLessDeviceIgnoresRegistry() {
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
