import Foundation

/// Location inclusion policy for advertisements.
public enum AdvertLocationPolicy: UInt8, Sendable, CaseIterable {
  case none = 0
  case share = 1
  case prefs = 2

  public var isEnabled: Bool {
    self != .none
  }
}
