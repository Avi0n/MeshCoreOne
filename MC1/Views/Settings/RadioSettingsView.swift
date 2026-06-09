import SwiftUI

/// Sub-page wrapping RadioPresetSection for the settings navigation
struct RadioSettingsView: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        List {
            RadioPresetSection()
        }
        .themedCanvas(theme)
        .navigationTitle(L10n.Settings.Radio.header)
        .navigationBarTitleDisplayMode(.inline)
    }
}
