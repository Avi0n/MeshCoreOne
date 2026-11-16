import Foundation
import SwiftData

public extension PersistenceStore {
  nonisolated func recentlyBlockedUpdates() -> AsyncStream<Void> {
    recentlyBlockedChanges.subscribe(bufferingPolicy: .bufferingNewest(1))
  }

  internal func publishRecentlyBlockedChange() {
    recentlyBlockedChanges.yield(())
  }

  func recentlyBlockedMessages() throws -> [RecentlyBlockedMessageDTO] {
    try sortedRecentlyBlockedMessages().map(RecentlyBlockedMessageDTO.init(from:))
  }

  func recentlyBlockedCount() throws -> Int {
    try modelContext.fetchCount(FetchDescriptor<RecentlyBlockedMessage>())
  }

  func hasRecentlyBlockedMessages(radioID: UUID, conversationID: UUID) async throws -> Bool {
    let predicate = #Predicate<RecentlyBlockedMessage> {
      $0.radioID == radioID && $0.conversationID == conversationID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    return try !modelContext.fetch(descriptor).isEmpty
  }

  internal func hasRecentlyBlockedMessage(_ identity: RecentlyBlockedMessageDTO.Identity) throws -> Bool {
    let radioID = identity.radioID
    let timestamp = identity.senderTimestamp
    let predicate = #Predicate<RecentlyBlockedMessage> {
      $0.radioID == radioID && $0.senderTimestamp == timestamp
    }
    return try modelContext.fetch(FetchDescriptor(predicate: predicate)).contains {
      $0.identity == identity
    }
  }

  internal func recordRecentlyBlockedMessage(_ entry: RecentlyBlockedMessageDTO) throws {
    guard try !hasRecentlyBlockedMessage(entry.identity) else { return }
    try commitPendingRxLogEntries()

    do {
      let rows = try modelContext.fetch(FetchDescriptor<RecentlyBlockedMessage>())
      let entry = try RecentlyBlockedMessage(entry)
      let sorted = (rows + [entry]).sorted(by: Self.isMoreRecent)
      modelContext.insert(entry)
      for expired in sorted.dropFirst(Self.recentlyBlockedRetentionLimit) {
        modelContext.delete(expired)
      }
      #if DEBUG
        try recordRecentlyBlockedFaultInjection?()
      #endif
      try modelContext.save()
      publishRecentlyBlockedChange()
    } catch {
      modelContext.rollback()
      throw error
    }
  }

  private func sortedRecentlyBlockedMessages() throws -> [RecentlyBlockedMessage] {
    try modelContext.fetch(FetchDescriptor<RecentlyBlockedMessage>()).sorted(by: Self.isMoreRecent)
  }

  private static func isMoreRecent(_ left: RecentlyBlockedMessage, _ right: RecentlyBlockedMessage) -> Bool {
    if left.arrivedAt == right.arrivedAt {
      left.id.uuidString > right.id.uuidString
    } else {
      left.arrivedAt > right.arrivedAt
    }
  }
}
