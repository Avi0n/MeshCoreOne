import Foundation
@testable import MC1Services

extension DeviceDTO {

    /// Creates a DeviceDTO with sensible test defaults. Override specific fields with `copy {}`.
    ///
    /// Usage:
    /// ```
    /// let device = DeviceDTO.testDevice()
    /// let custom = DeviceDTO.testDevice(id: myID).copy { $0.firmwareVersion = 10 }
    /// ```
    static func testDevice(
        id: UUID = UUID(),
        radioID: UUID = UUID(),
        publicKey: Data = Data(repeating: 0x01, count: 32),
        nodeName: String = "TestDevice",
        firmwareVersion: UInt8 = 9,
        firmwareVersionString: String = "v1.13.0",
        maxContacts: UInt16 = 100,
        maxChannels: UInt8 = 8,
        frequency: UInt32 = 915_000,
        bandwidth: UInt32 = 250_000,
        spreadingFactor: UInt8 = 10,
        codingRate: UInt8 = 5,
        txPower: Int8 = 20,
        maxTxPower: Int8 = 20,
        manualAddContacts: Bool = false,
        multiAcks: UInt8 = 2,
        lastConnected: Date = Date(),
        lastContactSync: UInt32 = 0,
        isActive: Bool = true
    ) -> DeviceDTO {
        DeviceDTO(
            id: id,
            radioID: radioID,
            publicKey: publicKey,
            nodeName: nodeName,
            firmwareVersion: firmwareVersion,
            firmwareVersionString: firmwareVersionString,
            manufacturerName: "TestMfg",
            buildDate: "01 Jan 2025",
            maxContacts: maxContacts,
            maxChannels: maxChannels,
            frequency: frequency,
            bandwidth: bandwidth,
            spreadingFactor: spreadingFactor,
            codingRate: codingRate,
            txPower: txPower,
            maxTxPower: maxTxPower,
            latitude: 0,
            longitude: 0,
            blePin: 0,
            manualAddContacts: manualAddContacts,
            multiAcks: multiAcks,
            telemetryModeBase: 2,
            telemetryModeLoc: 0,
            telemetryModeEnv: 0,
            advertLocationPolicy: 0,
            lastConnected: lastConnected,
            lastContactSync: lastContactSync,
            isActive: isActive,
            ocvPreset: nil,
            customOCVArrayString: nil
        )
    }
}
