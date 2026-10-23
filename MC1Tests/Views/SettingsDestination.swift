@testable import MC1
import Testing

enum SettingsDestination: CaseIterable, Sendable, CustomTestStringConvertible {
  case translateInto
  case offlineMaps
  case manageRegions

  var root: SettingsRoot {
    switch self {
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
    case .translateInto: L10n.Settings.Language.TranslateInto.header
    case .offlineMaps: L10n.Settings.OfflineMaps.title
    case .manageRegions: L10n.Chats.Chats.ChannelInfo.Region.manageRegions
    }
  }

  var title: String {
    switch self {
    case .translateInto: L10n.Settings.Language.TranslateInto.header
    case .offlineMaps: L10n.Settings.OfflineMaps.title
    case .manageRegions: L10n.Chats.Chats.ChannelInfo.Region.manage
    }
  }

  var markers: [String] {
    switch self {
    case .translateInto: [L10n.Settings.Language.matchAppLanguage]
    case .offlineMaps: [L10n.Settings.OfflineMaps.emptyTitle]
    case .manageRegions: [L10n.Chats.Chats.ChannelInfo.Region.addManually]
    }
  }

  var testDescription: String {
    switch self {
    case .translateInto: "translateInto"
    case .offlineMaps: "offlineMaps"
    case .manageRegions: "manageRegions"
    }
  }
}
