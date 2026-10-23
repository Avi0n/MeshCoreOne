import Foundation

/// In-memory Nodes ancestry shared by compact and regular navigation.
enum NodesRoute: Hashable {
  case blockedContacts
  case contact(id: UUID, presentationRevision: Int)
  case discovery(presentationRevision: Int)
  case savedHistory(ContactRoute.TelemetryHistory)
  case locationMap(presentationID: UUID)
}
