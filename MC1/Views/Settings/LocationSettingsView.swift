import SwiftUI

/// Sub-page wrapping LocationSettingsSection for the settings navigation
struct LocationSettingsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @State private var showingLocationPicker = false

  var body: some View {
    List {
      LocationSettingsSection(showingLocationPicker: $showingLocationPicker)
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.Location.header)
    .navigationBarTitleDisplayMode(.inline)
    .sheet(isPresented: $showingLocationPicker) {
      LocationPickerView.forLocalDevice(appState: appState)
    }
  }
}
