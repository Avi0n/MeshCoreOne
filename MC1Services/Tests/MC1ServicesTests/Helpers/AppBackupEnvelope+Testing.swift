import Foundation
@testable import MC1Services

extension AppBackupEnvelope {
  /// Builds a test envelope with a manifest already derived from the passed arrays.
  /// Every DTO array and userDefaults payload defaults to empty, so call sites
  /// only pass what the specific test needs.
  static func test(
    exportDate: Date = Date(timeIntervalSince1970: 1_700_000_000),
    appVersion: String = "test",
    appBuild: String = "1",
    devices: [DeviceDTO] = [],
    contacts: [ContactDTO] = [],
    channels: [ChannelDTO] = [],
    messages: [MessageDTO] = [],
    messageRepeats: [MessageRepeatDTO] = [],
    reactions: [ReactionDTO] = [],
    roomMessages: [RoomMessageDTO] = [],
    remoteNodeSessions: [RemoteNodeSessionDTO] = [],
    savedTracePaths: [SavedTracePathDTO] = [],
    blockedChannelSenders: [BlockedChannelSenderDTO] = [],
    nodeStatusSnapshots: [NodeStatusSnapshotDTO] = [],
    discoveredNodes: [DiscoveredNodeDTO] = [],
    userDefaults: BackupUserDefaults? = nil
  ) -> AppBackupEnvelope {
    var envelope = AppBackupEnvelope(
      exportDate: exportDate,
      appVersion: appVersion,
      appBuild: appBuild,
      devices: devices,
      contacts: contacts,
      channels: channels,
      messages: messages,
      messageRepeats: messageRepeats,
      reactions: reactions,
      roomMessages: roomMessages,
      remoteNodeSessions: remoteNodeSessions,
      savedTracePaths: savedTracePaths,
      blockedChannelSenders: blockedChannelSenders,
      nodeStatusSnapshots: nodeStatusSnapshots,
      discoveredNodes: discoveredNodes,
      userDefaults: userDefaults
    )
    envelope.manifest = BackupManifest(from: envelope)
    return envelope
  }
}
