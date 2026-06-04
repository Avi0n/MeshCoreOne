import SwiftUI

/// Settings → Appearance row. Trailing detail shows the active theme's name (matching the
/// region/mapStyle row pattern). Placed near the top, with other display-style settings.
struct AppearanceSection: View {
    @Environment(\.appState) private var appState
    @Environment(\.appTheme) private var activeTheme
    let isSidebar: Bool

    var body: some View {
        SettingsDetailRow(detail: .appearance, isSidebar: isSidebar) {
            HStack {
                TintedLabel(L10n.Settings.Appearance.title, systemImage: "paintpalette")
                Spacer()
                Text(activeTheme.localizedName)
                    .foregroundStyle(.secondary)
            }
        }
        .accessibilityValue(activeTheme.localizedName)
    }
}
