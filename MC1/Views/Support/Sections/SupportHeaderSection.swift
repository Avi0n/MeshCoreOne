import SwiftUI

struct SupportHeaderSection: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        Section {
            Text(L10n.Settings.Support.Header.body)
                .font(.subheadline)
                .foregroundStyle(.secondary)
        }
        .themedRowBackground(theme)
    }
}
