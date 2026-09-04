import Foundation

/// Platform-neutral control-flow signals from the device-pairing seam (`DevicePairingService`
/// and `ConnectionManager.pairNewDevice()`), so app-layer call sites handle pairing
/// cancellation and re-entry without importing AccessorySetupKit's error type.
///
/// The iOS adapter (`AccessorySetupPairingService`) translates the equivalent
/// `AccessorySetupKitError` cases into these at the seam boundary; the macOS scan picker
/// throws them directly.
public enum DevicePairingError: LocalizedError, Sendable {
  /// The user dismissed the discovery picker, or declined iOS Remove Accessory
  /// (`ASError.Code.userCancelled`). A benign cancellation, not a failure.
  case cancelled

  /// A pairing flow is already running; the re-entrant request was ignored.
  case alreadyInProgress

  /// ASK rejected `showPicker` because the app cannot present it yet
  /// (`pickerRestricted` / `sessionNotActive`). Usually Remove Accessory still
  /// owns the scene. Retry `pairNewDevice` once the app is active; do not
  /// surface a connection-failed alert.
  case pickerUnavailable

  public var errorDescription: String? {
    switch self {
    case .cancelled:
      "Device selection was cancelled."
    case .alreadyInProgress:
      "Device pairing is already in progress."
    case .pickerUnavailable:
      "Device picker is temporarily unavailable."
    }
  }
}
