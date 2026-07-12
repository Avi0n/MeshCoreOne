import Foundation

/// Platform-neutral control-flow signals from the device-pairing seam (`DevicePairingService`
/// and `ConnectionManager.pairNewDevice()`), so app-layer call sites handle pairing
/// cancellation and re-entry without importing AccessorySetupKit's error type.
///
/// The iOS adapter (`AccessorySetupPairingService`) translates the equivalent
/// `AccessorySetupKitError` cases into these at the seam boundary; the macOS scan picker
/// throws them directly.
public enum DevicePairingError: LocalizedError, Sendable {
  /// The user dismissed the discovery picker (the AccessorySetupKit system picker on iOS,
  /// the in-app scan sheet on macOS). A benign cancellation, not a failure.
  case cancelled

  /// A pairing flow is already running; the re-entrant request was ignored.
  case alreadyInProgress

  public var errorDescription: String? {
    switch self {
    case .cancelled:
      "Device selection was cancelled."
    case .alreadyInProgress:
      "Device pairing is already in progress."
    }
  }
}
