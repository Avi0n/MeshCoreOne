import Foundation

/// Callbacks from the system pairing registry.
///
/// On iOS these fire via AccessorySetupKit (a device removed from Settings, or a
/// failed PIN entry). On macOS "Designed for iPad" there is no system pairing
/// registry, so these never fire — CoreBluetooth manages bonds at the OS level
/// without app-visible events.
@MainActor
public protocol DevicePairingDelegate: AnyObject {
  /// The user removed a device from the system pairing registry
  /// (iOS: Settings → Privacy & Security → Accessories).
  func devicePairing(_ service: any DevicePairingService, didRemoveDeviceWithID id: UUID)

  /// Pairing failed for a device (iOS: wrong PIN). The local record should be cleaned up.
  func devicePairing(_ service: any DevicePairingService, didFailPairingForDeviceWithID id: UUID)
}
