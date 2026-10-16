import Foundation

enum GPSSource: String, CaseIterable {
  case phone
  case device
}

struct DevicePreferenceStore {
  private let userDefaults: UserDefaults

  init(userDefaults: UserDefaults = .standard) {
    self.userDefaults = userDefaults
  }

  // MARK: - Auto-Update Location

  func isAutoUpdateLocationEnabled(deviceID: UUID) -> Bool {
    userDefaults.bool(forKey: Self.autoUpdateLocationKey(deviceID: deviceID))
  }

  func setAutoUpdateLocationEnabled(_ enabled: Bool, deviceID: UUID) {
    userDefaults.set(enabled, forKey: Self.autoUpdateLocationKey(deviceID: deviceID))
  }

  // MARK: - GPS Source

  func gpsSource(deviceID: UUID) -> GPSSource {
    guard let raw = userDefaults.string(forKey: Self.gpsSourceKey(deviceID: deviceID)),
          let source = GPSSource(rawValue: raw) else {
      return .phone
    }
    return source
  }

  func hasSetGPSSource(deviceID: UUID) -> Bool {
    userDefaults.string(forKey: Self.gpsSourceKey(deviceID: deviceID)) != nil
  }

  func setGPSSource(_ source: GPSSource, deviceID: UUID) {
    userDefaults.set(source.rawValue, forKey: Self.gpsSourceKey(deviceID: deviceID))
  }

  // MARK: - Keys

  private static func autoUpdateLocationKey(deviceID: UUID) -> String {
    "device.\(deviceID.uuidString).autoUpdateLocation"
  }

  private static func gpsSourceKey(deviceID: UUID) -> String {
    "device.\(deviceID.uuidString).gpsSource"
  }
}
