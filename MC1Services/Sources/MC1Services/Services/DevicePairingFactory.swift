import Foundation

/// Selects the `DevicePairingService` implementation for the current runtime.
///
/// This is the single place the app decides between the AccessorySetupKit pairing flow and
/// the macOS CoreBluetooth scan-picker flow. The decision is `ProcessInfo.processInfo.
/// isiOSAppOnMac` — `true` when the iOS binary is running on macOS via "Designed for iPad",
/// where AccessorySetupKit is present but non-functional. Keeping the branch here means no
/// `#if os(...)` or `isiOSAppOnMac` check leaks into `ConnectionManager` or the views.
enum DevicePairingFactory {
  /// Builds the pairing service appropriate for the current platform.
  @MainActor
  static func make() -> any DevicePairingService {
    if ProcessInfo.processInfo.isiOSAppOnMac {
      return BluetoothScanPairingService()
    }
    return AccessorySetupPairingService(accessorySetupKit: AccessorySetupKitService())
  }
}
