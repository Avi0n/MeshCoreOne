import Foundation

/// `UserDefaults`-backed record of the last connected device (device ID,
/// radio ID, device name) plus the most recent disconnect diagnostic.
///
/// `ConnectionManager` owns the only production instance and exposes thin
/// forwarders (`lastConnectedDeviceID`, `lastConnectedRadioID`,
/// `lastDisconnectDiagnostic`) so callers never touch the keys directly.
/// `ConnectionIntent` persistence is deliberately separate: intent is the
/// "does the user want to be connected" axis, not last-device state.
struct LastConnectionStore {
  private let defaults: UserDefaults

  init(defaults: UserDefaults) {
    self.defaults = defaults
  }

  /// The last connected device ID (for auto-reconnect).
  var deviceID: UUID? {
    guard let uuidString = defaults.string(forKey: PersistenceKeys.lastConnectedDeviceID) else {
      return nil
    }
    return UUID(uuidString: uuidString)
  }

  /// The last connected radio ID (for offline data scoping).
  var radioID: UUID? {
    guard let uuidString = defaults.string(forKey: PersistenceKeys.lastConnectedRadioID) else {
      return nil
    }
    return UUID(uuidString: uuidString)
  }

  /// The last connected device name (for offline display when disconnected).
  var deviceName: String? {
    defaults.string(forKey: PersistenceKeys.lastConnectedDeviceName)
  }

  /// Records a successful connection for future restoration.
  func persist(deviceID: UUID, radioID: UUID, deviceName: String) {
    defaults.set(deviceID.uuidString, forKey: PersistenceKeys.lastConnectedDeviceID)
    defaults.set(radioID.uuidString, forKey: PersistenceKeys.lastConnectedRadioID)
    defaults.set(deviceName, forKey: PersistenceKeys.lastConnectedDeviceName)
  }

  /// Clears the persisted connection record.
  func clear() {
    defaults.removeObject(forKey: PersistenceKeys.lastConnectedDeviceID)
    defaults.removeObject(forKey: PersistenceKeys.lastConnectedRadioID)
    defaults.removeObject(forKey: PersistenceKeys.lastConnectedDeviceName)
  }

  /// Most recent disconnect diagnostic summary persisted across app launches.
  var disconnectDiagnostic: String? {
    defaults.string(forKey: PersistenceKeys.lastDisconnectDiagnostic)
  }

  /// Persists a disconnect diagnostic prefixed with the current ISO8601 timestamp.
  func persistDisconnectDiagnostic(_ summary: String) {
    let timestamp = Date().ISO8601Format()
    defaults.set("\(timestamp) \(summary)", forKey: PersistenceKeys.lastDisconnectDiagnostic)
  }
}
