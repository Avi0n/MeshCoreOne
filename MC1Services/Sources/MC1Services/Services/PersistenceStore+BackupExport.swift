import Foundation
import SwiftData

struct BackupExportSnapshot {
  let devices: [DeviceDTO]
  let contacts: [ContactDTO]
  let channels: [ChannelDTO]
  let messages: [MessageDTO]
  let messageRepeats: [MessageRepeatDTO]
  let reactions: [ReactionDTO]
  let roomMessages: [RoomMessageDTO]
  let remoteNodeSessions: [RemoteNodeSessionDTO]
  let savedTracePaths: [SavedTracePathDTO]
  let blockedChannelSenders: [BlockedChannelSenderDTO]
  let nodeStatusSnapshots: [NodeStatusSnapshotDTO]
  let discoveredNodes: [DiscoveredNodeDTO]
}

// MARK: - Export (fetchAll)

public extension PersistenceStore {
  func fetchAllDevices() throws -> [DeviceDTO] {
    let descriptor = FetchDescriptor<Device>()
    return try modelContext.fetch(descriptor).map { DeviceDTO(from: $0) }
  }

  func fetchAllContacts() throws -> [ContactDTO] {
    let descriptor = FetchDescriptor<Contact>()
    return try modelContext.fetch(descriptor).map { ContactDTO(from: $0) }
  }

  func fetchAllChannels() throws -> [ChannelDTO] {
    let descriptor = FetchDescriptor<Channel>()
    return try modelContext.fetch(descriptor).map { ChannelDTO(from: $0) }
  }

  /// Fetches all messages for backup export, skipping external-storage link-preview
  /// blobs at the DTO boundary so SwiftData never faults them in. Backfills the
  /// deduplication key on pre-migration incoming messages so import can match them.
  /// Outgoing messages are intentionally left nil — restore keys them on UUID identity.
  internal func fetchAllMessagesForBackup() throws -> [MessageDTO] {
    let descriptor = FetchDescriptor<Message>()
    return try modelContext.fetch(descriptor).map { message in
      var dto = MessageDTO(from: message, includeLinkPreviewBlobs: false)
      if dto.direction != .outgoing, dto.deduplicationKey == nil {
        dto.deduplicationKey = DeduplicationKey.contentBased(
          contactID: dto.contactID,
          channelIndex: dto.channelIndex,
          senderNodeName: dto.senderNodeName,
          timestamp: dto.timestamp,
          content: dto.text
        )
      }
      return dto
    }
  }

  func fetchAllReactions() throws -> [ReactionDTO] {
    let descriptor = FetchDescriptor<Reaction>()
    return try modelContext.fetch(descriptor).map { ReactionDTO(from: $0) }
  }

  func fetchAllRemoteNodeSessions() throws -> [RemoteNodeSessionDTO] {
    let descriptor = FetchDescriptor<RemoteNodeSession>()
    return try modelContext.fetch(descriptor).map { RemoteNodeSessionDTO(from: $0) }
  }

  func fetchAllSavedTracePaths() throws -> [SavedTracePathDTO] {
    let descriptor = FetchDescriptor<SavedTracePath>()
    return try modelContext.fetch(descriptor).map { SavedTracePathDTO(from: $0) }
  }

  func fetchAllBlockedChannelSenders() throws -> [BlockedChannelSenderDTO] {
    let descriptor = FetchDescriptor<BlockedChannelSender>()
    return try modelContext.fetch(descriptor).map { BlockedChannelSenderDTO(from: $0) }
  }

  /// Scoped by parent message IDs so we don't fetch orphaned repeats.
  /// Chunks the `contains` predicate to stay under SQLite's bound-parameter limit
  /// on histories with tens of thousands of messages.
  func fetchAllMessageRepeats(messageIDs: Set<UUID>) throws -> [MessageRepeatDTO] {
    let messageIDArray = Array(messageIDs)
    guard !messageIDArray.isEmpty else { return [] }
    let repeats = try fetchInChunks(keys: messageIDArray) { chunk in
      let predicate = #Predicate<MessageRepeat> { chunk.contains($0.messageID) }
      return try modelContext.fetch(FetchDescriptor(predicate: predicate))
    }
    return repeats.map { MessageRepeatDTO(from: $0) }
  }

  /// Scoped by parent session IDs so we don't fetch orphaned room messages.
  func fetchAllRoomMessages(sessionIDs: Set<UUID>) throws -> [RoomMessageDTO] {
    let sessionIDArray = Array(sessionIDs)
    guard !sessionIDArray.isEmpty else { return [] }
    let messages = try fetchInChunks(keys: sessionIDArray) { chunk in
      let predicate = #Predicate<RoomMessage> { chunk.contains($0.sessionID) }
      return try modelContext.fetch(FetchDescriptor(predicate: predicate))
    }
    return messages.map { RoomMessageDTO(from: $0) }
  }

  func fetchAllNodeStatusSnapshots() throws -> [NodeStatusSnapshotDTO] {
    let descriptor = FetchDescriptor<NodeStatusSnapshot>()
    return try modelContext.fetch(descriptor).map { NodeStatusSnapshotDTO(from: $0) }
  }

  func fetchAllDiscoveredNodes() throws -> [DiscoveredNodeDTO] {
    let descriptor = FetchDescriptor<DiscoveredNode>()
    return try modelContext.fetch(descriptor).map { DiscoveredNodeDTO(from: $0) }
  }

  /// Fetches all backup-relevant model data in a single store-actor turn.
  ///
  /// This keeps the export parent/child relationships aligned with the same
  /// view of the store, rather than allowing sync writes on the authoritative
  /// store actor to interleave between separate awaited fetches.
  internal func fetchBackupExportSnapshot() throws -> BackupExportSnapshot {
    let devices = try fetchAllDevices().map { $0.redactedForBackup() }
    let contacts = try fetchAllContacts()
    let channels = try fetchAllChannels()
    let messages = try fetchAllMessagesForBackup()
    let reactions = try fetchAllReactions()
    let sessions = try fetchAllRemoteNodeSessions()
    let tracePaths = try fetchAllSavedTracePaths()
    let blockedSenders = try fetchAllBlockedChannelSenders()
    let messageRepeats = try fetchAllMessageRepeats(messageIDs: Set(messages.map(\.id)))
    let roomMessages = try fetchAllRoomMessages(sessionIDs: Set(sessions.map(\.id)))
    let nodeSnapshots = try fetchAllNodeStatusSnapshots()
    let discoveredNodes = try fetchAllDiscoveredNodes()

    return BackupExportSnapshot(
      devices: devices,
      contacts: contacts,
      channels: channels,
      messages: messages,
      messageRepeats: messageRepeats,
      reactions: reactions,
      roomMessages: roomMessages,
      remoteNodeSessions: sessions,
      savedTracePaths: tracePaths,
      blockedChannelSenders: blockedSenders,
      nodeStatusSnapshots: nodeSnapshots,
      discoveredNodes: discoveredNodes
    )
  }
}
