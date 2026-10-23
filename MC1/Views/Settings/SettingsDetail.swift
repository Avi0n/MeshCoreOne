import SwiftUI

/// Settings pages reached from the settings list. `SettingsListContent` writes
/// the selection; `SettingsDetailView` renders it in the split detail.
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
  case language
  case backup
  case support
  case feedback

  /// The My Device rows only exist while a radio is connected; clearing their selection on
  /// disconnect or a radio switch keeps the detail pane from stranding a now-gone device page.
  var requiresDevice: Bool {
    switch self {
    case .deviceInfo, .radio, .location, .connection, .advanced:
      true
    case .notifications, .chats, .appearance, .maps, .language, .backup, .support, .feedback:
      false
    }
  }
}
