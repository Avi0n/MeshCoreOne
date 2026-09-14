import Foundation
import MeshCore
import SwiftData

public extension PersistenceStore {
  // MARK: - Blocked Channel Senders

  func saveBlockedChannelSender(_ dto: BlockedChannelSenderDTO) throws {
    let targetRadioID = dto.radioID
    let targetName = dto.name
    let predicate = #Predicate<BlockedChannelSender> { entry in
      entry.radioID == targetRadioID && entry.name == targetName
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let existing = try modelContext.fetch(descriptor).first {
      existing.dateBlocked = dto.dateBlocked
    } else {
      let entry = BlockedChannelSender(
        id: dto.id,
        name: targetName,
        radioID: dto.radioID,
        dateBlocked: dto.dateBlocked
      )
      modelContext.insert(entry)
    }

    try modelContext.save()
  }

  func deleteBlockedChannelSender(radioID: UUID, name: String) throws {
    let targetRadioID = radioID
    let targetName = name
    let predicate = #Predicate<BlockedChannelSender> { entry in
      entry.radioID == targetRadioID && entry.name == targetName
    }
    if let entry = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first {
      modelContext.delete(entry)
      try modelContext.save()
    }
  }

  func fetchBlockedChannelSenders(radioID: UUID) throws -> [BlockedChannelSenderDTO] {
    let targetRadioID = radioID
    let predicate = #Predicate<BlockedChannelSender> { entry in
      entry.radioID == targetRadioID
    }
    let descriptor = FetchDescriptor(
      predicate: predicate,
      sortBy: [SortDescriptor(\.dateBlocked, order: .reverse)]
    )
    let entries = try modelContext.fetch(descriptor)
    return entries.map { BlockedChannelSenderDTO(from: $0) }
  }

  // MARK: - Mention Tracking

  func incrementChannelUnreadMentionCount(channelID: UUID) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let channel = try modelContext.fetch(descriptor).first else { return }
    channel.unreadMentionCount += 1
    try modelContext.save()
  }

  func decrementChannelUnreadMentionCount(channelID: UUID) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let channel = try modelContext.fetch(descriptor).first else { return }
    channel.unreadMentionCount = max(0, channel.unreadMentionCount - 1)
    try modelContext.save()
  }

  func clearChannelUnreadMentionCount(channelID: UUID) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let channel = try modelContext.fetch(descriptor).first else { return }
    channel.unreadMentionCount = 0
    try modelContext.save()
  }

  func fetchUnseenChannelMentionIDs(radioID: UUID, channelIndex: UInt8) throws -> [UUID] {
    let targetRadioID = radioID
    let targetIndex: UInt8? = channelIndex
    let predicate = #Predicate<Message> { message in
      message.radioID == targetRadioID &&
        message.channelIndex == targetIndex &&
        message.containsSelfMention == true &&
        message.mentionSeen == false
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]

    let messages = try modelContext.fetch(descriptor)
    return messages.map(\.id)
  }

  // MARK: - Channel Operations

  /// Fetch all channels for a device
  func fetchChannels(radioID: UUID) throws -> [ChannelDTO] {
    let targetRadioID = radioID
    let predicate = #Predicate<Channel> { channel in
      channel.radioID == targetRadioID
    }
    let descriptor = FetchDescriptor(
      predicate: predicate,
      sortBy: [SortDescriptor(\.index)]
    )
    let channels = try modelContext.fetch(descriptor)
    return channels.map { ChannelDTO(from: $0) }
  }

  /// Fetch a channel by index
  func fetchChannel(radioID: UUID, index: UInt8) throws -> ChannelDTO? {
    let targetRadioID = radioID
    let targetIndex = index
    let predicate = #Predicate<Channel> { channel in
      channel.radioID == targetRadioID && channel.index == targetIndex
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    return try modelContext.fetch(descriptor).first.map { ChannelDTO(from: $0) }
  }

  /// Fetch a channel by ID
  func fetchChannel(id: UUID) throws -> ChannelDTO? {
    let targetID = id
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1
    return try modelContext.fetch(descriptor).first.map { ChannelDTO(from: $0) }
  }

  /// Save or update a channel from ChannelInfo. A changed secret is a different
  /// channel occupying the slot, so the previous occupant's history is wiped.
  func saveChannel(radioID: UUID, from info: ChannelInfo) throws -> UUID {
    // Commit batched RxLog inserts first so a failed save/rollback cannot discard them.
    try commitPendingRxLogEntries()
    let targetRadioID = radioID
    let targetIndex = info.index
    let predicate = #Predicate<Channel> { channel in
      channel.radioID == targetRadioID && channel.index == targetIndex
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    do {
      let channel: Channel
      if let existing = try modelContext.fetch(descriptor).first {
        try applyRadioChannelInfo(info, to: existing)
        channel = existing
      } else {
        channel = Channel(radioID: radioID, from: info)
        modelContext.insert(channel)
      }

      try modelContext.save()
      return channel.id
    } catch {
      modelContext.rollback()
      throw error
    }
  }

  /// Persists a full channel-sync pass in a single transaction. See
  /// ``PersistenceStoreProtocol/batchSaveChannels(radioID:configured:unconfiguredIndices:pruneBeyond:)``
  /// for the contract. Collapses the per-index `saveChannel`/`deleteChannel` calls — each its
  /// own commit, plus a redundant re-fetch — into one fetch, one mutation pass, and one
  /// `save()`. Indices that were neither confirmed configured nor reported unconfigured are
  /// left untouched, so a circuit-breaker abort never deletes channels it could not read.
  func batchSaveChannels(
    radioID: UUID,
    configured: [ChannelInfo],
    unconfiguredIndices: [UInt8],
    pruneBeyond maxChannels: UInt8?
  ) async throws -> [ChannelDTO] {
    // Commit batched RxLog inserts first so a failed save/rollback cannot discard them.
    try commitPendingRxLogEntries()
    let targetRadioID = radioID
    let predicate = #Predicate<Channel> { channel in
      channel.radioID == targetRadioID
    }
    let existing = try modelContext.fetch(FetchDescriptor(predicate: predicate))
    var byIndex = Dictionary(existing.map { ($0.index, $0) }, uniquingKeysWith: { current, _ in current })

    do {
      for info in configured {
        if let row = byIndex[info.index] {
          try applyRadioChannelInfo(info, to: row)
        } else {
          let channel = Channel(radioID: radioID, from: info)
          modelContext.insert(channel)
          byIndex[info.index] = channel
        }
      }

      // A slot the radio reports empty has no legitimate history, so wipe it even
      // when the row is already gone; that clears leftovers from earlier prunes.
      for index in unconfiguredIndices {
        try _deleteMessagesForChannelWithoutSaving(radioID: radioID, channelIndex: index)
        if let stale = byIndex[index] {
          modelContext.delete(stale)
          byIndex[index] = nil
        }
      }

      if let maxChannels {
        for (index, row) in byIndex where index >= maxChannels {
          try _deleteMessagesForChannelWithoutSaving(radioID: radioID, channelIndex: index)
          modelContext.delete(row)
          byIndex[index] = nil
        }
      }

      #if DEBUG
        try batchSaveChannelsFaultInjection?()
      #endif
      try modelContext.save()
    } catch {
      // Discard the staged upserts and deletes so a later successful save on this shared
      // context cannot flush them and delete channels this failed pass meant to keep.
      modelContext.rollback()
      throw error
    }
    return try fetchChannels(radioID: radioID)
  }

  /// Save or update a channel from DTO
  func saveChannel(_ dto: ChannelDTO) throws {
    let targetID = dto.id
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let existing = try modelContext.fetch(descriptor).first {
      existing.apply(dto)
    } else {
      modelContext.insert(Channel(dto: dto))
    }

    try modelContext.save()
  }

  /// Delete a channel together with its slot's messages in one save, so a
  /// failed save rolls both back and a later channel at that slot starts empty.
  func deleteChannel(id: UUID) throws {
    // Commit batched RxLog inserts first so a failed save/rollback cannot discard them.
    try commitPendingRxLogEntries()
    let targetID = id
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    guard let channel = try modelContext.fetch(FetchDescriptor(predicate: predicate)).first else { return }
    do {
      try _deleteMessagesForChannelWithoutSaving(radioID: channel.radioID, channelIndex: channel.index)
      modelContext.delete(channel)
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
  }

  /// Delete all messages for a channel, cascading PendingSend, MessageRepeat, and
  /// Reaction rows. A failed save rolls back so a later save cannot flush the staged deletes.
  func deleteMessagesForChannel(radioID: UUID, channelIndex: UInt8) throws {
    // Commit batched RxLog inserts first so a failed save/rollback cannot discard them.
    try commitPendingRxLogEntries()
    do {
      try _deleteMessagesForChannelWithoutSaving(radioID: radioID, channelIndex: channelIndex)
      #if DEBUG
        try deleteMessagesForChannelFaultInjection?()
      #endif
      try modelContext.save()
    } catch {
      modelContext.rollback()
      throw error
    }
  }

  /// Stages message, PendingSend, Reaction, and MessageRepeat deletes as registered
  /// objects so a caller's `rollback()` restores them. Chunked under SQLITE_MAX_VARIABLE_NUMBER.
  private func _deleteMessagesForChannelWithoutSaving(radioID: UUID, channelIndex: UInt8) throws {
    let targetRadioID = radioID
    let targetChannelIndex: UInt8? = channelIndex
    let messagePredicate = #Predicate<Message> { message in
      message.radioID == targetRadioID && message.channelIndex == targetChannelIndex
    }
    let messages = try modelContext.fetch(FetchDescriptor(predicate: messagePredicate))
    guard !messages.isEmpty else { return }

    let messageIDs = messages.map(\.id)
    let chunkSize = 500
    for start in stride(from: 0, to: messageIDs.count, by: chunkSize) {
      let chunk = Array(messageIDs[start..<min(start + chunkSize, messageIDs.count)])
      for row in try modelContext.fetch(FetchDescriptor<PendingSend>(predicate: #Predicate { chunk.contains($0.messageID) })) {
        modelContext.delete(row)
      }
      for row in try modelContext.fetch(FetchDescriptor<Reaction>(predicate: #Predicate { chunk.contains($0.messageID) })) {
        modelContext.delete(row)
      }
      for row in try modelContext.fetch(FetchDescriptor<MessageRepeat>(predicate: #Predicate { chunk.contains($0.messageID) })) {
        modelContext.delete(row)
      }
    }
    for message in messages {
      modelContext.delete(message)
    }
  }

  /// Applies a radio-reported `ChannelInfo` to an existing row. A different secret
  /// means a different occupant, so prior messages and derived counters are wiped first.
  private func applyRadioChannelInfo(_ info: ChannelInfo, to row: Channel) throws {
    if row.secret != info.secret {
      try _deleteMessagesForChannelWithoutSaving(radioID: row.radioID, channelIndex: row.index)
      row.lastMessageDate = nil
      row.unreadCount = 0
      row.unreadMentionCount = 0
    }
    row.update(from: info)
  }

  /// Update channel's last message info (nil clears the date)
  func updateChannelLastMessage(channelID: UUID, date: Date?) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let channel = try modelContext.fetch(descriptor).first {
      channel.lastMessageDate = date
      try modelContext.save()
    }
  }

  // MARK: - Channel Unread Count

  /// Increment unread count for a channel
  func incrementChannelUnreadCount(channelID: UUID) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let channel = try modelContext.fetch(descriptor).first {
      channel.unreadCount += 1
      try modelContext.save()
    }
  }

  /// Clear unread count for a channel
  func clearChannelUnreadCount(channelID: UUID) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { channel in
      channel.id == targetID
    }
    var descriptor = FetchDescriptor(predicate: predicate)
    descriptor.fetchLimit = 1

    if let channel = try modelContext.fetch(descriptor).first {
      channel.unreadCount = 0
      try modelContext.save()
    }
  }

  /// Clear unread count for a channel by radioID and index
  /// More efficient than fetching the full channel DTO when only clearing unread
  func clearChannelUnreadCount(radioID: UUID, index: UInt8) throws {
    let targetRadioID = radioID
    let targetIndex = index
    let predicate = #Predicate<Channel> { channel in
      channel.radioID == targetRadioID && channel.index == targetIndex
    }
    var descriptor = FetchDescriptor<Channel>(predicate: predicate)
    descriptor.fetchLimit = 1
    if let channel = try modelContext.fetch(descriptor).first {
      channel.unreadCount = 0
      try modelContext.save()
    }
  }

  /// Sets the muted state for a channel
  func setChannelMuted(_ channelID: UUID, isMuted: Bool) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { $0.id == targetID }
    var descriptor = FetchDescriptor<Channel>(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let channel = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.channelNotFound
    }

    channel.notificationLevel = isMuted ? .muted : .all
    try modelContext.save()
  }

  /// Sets the notification level for a channel
  func setChannelNotificationLevel(_ channelID: UUID, level: NotificationLevel) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { $0.id == targetID }
    var descriptor = FetchDescriptor<Channel>(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let channel = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.channelNotFound
    }

    channel.notificationLevel = level
    try modelContext.save()
  }

  /// Sets the favorite state for a channel
  func setChannelFavorite(_ channelID: UUID, isFavorite: Bool) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { $0.id == targetID }
    var descriptor = FetchDescriptor<Channel>(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let channel = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.channelNotFound
    }

    channel.isFavorite = isFavorite
    try modelContext.save()
  }

  /// Atomically updates the per-channel flood-scope preference. Writes both backing
  /// storage fields (`floodScopeModeRawValue` and `regionScope`) in one step so
  /// callers cannot persist a malformed combination.
  func setChannelFloodScope(_ channelID: UUID, floodScope: ChannelFloodScope) throws {
    let targetID = channelID
    let predicate = #Predicate<Channel> { $0.id == targetID }
    var descriptor = FetchDescriptor<Channel>(predicate: predicate)
    descriptor.fetchLimit = 1

    guard let channel = try modelContext.fetch(descriptor).first else {
      throw PersistenceStoreError.channelNotFound
    }

    channel.floodScope = floodScope
    try modelContext.save()
  }

  #if DEBUG
    func setBatchSaveChannelsFaultInjection(_ hook: (@Sendable () throws -> Void)?) {
      batchSaveChannelsFaultInjection = hook
    }

    func setDeleteMessagesForChannelFaultInjection(_ hook: (@Sendable () throws -> Void)?) {
      deleteMessagesForChannelFaultInjection = hook
    }
  #endif
}
