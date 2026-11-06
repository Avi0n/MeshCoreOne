import Foundation
import Synchronization

/// Authority captured before receive can suspend. Link teardown and partition retirement
/// invalidate the same authority at both the store and the buffered event consumer.
public struct ReceiveScope: Sendable, Equatable {
  public final class Lifetime: Sendable {
    private let active = Mutex(true)

    public init() {}

    public var isCurrent: Bool {
      active.withLock { $0 }
    }

    func retire() {
      active.withLock { $0 = false }
    }
  }

  public let radioID: UUID
  private let link: Lifetime
  private let partition: Lifetime

  init(radioID: UUID, link: Lifetime, partition: Lifetime) {
    self.radioID = radioID
    self.link = link
    self.partition = partition
  }

  public static func == (lhs: Self, rhs: Self) -> Bool {
    lhs.radioID == rhs.radioID && lhs.link === rhs.link && lhs.partition === rhs.partition
  }

  public var isCurrent: Bool {
    link.isCurrent && partition.isCurrent
  }
}
