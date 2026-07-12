import MC1Services

extension BackupModelKind {
  /// Localized display label used by backup preview, import-success, and
  /// export-success rows. Lives in the MC1 target because `L10n` is
  /// generated here by SwiftGen and is not visible from MC1Services.
  var label: String {
    switch self {
    case .messages: L10n.Settings.Settings.Backup.Import.Preview.messages
    case .contacts: L10n.Settings.Settings.Backup.Import.Preview.contacts
    case .channels: L10n.Settings.Settings.Backup.Import.Preview.channels
    case .devices: L10n.Settings.Settings.Backup.Import.Preview.devices
    case .roomMessages: L10n.Settings.Settings.Backup.Import.Preview.roomMessages
    case .reactions: L10n.Settings.Settings.Backup.Import.Preview.reactions
    case .messageRepeats: L10n.Settings.Settings.Backup.Import.Preview.messageRepeats
    case .savedTracePaths: L10n.Settings.Settings.Backup.Import.Preview.savedPaths
    case .remoteNodeSessions: L10n.Settings.Settings.Backup.Import.Preview.remoteNodeSessions
    case .blockedChannelSenders: L10n.Settings.Settings.Backup.Import.Preview.blockedSenders
    case .nodeStatusSnapshots: L10n.Settings.Settings.Backup.Import.Preview.nodeStatusSnapshots
    }
  }
}
