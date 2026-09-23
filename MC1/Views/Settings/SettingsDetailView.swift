import SwiftUI

/// Renders the destination for a `SettingsDetail`. Used as the split detail
/// root by `SettingsView`.
struct SettingsDetailView: View {
  @Environment(\.appState) private var appState
  let detail: SettingsDetail

  var body: some View {
    switch detail {
    case .deviceInfo:
      DeviceInfoView()
    case .radio:
      RadioSettingsView()
    case .location:
      LocationSettingsView()
    case .connection:
      ConnectionSettingsView()
    case .advanced:
      AdvancedSettingsView()
    case .notifications:
      NotificationSettingsView()
    case .chats:
      ChatSettingsView()
    case .appearance:
      AppearanceView()
    case .maps:
      MapsSettingsView()
    case .language:
      LanguageSettingsView()
    case .backup:
      BackupRestoreView(
        connectionManager: appState.connectionManager,
        onImportRestoredData: { [appState] in appState.notifyDataRestored() },
        onChannelDraftSlotsAffected: { [appState] slotsByRadio in
          appState.draftStore.clearChannelDrafts(slotsByRadio: slotsByRadio)
        }
      )
    case .support:
      SupportDevelopmentView()
    case .feedback:
      FeedbackView()
    }
  }
}
