import Foundation

/// Completion of the caller's wait, distinct from the physical link state.
@MainActor
public enum ConnectionResult {
  case operational(ConnectionSnapshot)
  case handoff(requestID: UUID)
  case superseded
  case cancelled
}
