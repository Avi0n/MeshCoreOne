import Foundation

// MARK: - BLE Service UUIDs

/// Nordic UART Service UUIDs for MeshCore device communication.
///
/// ## Naming Convention
/// TX/RX are named from the **central's perspective** (this app):
/// - TX = we transmit (write) to the peripheral
/// - RX = we receive (notifications) from the peripheral
///
/// This is inverted from the Nordic UART Service standard naming, which uses
/// the peripheral's perspective. The actual UUIDs are correct.
enum BLEServiceUUID {
  /// Nordic UART Service UUID
  static let nordicUART = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E"
  /// TX Characteristic - central writes to this (Nordic: RX Characteristic)
  static let txCharacteristic = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E"
  /// RX Characteristic - central receives notifications (Nordic: TX Characteristic)
  static let rxCharacteristic = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E"
}
