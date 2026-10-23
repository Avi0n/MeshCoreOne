@testable import MC1
import Testing

enum SettingsRoot: CaseIterable, Sendable, CustomTestStringConvertible {
  case deviceInfo
  case radio
  case location
  case connection
  case advanced
  case notifications
  case chats
  case appearance
  case maps
  case language
  case backup
  case support
  case feedback

  var detail: SettingsDetail {
    switch self {
    case .deviceInfo: .deviceInfo
    case .radio: .radio
    case .location: .location
    case .connection: .connection
    case .advanced: .advanced
    case .notifications: .notifications
    case .chats: .chats
    case .appearance: .appearance
    case .maps: .maps
    case .language: .language
    case .backup: .backup
    case .support: .support
    case .feedback: .feedback
    }
  }

  var requiresDevice: Bool {
    detail.requiresDevice
  }

  var title: String {
    switch self {
    case .deviceInfo: L10n.Settings.DeviceInfo.title
    case .radio: L10n.Settings.Radio.header
    case .location: L10n.Settings.Location.header
    case .connection: L10n.Settings.Connection.header
    case .advanced: L10n.Settings.AdvancedSettings.title
    case .notifications: L10n.Settings.Notifications.header
    case .chats: L10n.Settings.ChatSettings.title
    case .appearance: L10n.Settings.Appearance.title
    case .maps: L10n.Settings.Maps.title
    case .language: L10n.Settings.Language.title
    case .backup: L10n.Settings.Settings.Backup.title
    case .support: L10n.Settings.Support.title
    case .feedback: L10n.Settings.Feedback.title
    }
  }

  var markers: [String] {
    switch self {
    case .deviceInfo:
      [L10n.Settings.DeviceInfo.publicKey]
    case .radio:
      [L10n.Settings.Radio.repeatMode]
    case .location:
      [L10n.Settings.Location.autoUpdate]
    case .connection:
      [L10n.Settings.Bluetooth.header]
    case .advanced:
      [L10n.Chats.Chats.ChannelInfo.Region.manageRegions]
    case .notifications:
      [L10n.Settings.Notifications.enable, L10n.Settings.Notifications.disabled, L10n.Settings.Notifications.contactMessages]
    case .chats:
      [L10n.Settings.ReplyWithQuote.toggle]
    case .appearance:
      [L10n.Settings.Appearance.Scheme.header]
    case .maps:
      [L10n.Settings.Maps.appearance]
    case .language:
      [L10n.Settings.Language.TranslationOffers.title]
    case .backup:
      [L10n.Settings.Settings.Backup.FileBackup.header]
    case .support:
      [L10n.Settings.Support.Restore.button]
    case .feedback:
      [L10n.Settings.Feedback.GitHub.link]
    }
  }

  var testDescription: String {
    switch self {
    case .deviceInfo: "deviceInfo"
    case .radio: "radio"
    case .location: "location"
    case .connection: "connection"
    case .advanced: "advanced"
    case .notifications: "notifications"
    case .chats: "chats"
    case .appearance: "appearance"
    case .maps: "maps"
    case .language: "language"
    case .backup: "backup"
    case .support: "support"
    case .feedback: "feedback"
    }
  }
}
