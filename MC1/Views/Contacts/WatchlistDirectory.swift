import Foundation
import MC1Services

/// Board rows and Refresh share the same display order.
enum WatchlistDirectory {
  static func markedRepeaters(
    in contacts: [ContactDTO],
    radioID: UUID,
    pendingRemovalIDs: Set<UUID>
  ) -> [ContactDTO] {
    contacts
      .filter {
        $0.radioID == radioID && $0.isMyNode && $0.type == .repeater && !pendingRemovalIDs.contains($0.id)
      }
      .sorted(by: displayOrder)
  }

  static func showsButton(
    searchText: String,
    contacts: [ContactDTO],
    radioID: UUID?,
    pendingRemovalIDs: Set<UUID>
  ) -> Bool {
    guard searchText.isEmpty, let radioID else { return false }
    return contacts.contains {
      $0.radioID == radioID && $0.type == .repeater && !pendingRemovalIDs.contains($0.id)
    }
  }

  static func displayOrder(_ lhs: ContactDTO, _ rhs: ContactDTO) -> Bool {
    let order = lhs.displayName.localizedCompare(rhs.displayName)
    if order == .orderedSame {
      return lhs.id.uuidString < rhs.id.uuidString
    }
    return order == .orderedAscending
  }
}
