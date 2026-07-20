import Foundation

/// `UserDefaults`-backed record of the last connected device (device ID,
/// radio ID, device name) plus the most recent disconnect diagnostic and the
/// last verified BLE bond.
///
/// `ConnectionManager` owns the only production instance and exposes thin
/// forwarders (`lastConnectedDeviceID`, `lastConnectedRadioID`,
/// `lastDisconnectDiagnostic`) so callers never touch the keys directly.
/// `ConnectionIntent` persistence is deliberately separate: intent is the
/// "does the user want to be connected" axis, not last-device state.
///
/// `@unchecked Sendable`: the only stored property is a `UserDefaults`
/// reference, which Apple documents as thread-safe.
struct LastConnectionStore: @unchecked Sendable {
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

  /// Clears the persisted connection record, including the bond verification,
  /// so a re-pair after removal starts with no stale grace evidence.
  func clear() {
    defaults.removeObject(forKey: PersistenceKeys.lastConnectedDeviceID)
    defaults.removeObject(forKey: PersistenceKeys.lastConnectedRadioID)
    defaults.removeObject(forKey: PersistenceKeys.lastConnectedDeviceName)
    defaults.removeObject(forKey: PersistenceKeys.lastBondVerifiedDeviceID)
    defaults.removeObject(forKey: PersistenceKeys.lastBondVerifiedDate)
  }

  /// Records that the given device's bond completed a verified encrypted
  /// session just now. Single-slot like the rest of the store: one radio is
  /// connected at a time, and the check is keyed to the device ID.
  func persistBondVerification(deviceID: UUID) {
    defaults.set(deviceID.uuidString, forKey: PersistenceKeys.lastBondVerifiedDeviceID)
    defaults.set(Date(), forKey: PersistenceKeys.lastBondVerifiedDate)
  }

  /// The device holding the bond-verification slot, or nil when no bond has
  /// verified. Distinct from `deviceID`: a WiFi connection overwrites the
  /// connection slot without touching the bond slot, so cross-launch grace
  /// seeding must key off this value.
  var bondVerifiedDeviceID: UUID? {
    guard let uuidString = defaults.string(forKey: PersistenceKeys.lastBondVerifiedDeviceID) else {
      return nil
    }
    return UUID(uuidString: uuidString)
  }

  /// When the given device's bond last completed a verified encrypted session,
  /// or nil if it never has (or the slot belongs to a different device).
  func bondVerificationDate(for deviceID: UUID) -> Date? {
    guard defaults.string(forKey: PersistenceKeys.lastBondVerifiedDeviceID) == deviceID.uuidString else {
      return nil
    }
    return defaults.object(forKey: PersistenceKeys.lastBondVerifiedDate) as? Date
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
