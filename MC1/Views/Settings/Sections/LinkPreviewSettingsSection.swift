import MC1Services
import SwiftUI

/// Settings section for link preview preferences
struct LinkPreviewSettingsSection: View {
    @Environment(\.appTheme) private var theme
    @AppStorage(AppStorageKey.linkPreviewsEnabled.rawValue) private var previewsEnabled = AppStorageKey.defaultLinkPreviewsEnabled
    @AppStorage(AppStorageKey.linkPreviewsAutoResolveDM.rawValue) private var autoResolveDM = AppStorageKey.defaultLinkPreviewsAutoResolveDM
    @AppStorage(AppStorageKey.linkPreviewsAutoResolveChannels.rawValue) private var autoResolveChannels = AppStorageKey.defaultLinkPreviewsAutoResolveChannels
    @AppStorage(AppStorageKey.showInlineImages.rawValue) private var showInlineImages = AppStorageKey.defaultShowInlineImages
    @AppStorage(AppStorageKey.autoPlayGIFs.rawValue) private var autoPlayGIFs = AppStorageKey.defaultAutoPlayGIFs

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
