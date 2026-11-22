import Foundation
import MC1Services

/// Watchlist remains the selected root while its Contact is open.
enum NodesSelection: Equatable {
  case contact(ContactDTO)
  case discovery
  case watchlist(radioID: UUID, contact: ContactDTO? = nil)

  var rootContact: ContactDTO? {
    guard case let .contact(contact) = self else { return nil }
    return contact
  }

  var contact: ContactDTO? {
    switch self {
    case let .contact(contact): contact
    case let .watchlist(_, contact): contact
    case .discovery: nil
    }
  }

  var watchlistRadioID: UUID? {
    guard case let .watchlist(radioID, _) = self else { return nil }
    return radioID
  }
}
