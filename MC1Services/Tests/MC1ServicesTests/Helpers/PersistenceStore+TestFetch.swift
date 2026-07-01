import Foundation
@testable import MC1Services
import SwiftData

/// Test-only radio-scoped fetchAll helpers. These exist solely so the backup
/// integration tests can assert on what a specific radio imported, without
/// widening the production `PersistenceStore` API.
public extension PersistenceStore {
  func fetchAllContacts(radioID: UUID) throws -> [ContactDTO] {
    let targetRadioID = radioID
    let predicate = #Predicate<Contact> { $0.radioID == targetRadioID }
    return try modelContext.fetch(FetchDescriptor<Contact>(predicate: predicate))
      .map { ContactDTO(from: $0) }
  }

  func fetchAllChannels(radioID: UUID) throws -> [ChannelDTO] {
    let targetRadioID = radioID
    let predicate = #Predicate<Channel> { $0.radioID == targetRadioID }
    return try modelContext.fetch(FetchDescriptor<Channel>(predicate: predicate))
      .map { ChannelDTO(from: $0) }
  }

  func fetchAllMessages() throws -> [MessageDTO] {
    try modelContext.fetch(FetchDescriptor<Message>()).map { MessageDTO(from: $0) }
  }

  func fetchAllMessages(radioID: UUID) throws -> [MessageDTO] {
    let targetRadioID = radioID
    let predicate = #Predicate<Message> { $0.radioID == targetRadioID }
    return try modelContext.fetch(FetchDescriptor<Message>(predicate: predicate))
      .map { MessageDTO(from: $0) }
  }

  func fetchAllReactions(radioID: UUID) throws -> [ReactionDTO] {
    let targetRadioID = radioID
    let predicate = #Predicate<Reaction> { $0.radioID == targetRadioID }
    return try modelContext.fetch(FetchDescriptor<Reaction>(predicate: predicate))
      .map { ReactionDTO(from: $0) }
  }

  /// Inserts a raw Message with an explicit `sortDate` distinct from `createdAt`,
  /// simulating a row persisted before the `sortDate` column existed (which comes
  /// up with the `Date.distantPast` schema default). The production save path
  /// always pins `sortDate` to `createdAt`, so this is test-only.
  func insertMessageWithSortDate(
    id: UUID,
    radioID: UUID,
    text: String,
    createdAt: Date,
    sortDate: Date
  ) throws {
    let message = Message(
      id: id,
      radioID: radioID,
      text: text,
      createdAt: createdAt,
      sortDate: sortDate
    )
    modelContext.insert(message)
    try modelContext.save()
  }

  /// Overwrites the `sortDate` on an existing Message row (test-only).
  func setMessageSortDate(id: UUID, sortDate: Date) throws {
    let targetID = id
    let predicate = #Predicate<Message> { $0.id == targetID }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    guard let message = try modelContext.fetch(descriptor).first else { return }
    message.sortDate = sortDate
    try modelContext.save()
  }
}
