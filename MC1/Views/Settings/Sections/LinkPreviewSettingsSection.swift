import MC1Services
import SwiftUI

/// Settings section for the link-content master toggle. One master
/// (`linkPreviewsEnabled`) governs both link-preview cards and inline images;
/// the DM/channel scope sub-toggles and GIF autoplay nest beneath it.
struct LinkPreviewSettingsSection: View {
  @Environment(\.appTheme) private var theme
  @Environment(\.accessibilityReduceMotion) private var reduceMotion
  @AppStorage(AppStorageKey.linkPreviewsEnabled.rawValue) private var previewsEnabled = AppStorageKey.defaultLinkPreviewsEnabled
  @AppStorage(AppStorageKey.linkPreviewsAutoResolveDM.rawValue) private var autoResolveDM = AppStorageKey.defaultLinkPreviewsAutoResolveDM
  @AppStorage(AppStorageKey.linkPreviewsAutoResolveChannels.rawValue) private var autoResolveChannels = AppStorageKey.defaultLinkPreviewsAutoResolveChannels
  @AppStorage(AppStorageKey.autoPlayGIFs.rawValue) private var autoPlayGIFs = AppStorageKey.defaultAutoPlayGIFs

  var body: some View {
    Section {
      Toggle(isOn: $previewsEnabled) {
        TintedLabel(L10n.Settings.LinkPreviews.toggle, systemImage: "link")
      }

      if previewsEnabled {
        Toggle(L10n.Settings.LinkPreviews.showInDMs, isOn: $autoResolveDM)
        Toggle(L10n.Settings.LinkPreviews.showInChannels, isOn: $autoResolveChannels)
        Toggle(L10n.Settings.InlineImages.autoPlayGifs, isOn: $autoPlayGIFs)
      }
    } header: {
      Text(L10n.Settings.LinkPreviews.header)
    } footer: {
      VStack(alignment: .leading, spacing: 4) {
        Text(L10n.Settings.LinkPreviews.footer)
        if previewsEnabled, reduceMotion {
          Text(L10n.Settings.LinkPreviews.reduceMotionNote)
        }
      }
    }
    .themedRowBackground(theme)
  }
}

#Preview {
  Form {
    LinkPreviewSettingsSection()
  }
}
