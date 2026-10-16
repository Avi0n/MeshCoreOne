import Foundation

/// Key for message lookup in LRU cache
struct MessageCacheKey: Hashable {
  let channelIndex: UInt8
  let senderName: String
  let messageHash: String
}

/// Key for DM message lookup in LRU cache
struct DirectMessageCacheKey: Hashable {
  let contactID: UUID
  let messageHash: String
}

/// Candidate message for reaction matching
struct MessageCandidate: Equatable {
  let messageID: UUID
  let text: String
  let timestamp: UInt32
  let indexedAt: Date

  init(messageID: UUID, text: String, timestamp: UInt32, indexedAt: Date = Date()) {
    self.messageID = messageID
    self.text = text
    self.timestamp = timestamp
    self.indexedAt = indexedAt
  }
}

/// LRU cache for recent channel messages to enable reaction matching with collision resolution
actor MessageLRUCache {
  private var cache: [MessageCacheKey: [MessageCandidate]] = [:]
  private var order: [MessageCacheKey] = []

  private var dmCache: [DirectMessageCacheKey: [MessageCandidate]] = [:]
  private var dmOrder: [DirectMessageCacheKey] = []

  private let capacity: Int
  private let maxCandidatesPerKey: Int

  init(capacity: Int = 500, maxCandidatesPerKey: Int = 5) {
    self.capacity = capacity
    self.maxCandidatesPerKey = maxCandidatesPerKey
  }

  /// Indexes a message for later lookup
  func index(messageID: UUID, channelIndex: UInt8, senderName: String, text: String, timestamp: UInt32) {
    let hash = ReactionParser.generateMessageHash(text: text, timestamp: timestamp)
    let key = MessageCacheKey(channelIndex: channelIndex, senderName: senderName, messageHash: hash)
    let candidate = MessageCandidate(messageID: messageID, text: text, timestamp: timestamp)

    // Update LRU order
    if let existingIndex = order.firstIndex(of: key) {
      order.remove(at: existingIndex)
    }
    order.append(key)

    // Evict oldest key if at capacity
    if order.count > capacity, let oldest = order.first {
      order.removeFirst()
      cache.removeValue(forKey: oldest)
    }

    // Add candidate to key's list
    var candidates = cache[key] ?? []

    // Remove existing candidate with same messageID (re-indexing)
    candidates.removeAll { $0.messageID == messageID }

    // Append new candidate
    candidates.append(candidate)

    // Prune to max candidates (keep most recent)
    if candidates.count > maxCandidatesPerKey {
      candidates = Array(candidates.suffix(maxCandidatesPerKey))
    }

    cache[key] = candidates
  }

  /// Looks up candidates by cache key
  func lookup(channelIndex: UInt8, senderName: String, messageHash: String) -> [MessageCandidate] {
    let key = MessageCacheKey(channelIndex: channelIndex, senderName: senderName, messageHash: messageHash)
    return cache[key] ?? []
  }

  /// Indexes a DM message for later lookup
  func indexDM(messageID: UUID, contactID: UUID, text: String, timestamp: UInt32) {
    let hash = ReactionParser.generateMessageHash(text: text, timestamp: timestamp)
    let key = DirectMessageCacheKey(contactID: contactID, messageHash: hash)
    let candidate = MessageCandidate(messageID: messageID, text: text, timestamp: timestamp)

    // Update LRU order
    if let existingIndex = dmOrder.firstIndex(of: key) {
      dmOrder.remove(at: existingIndex)
    }
    dmOrder.append(key)

    // Evict oldest key if at capacity
    if dmOrder.count > capacity, let oldest = dmOrder.first {
      dmOrder.removeFirst()
      dmCache.removeValue(forKey: oldest)
    }

    // Add candidate to key's list
    var candidates = dmCache[key] ?? []

    // Remove existing candidate with same messageID (re-indexing)
    candidates.removeAll { $0.messageID == messageID }

    // Append new candidate
    candidates.append(candidate)

    // Prune to max candidates (keep most recent)
    if candidates.count > maxCandidatesPerKey {
      candidates = Array(candidates.suffix(maxCandidatesPerKey))
    }

    dmCache[key] = candidates
  }

  /// Looks up DM candidates by cache key
  func lookupDM(contactID: UUID, messageHash: String) -> [MessageCandidate] {
    let key = DirectMessageCacheKey(contactID: contactID, messageHash: messageHash)
    return dmCache[key] ?? []
  }

  /// Clears the cache
  func clear() {
    cache.removeAll()
    order.removeAll()
    dmCache.removeAll()
    dmOrder.removeAll()
  }
}
