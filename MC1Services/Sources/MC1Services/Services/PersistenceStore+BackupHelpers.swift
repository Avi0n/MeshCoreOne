import Foundation
import SwiftData

/// Maximum entries per `Array.contains($0.field)` predicate, kept well under SQLite's
/// default `SQLITE_MAX_VARIABLE_NUMBER` (32,766) to defend against extreme imports
/// without relying on undocumented platform limits.
private let maxKeysPerFetch = 900

/// Chunks `keys` and fetches in batches, returning the union of results. Use for any
/// `#Predicate` that filters by `keys.contains($0.field)` when `keys.count` could
/// realistically grow into the thousands.
func fetchInChunks<Model, Key>(
  keys: [Key],
  chunkSize: Int = maxKeysPerFetch,
  fetcher: ([Key]) throws -> [Model]
) throws -> [Model] {
  guard !keys.isEmpty else { return [] }
  if keys.count <= chunkSize {
    return try fetcher(keys)
  }
  var combined: [Model] = []
  var index = 0
  while index < keys.count {
    try Task.checkCancellation()
    let end = Swift.min(index + chunkSize, keys.count)
    try combined.append(contentsOf: fetcher(Array(keys[index..<end])))
    index = end
  }
  return combined
}

// MARK: - Composite key builders

extension PersistenceStore {
  func savedTracePathKey(radioID: UUID, pathBytes: Data, hashSize: Int) -> String {
    "\(radioID)-\(pathBytes.base64EncodedString())-\(hashSize)"
  }

  func contactKey(radioID: UUID, publicKey: Data) -> String {
    "\(radioID)-\(publicKey.base64EncodedString())"
  }

  func channelKey(radioID: UUID, index: UInt8) -> String {
    "\(radioID)-\(index)"
  }

  /// Dedup key for backup reconciliation.
  /// Outgoing messages key on their UUID so two intentional sends with identical
  /// text/timestamp/recipient do not collapse on restore. Incoming messages fall
  /// back to their stored live-sync dedup key (or a content-based derivation when
  /// the field was never populated — e.g. pre-schema rows), scoped by `radioID`
  /// so two companion radios that both received the same wire packet restore as
  /// two rows rather than collapsing into one under a single radio.
  func messageBackupKey(for dto: MessageDTO) -> String {
    if dto.direction == .outgoing {
      return "\(DeduplicationKey.outgoingIdentityPrefix)\(dto.id.uuidString)"
    }
    let base = dto.deduplicationKey ?? DeduplicationKey.contentBased(
      contactID: dto.contactID,
      channelIndex: dto.channelIndex,
      senderNodeName: dto.senderNodeName,
      timestamp: dto.timestamp,
      content: dto.text
    )
    return "\(dto.radioID.uuidString)-\(base)"
  }

  func messageBackupKey(for message: Message) -> String {
    if message.direction == .outgoing {
      return "\(DeduplicationKey.outgoingIdentityPrefix)\(message.id.uuidString)"
    }
    let base = message.deduplicationKey ?? DeduplicationKey.contentBased(
      contactID: message.contactID,
      channelIndex: message.channelIndex,
      senderNodeName: message.senderNodeName,
      timestamp: message.timestamp,
      content: message.text
    )
    return "\(message.radioID.uuidString)-\(base)"
  }

  func reactionKey(messageID: UUID, senderName: String, emoji: String) -> String {
    "\(messageID)-\(senderName)-\(emoji)"
  }

  func roomMessageKey(sessionID: UUID, deduplicationKey: String) -> String {
    "\(sessionID)-\(deduplicationKey)"
  }

  func blockedChannelSenderKey(radioID: UUID, name: String) -> String {
    "\(radioID)-\(name)"
  }

  func remoteNodeSessionKey(radioID: UUID, publicKey: Data) -> String {
    "\(radioID)-\(publicKey.base64EncodedString())"
  }

  func discoveredNodeKey(radioID: UUID, publicKey: Data) -> String {
    "\(radioID)-\(publicKey.base64EncodedString())"
  }

