/// User-actionable Bluetooth availability for the device pickers.
///
/// Collapses `CBManagerState` to the cases a person can act on: scanning can proceed, Bluetooth is
/// powered off, or the app is not authorized to use Bluetooth. Transient and unsupported states map
/// to `.ready` so a picker keeps showing its scanning state rather than a remedy the user cannot act on.
public enum BluetoothAvailability: Sendable {
  /// Bluetooth is usable, or in a transient state expected to resolve on its own.
  case ready
  /// Bluetooth is powered off; the user can turn it on in system settings.
  case poweredOff
  /// The app lacks Bluetooth permission; the user can grant it in system settings.
  case unauthorized
}
