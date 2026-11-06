@testable import MC1
import Testing

enum SettingsDestination: CaseIterable, Sendable, CustomTestStringConvertible {
  case recentlyBlocked
  case blockedChannelSenders
  case blockedContacts
  case translateInto
  case offlineMaps
  case manageRegions

  var root: SettingsRoot {
    switch self {
    case .recentlyBlocked, .blockedChannelSenders, .blockedContacts: .blocksAndFilters
    case .translateInto: .language
    case .offlineMaps: .maps
    case .manageRegions: .advanced
    }
  }

  var preservesSearch: Bool {
    self == .translateInto
  }

  var row: String {
    switch self {
    case .recentlyBlocked: L10n.Settings.TextRules.Archive.title
    case .blockedChannelSenders: L10n.Settings.Blocking.channelSenders
    case .blockedContacts: L10n.Settings.Blocking.contacts
    case .translateInto: L10n.Settings.Language.TranslateInto.header
    case .offlineMaps: L10n.Settings.OfflineMaps.title
    case .manageRegions: L10n.Chats.Chats.ChannelInfo.Region.manageRegions
    }
  }

  var title: String {
    switch self {
    case .recentlyBlocked: L10n.Settings.TextRules.Archive.title
    case .blockedChannelSenders: L10n.Settings.Blocking.ChannelSenders.title
    case .blockedContacts: L10n.Contacts.Contacts.Blocked.title
    case .translateInto: L10n.Settings.Language.TranslateInto.header
    case .offlineMaps: L10n.Settings.OfflineMaps.title
    case .manageRegions: L10n.Chats.Chats.ChannelInfo.Region.manage
    }
  }

  var markers: [String] {
    switch self {
    case .recentlyBlocked: [L10n.Settings.TextRules.Archive.empty]
    case .blockedChannelSenders: [L10n.Settings.Blocking.ChannelSenders.Empty.title]
    case .blockedContacts: [L10n.Contacts.Contacts.Blocked.Empty.title]
    case .translateInto: [L10n.Settings.Language.matchAppLanguage]
    case .offlineMaps: [L10n.Settings.OfflineMaps.emptyTitle, L10n.Settings.OfflineMaps.storageUsed]
    case .manageRegions: [L10n.Chats.Chats.ChannelInfo.Region.addManually]
    }
  }

  var testDescription: String {
    switch self {
    case .recentlyBlocked: "recentlyBlocked"
    case .blockedChannelSenders: "blockedChannelSenders"
    case .blockedContacts: "blockedContacts"
    case .translateInto: "translateInto"
    case .offlineMaps: "offlineMaps"
    case .manageRegions: "manageRegions"
    }
  }
}
