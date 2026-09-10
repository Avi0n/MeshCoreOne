import Foundation

enum AccessorySetupKitDiscoveryCriteria {
  /// Opt into AccessorySetupKit filtered discovery (iOS 26.1+) so the picker can relabel
  /// each match with its advertised BLE name; older systems use the static picker label.
  static let usesFilteredDiscovery = true

  static let bluetoothServiceUUID = BLEServiceUUID.nordicUART
}
