@testable import MC1Services
import Testing

@Suite("AccessorySetupKit Discovery Criteria Tests")
struct AccessorySetupKitDiscoveryCriteriaTests {
  @Test
  func `supported Bluetooth name substrings match shipped MeshCore families`() {
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

  @Test
  func `all discovery criteria use the Nordic UART service UUID`() {
    let criteria = AccessorySetupKitDiscoveryCriteria.supportedBluetoothCriteria

    #expect(criteria.count == AccessorySetupKitDiscoveryCriteria.bluetoothNameSubstrings.count)
    #expect(criteria.allSatisfy { $0.bluetoothServiceUUID == BLEServiceUUID.nordicUART })
    #expect(Set(criteria.map(\.bluetoothNameSubstring)).count == criteria.count)
  }

  @Test
  func `picker opts into filtered discovery so matches can be relabeled with advertised names`() {
    #expect(AccessorySetupKitDiscoveryCriteria.usesFilteredDiscovery == true)
  }
}
