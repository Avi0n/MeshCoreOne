import SwiftUI

/// About and links section
struct AboutSection: View {
    @Environment(\.appTheme) private var theme

    var body: some View {
        Section {
            Link(destination: URL(string: "https://meshcore.io")!) {
                HStack {
                    TintedLabel(L10n.Settings.About.website, systemImage: "globe")
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .foregroundStyle(.primary)

            Link(destination: URL(string: "https://map.meshcore.io/")!) {
                HStack {
                    TintedLabel(L10n.Settings.About.onlineMap, systemImage: "map")
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .foregroundStyle(.primary)

            Link(destination: URL(string: "https://github.com/Avi0n/MeshCoreOne")!) {
                HStack {
                    TintedLabel(L10n.Settings.About.github, systemImage: "chevron.left.forwardslash.chevron.right")
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .foregroundStyle(.primary)

        } header: {
            Text(L10n.Settings.About.header)
        }
        .themedRowBackground(theme)
    }
}
