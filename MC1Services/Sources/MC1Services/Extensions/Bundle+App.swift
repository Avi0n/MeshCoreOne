import Foundation

public extension Bundle {
  /// `CFBundleShortVersionString` from the bundle's Info.plist, or `"unknown"` if absent.
  var appVersion: String {
    infoDictionary?["CFBundleShortVersionString"] as? String ?? "unknown"
  }

  /// `CFBundleVersion` from the bundle's Info.plist, or `"unknown"` if absent.
  var appBuild: String {
    infoDictionary?["CFBundleVersion"] as? String ?? "unknown"
  }
}
