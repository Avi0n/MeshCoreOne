import Foundation
import SwiftData

extension PersistenceStore {

    // MARK: - Contact Operations

    /// Fetch all contacts for a device
    public func fetchContacts(radioID: UUID) throws -> [ContactDTO] {
        let targetRadioID = radioID
        let predicate = #Predicate<Contact> { contact in
            contact.radioID == targetRadioID
        }
        let descriptor = FetchDescriptor(
            predicate: predicate,
            sortBy: [SortDescriptor(\.name)]
        )
        let contacts = try modelContext.fetch(descriptor)
        return contacts.map { ContactDTO(from: $0) }
    }

    /// Fetch contacts with recent messages (for chat list)
    public func fetchConversations(radioID: UUID) throws -> [ContactDTO] {
        let targetRadioID = radioID
        let predicate = #Predicate<Contact> { contact in
            contact.radioID == targetRadioID && contact.lastMessageDate != nil
        }
        let descriptor = FetchDescriptor(
            predicate: predicate,
            sortBy: [SortDescriptor(\Contact.lastMessageDate, order: .reverse)]
        )
        let contacts = try modelContext.fetch(descriptor)
        return contacts.map { ContactDTO(from: $0) }
    }

    /// Fetch a contact by ID
    public func fetchContact(id: UUID) throws -> ContactDTO? {
        let targetID = id
        let predicate = #Predicate<Contact> { contact in
            contact.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { ContactDTO(from: $0) }
    }

    /// Fetch a contact by public key
    public func fetchContact(radioID: UUID, publicKey: Data) throws -> ContactDTO? {
        let targetRadioID = radioID
        let targetKey = publicKey
        let predicate = #Predicate<Contact> { contact in
            contact.radioID == targetRadioID && contact.publicKey == targetKey
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { ContactDTO(from: $0) }
    }

    /// Fetch a contact by public key prefix (6 bytes)
    public func fetchContact(radioID: UUID, publicKeyPrefix: Data) throws -> ContactDTO? {
        let targetRadioID = radioID
        let predicate = #Predicate<Contact> { contact in
            contact.radioID == targetRadioID
        }
        let contacts = try modelContext.fetch(FetchDescriptor(predicate: predicate))
        return contacts.first { $0.publicKey.prefix(6) == publicKeyPrefix }.map { ContactDTO(from: $0) }
    }

    /// Fetch all contacts with their public keys grouped by 1-byte prefix.
    /// Used for crypto operations when looking up contacts by public key prefix.
    public func fetchContactPublicKeysByPrefix(radioID: UUID) throws -> [UInt8: [Data]] {
        let targetRadioID = radioID
        let predicate = #Predicate<Contact> { contact in
            contact.radioID == targetRadioID
        }
        let descriptor = FetchDescriptor(predicate: predicate)
        let contacts = try modelContext.fetch(descriptor)

        var result: [UInt8: [Data]] = [:]
        for contact in contacts {
            guard contact.publicKey.count >= 1 else { continue }
            let prefix = contact.publicKey[0]
            result[prefix, default: []].append(contact.publicKey)
        }
        return result
    }

    /// Save or update a contact from a ContactFrame
    public func saveContact(radioID: UUID, from frame: ContactFrame) throws -> UUID {
        let targetRadioID = radioID
        let targetKey = frame.publicKey
        let predicate = #Predicate<Contact> { contact in
            contact.radioID == targetRadioID && contact.publicKey == targetKey
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        let contact: Contact
        if let existing = try modelContext.fetch(descriptor).first {
            existing.update(from: frame)
            contact = existing
        } else {
            contact = Contact(radioID: radioID, from: frame)
            modelContext.insert(contact)
        }

        try modelContext.save()
        return contact.id
    }

    /// Save or update a contact from DTO
    public func saveContact(_ dto: ContactDTO) throws {
        let targetID = dto.id
        let predicate = #Predicate<Contact> { contact in
            contact.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let existing = try modelContext.fetch(descriptor).first {
            existing.apply(dto)
        } else {
            modelContext.insert(Contact(dto: dto))
        }

        try modelContext.save()
    }

    /// Delete a contact
    public func deleteContact(id: UUID) throws {
        let targetID = id
        let predicate = #Predicate<Contact> { contact in
            contact.id == targetID
        }
        if let contact = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first {
            modelContext.delete(contact)
            try modelContext.save()
        }
    }

    /// Fetch all blocked contacts for a device
    public func fetchBlockedContacts(radioID: UUID) throws -> [ContactDTO] {
        let targetRadioID = radioID
        let predicate = #Predicate<Contact> { contact in
            contact.radioID == targetRadioID && contact.isBlocked == true
        }
        let descriptor = FetchDescriptor(
            predicate: predicate,
            sortBy: [SortDescriptor(\.name)]
        )
        let contacts = try modelContext.fetch(descriptor)
        return contacts.map { ContactDTO(from: $0) }
    }

    /// Update contact's last message info (nil clears the date, removing from conversations list)
    public func updateContactLastMessage(contactID: UUID, date: Date?) throws {
        let targetID = contactID
        let predicate = #Predicate<Contact> { contact in
            contact.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let contact = try modelContext.fetch(descriptor).first {
            contact.lastMessageDate = date
            try modelContext.save()
        }
    }

    /// Increment unread count for a contact
    public func incrementUnreadCount(contactID: UUID) throws {
        let targetID = contactID
        let predicate = #Predicate<Contact> { contact in
            contact.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let contact = try modelContext.fetch(descriptor).first {
            contact.unreadCount += 1
            try modelContext.save()
        }
    }

    /// Clear unread count for a contact
    public func clearUnreadCount(contactID: UUID) throws {
        let targetID = contactID
        let predicate = #Predicate<Contact> { contact in
            contact.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        if let contact = try modelContext.fetch(descriptor).first {
            contact.unreadCount = 0
            try modelContext.save()
        }
    }

    // MARK: - Mention Tracking

    public func incrementUnreadMentionCount(contactID: UUID) throws {
        let targetID = contactID
        let predicate = #Predicate<Contact> { contact in
            contact.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let contact = try modelContext.fetch(descriptor).first else { return }
        contact.unreadMentionCount += 1
        try modelContext.save()
    }

    public func decrementUnreadMentionCount(contactID: UUID) throws {
        let targetID = contactID
        let predicate = #Predicate<Contact> { contact in
            contact.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let contact = try modelContext.fetch(descriptor).first else { return }
        contact.unreadMentionCount = max(0, contact.unreadMentionCount - 1)
        try modelContext.save()
    }

    public func clearUnreadMentionCount(contactID: UUID) throws {
        let targetID = contactID
        let predicate = #Predicate<Contact> { contact in
            contact.id == targetID
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let contact = try modelContext.fetch(descriptor).first else { return }
        contact.unreadMentionCount = 0
        try modelContext.save()
    }

    public func fetchUnseenMentionIDs(contactID: UUID) throws -> [UUID] {
        let targetID = contactID
        let predicate = #Predicate<Message> { message in
            message.contactID == targetID &&
            message.containsSelfMention == true &&
            message.mentionSeen == false
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]

        let messages = try modelContext.fetch(descriptor)
        return messages.map(\.id)
    }

    /// Sets the muted state for a contact
    public func setContactMuted(_ contactID: UUID, isMuted: Bool) throws {
        let targetID = contactID
        let predicate = #Predicate<Contact> { $0.id == targetID }
        var descriptor = FetchDescriptor<Contact>(predicate: predicate)
        descriptor.fetchLimit = 1

        guard let contact = try modelContext.fetch(descriptor).first else {
            throw PersistenceStoreError.contactNotFound
        }

        contact.isMuted = isMuted
        try modelContext.save()
    }

    /// Delete all messages, reactions, message repeats, and pending sends for a contact
    /// in a single transactional save.
    public func deleteMessagesForContact(contactID: UUID) throws {
        let targetContactID: UUID? = contactID
        let messagePredicate = #Predicate<Message> { message in
            message.contactID == targetContactID
        }

        let messageIDs = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate)).map(\.id)

        try _deletePendingSendsForMessageIDsWithoutSaving(messageIDs: messageIDs)

        // Reaction is keyed by contactID directly here (single-value predicate) —
        // no SQLITE_MAX_VARIABLE_NUMBER risk, no chunking needed.
        try modelContext.delete(model: Reaction.self, where: #Predicate {
            $0.contactID == targetContactID
        })
        // Cascade MessageRepeat — no contactID column, so key by messageIDs.
        // Chunk to stay under SQLITE_MAX_VARIABLE_NUMBER (32766 on iOS 18+).
        if !messageIDs.isEmpty {
            let chunkSize = 500
            for start in stride(from: 0, to: messageIDs.count, by: chunkSize) {
                let chunk = Array(messageIDs[start..<min(start + chunkSize, messageIDs.count)])
                try modelContext.delete(model: MessageRepeat.self, where: #Predicate {
                    chunk.contains($0.messageID)
                })
            }
        }
        try modelContext.delete(model: Message.self, where: messagePredicate)
        try modelContext.save()
    }

    // MARK: - Contact Helper Methods

    /// Find contact display name by 4-byte or 6-byte public key prefix.
    /// Searches across all devices — room message authors may only be known
    /// from a previously-connected radio's contact list.
    public func findContactNameByKeyPrefix(_ prefix: Data) throws -> String? {
        // Fetch all contacts and filter by prefix match
        let contacts = try modelContext.fetch(FetchDescriptor<Contact>())
        let prefixLength = prefix.count
        return contacts.first { contact in
            contact.publicKey.prefix(prefixLength) == prefix
        }?.displayName
    }

    /// Find contact by 32-byte public key.
    /// Searches across all devices — used for routing hints where the contact
    /// may exist under a different device's ID.
    public func findContactByPublicKey(_ publicKey: Data) throws -> ContactDTO? {
        let targetKey = publicKey
        let predicate = #Predicate<Contact> { contact in
            contact.publicKey == targetKey
        }
        var descriptor = FetchDescriptor(predicate: predicate)
        descriptor.fetchLimit = 1
        return try modelContext.fetch(descriptor).first.map { ContactDTO(from: $0) }
    }

    public func fetchContactPublicKeys(radioID: UUID) throws -> Set<Data> {
        let targetRadioID = radioID
        let predicate = #Predicate<Contact> { $0.radioID == targetRadioID }
        let descriptor = FetchDescriptor<Contact>(predicate: predicate)
        let contacts = try modelContext.fetch(descriptor)
        return Set(contacts.map { $0.publicKey })
    }
}
