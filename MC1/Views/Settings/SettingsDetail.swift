import SwiftUI

/// The set of Settings detail pages reached from the settings list. Shared by the compact
/// `SettingsView` (which pushes each via `NavigationLink(value:)`) and the iPad split columns
/// (`SettingsListContent` list selection + `SettingsDetailView` detail), and persisted as the
/// active selection on `NavigationCoordinator`.
enum SettingsDetail: Hashable {
  case deviceInfo
  case radio
  case location
  case connection
  case advanced
  case notifications
  case chats
  case appearance
  case maps
  case backup
  case support
  case feedback

  /// The My Device rows only exist while a radio is connected; clearing their selection on
  /// disconnect or a radio switch keeps the detail pane from stranding a now-gone device page.
  var requiresDevice: Bool {
    switch self {
    case .deviceInfo, .radio, .location, .connection, .advanced:
      true
    case .notifications, .chats, .appearance, .maps, .backup, .support, .feedback:
      false
    }
  }
}
