import Foundation

/// Distinguishes an omitted nickname from a cleared nickname when updating preferences.
public enum ContactNicknameUpdate: Sendable, Equatable {
  case unchanged
  case clear
  case set(String)
}
