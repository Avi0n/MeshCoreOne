import Foundation

/// A BLE peripheral surfaced by a device-discovery scan.
///
/// Produced by `ConnectionManager.startBLEScanning()` and consumed by the device
/// selection and macOS scan-picker UIs. Carries only what the picker needs — the
/// CoreBluetooth identifier used to connect, the advertised name to show the user,
/// and the signal strength.
public struct DiscoveredDevice: Identifiable, Sendable, Equatable {
  /// The peripheral's CoreBluetooth identifier. Becomes `Device.id` on connect.
  public let id: UUID
  /// The advertised local name, if the peripheral published one.
  public let name: String?
  /// The received signal strength indicator, in dBm.
  public let rssi: Int

  public init(id: UUID, name: String?, rssi: Int) {
    self.id = id
    self.name = name
    self.rssi = rssi
  }
}
