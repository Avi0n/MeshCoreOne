import Foundation

/// The row a preference write committed, and the block transition taken from that row.
/// Cleanup follows this transition, not a snapshot taken before the radio wait.
public struct ContactPreferenceCommit: Sendable, Equatable {
  public enum BlockTransition: Sendable, Equatable {
    case none
    case blocked
    case unblocked
  }

  public let contact: ContactDTO
  public let blockTransition: BlockTransition

  public init(contact: ContactDTO, blockTransition: BlockTransition) {
    self.contact = contact
    self.blockTransition = blockTransition
  }
}
