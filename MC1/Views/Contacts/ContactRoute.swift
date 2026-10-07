import Foundation

/// Nodes list-stack destinations. Contact detail is the split selection,
/// not a push on this stack.
enum ContactRoute: Hashable {
  case blockedContacts

  /// Telemetry history push. `ContactDetailView` registers this itself because chat
  /// info sheets host the same view, so the destination has to travel with it.
  struct TelemetryHistory: Hashable {
    let publicKey: Data
    let radioID: UUID
    var showNeighbors = true
  }
}
