import Foundation

/// Attempts on the current out path, followed by fallback attempts after one out path reset.
public struct DMSendBudget: Sendable, Equatable {
  public static let defaultDirectAttempts = 4
  public static let defaultFloodAttempts = 4
  public static let directRange = 1...4
  public static let floodRange = 0...4
  /// Flood Attempts menu. Zero is stored by turning Fall Back to Flood off, not by this menu.
  public static let enabledFloodRange = 1...4

  /// Off stores zero. On stores the default only when the stored count is zero.
  public static func floodAttempts(enablingFallback isOn: Bool, stored: Int) -> Int {
    guard isOn else { return 0 }
    guard stored > 0 else { return defaultFloodAttempts }
    return stored
  }

  public static func menuFloodAttempts(stored: Int) -> Int {
    enabledFloodRange.contains(stored) ? stored : defaultFloodAttempts
  }

  public static func clampedMenuFloodAttempts(_ value: Int) -> Int {
    clamped(value, to: enabledFloodRange)
  }

  static let `default` = DMSendBudget(
    directAttempts: defaultDirectAttempts,
    floodAttempts: defaultFloodAttempts
  )
  static let singleAttempt = DMSendBudget(directAttempts: 1, floodAttempts: 0)

  public let directAttempts: Int
  public let floodAttempts: Int
  public var totalAttempts: Int {
    directAttempts + floodAttempts
  }

  public init(directAttempts: Int, floodAttempts: Int) {
    self.directAttempts = Self.clampedDirectAttempts(directAttempts)
    self.floodAttempts = Self.clampedFloodAttempts(floodAttempts)
  }

  public static func current(defaults: UserDefaults = .standard) -> DMSendBudget {
    let storedDirect = defaults.object(forKey: AppStorageKey.dmDirectAttempts.rawValue) as? Int
    let storedFlood = defaults.object(forKey: AppStorageKey.dmFloodAttempts.rawValue) as? Int
    return DMSendBudget(
      directAttempts: storedDirect ?? defaultDirectAttempts,
      floodAttempts: storedFlood ?? defaultFloodAttempts
    )
  }

  public static func clampedDirectAttempts(_ value: Int) -> Int {
    clamped(value, to: directRange)
  }

  public static func clampedFloodAttempts(_ value: Int) -> Int {
    clamped(value, to: floodRange)
  }

  private static func clamped(_ value: Int, to range: ClosedRange<Int>) -> Int {
    min(max(value, range.lowerBound), range.upperBound)
  }
}
