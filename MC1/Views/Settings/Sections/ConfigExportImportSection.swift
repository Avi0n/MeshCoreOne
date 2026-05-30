import SwiftUI
import MC1Services

struct ConfigExportImportSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var theme

    private var isDisabled: Bool {
        appState.connectionState != .ready
    }

    var body: some View {
        Section {
            NavigationLink {
                NodeConfigExportView()
            } label: {
                TintedLabel(L10n.Settings.ConfigExport.export, systemImage: "square.and.arrow.up")
            }
            .disabled(isDisabled)

            NavigationLink {
                NodeConfigImportView()
            } label: {
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
