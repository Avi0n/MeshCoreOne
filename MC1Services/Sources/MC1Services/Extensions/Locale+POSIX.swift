import Foundation

public extension Locale {
  /// POSIX locale — always uses period as decimal separator.
  /// Use for technical values like radio frequency and bandwidth.
  static let posix = Locale(identifier: "en_US_POSIX")
}
