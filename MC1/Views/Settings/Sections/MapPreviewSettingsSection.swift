import MC1Services
import SwiftUI

/// Settings section for chat map-thumbnail preferences. Gates the
/// third-party tile request fired from `MapPreviewFragmentView.onAppear` —
/// when off, `MessageFragmentBuilder` skips the fragment entirely and the
/// coordinate text in the message body remains tappable.
struct MapPreviewSettingsSection: View {
  @Environment(\.appTheme) private var theme
  @AppStorage(AppStorageKey.showMapPreviewThumbnails.rawValue)
  private var showMapPreviewThumbnails = AppStorageKey.defaultShowMapPreviewThumbnails

  var body: some View {
    Section {
      Toggle(isOn: $showMapPreviewThumbnails) {
        TintedLabel(L10n.Settings.MapPreviews.toggle, systemImage: "map")
      }
    } header: {
      Text(L10n.Settings.MapPreviews.header)
    } footer: {
      Text(L10n.Settings.MapPreviews.footer)
    }
    .themedRowBackground(theme)
  }
}

#Preview {
  Form {
    MapPreviewSettingsSection()
  }
}
