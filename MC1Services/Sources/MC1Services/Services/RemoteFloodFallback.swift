import Foundation

public enum RemoteFloodFallback {
  public static let defaultEnabled = true

  /// `UserDefaults.bool(forKey:)` is false when the key is absent, so a missing key stays on.
  public static func isEnabled(defaults: UserDefaults = .standard) -> Bool {
    let key = AppStorageKey.remoteFloodFallback.rawValue
    guard defaults.object(forKey: key) != nil else { return defaultEnabled }
    return defaults.bool(forKey: key)
  }
}
