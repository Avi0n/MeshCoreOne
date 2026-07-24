import MC1Services
import SwiftUI

/// Settings → Maps hub: basemap appearance and Offline Maps entry.
struct MapsSettingsView: View {
  @Environment(\.appTheme) private var theme
  @AppStorage(AppStorageKey.mapColorSchemePreference.rawValue)
  private var mapColorSchemeRaw = AppStorageKey.defaultMapColorSchemePreference

  private var mapColorScheme: Binding<AppColorSchemePreference> {
    Binding(
      get: { AppColorSchemePreference(rawValue: mapColorSchemeRaw) ?? .system },
      set: { mapColorSchemeRaw = $0.rawValue }
    )
  }

  var body: some View {
    List {
      Section {
        Picker(L10n.Settings.Maps.appearance, selection: mapColorScheme) {
          Text(L10n.Settings.Appearance.Scheme.system).tag(AppColorSchemePreference.system)
          Text(L10n.Settings.Appearance.Scheme.light).tag(AppColorSchemePreference.light)
          Text(L10n.Settings.Appearance.Scheme.dark).tag(AppColorSchemePreference.dark)
        }
      } header: {
        Text(L10n.Settings.Maps.displayHeader)
      } footer: {
        Text(L10n.Settings.Maps.appearanceFooter)
      }
      .themedRowBackground(theme)

      Section {
        NavigationLink {
          OfflineMapSettingsView()
        } label: {
          TintedLabel(L10n.Settings.OfflineMaps.title, systemImage: "arrow.down.circle")
        }
      } header: {
        Text(L10n.Settings.Maps.offlineHeader)
      }
      .themedRowBackground(theme)
    }
    .themedCanvas(theme)
    .navigationTitle(L10n.Settings.Maps.title)
  }
}
