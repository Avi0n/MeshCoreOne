import SwiftUI

/// About and links section
struct AboutSection: View {
    @Environment(\.appTheme) private var theme
    let isSidebar: Bool

    var body: some View {
        Section {
            #if SIDELOAD
            Link(destination: URL(string: "https://github.com/sponsors/Avi0n")!) {
                HStack {
                    TintedLabel(L10n.Settings.Support.title, systemImage: "heart")
                    Spacer()
                    Image(systemName: "arrow.up.right")
                        .font(.caption)
                        .foregroundStyle(.tertiary)
                }
            }
            .foregroundStyle(.primary)
            #else
            SettingsDetailRow(detail: .support) {
                TintedLabel(L10n.Settings.Support.title, systemImage: "heart")
            }
            #endif

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

            Link(destination: URL(string: "https://meshcoreone.com/privacy.html")!) {
                HStack {
                    TintedLabel(L10n.Settings.About.privacyPolicy, systemImage: "hand.raised")
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
        .themedRowBackground(theme, flatten: isSidebar)
    }
}
