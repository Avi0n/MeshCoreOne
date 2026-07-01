import Foundation
import MC1Services

/// Push destinations for the Contacts feature stacks, registered once in
/// `ContactsSidebarContent` so the compact stack and the iPad content column share one set.
/// Equality and hashing use the contact's stable id, so path identity survives row updates
/// while the carried payload still lets the destination build before the list has loaded.
enum ContactRoute: Hashable {
  case detail(ContactDTO)
  case blockedContacts

  /// Telemetry history push destination. `ContactDetailView` registers and pushes this
  /// itself because it is hosted by three different stacks (Contacts stack, iPad detail
  /// column, chat info sheet), so the destination must travel with the view.
  struct TelemetryHistory: Hashable {
    let publicKey: Data
    let radioID: UUID
    var showNeighbors = true
  }

  private enum Kind: UInt8, Hashable {
    case detail
    case blockedContacts
  }

  private var kind: Kind {
    switch self {
    case .detail: .detail
    case .blockedContacts: .blockedContacts
    }
  }

  static func == (lhs: ContactRoute, rhs: ContactRoute) -> Bool {
    switch (lhs, rhs) {
    case let (.detail(lhsContact), .detail(rhsContact)):
      lhsContact.id == rhsContact.id
    case (.blockedContacts, .blockedContacts):
      true
    default:
      false
    }
  }

  func hash(into hasher: inout Hasher) {
    hasher.combine(kind)
    if case let .detail(contact) = self {
      hasher.combine(contact.id)
    }
  }
}
