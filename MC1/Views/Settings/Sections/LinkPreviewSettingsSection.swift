import SwiftUI

/// Settings section for link preview preferences
struct LinkPreviewSettingsSection: View {
    @Environment(\.appTheme) private var theme
    @AppStorage("linkPreviewsEnabled") private var previewsEnabled = false
    @AppStorage("linkPreviewsAutoResolveDM") private var autoResolveDM = true
    @AppStorage("linkPreviewsAutoResolveChannels") private var autoResolveChannels = true
    @AppStorage("showInlineImages") private var showInlineImages = true
    @AppStorage("autoPlayGIFs") private var autoPlayGIFs = true

    var body: some View {
        Section {
            Toggle(isOn: $previewsEnabled) {
                TintedLabel(L10n.Settings.LinkPreviews.toggle, systemImage: "link")
            }

            if previewsEnabled {
                Toggle(L10n.Settings.LinkPreviews.showInDMs, isOn: $autoResolveDM)
                Toggle(L10n.Settings.LinkPreviews.showInChannels, isOn: $autoResolveChannels)
            }
        } header: {
            Text(L10n.Settings.LinkPreviews.header)
        } footer: {
            Text(L10n.Settings.LinkPreviews.footer)
        }
        .themedRowBackground(theme)

        Section {
            Toggle(isOn: $showInlineImages) {
                TintedLabel(L10n.Settings.InlineImages.toggle, systemImage: "photo")
            }

            if showInlineImages {
                Toggle(isOn: $autoPlayGIFs) {
                    TintedLabel(L10n.Settings.InlineImages.autoPlayGifs, systemImage: "play.square")
                }
            }
        } footer: {
            Text(L10n.Settings.InlineImages.footer)
        }
        .themedRowBackground(theme)
    }
}

#Preview {
    Form {
        LinkPreviewSettingsSection()
    }
}