  func nodeStatusSnapshotKey(nodePublicKey: Data, timestamp: Date) -> String {
    // Use milliseconds (Int) to stay stable across JSON `.secondsSince1970`
    // encode/decode roundtrips — Double.bitPattern can drift on roundtrip
    // even when the two Dates compare equal.
    // Two distinct snapshots for one node within the same millisecond intentionally
    // coalesce (the second is counted as skipped). This is acceptable, rare diagnostic
    // coalescing; the id is deliberately not part of the key so re-import stays idempotent.
    let milliseconds = Int(timestamp.timeIntervalSince1970 * 1000)
    return "\(nodePublicKey.base64EncodedString())-\(milliseconds)"
  }

  /// Rewrite the contact-UUID segment of a DM dedup key. Only the `dm-{uuid}-` prefix
  /// is touched, never raw substrings elsewhere in the key, so a UUID that happens to
  /// appear inside the trailing hash (vanishingly unlikely, but possible) is preserved.
  func rewriteDMDeduplicationKey(_ key: String, from backupID: UUID, to localID: UUID) -> String {
    let oldPrefix = "\(DeduplicationKey.directMessagePrefix)\(backupID.uuidString)-"
    guard key.hasPrefix(oldPrefix) else { return key }
    let newPrefix = "\(DeduplicationKey.directMessagePrefix)\(localID.uuidString)-"
    return newPrefix + key.dropFirst(oldPrefix.count)
  }

  /// Rewrite the channel-index segment of a content-based channel dedup key when a
  /// backup channel is relocated to a different local slot. Only the leading
  /// `ch-{index}-` segment is touched, mirroring ``rewriteDMDeduplicationKey``, so a
  /// numeric run elsewhere in the key (timestamp, hash) cannot be misinterpreted as the
  /// index. Keys that don't carry this prefix (outgoing identity keys, DM keys) pass
  /// through unchanged.
  func rewriteChannelDeduplicationKey(_ key: String, from backupIndex: UInt8, to localIndex: UInt8) -> String {
    let oldPrefix = "\(DeduplicationKey.channelPrefix)\(backupIndex)-"
    guard key.hasPrefix(oldPrefix) else { return key }
    let newPrefix = "\(DeduplicationKey.channelPrefix)\(localIndex)-"
    return newPrefix + key.dropFirst(oldPrefix.count)
  }
}

// MARK: - Per-table existing-row lookups

extension PersistenceStore {
  func fetchExistingContactsByKey(radioIDs: Set<UUID>) throws -> [String: Contact] {
    let radioIDArray = Array(radioIDs)
    guard !radioIDArray.isEmpty else { return [:] }

    let predicate = #Predicate<Contact> { radioIDArray.contains($0.radioID) }
    let contacts = try modelContext.fetch(FetchDescriptor(predicate: predicate))
    return Dictionary(uniqueKeysWithValues: contacts.map {
      (contactKey(radioID: $0.radioID, publicKey: $0.publicKey), $0)
    })
  }

  func fetchExistingChannelsByKey(radioIDs: Set<UUID>) throws -> [String: Channel] {
    let radioIDArray = Array(radioIDs)
    guard !radioIDArray.isEmpty else { return [:] }

    let predicate = #Predicate<Channel> { radioIDArray.contains($0.radioID) }
    let channels = try modelContext.fetch(FetchDescriptor(predicate: predicate))
    return Dictionary(uniqueKeysWithValues: channels.map {
      (channelKey(radioID: $0.radioID, index: $0.index), $0)
    })
  }

  /// Fetches every local channel for the given radios as raw models. Channel
  /// reconciliation needs to index local rows by both secret and slot in one pass,
  /// which the `(radioID, index)`-keyed dictionary above can't express.
  func fetchExistingChannels(radioIDs: Set<UUID>) throws -> [Channel] {
    let radioIDArray = Array(radioIDs)
    guard !radioIDArray.isEmpty else { return [] }

    let predicate = #Predicate<Channel> { radioIDArray.contains($0.radioID) }
    return try modelContext.fetch(FetchDescriptor(predicate: predicate))
  }

  func fetchExistingRemoteNodeSessionsByKey(radioIDs: Set<UUID>) throws -> [String: RemoteNodeSession] {
    let radioIDArray = Array(radioIDs)
    guard !radioIDArray.isEmpty else { return [:] }

    let predicate = #Predicate<RemoteNodeSession> { radioIDArray.contains($0.radioID) }
    let sessions = try modelContext.fetch(FetchDescriptor(predicate: predicate))
    return Dictionary(uniqueKeysWithValues: sessions.map {
      (remoteNodeSessionKey(radioID: $0.radioID, publicKey: $0.publicKey), $0)
    })
  }
}

