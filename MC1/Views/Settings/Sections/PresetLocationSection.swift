import MC1Services
import SwiftUI

/// Preset-location filter on Settings → Radio. Hidden by the parent when Repeat Mode is on.
struct PresetLocationSection: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var theme
  @Environment(PresetLocationSession.self) private var session
  @Binding var activeSheet: RegionPickerRows.Sheet?

  private var selection: RegionSelection? {
    appState.regionSelection
  }

  private var isExpanded: Bool {
    PresetLocationPolicy.shouldExpandOnRadio(
      authorized: appState.locationService.isAuthorized,
      selection: selection
    )
  }

  private var rowDetail: String {
    if let selection {
      return RegionalAreas.displayName(for: selection)
    }
    return L10n.Settings.Radio.PresetLocation.notSet
  }

  var body: some View {
    Group {
      if isExpanded {
        Section {
          RegionPickerRows(
            selection: Bindable(appState).regionSelection,
            activeSheet: $activeSheet
          )
          presetLocationUseMyLocationButton(session: session, appState: appState)
        } footer: {
          Text(L10n.Settings.Radio.PresetLocation.footer)
        }
      } else {
        Section {
          NavigationLink(value: SettingsSubpage.presetLocation) {
            HStack {
              Text(L10n.Settings.Radio.presetLocation)
              Spacer()
              Text(rowDetail)
                .foregroundStyle(.secondary)
            }
          }
        } footer: {
          Text(L10n.Settings.Radio.PresetLocation.footer)
        }
      }
    }
    .themedRowBackground(theme)
  }
}

@MainActor
func presetLocationUseMyLocationButton(
  session: PresetLocationSession,
  appState: AppState
) -> some View {
  Button {
    session.useMyLocation(from: appState)
  } label: {
    if session.isResolving {
      HStack {
        ProgressView()
        Text(L10n.Settings.Radio.PresetLocation.UseMyLocation.locating)
      }
    } else {
      Text(L10n.Onboarding.Region.useMyLocation)
    }
  }
  .disabled(session.isResolving)
  .accessibilityLabel(L10n.Onboarding.Region.useMyLocation)
  .accessibilityValue(
    session.isResolving ? L10n.Settings.Radio.PresetLocation.UseMyLocation.locating : ""
  )
}
