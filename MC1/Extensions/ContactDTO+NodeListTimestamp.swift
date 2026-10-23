import Foundation
import MC1Services

extension ContactDTO {
  private static let nodeListNeverHeardTimestamp: UInt32 = 0

  func nodeListTimestamp(relativeTo referenceDate: Date) -> UInt32? {
    if AdvertTimestamp.isPlausible(lastAdvertTimestamp, relativeTo: referenceDate) {
      return lastAdvertTimestamp
    }

    guard let lastHeardTimestamp, lastHeardTimestamp > Self.nodeListNeverHeardTimestamp else {
      return nil
    }
    return lastHeardTimestamp
  }
}
