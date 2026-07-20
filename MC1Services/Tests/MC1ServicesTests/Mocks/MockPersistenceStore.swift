import Foundation
@testable import MC1Services
import MeshCore

/// Mock implementation of PersistenceStoreProtocol for testing.
///
/// Uses in-memory storage for all data. Configure by adding items to the
/// storage dictionaries or setting stubbed errors.
public actor MockPersistenceStore: PersistenceStoreProtocol {
  // MARK: - In-Memory Storage

  public var messages: [UUID: MessageDTO] = [:]
  public var contacts: [UUID: ContactDTO] = [:]
  public var channels: [UUID: ChannelDTO] = [:]
  public var debugLogEntries: [DebugLogEntryDTO] = []

  // MARK: - Stubbed Errors

  public var stubbedSaveMessageError: Error?
  public var stubbedFetchMessageError: Error?
  public var stubbedUpdateMessageStatusError: Error?
  public var stubbedSaveContactError: Error?
  public var stubbedFetchContactError: Error?
  public var stubbedDeleteContactError: Error?
  public var stubbedSaveChannelError: Error?
  public var stubbedFetchChannelError: Error?
  public var stubbedDeleteChannelError: Error?
  public var stubbedDebugLogError: Error?

  // MARK: - Recorded Invocations

  public private(set) var savedMessages: [MessageDTO] = []
  public private(set) var savedContacts: [ContactDTO] = []
  public private(set) var savedChannels: [ChannelDTO] = []
  public private(set) var deletedContactIDs: [UUID] = []
  public private(set) var deletedChannelIDs: [UUID] = []
  public private(set) var deletedMessagesForContactIDs: [UUID] = []
  public private(set) var deletedMessagesForChannelCalls: [(radioID: UUID, channelIndex: UInt8)] = []
  public private(set) var deletedChannelMessagesFromSenderCalls: [(senderName: String, radioID: UUID)] = []
  public private(set) var updatedMessageStatuses: [(id: UUID, status: MessageStatus)] = []
  public private(set) var updatedMessageAcks: [(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32?)] = []

  // MARK: - Initialization

  public init() {}

  // MARK: - Message Operations

  public func isDuplicateMessage(deduplicationKey: String, radioID: UUID) async throws -> Bool {
    messages.values.contains { $0.deduplicationKey == deduplicationKey && $0.radioID == radioID }
  }

  public func saveMessage(_ dto: MessageDTO) async throws {
    savedMessages.append(dto)
    if let error = stubbedSaveMessageError {
      throw error
    }
    messages[dto.id] = dto
  }

  public func fetchMessage(id: UUID) async throws -> MessageDTO? {
    if let error = stubbedFetchMessageError {
      throw error
    }
    return messages[id]
  }

  public func fetchLastMessages(contactIDs: [UUID], limit: Int) throws -> [UUID: [MessageDTO]] {
    if let error = stubbedFetchMessageError { throw error }
    var result: [UUID: [MessageDTO]] = [:]
    for contactID in contactIDs {
      let filtered = messages.values.filter { $0.contactID == contactID }
        .sorted { $0.timestamp < $1.timestamp }
      result[contactID] = Array(filtered.prefix(limit))
    }
    return result
  }

  public func fetchLastChannelMessages(channels: [(radioID: UUID, channelIndex: UInt8, id: UUID)], limit: Int) throws -> [UUID: [MessageDTO]] {
    if let error = stubbedFetchMessageError { throw error }
    var result: [UUID: [MessageDTO]] = [:]
    for channel in channels {
      let filtered = messages.values.filter { $0.radioID == channel.radioID && $0.channelIndex == channel.channelIndex }
        .sorted { $0.timestamp < $1.timestamp }
      result[channel.id] = Array(filtered.prefix(limit))
    }
    return result
  }

  public func fetchMessages(contactID: UUID, limit: Int, offset: Int) async throws -> [MessageDTO] {
    if let error = stubbedFetchMessageError {
      throw error
    }
    let filtered = messages.values.filter { $0.contactID == contactID }
      .sorted { $0.timestamp < $1.timestamp }
    return Array(filtered.dropFirst(offset).prefix(limit))
  }

  public func fetchMessages(radioID: UUID, channelIndex: UInt8, limit: Int, offset: Int) async throws -> [MessageDTO] {
    if let error = stubbedFetchMessageError {
      throw error
    }
    let filtered = messages.values.filter { $0.radioID == radioID && $0.channelIndex == channelIndex }
      .sorted { $0.timestamp < $1.timestamp }
    return Array(filtered.dropFirst(offset).prefix(limit))
  }

  public func findChannelMessageForReaction(
    radioID: UUID,
    channelIndex: UInt8,
    parsedReaction: ParsedReaction,
    localNodeName: String?,
    timestampWindow: ClosedRange<UInt32>,
    limit: Int
  ) async throws -> MessageDTO? {
    let candidates = try await fetchChannelMessageCandidates(
      radioID: radioID,
      channelIndex: channelIndex,
      timestampWindow: timestampWindow,
      limit: limit
    )

    for candidate in candidates {
      if candidate.direction == .outgoing {
        guard let localNodeName, parsedReaction.targetSender == localNodeName else {
          continue
        }
      } else {
        guard candidate.senderNodeName == parsedReaction.targetSender else {
          continue
        }
      }

      let hash = ReactionParser.generateMessageHash(
        text: candidate.text,
        timestamp: candidate.reactionTimestamp
      )
      guard hash == parsedReaction.messageHash else { continue }

      return candidate
    }

    return nil
  }

  public func fetchChannelMessageCandidates(
    radioID: UUID,
    channelIndex: UInt8,
    timestampWindow: ClosedRange<UInt32>,
    limit: Int
  ) async throws -> [MessageDTO] {
    if let error = stubbedFetchMessageError {
      throw error
    }

    return messages.values.filter {
      $0.radioID == radioID &&
        $0.channelIndex == channelIndex &&
        timestampWindow.contains($0.timestamp)
    }
    .sorted {
      if $0.timestamp != $1.timestamp { return $0.timestamp > $1.timestamp }
      return $0.createdAt > $1.createdAt
    }
    .prefix(limit)
    .map(\.self)
  }

  public func fetchDMMessageCandidates(
    radioID: UUID,
    contactID: UUID,
    timestampWindow: ClosedRange<UInt32>,
    limit: Int
  ) async throws -> [MessageDTO] {
    if let error = stubbedFetchMessageError {
      throw error
    }

    return messages.values.filter {
      $0.radioID == radioID &&
        $0.contactID == contactID &&
        timestampWindow.contains($0.timestamp)
    }
    .sorted {
      if $0.timestamp != $1.timestamp { return $0.timestamp > $1.timestamp }
      return $0.createdAt > $1.createdAt
    }
    .prefix(limit)
    .map(\.self)
  }

  public func findDMMessageForReaction(
    radioID: UUID,
    contactID: UUID,
    messageHash: String,
    timestampWindow: ClosedRange<UInt32>,
    limit: Int
  ) async throws -> MessageDTO? {
    let candidates = try await fetchDMMessageCandidates(
      radioID: radioID,
      contactID: contactID,
      timestampWindow: timestampWindow,
      limit: limit
    )

    for candidate in candidates {
      // Skip messages that are themselves reactions
      if ReactionParser.isReactionText(candidate.text, isDM: true) { continue }

      let hash = ReactionParser.generateMessageHash(
        text: candidate.text,
        timestamp: candidate.reactionTimestamp
      )
      if hash == messageHash {
        return candidate
      }
    }

    return nil
  }

  public func updateMessageStatus(id: UUID, status: MessageStatus) async throws {
    updatedMessageStatuses.append((id: id, status: status))
    if let error = stubbedUpdateMessageStatusError {
      throw error
    }
    if var message = messages[id] {
      messages[id] = MessageDTO(
        id: message.id,
        radioID: message.radioID,
        contactID: message.contactID,
        channelIndex: message.channelIndex,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        direction: message.direction,
        status: status,
        textType: message.textType,
        ackCode: message.ackCode,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderKeyPrefix,
        senderNodeName: message.senderNodeName,
        isRead: message.isRead,
        replyToID: message.replyToID,
        roundTripTime: message.roundTripTime,
        heardRepeats: message.heardRepeats,
        retryAttempt: message.retryAttempt,
        maxRetryAttempts: message.maxRetryAttempts
      )
    }
  }

  public func updateMessageStatusUnlessDelivered(id: UUID, status: MessageStatus) async throws -> Bool {
    guard let message = messages[id], message.status != .delivered else { return false }
    try await updateMessageStatus(id: id, status: status)
    return true
  }

  public func clearRetryingToSent(id: UUID) async throws -> Bool {
    guard let message = messages[id],
          message.status != .delivered,
          message.status != .failed else { return false }
    try await updateMessageStatus(id: id, status: .sent)
    return true
  }

  public func hasOutgoingSentDM(ackCode: UInt32) async throws -> Bool {
    messages.values.contains {
      $0.ackCode == ackCode && $0.status == .sent && $0.direction == .outgoing && $0.channelIndex == nil
    }
  }

  public func updateMessageAck(id: UUID, ackCode: UInt32, status: MessageStatus, roundTripTime: UInt32?) async throws {
    updatedMessageAcks.append((id: id, ackCode: ackCode, status: status, roundTripTime: roundTripTime))
    if let error = stubbedUpdateMessageStatusError {
      throw error
    }
    if var message = messages[id] {
      // Mirror PersistenceStore.updateMessageAck: a terminal row
      // (.delivered/.failed) is not flipped by a late ACK; only the
      // legitimate .sent -> .delivered upgrade is allowed through.
      if message.status == .delivered || message.status == .failed, status != message.status { return }
      messages[id] = MessageDTO(
        id: message.id,
        radioID: message.radioID,
        contactID: message.contactID,
        channelIndex: message.channelIndex,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        direction: message.direction,
        status: status,
        textType: message.textType,
        ackCode: ackCode,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderKeyPrefix,
        senderNodeName: message.senderNodeName,
        isRead: message.isRead,
        replyToID: message.replyToID,
        roundTripTime: roundTripTime,
        heardRepeats: message.heardRepeats,
        retryAttempt: message.retryAttempt,
        maxRetryAttempts: message.maxRetryAttempts
      )
    }
  }

  public func updateMessageRetryStatus(id: UUID, status: MessageStatus, retryAttempt: Int, maxRetryAttempts: Int) async throws {
    if let error = stubbedUpdateMessageStatusError {
      throw error
    }
    // Mirror PersistenceStore: a terminal row (.delivered/.failed) is not
    // overwritten by a stale retry iteration.
    if let message = messages[id], message.status != .delivered, message.status != .failed {
      messages[id] = MessageDTO(
        id: message.id,
        radioID: message.radioID,
        contactID: message.contactID,
        channelIndex: message.channelIndex,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        direction: message.direction,
        status: status,
        textType: message.textType,
        ackCode: message.ackCode,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderKeyPrefix,
        senderNodeName: message.senderNodeName,
        isRead: message.isRead,
        replyToID: message.replyToID,
        roundTripTime: message.roundTripTime,
        heardRepeats: message.heardRepeats,
        sendCount: message.sendCount,
        retryAttempt: retryAttempt,
        maxRetryAttempts: maxRetryAttempts
      )
    }
  }

  public func updateMessageTimestamp(id: UUID, timestamp: UInt32) async throws {
    if let message = messages[id] {
      messages[id] = MessageDTO(
        id: message.id,
        radioID: message.radioID,
        contactID: message.contactID,
        channelIndex: message.channelIndex,
        text: message.text,
        timestamp: timestamp,
        createdAt: message.createdAt,
        direction: message.direction,
        status: message.status,
        textType: message.textType,
        ackCode: message.ackCode,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderKeyPrefix,
        senderNodeName: message.senderNodeName,
        isRead: message.isRead,
        replyToID: message.replyToID,
        roundTripTime: message.roundTripTime,
        heardRepeats: message.heardRepeats,
        sendCount: message.sendCount,
        retryAttempt: message.retryAttempt,
        maxRetryAttempts: message.maxRetryAttempts
      )
    }
  }

  public func updateMessageHeardRepeats(id: UUID, heardRepeats: Int) async throws {
    if let message = messages[id] {
      messages[id] = MessageDTO(
        id: message.id,
        radioID: message.radioID,
        contactID: message.contactID,
        channelIndex: message.channelIndex,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        direction: message.direction,
        status: message.status,
        textType: message.textType,
        ackCode: message.ackCode,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderKeyPrefix,
        senderNodeName: message.senderNodeName,
        isRead: message.isRead,
        replyToID: message.replyToID,
        roundTripTime: message.roundTripTime,
        heardRepeats: heardRepeats,
        retryAttempt: message.retryAttempt,
        maxRetryAttempts: message.maxRetryAttempts
      )
    }
  }

  public func markMessageAsRead(id: UUID) async throws {
    if var message = messages[id] {
      message.isRead = true
      messages[id] = message
    }
  }

  public func updateMessageLinkPreview(
    id: UUID,
    url: String?,
    title: String?,
    imageData: Data?,
    iconData: Data?,
    fetched: Bool
  ) throws {
    if let message = messages[id] {
      messages[id] = MessageDTO(
        id: message.id,
        radioID: message.radioID,
        contactID: message.contactID,
        channelIndex: message.channelIndex,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        direction: message.direction,
        status: message.status,
        textType: message.textType,
        ackCode: message.ackCode,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderKeyPrefix,
        senderNodeName: message.senderNodeName,
        isRead: message.isRead,
        replyToID: message.replyToID,
        roundTripTime: message.roundTripTime,
        heardRepeats: message.heardRepeats,
        retryAttempt: message.retryAttempt,
        maxRetryAttempts: message.maxRetryAttempts,
        linkPreviewURL: url,
        linkPreviewTitle: title,
        linkPreviewImageData: imageData,
        linkPreviewIconData: iconData,
        linkPreviewFetched: fetched
      )
    }
  }

  // MARK: - Contact Operations

  public func fetchContacts(radioID: UUID) async throws -> [ContactDTO] {
    if let error = stubbedFetchContactError {
      throw error
    }
    return Array(contacts.values.filter { $0.radioID == radioID })
  }

  public func fetchConversations(radioID: UUID) async throws -> [ContactDTO] {
    if let error = stubbedFetchContactError {
      throw error
    }
    return contacts.values
      .filter { $0.radioID == radioID && $0.lastMessageDate != nil }
      .sorted { ($0.lastMessageDate ?? .distantPast) > ($1.lastMessageDate ?? .distantPast) }
  }

  public func fetchContact(id: UUID) async throws -> ContactDTO? {
    if let error = stubbedFetchContactError {
      throw error
    }
    return contacts[id]
  }

  public func fetchContact(radioID: UUID, publicKey: Data) async throws -> ContactDTO? {
    if let error = stubbedFetchContactError {
      throw error
    }
    return contacts.values.first { $0.radioID == radioID && $0.publicKey == publicKey }
  }

  public func fetchContact(radioID: UUID, publicKeyPrefix: Data) async throws -> ContactDTO? {
    if let error = stubbedFetchContactError {
      throw error
    }
    return contacts.values.first { $0.radioID == radioID && $0.publicKey.prefix(6) == publicKeyPrefix }
  }

  public func fetchContactPublicKeysByPrefix(radioID: UUID) async throws -> [UInt8: [Data]] {
    if let error = stubbedFetchContactError {
      throw error
    }
    var result: [UInt8: [Data]] = [:]
    for contact in contacts.values {
      guard contact.radioID == radioID, contact.publicKey.count >= 1 else { continue }
      let prefix = contact.publicKey[0]
      result[prefix, default: []].append(contact.publicKey)
    }
    return result
  }

  public func findContactNameByKeyPrefix(_ prefix: Data) async throws -> String? {
    if let error = stubbedFetchContactError {
      throw error
    }
    return contacts.values.first { $0.publicKey.prefix(prefix.count) == prefix }?.displayName
  }

  public func findContactByPublicKey(_ publicKey: Data) async throws -> ContactDTO? {
    if let error = stubbedFetchContactError {
      throw error
    }
    return contacts.values.first { $0.publicKey == publicKey }
  }

  /// When true, the next `saveContact(radioID:from:)` suspends until `releaseSaveContact()`.
  private var saveContactHoldRequested = false
  private var saveContactHoldContinuation: CheckedContinuation<Void, Never>?

  /// Number of completed `saveContact(radioID:from:)` calls (post-hold). Tests wait on
  /// this after `releaseSaveContact()` so commit/rollback assertions are not racy.
  public private(set) var saveContactCompletedCount = 0

  /// Causes the next frame `saveContact` to suspend until `releaseSaveContact()`.
  public func holdNextSaveContact() {
    saveContactHoldRequested = true
  }

  /// Releases a held `saveContact(radioID:from:)`.
  public func releaseSaveContact() {
    if let continuation = saveContactHoldContinuation {
      saveContactHoldContinuation = nil
      continuation.resume()
    }
    saveContactHoldRequested = false
  }

  /// Whether a frame `saveContact` is currently suspended on the hold gate.
  public var isSaveContactHeld: Bool {
    saveContactHoldContinuation != nil
  }

  @discardableResult
  public func saveContact(radioID: UUID, from frame: ContactFrame) async throws -> (id: UUID, isNew: Bool) {
    if saveContactHoldRequested {
      saveContactHoldRequested = false
      await withCheckedContinuation { (continuation: CheckedContinuation<Void, Never>) in
        saveContactHoldContinuation = continuation
      }
    }
    defer { saveContactCompletedCount += 1 }
    if let error = stubbedSaveContactError {
      throw error
    }
    // Check if contact already exists
    if let existing = contacts.values.first(where: { $0.radioID == radioID && $0.publicKey == frame.publicKey }) {
      return (id: existing.id, isNew: false)
    }
    let id = UUID()
    let dto = ContactDTO(
      id: id,
      radioID: radioID,
      publicKey: frame.publicKey,
      name: frame.name,
      typeRawValue: frame.typeRawValue,
      flags: frame.flags,
      outPathLength: frame.outPathLength,
      outPath: frame.outPath,
      lastAdvertTimestamp: frame.lastAdvertTimestamp,
      latitude: frame.latitude,
      longitude: frame.longitude,
      lastModified: frame.lastModified,
      nickname: nil,
      isBlocked: false,
      isMuted: false,
      isFavorite: false,
      lastMessageDate: nil,
      unreadCount: 0
    )
    contacts[id] = dto
    savedContacts.append(dto)
    return (id: id, isNew: true)
  }

  public func deleteContactIfUnreferenced(id: UUID) async throws {
    // Same actor region as the message map: probe and delete with no suspension between.
    if messages.values.contains(where: { $0.contactID == id }) {
      return
    }
    try await deleteContact(id: id)
  }

  public func saveContact(_ dto: ContactDTO) async throws {
    savedContacts.append(dto)
    if let error = stubbedSaveContactError {
      throw error
    }
    contacts[dto.id] = dto
  }

  public func deleteContact(id: UUID) async throws {
    deletedContactIDs.append(id)
    if let error = stubbedDeleteContactError {
      throw error
    }
    cascadeDeleteContactData(contactID: id)
    contacts.removeValue(forKey: id)
  }

  /// Mirrors the real store's cascade: messages, their pending sends, and
  /// reactions scoped to the contact die together, keyed by the contact ID
  /// value rather than the contact row.
  private func cascadeDeleteContactData(contactID: UUID) {
    let messageIDs = Set(messages.values.filter { $0.contactID == contactID }.map(\.id))
    messages = messages.filter { !messageIDs.contains($0.key) }
    pendingSends = pendingSends.filter { !messageIDs.contains($0.value.messageID) }
    reactions = reactions.compactMapValues { list in
      let kept = list.filter { $0.contactID != contactID }
      return kept.isEmpty ? nil : kept
    }
  }

  public func updateContactLastMessage(contactID: UUID, date: Date?) async throws {
    if let contact = contacts[contactID] {
      contacts[contactID] = ContactDTO(
        id: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        name: contact.name,
        typeRawValue: contact.typeRawValue,
        flags: contact.flags,
        outPathLength: contact.outPathLength,
        outPath: contact.outPath,
        lastAdvertTimestamp: contact.lastAdvertTimestamp,
        latitude: contact.latitude,
        longitude: contact.longitude,
        lastModified: contact.lastModified,
        nickname: contact.nickname,
        isBlocked: contact.isBlocked,
        isMuted: contact.isMuted,
        isFavorite: contact.isFavorite,
        lastMessageDate: date,
        unreadCount: contact.unreadCount,
        unreadMentionCount: contact.unreadMentionCount
      )
    }
  }

  public func incrementUnreadCount(contactID: UUID) async throws {
    if let contact = contacts[contactID] {
      contacts[contactID] = ContactDTO(
        id: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        name: contact.name,
        typeRawValue: contact.typeRawValue,
        flags: contact.flags,
        outPathLength: contact.outPathLength,
        outPath: contact.outPath,
        lastAdvertTimestamp: contact.lastAdvertTimestamp,
        latitude: contact.latitude,
        longitude: contact.longitude,
        lastModified: contact.lastModified,
        nickname: contact.nickname,
        isBlocked: contact.isBlocked,
        isMuted: contact.isMuted,
        isFavorite: contact.isFavorite,
        lastMessageDate: contact.lastMessageDate,
        unreadCount: contact.unreadCount + 1,
        unreadMentionCount: contact.unreadMentionCount
      )
    }
  }

  public func clearUnreadCount(contactID: UUID) async throws {
    if let contact = contacts[contactID] {
      contacts[contactID] = ContactDTO(
        id: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        name: contact.name,
        typeRawValue: contact.typeRawValue,
        flags: contact.flags,
        outPathLength: contact.outPathLength,
        outPath: contact.outPath,
        lastAdvertTimestamp: contact.lastAdvertTimestamp,
        latitude: contact.latitude,
        longitude: contact.longitude,
        lastModified: contact.lastModified,
        nickname: contact.nickname,
        isBlocked: contact.isBlocked,
        isMuted: contact.isMuted,
        isFavorite: contact.isFavorite,
        lastMessageDate: contact.lastMessageDate,
        unreadCount: 0,
        unreadMentionCount: contact.unreadMentionCount
      )
    }
  }

  // MARK: - Mention Tracking

  public func markMentionSeen(messageID: UUID) async throws {
    if let message = messages[messageID] {
      messages[messageID] = MessageDTO(
        id: message.id,
        radioID: message.radioID,
        contactID: message.contactID,
        channelIndex: message.channelIndex,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        direction: message.direction,
        status: message.status,
        textType: message.textType,
        ackCode: message.ackCode,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderKeyPrefix,
        senderNodeName: message.senderNodeName,
        isRead: message.isRead,
        replyToID: message.replyToID,
        roundTripTime: message.roundTripTime,
        heardRepeats: message.heardRepeats,
        retryAttempt: message.retryAttempt,
        maxRetryAttempts: message.maxRetryAttempts,
        deduplicationKey: message.deduplicationKey,
        linkPreviewURL: message.linkPreviewURL,
        linkPreviewTitle: message.linkPreviewTitle,
        linkPreviewImageData: message.linkPreviewImageData,
        linkPreviewIconData: message.linkPreviewIconData,
        linkPreviewFetched: message.linkPreviewFetched,
        containsSelfMention: message.containsSelfMention,
        mentionSeen: true
      )
    }
  }

  public func incrementUnreadMentionCount(contactID: UUID) async throws {
    if let contact = contacts[contactID] {
      contacts[contactID] = ContactDTO(
        id: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        name: contact.name,
        typeRawValue: contact.typeRawValue,
        flags: contact.flags,
        outPathLength: contact.outPathLength,
        outPath: contact.outPath,
        lastAdvertTimestamp: contact.lastAdvertTimestamp,
        latitude: contact.latitude,
        longitude: contact.longitude,
        lastModified: contact.lastModified,
        nickname: contact.nickname,
        isBlocked: contact.isBlocked,
        isMuted: contact.isMuted,
        isFavorite: contact.isFavorite,
        lastMessageDate: contact.lastMessageDate,
        unreadCount: contact.unreadCount,
        unreadMentionCount: contact.unreadMentionCount + 1
      )
    }
  }

  public func decrementUnreadMentionCount(contactID: UUID) async throws {
    if let contact = contacts[contactID] {
      contacts[contactID] = ContactDTO(
        id: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        name: contact.name,
        typeRawValue: contact.typeRawValue,
        flags: contact.flags,
        outPathLength: contact.outPathLength,
        outPath: contact.outPath,
        lastAdvertTimestamp: contact.lastAdvertTimestamp,
        latitude: contact.latitude,
        longitude: contact.longitude,
        lastModified: contact.lastModified,
        nickname: contact.nickname,
        isBlocked: contact.isBlocked,
        isMuted: contact.isMuted,
        isFavorite: contact.isFavorite,
        lastMessageDate: contact.lastMessageDate,
        unreadCount: contact.unreadCount,
        unreadMentionCount: max(0, contact.unreadMentionCount - 1)
      )
    }
  }

  public func clearUnreadMentionCount(contactID: UUID) async throws {
    if let contact = contacts[contactID] {
      contacts[contactID] = ContactDTO(
        id: contact.id,
        radioID: contact.radioID,
        publicKey: contact.publicKey,
        name: contact.name,
        typeRawValue: contact.typeRawValue,
        flags: contact.flags,
        outPathLength: contact.outPathLength,
        outPath: contact.outPath,
        lastAdvertTimestamp: contact.lastAdvertTimestamp,
        latitude: contact.latitude,
        longitude: contact.longitude,
        lastModified: contact.lastModified,
        nickname: contact.nickname,
        isBlocked: contact.isBlocked,
        isMuted: contact.isMuted,
        isFavorite: contact.isFavorite,
        lastMessageDate: contact.lastMessageDate,
        unreadCount: contact.unreadCount,
        unreadMentionCount: 0
      )
    }
  }

  public func incrementChannelUnreadMentionCount(channelID: UUID) async throws {
    if let channel = channels[channelID] {
      channels[channelID] = ChannelDTO(
        id: channel.id,
        radioID: channel.radioID,
        index: channel.index,
        name: channel.name,
        secret: channel.secret,
        isEnabled: channel.isEnabled,
        lastMessageDate: channel.lastMessageDate,
        unreadCount: channel.unreadCount,
        unreadMentionCount: channel.unreadMentionCount + 1,
        notificationLevel: channel.notificationLevel,
        isFavorite: channel.isFavorite
      )
    }
  }

  public func decrementChannelUnreadMentionCount(channelID: UUID) async throws {
    if let channel = channels[channelID] {
      channels[channelID] = ChannelDTO(
        id: channel.id,
        radioID: channel.radioID,
        index: channel.index,
        name: channel.name,
        secret: channel.secret,
        isEnabled: channel.isEnabled,
        lastMessageDate: channel.lastMessageDate,
        unreadCount: channel.unreadCount,
        unreadMentionCount: max(0, channel.unreadMentionCount - 1),
        notificationLevel: channel.notificationLevel,
        isFavorite: channel.isFavorite
      )
    }
  }

  public func clearChannelUnreadMentionCount(channelID: UUID) async throws {
    if let channel = channels[channelID] {
      channels[channelID] = ChannelDTO(
        id: channel.id,
        radioID: channel.radioID,
        index: channel.index,
        name: channel.name,
        secret: channel.secret,
        isEnabled: channel.isEnabled,
        lastMessageDate: channel.lastMessageDate,
        unreadCount: channel.unreadCount,
        unreadMentionCount: 0,
        notificationLevel: channel.notificationLevel,
        isFavorite: channel.isFavorite
      )
    }
  }

  public func fetchUnseenMentionIDs(contactID: UUID) async throws -> [UUID] {
    messages.values
      .filter { $0.contactID == contactID && $0.containsSelfMention && !$0.mentionSeen }
      .sorted { $0.timestamp < $1.timestamp }
      .map(\.id)
  }

  public func fetchUnseenChannelMentionIDs(radioID: UUID, channelIndex: UInt8) async throws -> [UUID] {
    messages.values
      .filter { $0.radioID == radioID && $0.channelIndex == channelIndex && $0.containsSelfMention && !$0.mentionSeen }
      .sorted { $0.timestamp < $1.timestamp }
      .map(\.id)
  }

  public func deleteMessagesForContact(contactID: UUID) async throws {
    deletedMessagesForContactIDs.append(contactID)
    cascadeDeleteContactData(contactID: contactID)
  }

  public func fetchBlockedContacts(radioID: UUID) async throws -> [ContactDTO] {
    if let error = stubbedFetchContactError {
      throw error
    }
    return Array(contacts.values.filter { $0.radioID == radioID && $0.isBlocked })
  }

  // MARK: - Blocked Channel Senders

  public var blockedChannelSenders: [String: BlockedChannelSenderDTO] = [:]
  public private(set) var savedBlockedChannelSenders: [BlockedChannelSenderDTO] = []
  public private(set) var deletedBlockedChannelSenderNames: [(radioID: UUID, name: String)] = []

  public func saveBlockedChannelSender(_ dto: BlockedChannelSenderDTO) async throws {
    savedBlockedChannelSenders.append(dto)
    blockedChannelSenders["\(dto.radioID)-\(dto.name)"] = dto
  }

  public func deleteBlockedChannelSender(radioID: UUID, name: String) async throws {
    deletedBlockedChannelSenderNames.append((radioID: radioID, name: name))
    blockedChannelSenders.removeValue(forKey: "\(radioID)-\(name)")
  }

  public func fetchBlockedChannelSenders(radioID: UUID) async throws -> [BlockedChannelSenderDTO] {
    Array(blockedChannelSenders.values.filter { $0.radioID == radioID })
  }

  // MARK: - Channel Operations

  public func fetchChannels(radioID: UUID) async throws -> [ChannelDTO] {
    if let error = stubbedFetchChannelError {
      throw error
    }
    return channels.values.filter { $0.radioID == radioID }.sorted { $0.index < $1.index }
  }

  public func fetchChannel(radioID: UUID, index: UInt8) async throws -> ChannelDTO? {
    if let error = stubbedFetchChannelError {
      throw error
    }
    return channels.values.first { $0.radioID == radioID && $0.index == index }
  }

  public func fetchChannel(id: UUID) async throws -> ChannelDTO? {
    if let error = stubbedFetchChannelError {
      throw error
    }
    return channels[id]
  }

  @discardableResult
  public func saveChannel(radioID: UUID, from info: ChannelInfo) async throws -> UUID {
    if let error = stubbedSaveChannelError {
      throw error
    }
    // Check if channel already exists
    if let existing = channels.values.first(where: { $0.radioID == radioID && $0.index == info.index }) {
      // Update existing
      channels[existing.id] = ChannelDTO(
        id: existing.id,
        radioID: radioID,
        index: info.index,
        name: info.name,
        secret: info.secret,
        isEnabled: !info.name.isEmpty,
        lastMessageDate: existing.lastMessageDate,
        unreadCount: existing.unreadCount,
        unreadMentionCount: existing.unreadMentionCount,
        notificationLevel: existing.notificationLevel,
        isFavorite: existing.isFavorite
      )
      return existing.id
    }
    let id = UUID()
    let dto = ChannelDTO(
      id: id,
      radioID: radioID,
      index: info.index,
      name: info.name,
      secret: info.secret,
      isEnabled: !info.name.isEmpty,
      lastMessageDate: nil,
      unreadCount: 0,
      unreadMentionCount: 0,
      notificationLevel: .all,
      isFavorite: false
    )
    channels[id] = dto
    savedChannels.append(dto)
    return id
  }

  public func saveChannel(_ dto: ChannelDTO) async throws {
    savedChannels.append(dto)
    if let error = stubbedSaveChannelError {
      throw error
    }
    channels[dto.id] = dto
  }

  public func deleteChannel(id: UUID) async throws {
    deletedChannelIDs.append(id)
    if let error = stubbedDeleteChannelError {
      throw error
    }
    channels.removeValue(forKey: id)
  }

  public func deleteMessagesForChannel(radioID: UUID, channelIndex: UInt8) async throws {
    deletedMessagesForChannelCalls.append((radioID: radioID, channelIndex: channelIndex))
    messages = messages.filter { $0.value.radioID != radioID || $0.value.channelIndex != channelIndex }
  }

  public func deleteChannelMessages(fromSender senderName: String, radioID: UUID) async throws {
    deletedChannelMessagesFromSenderCalls.append((senderName: senderName, radioID: radioID))
    messages = messages.filter { _, msg in
      !(msg.senderNodeName == senderName && msg.radioID == radioID && msg.channelIndex != nil)
    }
  }

  public func updateChannelLastMessage(channelID: UUID, date: Date?) async throws {
    if let channel = channels[channelID] {
      channels[channelID] = ChannelDTO(
        id: channel.id,
        radioID: channel.radioID,
        index: channel.index,
        name: channel.name,
        secret: channel.secret,
        isEnabled: channel.isEnabled,
        lastMessageDate: date,
        unreadCount: channel.unreadCount,
        unreadMentionCount: channel.unreadMentionCount,
        notificationLevel: channel.notificationLevel,
        isFavorite: channel.isFavorite
      )
    }
  }

  public func incrementChannelUnreadCount(channelID: UUID) async throws {
    if let channel = channels[channelID] {
      channels[channelID] = ChannelDTO(
        id: channel.id,
        radioID: channel.radioID,
        index: channel.index,
        name: channel.name,
        secret: channel.secret,
        isEnabled: channel.isEnabled,
        lastMessageDate: channel.lastMessageDate,
        unreadCount: channel.unreadCount + 1,
        unreadMentionCount: channel.unreadMentionCount,
        notificationLevel: channel.notificationLevel,
        isFavorite: channel.isFavorite
      )
    }
  }

  public func clearChannelUnreadCount(radioID: UUID, index: UInt8) async throws {
    if let channel = channels.values.first(where: { $0.radioID == radioID && $0.index == index }) {
      try await clearChannelUnreadCount(channelID: channel.id)
    }
  }

  public func clearChannelUnreadCount(channelID: UUID) async throws {
    if let channel = channels[channelID] {
      channels[channelID] = ChannelDTO(
        id: channel.id,
        radioID: channel.radioID,
        index: channel.index,
        name: channel.name,
        secret: channel.secret,
        isEnabled: channel.isEnabled,
        lastMessageDate: channel.lastMessageDate,
        unreadCount: 0,
        unreadMentionCount: channel.unreadMentionCount,
        notificationLevel: channel.notificationLevel,
        isFavorite: channel.isFavorite
      )
    }
  }

  public func setChannelNotificationLevel(_ channelID: UUID, level: NotificationLevel) async throws {
    if let channel = channels[channelID] {
      channels[channelID] = ChannelDTO(
        id: channel.id,
        radioID: channel.radioID,
        index: channel.index,
        name: channel.name,
        secret: channel.secret,
        isEnabled: channel.isEnabled,
        lastMessageDate: channel.lastMessageDate,
        unreadCount: channel.unreadCount,
        unreadMentionCount: channel.unreadMentionCount,
        notificationLevel: level,
        isFavorite: channel.isFavorite
      )
    }
  }

  public func setSessionNotificationLevel(_ sessionID: UUID, level: NotificationLevel) async throws {
    // Stub - sessions not tracked in mock
  }

  // MARK: - RxLogEntry Lookup

  private var mockRxLogEntries: [RxLogEntryDTO] = []

  public func setMockRxLogEntry(_ entry: RxLogEntryDTO) {
    mockRxLogEntries.append(entry)
  }

  public func findRxLogEntry(
    radioID: UUID,
    channelIndex: UInt8?,
    senderTimestamp: UInt32
  ) throws -> RxLogEntryDTO? {
    if let channelIndex {
      mockRxLogEntries.first { entry in
        entry.radioID == radioID &&
          entry.channelIndex == channelIndex &&
          entry.senderTimestamp == senderTimestamp
      }
    } else {
      mockRxLogEntries.first { entry in
        entry.radioID == radioID &&
          entry.senderTimestamp == senderTimestamp &&
          entry.channelIndex == nil
      }
    }
  }

  public func findRxLogEntryBySenderPrefix(
    radioID: UUID,
    senderPrefixByte: UInt8,
    receivedSince: Date
  ) throws -> RxLogEntryDTO? {
    mockRxLogEntries.first { entry in
      entry.radioID == radioID &&
        entry.channelIndex == nil &&
        entry.payloadType == .textMessage &&
        entry.receivedAt >= receivedSince &&
        entry.packetPayload.count >= 2 &&
        entry.packetPayload[1] == senderPrefixByte
    }
  }

  // MARK: - RX Log

  public private(set) var updatedRxLogRegions: [(id: UUID, regionScope: String?)] = []
  public private(set) var updatedChannelMessageRegions: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?)] = []
  public private(set) var updatedDMMessageRegions: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?)] = []

  public func saveRxLogEntry(_ dto: RxLogEntryDTO) async throws {
    mockRxLogEntries.append(dto)
  }

  public func fetchRxLogEntries(radioID: UUID, limit: Int) async throws -> [RxLogEntryDTO] {
    Array(
      mockRxLogEntries
        .filter { $0.radioID == radioID }
        .sorted { $0.receivedAt > $1.receivedAt }
        .prefix(limit)
    )
  }

  public func clearRxLogEntries(radioID: UUID) async throws {
    mockRxLogEntries.removeAll { $0.radioID == radioID }
  }

  public func pruneRxLogEntries(radioID: UUID, keepCount: Int, pruneThreshold: Int) async throws {
    let entries = mockRxLogEntries.filter { $0.radioID == radioID }
    guard entries.count > keepCount + pruneThreshold else { return }
    let keptIDs = Set(entries.sorted { $0.receivedAt > $1.receivedAt }.prefix(keepCount).map(\.id))
    mockRxLogEntries.removeAll { $0.radioID == radioID && !keptIDs.contains($0.id) }
  }

  public func fetchEntriesWithMissingRegion(radioID: UUID) async throws -> [RxLogEntryDTO] {
    mockRxLogEntries.filter { $0.radioID == radioID && $0.transportCode != nil && $0.regionScope == nil }
  }

  public func fetchRecentEntriesByDecryptStatus(radioID: UUID, status: DecryptStatus, since: Date) async throws -> [RxLogEntryDTO] {
    mockRxLogEntries.filter { $0.radioID == radioID && $0.decryptStatus == status && $0.receivedAt >= since }
  }

  public func batchUpdateRxLogRegion(updates: [(id: UUID, regionScope: String?)]) async throws {
    updatedRxLogRegions.append(contentsOf: updates)
  }

  public func batchUpdateRxLogDecryption(
    _ updates: [(id: UUID, channelIndex: UInt8?, channelName: String?, senderTimestamp: UInt32?)]
  ) async throws {
    for update in updates {
      guard let index = mockRxLogEntries.firstIndex(where: { $0.id == update.id }) else { continue }
      mockRxLogEntries[index].channelIndex = update.channelIndex
      mockRxLogEntries[index].channelName = update.channelName
      mockRxLogEntries[index].senderTimestamp = update.senderTimestamp
    }
  }

  public func batchUpdateChannelMessageRegion(
    radioID: UUID,
    updates: [(channelIndex: UInt8, senderTimestamp: UInt32, regionScope: String?)]
  ) async throws {
    updatedChannelMessageRegions.append(contentsOf: updates)
  }

  public func batchUpdateDMMessageRegion(
    radioID: UUID,
    updates: [(senderPrefixByte: UInt8, senderTimestamp: UInt32, regionScope: String?)]
  ) async throws {
    updatedDMMessageRegions.append(contentsOf: updates)
  }

  // MARK: - Saved Trace Paths

  public var savedTracePaths: [UUID: SavedTracePathDTO] = [:]

  public func fetchSavedTracePaths(radioID: UUID) async throws -> [SavedTracePathDTO] {
    savedTracePaths.values.filter { $0.radioID == radioID }.sorted(by: { $0.createdDate > $1.createdDate })
  }

  public func fetchSavedTracePath(id: UUID) async throws -> SavedTracePathDTO? {
    savedTracePaths[id]
  }

  public func createSavedTracePath(radioID: UUID, name: String, pathBytes: Data, hashSize: Int, initialRun: TracePathRunDTO?) async throws -> SavedTracePathDTO {
    let id = UUID()
    let runs = initialRun.map { [$0] } ?? []
    let dto = SavedTracePathDTO(
      id: id,
      radioID: radioID,
      name: name,
      pathBytes: pathBytes,
      hashSize: hashSize,
      createdDate: Date(),
      runs: runs
    )
    savedTracePaths[id] = dto
    return dto
  }

  public func updateSavedTracePathName(id: UUID, name: String) async throws {
    if let path = savedTracePaths[id] {
      savedTracePaths[id] = SavedTracePathDTO(
        id: path.id,
        radioID: path.radioID,
        name: name,
        pathBytes: path.pathBytes,
        hashSize: path.hashSize,
        createdDate: path.createdDate,
        runs: path.runs
      )
    }
  }

  public func deleteSavedTracePath(id: UUID) async throws {
    savedTracePaths.removeValue(forKey: id)
  }

  public func appendTracePathRun(pathID: UUID, run: TracePathRunDTO) async throws {
    if let path = savedTracePaths[pathID] {
      var runs = path.runs
      runs.append(run)
      savedTracePaths[pathID] = SavedTracePathDTO(
        id: path.id,
        radioID: path.radioID,
        name: path.name,
        pathBytes: path.pathBytes,
        hashSize: path.hashSize,
        createdDate: path.createdDate,
        runs: runs
      )
    }
  }

  // MARK: - Heard Repeats

  public func findSentChannelMessage(radioID: UUID, channelIndex: UInt8, timestamp: UInt32, text: String) async throws -> MessageDTO? {
    nil // Stub
  }

  public func saveMessageRepeat(_ dto: MessageRepeatDTO) async throws {
    // Stub - no-op
  }

  public func fetchMessageRepeats(messageID: UUID) async throws -> [MessageRepeatDTO] {
    [] // Stub
  }

  public func deleteMessageRepeats(messageID: UUID) async throws {
    // Stub - no-op
  }

  public func messageRepeatExists(rxLogEntryID: UUID) async throws -> Bool {
    false // Stub
  }

  public func incrementMessageHeardRepeats(id: UUID) async throws -> Int {
    0 // Stub
  }

  public func incrementMessageSendCount(id: UUID) async throws -> Int {
    if let message = messages[id] {
      let newCount = message.sendCount + 1
      messages[id] = MessageDTO(
        id: message.id,
        radioID: message.radioID,
        contactID: message.contactID,
        channelIndex: message.channelIndex,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        direction: message.direction,
        status: message.status,
        textType: message.textType,
        ackCode: message.ackCode,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderKeyPrefix,
        senderNodeName: message.senderNodeName,
        isRead: message.isRead,
        replyToID: message.replyToID,
        roundTripTime: message.roundTripTime,
        heardRepeats: message.heardRepeats,
        sendCount: newCount,
        retryAttempt: message.retryAttempt,
        maxRetryAttempts: message.maxRetryAttempts
      )
      return newCount
    }
    return 0
  }

  // MARK: - Debug Log Operations

  public func saveDebugLogEntries(_ entries: [DebugLogEntryDTO]) async throws {
    if let error = stubbedDebugLogError {
      throw error
    }
    debugLogEntries.append(contentsOf: entries)
  }

  public func fetchDebugLogEntries(since: Date, limit: Int) async throws -> [DebugLogEntryDTO] {
    if let error = stubbedDebugLogError {
      throw error
    }
    return debugLogEntries
      .filter { $0.timestamp >= since }
      .sorted { $0.timestamp < $1.timestamp }
      .prefix(limit)
      .map(\.self)
  }

  public func countDebugLogEntries() async throws -> Int {
    if let error = stubbedDebugLogError {
      throw error
    }
    return debugLogEntries.count
  }

  public func pruneDebugLogEntries(olderThan cutoff: Date, keepCount: Int) async throws {
    if let error = stubbedDebugLogError {
      throw error
    }
    debugLogEntries.removeAll { $0.timestamp < cutoff }
    let sorted = debugLogEntries.sorted { $0.timestamp > $1.timestamp }
    let toKeep = Set(sorted.prefix(keepCount).map(\.id))
    debugLogEntries.removeAll { !toKeep.contains($0.id) }
  }

  public func clearDebugLogEntries() async throws {
    if let error = stubbedDebugLogError {
      throw error
    }
    debugLogEntries.removeAll()
  }

  // MARK: - Link Preview Cache

  public var linkPreviews: [String: LinkPreviewDataDTO] = [:]

  public func fetchLinkPreview(url: String) async throws -> LinkPreviewDataDTO? {
    linkPreviews[url]
  }

  public func saveLinkPreview(_ dto: LinkPreviewDataDTO) async throws {
    linkPreviews[dto.url] = dto
  }

  // MARK: - Device Operations

  /// Devices keyed by radio ID
  public var devices: [UUID: DeviceDTO] = [:]
  public private(set) var updatedLastContactSyncs: [(radioID: UUID, timestamp: UInt32)] = []

  public func fetchDevice(id: UUID) async throws -> DeviceDTO? {
    devices.values.first { $0.id == id }
  }

  public func fetchDevice(radioID: UUID) async throws -> DeviceDTO? {
    devices[radioID]
  }

  public func updateDeviceLastContactSync(radioID: UUID, timestamp: UInt32) async throws {
    updatedLastContactSyncs.append((radioID: radioID, timestamp: timestamp))
    if var device = devices[radioID] {
      device.lastContactSync = timestamp
      devices[radioID] = device
    }
  }

  public func saveDevice(_ dto: DeviceDTO) async throws {
    devices[dto.radioID] = dto
  }

  // MARK: - Room Session State

  public var remoteNodeSessions: [UUID: RemoteNodeSessionDTO] = [:]
  public private(set) var markedDisconnectedSessionIDs: [UUID] = []
  public private(set) var markedConnectedSessionIDs: [UUID] = []
  public private(set) var updatedRoomActivitySessionIDs: [UUID] = []
  public private(set) var savedRemoteNodeSessions: [RemoteNodeSessionDTO] = []
  public private(set) var updatedSessionConnections: [(id: UUID, isConnected: Bool, permissionLevel: RoomPermissionLevel)] = []
  public private(set) var deletedRemoteNodeSessionIDs: [UUID] = []

  public func fetchRemoteNodeSession(id: UUID) async throws -> RemoteNodeSessionDTO? {
    remoteNodeSessions[id]
  }

  public func fetchRemoteNodeSession(publicKey: Data) async throws -> RemoteNodeSessionDTO? {
    remoteNodeSessions.values.first { $0.publicKey == publicKey }
  }

  public func fetchRemoteNodeSessionByPrefix(_ prefix: Data) async throws -> RemoteNodeSessionDTO? {
    remoteNodeSessions.values.first { $0.publicKey.prefix(6) == prefix }
  }

  public func fetchRemoteNodeSessions(radioID: UUID) async throws -> [RemoteNodeSessionDTO] {
    remoteNodeSessions.values.filter { $0.radioID == radioID }
  }

  public func fetchConnectedRemoteNodeSessions() async throws -> [RemoteNodeSessionDTO] {
    remoteNodeSessions.values.filter(\.isConnected)
  }

  public func saveRemoteNodeSessionDTO(_ dto: RemoteNodeSessionDTO) async throws {
    savedRemoteNodeSessions.append(dto)
    remoteNodeSessions[dto.id] = dto
  }

  public func updateRemoteNodeSessionConnection(id: UUID, isConnected: Bool, permissionLevel: RoomPermissionLevel) async throws {
    updatedSessionConnections.append((id: id, isConnected: isConnected, permissionLevel: permissionLevel))
    if let session = remoteNodeSessions[id] {
      remoteNodeSessions[id] = RemoteNodeSessionDTO(
        id: session.id,
        radioID: session.radioID,
        publicKey: session.publicKey,
        name: session.name,
        role: session.role,
        latitude: session.latitude,
        longitude: session.longitude,
        isConnected: isConnected,
        permissionLevel: permissionLevel,
        lastConnectedDate: session.lastConnectedDate,
        lastBatteryMillivolts: session.lastBatteryMillivolts,
        lastUptimeSeconds: session.lastUptimeSeconds,
        lastNoiseFloor: session.lastNoiseFloor,
        unreadCount: session.unreadCount,
        notificationLevel: session.notificationLevel,
        lastRxAirtimeSeconds: session.lastRxAirtimeSeconds,
        neighborCount: session.neighborCount,
        lastSyncTimestamp: session.lastSyncTimestamp,
        lastMessageDate: session.lastMessageDate
      )
    }
  }

  public func cleanupDuplicateRemoteNodeSessions(publicKey: Data, keepID: UUID) async throws {
    let duplicateIDs = remoteNodeSessions.values
      .filter { $0.publicKey == publicKey && $0.id != keepID }
      .map(\.id)
    for id in duplicateIDs {
      remoteNodeSessions.removeValue(forKey: id)
    }
  }

  public func deleteRemoteNodeSession(id: UUID) async throws {
    deletedRemoteNodeSessionIDs.append(id)
    remoteNodeSessions.removeValue(forKey: id)
    roomMessages = roomMessages.filter { $0.value.sessionID != id }
  }

  public func markSessionDisconnected(_ sessionID: UUID) throws {
    markedDisconnectedSessionIDs.append(sessionID)
  }

  @discardableResult
  public func markRoomSessionConnected(_ sessionID: UUID) throws -> Bool {
    markedConnectedSessionIDs.append(sessionID)
    return true
  }

  public func updateRoomActivity(_ sessionID: UUID, syncTimestamp: UInt32?) throws {
    updatedRoomActivitySessionIDs.append(sessionID)
  }

  // MARK: - Room Message Operations

  public var roomMessages: [UUID: RoomMessageDTO] = [:]
  public private(set) var savedRoomMessages: [RoomMessageDTO] = []
  public private(set) var updatedRoomMessageStatuses: [(id: UUID, status: MessageStatus, ackCode: UInt32?, roundTripTime: UInt32?)] = []

  public func saveRoomMessage(_ dto: RoomMessageDTO) async throws {
    savedRoomMessages.append(dto)
    roomMessages[dto.id] = dto
  }

  public func fetchRoomMessage(id: UUID) async throws -> RoomMessageDTO? {
    roomMessages[id]
  }

  public func fetchRoomMessages(sessionID: UUID, limit: Int?, offset: Int?) async throws -> [RoomMessageDTO] {
    let filtered = roomMessages.values.filter { $0.sessionID == sessionID }
      .sorted { $0.timestamp < $1.timestamp }
    var result = Array(filtered)
    if let offset {
      result = Array(result.dropFirst(offset))
    }
    if let limit {
      result = Array(result.prefix(limit))
    }
    return result
  }

  public func isDuplicateRoomMessage(sessionID: UUID, deduplicationKey: String) async throws -> Bool {
    roomMessages.values.contains { $0.sessionID == sessionID && $0.deduplicationKey == deduplicationKey }
  }

  public func updateRoomMessageStatus(
    id: UUID,
    status: MessageStatus,
    ackCode: UInt32?,
    roundTripTime: UInt32?
  ) async throws {
    updatedRoomMessageStatuses.append((id: id, status: status, ackCode: ackCode, roundTripTime: roundTripTime))
    if let message = roomMessages[id] {
      roomMessages[id] = RoomMessageDTO(
        id: message.id,
        sessionID: message.sessionID,
        authorKeyPrefix: message.authorKeyPrefix,
        authorName: message.authorName,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        isFromSelf: message.isFromSelf,
        status: status,
        ackCode: ackCode ?? message.ackCode,
        roundTripTime: roundTripTime ?? message.roundTripTime,
        retryAttempt: message.retryAttempt,
        maxRetryAttempts: message.maxRetryAttempts
      )
    }
  }

  public func updateRoomMessageRetryStatus(
    id: UUID,
    status: MessageStatus,
    retryAttempt: Int,
    maxRetryAttempts: Int
  ) async throws {
    if let message = roomMessages[id] {
      roomMessages[id] = RoomMessageDTO(
        id: message.id,
        sessionID: message.sessionID,
        authorKeyPrefix: message.authorKeyPrefix,
        authorName: message.authorName,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        isFromSelf: message.isFromSelf,
        status: status,
        ackCode: message.ackCode,
        roundTripTime: message.roundTripTime,
        retryAttempt: retryAttempt,
        maxRetryAttempts: maxRetryAttempts
      )
    }
  }

  public private(set) var incrementedRoomUnreadSessionIDs: [UUID] = []
  public private(set) var resetRoomUnreadSessionIDs: [UUID] = []

  public func incrementRoomUnreadCount(_ sessionID: UUID) async throws {
    incrementedRoomUnreadSessionIDs.append(sessionID)
  }

  public func resetRoomUnreadCount(_ sessionID: UUID) async throws {
    resetRoomUnreadSessionIDs.append(sessionID)
  }

  // MARK: - Discovered Nodes

  public var discoveredNodes: [UUID: DiscoveredNodeDTO] = [:]

  public func upsertDiscoveredNode(radioID: UUID, from frame: ContactFrame) async throws -> (node: DiscoveredNodeDTO, isNew: Bool) {
    if let existing = discoveredNodes.values.first(where: { $0.radioID == radioID && $0.publicKey == frame.publicKey }) {
      let updated = DiscoveredNodeDTO(
        id: existing.id,
        radioID: radioID,
        publicKey: frame.publicKey,
        name: frame.name,
        typeRawValue: frame.type.rawValue,
        lastHeard: Date(),
        lastAdvertTimestamp: frame.lastAdvertTimestamp,
        latitude: frame.latitude,
        longitude: frame.longitude,
        outPathLength: frame.outPathLength,
        outPath: frame.outPath,
        inboundHopCount: existing.inboundHopCount,
        inboundHopAdvertTimestamp: existing.inboundHopAdvertTimestamp
      )
      discoveredNodes[existing.id] = updated
      return (node: updated, isNew: false)
    }

    let id = UUID()
    let dto = DiscoveredNodeDTO(
      id: id,
      radioID: radioID,
      publicKey: frame.publicKey,
      name: frame.name,
      typeRawValue: frame.type.rawValue,
      lastHeard: Date(),
      lastAdvertTimestamp: frame.lastAdvertTimestamp,
      latitude: frame.latitude,
      longitude: frame.longitude,
      outPathLength: frame.outPathLength,
      outPath: frame.outPath,
      inboundHopCount: nil,
      inboundHopAdvertTimestamp: nil
    )
    discoveredNodes[id] = dto
    return (node: dto, isNew: true)
  }

  /// Records every `setInboundHopCount` call for assertion and applies the same adopt rule as the
  /// production store so tests can observe the resulting stored value via `discoveredNodes`.
  public private(set) var inboundHopCountCalls: [(radioID: UUID, publicKey: Data, hopCount: Int, advertTimestamp: UInt32?)] = []

  public func setInboundHopCount(radioID: UUID, publicKey: Data, hopCount: Int, advertTimestamp: UInt32?) async throws {
    inboundHopCountCalls.append((radioID: radioID, publicKey: publicKey, hopCount: hopCount, advertTimestamp: advertTimestamp))
    guard let existing = discoveredNodes.values.first(where: {
      $0.radioID == radioID && $0.publicKey == publicKey
    }) else { return }
    guard let adopted = adoptInboundHop(
      storedHops: existing.inboundHopCount,
      storedTimestamp: existing.inboundHopAdvertTimestamp,
      incomingHops: hopCount,
      incomingTimestamp: advertTimestamp
    ) else { return }
    discoveredNodes[existing.id] = DiscoveredNodeDTO(
      id: existing.id,
      radioID: existing.radioID,
      publicKey: existing.publicKey,
      name: existing.name,
      typeRawValue: existing.typeRawValue,
      lastHeard: existing.lastHeard,
      lastAdvertTimestamp: existing.lastAdvertTimestamp,
      latitude: existing.latitude,
      longitude: existing.longitude,
      outPathLength: existing.outPathLength,
      outPath: existing.outPath,
      inboundHopCount: adopted.hopCount,
      inboundHopAdvertTimestamp: adopted.advertTimestamp
    )
  }

  public func fetchDiscoveredNodes(radioID: UUID) async throws -> [DiscoveredNodeDTO] {
    discoveredNodes.values.filter { $0.radioID == radioID }
  }

  public func deleteDiscoveredNode(id: UUID) async throws {
    discoveredNodes.removeValue(forKey: id)
  }

  public func clearDiscoveredNodes(radioID: UUID) async throws {
    let keysToRemove = discoveredNodes.values
      .filter { $0.radioID == radioID }
      .map(\.id)
    for key in keysToRemove {
      discoveredNodes.removeValue(forKey: key)
    }
  }

  public func fetchContactPublicKeys(radioID: UUID) async throws -> Set<Data> {
    if let error = stubbedFetchContactError {
      throw error
    }
    return Set(contacts.values.filter { $0.radioID == radioID }.map(\.publicKey))
  }

  // MARK: - Reactions

  public var reactions: [UUID: [ReactionDTO]] = [:]
  public private(set) var savedReactions: [ReactionDTO] = []
  public private(set) var deletedReactionsForMessageIDs: [UUID] = []

  public func fetchReactions(for messageID: UUID, limit: Int) async throws -> [ReactionDTO] {
    let messageReactions = reactions[messageID] ?? []
    return Array(messageReactions.sorted { $0.receivedAt > $1.receivedAt }.prefix(limit))
  }

  public func saveReaction(_ dto: ReactionDTO) async throws {
    savedReactions.append(dto)
    reactions[dto.messageID, default: []].append(dto)
  }

  public func reactionExists(messageID: UUID, senderName: String, emoji: String) async throws -> Bool {
    let messageReactions = reactions[messageID] ?? []
    return messageReactions.contains { $0.senderName == senderName && $0.emoji == emoji }
  }

  public func updateMessageReactionSummary(messageID: UUID, summary: String?) async throws {
    if let message = messages[messageID] {
      messages[messageID] = MessageDTO(
        id: message.id,
        radioID: message.radioID,
        contactID: message.contactID,
        channelIndex: message.channelIndex,
        text: message.text,
        timestamp: message.timestamp,
        createdAt: message.createdAt,
        direction: message.direction,
        status: message.status,
        textType: message.textType,
        ackCode: message.ackCode,
        pathLength: message.pathLength,
        snr: message.snr,
        senderKeyPrefix: message.senderKeyPrefix,
        senderNodeName: message.senderNodeName,
        isRead: message.isRead,
        replyToID: message.replyToID,
        roundTripTime: message.roundTripTime,
        heardRepeats: message.heardRepeats,
        retryAttempt: message.retryAttempt,
        maxRetryAttempts: message.maxRetryAttempts,
        reactionSummary: summary
      )
    }
  }

  public func deleteReactionsForMessage(messageID: UUID) async throws {
    deletedReactionsForMessageIDs.append(messageID)
    reactions.removeValue(forKey: messageID)
  }

  // MARK: - Node Status Snapshots

  public var nodeStatusSnapshots: [NodeStatusSnapshotDTO] = []

  // swiftlint:disable:next function_parameter_count
  public func saveNodeStatusSnapshot(
    nodePublicKey: Data,
    batteryMillivolts: UInt16?,
    lastSNR: Double?,
    lastRSSI: Int16?,
    noiseFloor: Int16?,
    uptimeSeconds: UInt32?,
    rxAirtimeSeconds: UInt32?,
    packetsSent: UInt32?,
    packetsReceived: UInt32?,
    receiveErrors: UInt32?,
    postedCount: UInt16?,
    postPushCount: UInt16?
  ) async throws -> UUID {
    let dto = NodeStatusSnapshotDTO(
      nodePublicKey: nodePublicKey,
      batteryMillivolts: batteryMillivolts,
      lastSNR: lastSNR,
      lastRSSI: lastRSSI,
      noiseFloor: noiseFloor,
      uptimeSeconds: uptimeSeconds,
      rxAirtimeSeconds: rxAirtimeSeconds,
      packetsSent: packetsSent,
      packetsReceived: packetsReceived,
      receiveErrors: receiveErrors,
      postedCount: postedCount,
      postPushCount: postPushCount
    )
    nodeStatusSnapshots.append(dto)
    return dto.id
  }

  public func fetchLatestNodeStatusSnapshot(nodePublicKey: Data) async throws -> NodeStatusSnapshotDTO? {
    nodeStatusSnapshots
      .filter { $0.nodePublicKey == nodePublicKey }
      .sorted { $0.timestamp > $1.timestamp }
      .first
  }

  public func fetchNodeStatusSnapshots(nodePublicKey: Data, since: Date?) async throws -> [NodeStatusSnapshotDTO] {
    nodeStatusSnapshots
      .filter { $0.nodePublicKey == nodePublicKey && (since == nil || $0.timestamp >= since!) }
      .sorted { $0.timestamp < $1.timestamp }
  }

  public func saveTelemetryOnlySnapshot(
    nodePublicKey: Data,
    telemetryEntries: [TelemetrySnapshotEntry]
  ) async throws -> UUID {
    let dto = NodeStatusSnapshotDTO(
      nodePublicKey: nodePublicKey,
      telemetryEntries: telemetryEntries
    )
    nodeStatusSnapshots.append(dto)
    return dto.id
  }

  public func updateSnapshotNeighbors(id: UUID, neighbors: [NeighborSnapshotEntry]) async throws {
    if let index = nodeStatusSnapshots.firstIndex(where: { $0.id == id }) {
      let existing = nodeStatusSnapshots[index]
      nodeStatusSnapshots[index] = NodeStatusSnapshotDTO(
        id: existing.id,
        timestamp: existing.timestamp,
        nodePublicKey: existing.nodePublicKey,
        batteryMillivolts: existing.batteryMillivolts,
        lastSNR: existing.lastSNR,
        lastRSSI: existing.lastRSSI,
        noiseFloor: existing.noiseFloor,
        uptimeSeconds: existing.uptimeSeconds,
        rxAirtimeSeconds: existing.rxAirtimeSeconds,
        packetsSent: existing.packetsSent,
        packetsReceived: existing.packetsReceived,
        receiveErrors: existing.receiveErrors,
        postedCount: existing.postedCount,
        postPushCount: existing.postPushCount,
        neighborSnapshots: neighbors,
        telemetryEntries: existing.telemetryEntries,
        latitude: existing.latitude,
        longitude: existing.longitude,
        altitude: existing.altitude
      )
    }
  }

  public func updateSnapshotTelemetry(id: UUID, telemetry: [TelemetrySnapshotEntry]) async throws {
    if let index = nodeStatusSnapshots.firstIndex(where: { $0.id == id }) {
      let existing = nodeStatusSnapshots[index]
      nodeStatusSnapshots[index] = NodeStatusSnapshotDTO(
        id: existing.id,
        timestamp: existing.timestamp,
        nodePublicKey: existing.nodePublicKey,
        batteryMillivolts: existing.batteryMillivolts,
        lastSNR: existing.lastSNR,
        lastRSSI: existing.lastRSSI,
        noiseFloor: existing.noiseFloor,
        uptimeSeconds: existing.uptimeSeconds,
        rxAirtimeSeconds: existing.rxAirtimeSeconds,
        packetsSent: existing.packetsSent,
        packetsReceived: existing.packetsReceived,
        receiveErrors: existing.receiveErrors,
        postedCount: existing.postedCount,
        postPushCount: existing.postPushCount,
        neighborSnapshots: existing.neighborSnapshots,
        telemetryEntries: telemetry,
        latitude: existing.latitude,
        longitude: existing.longitude,
        altitude: existing.altitude
      )
    }
  }

  /// Non-atomic in-memory stand-in for the concrete store's atomic override.
  /// This double is never driven concurrently, so it enriches the latest
  /// in-window row or inserts a new one without the single-turn guarantee.
  /// A location fix is first-wins, mirroring `PersistenceStore`: it is written
  /// only when the target row doesn't already have one.
  public func recordNodeStatusSnapshot(
    nodePublicKey: Data,
    status: NodeStatusMetrics?,
    telemetry: [TelemetrySnapshotEntry]?,
    neighbors: [NeighborSnapshotEntry]?,
    location: NodeLocationFix?
  ) async throws -> UUID {
    if let latest = try await fetchLatestNodeStatusSnapshot(nodePublicKey: nodePublicKey),
       latest.timestamp.distance(to: .now) < NodeSnapshotPolicy.minimumInterval {
      if let telemetry { try await updateSnapshotTelemetry(id: latest.id, telemetry: telemetry) }
      if let neighbors { try await updateSnapshotNeighbors(id: latest.id, neighbors: neighbors) }
      if let location, latest.latitude == nil { applyLocation(location, to: latest.id) }
      return latest.id
    }

    let id: UUID = if let status {
      try await saveNodeStatusSnapshot(
        nodePublicKey: nodePublicKey,
        batteryMillivolts: status.batteryMillivolts,
        lastSNR: status.lastSNR,
        lastRSSI: status.lastRSSI,
        noiseFloor: status.noiseFloor,
        uptimeSeconds: status.uptimeSeconds,
        rxAirtimeSeconds: status.rxAirtimeSeconds,
        packetsSent: status.packetsSent,
        packetsReceived: status.packetsReceived,
        receiveErrors: status.receiveErrors,
        postedCount: status.postedCount,
        postPushCount: status.postPushCount
      )
    } else {
      try await saveTelemetryOnlySnapshot(
        nodePublicKey: nodePublicKey,
        telemetryEntries: telemetry ?? []
      )
    }
    if let neighbors { try await updateSnapshotNeighbors(id: id, neighbors: neighbors) }
    if let location { applyLocation(location, to: id) }
    return id
  }

  /// Rewrites the stored DTO's location by index. The DTO is a value type, so an
  /// in-place mutation is a full rebuild; the mock keeps this in one helper.
  private func applyLocation(_ location: NodeLocationFix, to id: UUID) {
    guard let index = nodeStatusSnapshots.firstIndex(where: { $0.id == id }) else { return }
    let existing = nodeStatusSnapshots[index]
    nodeStatusSnapshots[index] = NodeStatusSnapshotDTO(
      id: existing.id,
      timestamp: existing.timestamp,
      nodePublicKey: existing.nodePublicKey,
      batteryMillivolts: existing.batteryMillivolts,
      lastSNR: existing.lastSNR,
      lastRSSI: existing.lastRSSI,
      noiseFloor: existing.noiseFloor,
      uptimeSeconds: existing.uptimeSeconds,
      rxAirtimeSeconds: existing.rxAirtimeSeconds,
      packetsSent: existing.packetsSent,
      packetsReceived: existing.packetsReceived,
      receiveErrors: existing.receiveErrors,
      postedCount: existing.postedCount,
      postPushCount: existing.postPushCount,
      neighborSnapshots: existing.neighborSnapshots,
      telemetryEntries: existing.telemetryEntries,
      latitude: location.latitude,
      longitude: location.longitude,
      altitude: location.altitude
    )
  }

  public func deleteOldNodeStatusSnapshots(olderThan date: Date) async throws {
    nodeStatusSnapshots.removeAll { $0.timestamp < date }
  }

  // MARK: - Pending Sends

  public var pendingSends: [UUID: PendingSendDTO] = [:]

  public func upsertPendingSend(_ dto: PendingSendDTO) async throws {
    pendingSends[dto.id] = dto
  }

  public func insertPendingSendAssigningSequence(_ dto: PendingSendDTO) async throws -> Int {
    let nextSequence = (pendingSends.values.filter { $0.radioID == dto.radioID }.map(\.sequence).max() ?? 0) + 1
    let assigned = PendingSendDTO(
      id: dto.id,
      radioID: dto.radioID,
      messageID: dto.messageID,
      kind: dto.kind,
      contactID: dto.contactID,
      channelIndex: dto.channelIndex,
      isResend: dto.isResend,
      messageText: dto.messageText,
      messageTimestamp: dto.messageTimestamp,
      localNodeName: dto.localNodeName,
      sequence: nextSequence,
      enqueuedAt: dto.enqueuedAt,
      attemptCount: dto.attemptCount
    )
    pendingSends[dto.id] = assigned
    return nextSequence
  }

  public func fetchPendingSends(radioID: UUID) async throws -> [PendingSendDTO] {
    pendingSends.values.filter { $0.radioID == radioID }.sorted { $0.sequence < $1.sequence }
  }

  public func deletePendingSend(id: UUID) async throws {
    pendingSends.removeValue(forKey: id)
  }

  public func deletePendingSendsForMessage(messageID: UUID) async throws {
    let matchingIDs = pendingSends.values.filter { $0.messageID == messageID }.map(\.id)
    for id in matchingIDs {
      pendingSends.removeValue(forKey: id)
    }
  }

  public func hasPendingSend(messageID: UUID) async throws -> Bool {
    pendingSends.values.contains { $0.messageID == messageID }
  }

  @discardableResult
  public func incrementPendingSendAttemptCount(messageID: UUID) async throws -> Int? {
    guard let row = pendingSends.values.first(where: { $0.messageID == messageID }) else { return nil }
    let nextCount = (row.attemptCount ?? 0) + 1
    pendingSends[row.id] = PendingSendDTO(
      id: row.id,
      radioID: row.radioID,
      messageID: row.messageID,
      kind: row.kind,
      contactID: row.contactID,
      channelIndex: row.channelIndex,
      isResend: row.isResend,
      messageText: row.messageText,
      messageTimestamp: row.messageTimestamp,
      localNodeName: row.localNodeName,
      sequence: row.sequence,
      enqueuedAt: row.enqueuedAt,
      attemptCount: nextCount
    )
    return nextCount
  }

  // MARK: - Test Helpers

  /// Resets all storage and recorded invocations
  public func reset() {
    messages = [:]
    contacts = [:]
    channels = [:]
    blockedChannelSenders = [:]
    debugLogEntries = []
    mockRxLogEntries = []
    linkPreviews = [:]
    pendingSends = [:]
    roomMessages = [:]
    discoveredNodes = [:]
    reactions = [:]
    nodeStatusSnapshots = []
    savedTracePaths = [:]
    savedMessages = []
    savedContacts = []
    savedChannels = []
    savedReactions = []
    savedRoomMessages = []
    deletedContactIDs = []
    deletedChannelIDs = []
    deletedMessagesForContactIDs = []
    deletedReactionsForMessageIDs = []
    deletedMessagesForChannelCalls = []
    updatedMessageStatuses = []
    updatedMessageAcks = []
    updatedRoomMessageStatuses = []
  }
}
