import CryptoKit
import Foundation
import OSLog

/// A reaction waiting for its target message to be indexed
public struct PendingReaction: Sendable {
  public let parsed: ParsedReaction
  public let channelIndex: UInt8
  public let senderNodeName: String
  public let rawText: String
  public let radioID: UUID
  public let receivedAt: Date
}

/// A DM reaction waiting for its target message to be indexed
public struct PendingDMReaction: Sendable {
  public let parsed: ParsedDMReaction
  public let contactID: UUID
  public let senderName: String
  public let rawText: String
  public let radioID: UUID
  public let receivedAt: Date
}

/// Result of persisting a reaction
public struct ReactionPersistResult: Sendable {
  public let messageID: UUID
  public let summary: String
}

/// Service for handling emoji reactions on channel messages
public actor ReactionService {
  private let logger = Logger(subsystem: "com.mc1", category: "ReactionService")
  private let messageCache: MessageLRUCache

  private static let maxPendingReactions = 100

  // Pending reactions queue: no TTL, session lifetime.
  private var pendingReactions: [PendingReactionKey: [PendingReaction]] = [:]
  private var pendingOrder: [PendingReactionKey] = []

  private struct PendingReactionKey: Hashable {
    let channelIndex: UInt8
    let targetSender: String
    let messageHash: String
  }

  private struct PendingDMReactionKey: Hashable {
    let contactID: UUID
    let messageHash: String
  }

  private var pendingDMReactions: [PendingDMReactionKey: [PendingDMReaction]] = [:]
  private var pendingDMOrder: [PendingDMReactionKey] = []

  init(messageCache: MessageLRUCache = MessageLRUCache()) {
    self.messageCache = messageCache
  }

  /// Indexes a message for reaction matching and returns any pending reactions that now match
  public func indexMessage(
    id: UUID,
    channelIndex: UInt8,
    senderName: String,
    text: String,
    timestamp: UInt32
  ) async -> [PendingReaction] {
    await messageCache.index(
      messageID: id,
      channelIndex: channelIndex,
      senderName: senderName,
      text: text,
      timestamp: timestamp
    )

    // Check pending queue for matching reactions
    let hash = ReactionParser.generateMessageHash(text: text, timestamp: timestamp)
    let key = PendingReactionKey(
      channelIndex: channelIndex,
      targetSender: senderName,
      messageHash: hash
    )

    guard let matched = pendingReactions.removeValue(forKey: key) else {
      return []
    }

    pendingOrder.removeAll { $0 == key }

    logger.debug("Matched \(matched.count) pending reaction(s) to message \(id)")

    return matched
  }

  /// Builds reaction wire format text for sending
  /// Format: `{emoji}@[{sender}]\n{hash}`
  public nonisolated func buildReactionText(
    emoji: String,
    targetSender: String,
    targetText: String,
    targetTimestamp: UInt32
  ) -> String {
    let hash = ReactionParser.generateMessageHash(text: targetText, timestamp: targetTimestamp)
    return "\(emoji)@[\(targetSender)]\n\(hash)"
  }

  /// Builds DM reaction wire format (shorter, no sender)
  public nonisolated func buildDMReactionText(
    emoji: String,
    targetText: String,
    targetTimestamp: UInt32
  ) -> String {
    ReactionParser.buildDMReactionText(
      emoji: emoji,
      targetText: targetText,
      targetTimestamp: targetTimestamp
    )
  }

  /// Finds target message ID for a parsed reaction using hash-based lookup
  func findTargetMessage(parsed: ParsedReaction, channelIndex: UInt8) async -> UUID? {
    let candidates = await messageCache.lookup(
      channelIndex: channelIndex,
      senderName: parsed.targetSender,
      messageHash: parsed.messageHash
    )

    // Return most recently indexed match
    return candidates.max(by: { $0.indexedAt < $1.indexedAt })?.messageID
  }

  /// Attempts to process incoming text as a reaction
  /// Returns true if handled as reaction, false to process as regular message
  nonisolated func tryProcessAsReaction(_ text: String) -> ParsedReaction? {
    ReactionParser.parse(text)
  }

  /// Queues a reaction that couldn't find its target message
  func queuePendingReaction(
    parsed: ParsedReaction,
    channelIndex: UInt8,
    senderNodeName: String,
    rawText: String,
    radioID: UUID
  ) {
    let key = PendingReactionKey(
      channelIndex: channelIndex,
      targetSender: parsed.targetSender,
      messageHash: parsed.messageHash
    )
    let pending = PendingReaction(
      parsed: parsed,
      channelIndex: channelIndex,
      senderNodeName: senderNodeName,
      rawText: rawText,
      radioID: radioID,
      receivedAt: Date()
    )

    if pendingReactions[key] != nil {
      pendingReactions[key]!.append(pending)
    } else {
      pendingReactions[key] = [pending]
      pendingOrder.append(key)
    }

    evictIfNeeded()
    logger.debug("Queued pending reaction \(parsed.emoji) for \(parsed.targetSender)")
  }

  /// Clears all pending reactions (call on disconnect)
  func clearPendingReactions() {
    let count = pendingReactions.values.reduce(0) { $0 + $1.count }
    let dmCount = pendingDMReactions.values.reduce(0) { $0 + $1.count }
    pendingReactions.removeAll()
    pendingOrder.removeAll()
    pendingDMReactions.removeAll()
    pendingDMOrder.removeAll()
    if count + dmCount > 0 {
      logger.debug("Cleared \(count + dmCount) pending reaction(s)")
    }
  }

  // MARK: - Persistence

  /// Persists a reaction and updates the message's reaction summary.
  /// Logs errors instead of silently discarding them.
  public func persistReactionAndUpdateSummary(
    _ reaction: ReactionDTO,
    using dataStore: any PersistenceStoreProtocol
  ) async -> ReactionPersistResult? {
    do {
      try await dataStore.saveReaction(reaction)
    } catch {
      logger.error("Failed to save reaction for message \(reaction.messageID): \(error.localizedDescription)")
      return nil
    }

    let reactions: [ReactionDTO]
    do {
      reactions = try await dataStore.fetchReactions(for: reaction.messageID)
    } catch {
      logger.error("Failed to fetch reactions for message \(reaction.messageID): \(error.localizedDescription)")
      return nil
    }

    let summary = ReactionParser.buildSummary(from: reactions)
    do {
      try await dataStore.updateMessageReactionSummary(messageID: reaction.messageID, summary: summary)
    } catch {
      logger.error("Failed to update reaction summary for message \(reaction.messageID): \(error.localizedDescription)")
      return nil
    }

    return ReactionPersistResult(messageID: reaction.messageID, summary: summary)
  }

  // MARK: - DM Reactions

  /// Indexes a DM message for reaction matching and returns any pending reactions that now match
  public func indexDMMessage(
    id: UUID,
    contactID: UUID,
    text: String,
    timestamp: UInt32
  ) async -> [PendingDMReaction] {
    await messageCache.indexDM(
      messageID: id,
      contactID: contactID,
      text: text,
      timestamp: timestamp
    )

    // Check pending queue for matching reactions
    let hash = ReactionParser.generateMessageHash(text: text, timestamp: timestamp)
    let key = PendingDMReactionKey(contactID: contactID, messageHash: hash)

    guard let matched = pendingDMReactions.removeValue(forKey: key) else {
      return []
    }

    pendingDMOrder.removeAll { $0 == key }

    logger.debug("Matched \(matched.count) pending DM reaction(s) to message \(id)")

    return matched
  }

  /// Finds target DM message ID by hash and contact
  func findDMTargetMessage(messageHash: String, contactID: UUID) async -> UUID? {
    let candidates = await messageCache.lookupDM(contactID: contactID, messageHash: messageHash)
    return candidates.max(by: { $0.indexedAt < $1.indexedAt })?.messageID
  }

  /// Queues a DM reaction that couldn't find its target message
  func queuePendingDMReaction(
    parsed: ParsedDMReaction,
    contactID: UUID,
    senderName: String,
    rawText: String,
    radioID: UUID
  ) {
    let key = PendingDMReactionKey(contactID: contactID, messageHash: parsed.messageHash)
    let pending = PendingDMReaction(
      parsed: parsed,
      contactID: contactID,
      senderName: senderName,
      rawText: rawText,
      radioID: radioID,
      receivedAt: Date()
    )

    if pendingDMReactions[key] != nil {
      pendingDMReactions[key]!.append(pending)
    } else {
      pendingDMReactions[key] = [pending]
      pendingDMOrder.append(key)
    }

    evictDMIfNeeded()
    logger.debug("Queued pending DM reaction \(parsed.emoji)")
  }

  private func evictIfNeeded() {
    var totalCount = pendingReactions.values.reduce(0) { $0 + $1.count }

    while totalCount > Self.maxPendingReactions, let oldestKey = pendingOrder.first {
      if var entries = pendingReactions[oldestKey], !entries.isEmpty {
        entries.removeFirst()
        totalCount -= 1

        if entries.isEmpty {
          pendingReactions.removeValue(forKey: oldestKey)
          pendingOrder.removeFirst()
        } else {
          pendingReactions[oldestKey] = entries
        }
      } else {
        pendingOrder.removeFirst()
      }
    }
  }

  private func evictDMIfNeeded() {
    var totalCount = pendingDMReactions.values.reduce(0) { $0 + $1.count }

    while totalCount > Self.maxPendingReactions, let oldestKey = pendingDMOrder.first {
      if var entries = pendingDMReactions[oldestKey], !entries.isEmpty {
        entries.removeFirst()
        totalCount -= 1

        if entries.isEmpty {
          pendingDMReactions.removeValue(forKey: oldestKey)
          pendingDMOrder.removeFirst()
        } else {
          pendingDMReactions[oldestKey] = entries
        }
      } else {
        pendingDMOrder.removeFirst()
      }
    }
  }
}
