import SwiftUI

/// Settings → Radio: preset picker, Repeat Mode, and manual Advanced fields.
struct RadioSettingsView: View {
  @Environment(\.appTheme) private var theme
  @State private var radioWriteInFlight = false

  var body: some View {
    List {
      RadioPresetSection(radioWriteInFlight: $radioWriteInFlight)
      AdvancedRadioSection(radioWriteInFlight: $radioWriteInFlight)
    }
    .themedCanvas(theme)
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
  }
}
