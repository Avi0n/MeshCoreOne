@testable import MC1Services
import Testing

@Suite("AccessorySetupKit Discovery Criteria Tests")
struct AccessorySetupKitDiscoveryCriteriaTests {
  @Test
  func `picker discovers by Nordic UART service UUID only`() {
    #expect(
      AccessorySetupKitDiscoveryCriteria.bluetoothServiceUUID == BLEServiceUUID.nordicUART
    )
    #expect(
      AccessorySetupKitDiscoveryCriteria.bluetoothServiceUUID
        == "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
    )
  }

  @Test
  func `picker opts into filtered discovery so matches can be relabeled with advertised names`() {
    #expect(AccessorySetupKitDiscoveryCriteria.usesFilteredDiscovery == true)
  }
}
