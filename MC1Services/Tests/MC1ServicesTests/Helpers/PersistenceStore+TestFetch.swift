import Foundation
import SwiftData
@testable import MC1Services

/// Test-only radio-scoped fetchAll helpers. These exist solely so the backup
/// integration tests can assert on what a specific radio imported, without
/// widening the production `PersistenceStore` API.
extension PersistenceStore {

    public func fetchAllContacts(radioID: UUID) throws -> [ContactDTO] {
        let targetRadioID = radioID
        let predicate = #Predicate<Contact> { $0.radioID == targetRadioID }
        return try modelContext.fetch(FetchDescriptor<Contact>(predicate: predicate))
            .map { ContactDTO(from: $0) }
    }

    public func fetchAllChannels(radioID: UUID) throws -> [ChannelDTO] {
        let targetRadioID = radioID
        let predicate = #Predicate<Channel> { $0.radioID == targetRadioID }
        return try modelContext.fetch(FetchDescriptor<Channel>(predicate: predicate))
            .map { ChannelDTO(from: $0) }
    }

    public func fetchAllMessages() throws -> [MessageDTO] {
        try modelContext.fetch(FetchDescriptor<Message>()).map { MessageDTO(from: $0) }
    }

    public func fetchAllMessages(radioID: UUID) throws -> [MessageDTO] {
        let targetRadioID = radioID
        let predicate = #Predicate<Message> { $0.radioID == targetRadioID }
        return try modelContext.fetch(FetchDescriptor<Message>(predicate: predicate))
            .map { MessageDTO(from: $0) }
    }

    public func fetchAllReactions(radioID: UUID) throws -> [ReactionDTO] {
        let targetRadioID = radioID
        let predicate = #Predicate<Reaction> { $0.radioID == targetRadioID }
        return try modelContext.fetch(FetchDescriptor<Reaction>(predicate: predicate))
            .map { ReactionDTO(from: $0) }
    }
}
