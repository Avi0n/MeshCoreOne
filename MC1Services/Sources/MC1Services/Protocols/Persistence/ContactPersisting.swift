import Foundation

/// Store operations for contact rows, contact mention tracking, and blocked senders.
public protocol ContactPersisting: Actor {
  // MARK: - Contact Operations

  /// Fetch all confirmed contacts for a device
  func fetchContacts(radioID: UUID) async throws -> [ContactDTO]

  /// Fetch contacts with recent messages
  func fetchConversations(radioID: UUID) async throws -> [ContactDTO]

  /// Fetch a contact by ID
  func fetchContact(id: UUID) async throws -> ContactDTO?

  /// Fetch a contact by public key
  func fetchContact(radioID: UUID, publicKey: Data) async throws -> ContactDTO?

  /// Fetch a contact by public key prefix
  func fetchContact(radioID: UUID, publicKeyPrefix: Data) async throws -> ContactDTO?

  /// Fetch all contacts with their public keys for crypto operations.
  /// Returns dictionary mapping 1-byte public key prefix to array of full 32-byte public keys.
  /// Multiple contacts may share the same prefix byte, so we store all of them.
  func fetchContactPublicKeysByPrefix(radioID: UUID) async throws -> [UInt8: [Data]]

  /// Find contact display name by 4-byte or 6-byte public key prefix.
  /// Searches across all devices, because room message authors may only be
  /// known from a previously-connected radio's contact list.
  func findContactNameByKeyPrefix(_ prefix: Data) async throws -> String?

  /// Find contact by 32-byte public key. Searches across all devices,
  /// for routing hints where the contact may exist under another device's ID.
  func findContactByPublicKey(_ publicKey: Data) async throws -> ContactDTO?

  /// Save or update a contact from a ContactFrame
  @discardableResult
  func saveContact(radioID: UUID, from frame: ContactFrame) async throws -> UUID

  /// Save or update a contact from DTO
  func saveContact(_ dto: ContactDTO) async throws

  /// Upsert contacts from frames in a single transaction, matching local rows by
  /// `(radioID, publicKey)`. Returns the number of frames persisted.
  @discardableResult
  func batchSaveContacts(radioID: UUID, from frames: [ContactFrame]) async throws -> Int

  /// Delete a contact and everything scoped to it (messages, reactions,
  /// message repeats, pending sends). The cascade is keyed by the contact ID
  /// value, so orphaned local data is removed even when the Contact row is
  /// already gone.
  func deleteContact(id: UUID) async throws

  /// Update contact's last message info (nil clears the date, removing from conversations list)
  func updateContactLastMessage(contactID: UUID, date: Date?) async throws

  /// Increment unread count for a contact
  func incrementUnreadCount(contactID: UUID) async throws

  /// Clear unread count for a contact
  func clearUnreadCount(contactID: UUID) async throws

  // MARK: - Mention Tracking

  /// Mark a mention as seen
  func markMentionSeen(messageID: UUID) async throws

  /// Increment unread mention count for a contact
  func incrementUnreadMentionCount(contactID: UUID) async throws

  /// Decrement unread mention count for a contact
  func decrementUnreadMentionCount(contactID: UUID) async throws

  /// Clear unread mention count for a contact
  func clearUnreadMentionCount(contactID: UUID) async throws

  /// Fetch unseen mention message IDs for a contact, ordered oldest-first
  func fetchUnseenMentionIDs(contactID: UUID) async throws -> [UUID]

  /// Delete all messages for a contact
  func deleteMessagesForContact(contactID: UUID) async throws

  /// Delete all channel messages from a specific sender for a device
  func deleteChannelMessages(fromSender senderName: String, radioID: UUID) async throws

  /// Fetch blocked contacts for a device
  func fetchBlockedContacts(radioID: UUID) async throws -> [ContactDTO]

  // MARK: - Blocked Channel Senders

  /// Save a blocked channel sender name (upserts to prevent duplicates)
  func saveBlockedChannelSender(_ dto: BlockedChannelSenderDTO) async throws

  /// Delete a blocked channel sender by device and name
  func deleteBlockedChannelSender(radioID: UUID, name: String) async throws

  /// Fetch all blocked channel senders for a device
  func fetchBlockedChannelSenders(radioID: UUID) async throws -> [BlockedChannelSenderDTO]
}

// MARK: - Default Parameter Values

extension ContactPersisting {
  /// Default batch upsert built from the per-item `saveContact` path. The concrete
  /// `PersistenceStore` overrides this with a single-transaction implementation; this
  /// fallback keeps lightweight test stubs conforming without their own batch logic.
  @discardableResult
  func batchSaveContacts(radioID: UUID, from frames: [ContactFrame]) async throws -> Int {
    for frame in frames {
      _ = try await saveContact(radioID: radioID, from: frame)
    }
    return frames.count
  }
}
