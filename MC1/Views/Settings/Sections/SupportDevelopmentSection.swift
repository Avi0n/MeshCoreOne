import SwiftUI

/// Settings → Support Development row, at the bottom of the Settings list. No badge.
struct SupportDevelopmentSection: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        Section {
            NavigationLink {
                SupportDevelopmentView()
            } label: {
                TintedLabel(L10n.Settings.Support.title, systemImage: "heart")
            }
        }
        .themedRowBackground(theme)
    }
}