// MARK: - Merge pre-existing rows from backup metadata

extension PersistenceStore {
  func mergeBackupMetadata(into contact: Contact, from dto: ContactDTO) -> Bool {
    var changed = false
    if contact.nickname == nil, let backupNickname = dto.nickname {
      contact.nickname = backupNickname
      changed = true
    }
    // Safety: never un-block, un-mute, or un-favorite via import
    if dto.isBlocked, !contact.isBlocked {
      contact.isBlocked = true
      changed = true
    }
    if dto.isMuted, !contact.isMuted {
      contact.isMuted = true
      changed = true
    }
    if dto.isFavorite, !contact.isFavorite {
      contact.isFavorite = true
      changed = true
    }
    if let backupDate = dto.lastMessageDate {
      if contact.lastMessageDate == nil || contact.lastMessageDate! < backupDate {
        contact.lastMessageDate = backupDate
        changed = true
      }
    }
    let mergedUnread = max(contact.unreadCount, dto.unreadCount)
    if contact.unreadCount != mergedUnread {
      contact.unreadCount = mergedUnread
      changed = true
    }
    let mergedMention = max(contact.unreadMentionCount, dto.unreadMentionCount)
    if contact.unreadMentionCount != mergedMention {
      contact.unreadMentionCount = mergedMention
      changed = true
    }
    if contact.ocvPreset == nil, let backupPreset = dto.ocvPreset {
      contact.ocvPreset = backupPreset
      changed = true
    }
    if contact.customOCVArrayString == nil, let backupOCV = dto.customOCVArrayString {
      contact.customOCVArrayString = backupOCV
      changed = true
    }
    return changed
  }

  func mergeBackupMetadata(into channel: Channel, from dto: ChannelDTO) -> Bool {
    var changed = false
    if let backupDate = dto.lastMessageDate {
      if channel.lastMessageDate == nil || channel.lastMessageDate! < backupDate {
        channel.lastMessageDate = backupDate
        changed = true
      }
    }
    let mergedUnread = max(channel.unreadCount, dto.unreadCount)
    if channel.unreadCount != mergedUnread {
      channel.unreadCount = mergedUnread
      changed = true
    }
    let mergedMention = max(channel.unreadMentionCount, dto.unreadMentionCount)
    if channel.unreadMentionCount != mergedMention {
      channel.unreadMentionCount = mergedMention
      changed = true
    }
    // Only adopt backup notification level if local is at default
    if channel.notificationLevel == .all, dto.notificationLevel != .all {
      channel.notificationLevel = dto.notificationLevel
      changed = true
    }
    if dto.isFavorite, !channel.isFavorite {
      channel.isFavorite = true
      changed = true
    }
    if channel.floodScope == .inherit, dto.floodScope != .inherit {
      channel.floodScope = dto.floodScope
      changed = true
    }
    return changed
  }

  func mergeBackupMetadata(into session: RemoteNodeSession, from dto: RemoteNodeSessionDTO) -> Bool {
    var changed = false
    let mergedUnread = max(session.unreadCount, dto.unreadCount)
    if session.unreadCount != mergedUnread {
      session.unreadCount = mergedUnread
      changed = true
    }
    // Only adopt backup notification level if local is at default
    if session.notificationLevel == .all, dto.notificationLevel != .all {
      session.notificationLevel = dto.notificationLevel
      changed = true
    }
    if dto.isFavorite, !session.isFavorite {
      session.isFavorite = true
      changed = true
    }
    let mergedSyncTimestamp = max(session.lastSyncTimestamp, dto.lastSyncTimestamp)
    if session.lastSyncTimestamp != mergedSyncTimestamp {
      session.lastSyncTimestamp = mergedSyncTimestamp
      changed = true
    }
    if let backupDate = dto.lastMessageDate {
      if session.lastMessageDate == nil || session.lastMessageDate! < backupDate {
        session.lastMessageDate = backupDate
        changed = true
      }
    }
    return changed
  }
}
