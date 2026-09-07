import MC1Services
import SwiftUI

/// Settings → Radio → Location. Country/state write `.manual` via RegionPickerRows.
struct PresetLocationView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(PresetLocationSession.self) private var session
  @State private var activeSheet: RegionPickerRows.Sheet?

  var body: some View {
    List {
      Section {
        RegionPickerRows(
          selection: Bindable(appState).regionSelection,
          activeSheet: $activeSheet
        )
        presetLocationUseMyLocationButton(session: session, appState: appState)
      } footer: {
        Text(L10n.Settings.Radio.PresetLocation.footer)
      }
      .themedRowBackground(theme)
    }
    .themedCanvas(theme)
    .regionPickerSheet($activeSheet, selection: Bindable(appState).regionSelection)
    .navigationTitle(L10n.Settings.Radio.presetLocation)
    .navigationBarTitleDisplayMode(.inline)
  }
}
