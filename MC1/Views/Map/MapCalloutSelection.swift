import Foundation
import MC1Services

enum MapCalloutSelection: Identifiable, Equatable {
  case contact(ContactDTO)
  case discovered(DiscoveredNodeDTO)

  var id: UUID {
    switch self {
    case let .contact(contact): contact.id
    case let .discovered(node): node.id
    }
  }
}
