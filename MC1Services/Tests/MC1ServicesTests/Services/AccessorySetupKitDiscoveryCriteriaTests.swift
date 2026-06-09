import Testing
@testable import MC1Services

@Suite("AccessorySetupKit Discovery Criteria Tests")
struct AccessorySetupKitDiscoveryCriteriaTests {

    @Test("supported Bluetooth name substrings match shipped MeshCore families")
    func supportedBluetoothNameSubstrings() {
        #expect(
            AccessorySetupKitDiscoveryCriteria.bluetoothNameSubstrings == [
                "MeshCore-",
                "Whisper-",
                "WisCore",
                "XIAO",
                "elecrow",
                "HT-n5262",
                "Seeed",
                "BQ",
                "ProMicro",
                "Keepteen",
                "Meshtiny",
                "T1000-E-BOOT",
                "me25ls01-BOOT",
                "NRF52 DK",
                "T-Impulse",
            ]
        )
    }

    @Test("all discovery criteria use the Nordic UART service UUID")
    func allDiscoveryCriteriaUseNordicUART() {
        let criteria = AccessorySetupKitDiscoveryCriteria.supportedBluetoothCriteria

        #expect(criteria.count == AccessorySetupKitDiscoveryCriteria.bluetoothNameSubstrings.count)
        #expect(criteria.allSatisfy { $0.bluetoothServiceUUID == BLEServiceUUID.nordicUART })
        #expect(Set(criteria.map(\.bluetoothNameSubstring)).count == criteria.count)
    }

    @Test("picker uses system default discovery instead of custom filtered discovery")
    func pickerUsesSystemDefaultDiscovery() {
        #expect(AccessorySetupKitDiscoveryCriteria.usesFilteredDiscovery == false)
    }
}
