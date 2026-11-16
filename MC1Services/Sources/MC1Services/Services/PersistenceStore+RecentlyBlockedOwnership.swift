import Foundation
import SwiftData

extension PersistenceStore {
  func stageRecentlyBlockedDeletion(radioID: UUID) throws {
    let rows = try modelContext.fetch(FetchDescriptor<RecentlyBlockedMessage>(
      predicate: #Predicate { $0.radioID == radioID }
    ))
    for row in rows {
      modelContext.delete(row)
    }
  }

  func stageRecentlyBlockedRoomDeletion(radioID: UUID, conversationID: UUID) throws {
    let rows = try modelContext.fetch(FetchDescriptor<RecentlyBlockedMessage>(
      predicate: #Predicate { $0.radioID == radioID && $0.conversationID == conversationID }
    ))
    for row in rows {
      modelContext.delete(row)
    }
  }

  func stageRecentlyBlockedContactDeletion(contactID: UUID) throws {
    let rows = try modelContext.fetch(FetchDescriptor<RecentlyBlockedMessage>(
      predicate: #Predicate { $0.contactID == contactID }
    ))
    for row in rows {
      modelContext.delete(row)
    }
  }

  func hasRecentlyBlockedContactReference(contactID: UUID) throws -> Bool {
    var descriptor = FetchDescriptor<RecentlyBlockedMessage>(predicate: #Predicate { $0.contactID == contactID })
    descriptor.fetchLimit = 1
    return try !modelContext.fetch(descriptor).isEmpty
  }

  @discardableResult
  func bindRecentlyBlockedContacts(radioID: UUID) throws -> Bool {
    let unbound = try modelContext.fetch(FetchDescriptor<RecentlyBlockedMessage>(
      predicate: #Predicate { $0.radioID == radioID && $0.conversationID == nil && $0.contactID == nil }
    ))
    guard !unbound.isEmpty else { return false }

    let contacts = try modelContext.fetch(FetchDescriptor<Contact>(predicate: #Predicate { $0.radioID == radioID }))
    let prefixLength = 6
    let contactsByPrefix = Dictionary(grouping: contacts) { Data($0.publicKey.prefix(prefixLength)) }
    var changed = false
    for row in unbound {
      guard let prefix = row.senderPrefix,
            let matches = contactsByPrefix[prefix],
            matches.count == 1, let contact = matches.first else { continue }
      row.contactID = contact.id
      changed = true
    }
    return changed
  }

  func saveContactChangesBindingRecentlyBlocked(radioID: UUID) throws {
    do {
      let changed = try bindRecentlyBlockedContacts(radioID: radioID)
      try modelContext.save()
      if changed { publishRecentlyBlockedChange() }
    } catch {
      modelContext.rollback()
      throw error
    }
  }
}
