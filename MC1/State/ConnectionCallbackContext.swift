import Foundation
import MC1Services

/// Captures connection ownership without retaining the services that store a callback.
struct ConnectionCallbackContext: Sendable {
  let generation: UUID
  let requestID: UUID

  @MainActor
  init(_ snapshot: ConnectionSnapshot) {
    generation = snapshot.generation
    requestID = snapshot.requestID
  }

  @MainActor
  func isCurrent(_ manager: ConnectionManager) -> Bool {
    manager.isCurrent(generation: generation, requestID: requestID)
  }
}
