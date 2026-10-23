import Foundation

public enum AdvertTimestamp {
  private static let minimumTimestamp: UInt32 = 1_577_836_800
  private static let futureTolerance: TimeInterval = 5 * 60

  public static func isPlausible(_ timestamp: UInt32, relativeTo referenceDate: Date) -> Bool {
    timestamp >= minimumTimestamp
      && TimeInterval(timestamp) <= referenceDate.timeIntervalSince1970 + futureTolerance
  }
}
