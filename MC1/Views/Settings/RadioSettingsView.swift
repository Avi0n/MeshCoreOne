import SwiftUI

/// Settings → Radio. Location is omitted while Repeat Mode is on.
struct RadioSettingsView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(\.openURL) private var openURL
  @State private var radioWriteInFlight = false
  @State private var session = PresetLocationSession()
  @State private var regionPickerSheet: RegionPickerRows.Sheet?

  private var appearTaskID: String {
    let deviceID = appState.connectedDevice?.id.uuidString ?? "none"
    let syncPhase = appState.connectionUI.currentSyncPhase.map { String(describing: $0) } ?? "none"
    let authorized = appState.locationService.isAuthorized
    return "\(deviceID)-\(String(describing: appState.connectionState))-\(syncPhase)-\(authorized)"
  }

  var body: some View {
    List {
      if appState.connectedDevice?.clientRepeat != true {
        PresetLocationSection(activeSheet: $regionPickerSheet)
      }
      RadioPresetSection(radioWriteInFlight: $radioWriteInFlight)
      if appState.connectedDevice?.supportsPathHashMode == true {
        PathHashModeSection()
      }
      AdvancedRadioSection(radioWriteInFlight: $radioWriteInFlight)
    }
    .themedCanvas(theme)
    .environment(session)
    .settingsSubpageDestinations(presetLocationSession: session)
    // Stays mounted across the authorization flip and a Location push. Children must not bind the same alerts.
    .presetLocationSessionAlerts(session, openURL: openURL)
    .regionPickerSheet(
      $regionPickerSheet,
      selection: Bindable(appState).regionSelection
    )
    .scrollDismissesKeyboard(.interactively)
    .navigationTitle(L10n.Settings.Radio.header)
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      ToolbarItemGroup(placement: .keyboard) {
        Spacer()
        Button(L10n.Localizable.Common.done) {
          UIApplication.shared.sendAction(
            #selector(UIResponder.resignFirstResponder),
            to: nil,
            from: nil,
            for: nil
          )
        }
      }
    }
    .task(id: appearTaskID) {
      session.resolveOnAppear(from: appState)
    }
  }
}
