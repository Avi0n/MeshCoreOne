import MC1Services
import SwiftUI

struct ConfigExportImportSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme

  private var isDisabled: Bool {
    appState.connectionState != .ready
  }

  var body: some View {
    Section {
      NavigationLink(value: SettingsSubpage.configExport) {
        TintedLabel(L10n.Settings.ConfigExport.export, systemImage: "square.and.arrow.up")
      }
      .disabled(isDisabled)

      NavigationLink(value: SettingsSubpage.configImport) {
        TintedLabel(L10n.Settings.ConfigImport.importConfig, systemImage: "square.and.arrow.down")
      }
      .disabled(isDisabled)
    } header: {
      Text(L10n.Settings.ConfigExport.sectionTitle)
    } footer: {
      Text(L10n.Settings.ConfigExport.sectionFooter)
    }
    .themedRowBackground(theme)
  }
}
