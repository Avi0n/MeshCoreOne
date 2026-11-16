import Foundation

/// A request-scoped read model for connection controls, independent of a live Link.
public struct ConnectionActivity: Sendable, Equatable {
  public enum Phase: Sendable, Equatable {
    case connecting
    case reconnecting
    case waitingToReconnect
    case connected
    case syncing
    case ready
  }

  public let requestID: UUID
  public let targetName: String?
  public let phase: Phase
}
