import MC1Services
import SwiftUI

struct AppearanceView: View {
  @Environment(\.appState) private var appState
  @Environment(\.appTheme) private var activeTheme
  @Environment(\.dynamicTypeSize) private var dynamicTypeSize

  @State private var schemeTrigger = 0
  @State private var selectionTrigger = 0

  private var themeService: ThemeService {
    appState.themeService
  }

  private var availableThemes: [Theme] {
    themeService.availableToCurrentUser()
  }

  private var columns: [GridItem] {
    dynamicTypeSize.isAccessibilitySize
      ? [GridItem(.flexible())]
      : [GridItem(.adaptive(minimum: ThemeCardMetrics.gridItemMinimum), spacing: ThemeCardMetrics.gridSpacing)]
  }

  /// "Purchase More Themes" is shown only when at least one registry theme is unavailable.
  static func shouldShowBrowseMore(available: [Theme]) -> Bool {
    available.count < ThemeRegistry.allThemes.count
  }

  var body: some View {
    List {
      schemeSection
      themesSection
      if Self.shouldShowBrowseMore(available: availableThemes) {
        Section {
          NavigationLink(L10n.Settings.Appearance.MoreThemes.link) {
            SupportDevelopmentView()
          }
        }
        .themedRowBackground(activeTheme)
      }
    }
    .themedCanvas(activeTheme)
    .navigationTitle(L10n.Settings.Appearance.title)
    .navigationBarTitleDisplayMode(.inline)
    .sensoryFeedback(.selection, trigger: schemeTrigger)
    .sensoryFeedback(.selection, trigger: selectionTrigger)
  }

  private var schemeSection: some View {
    Section {
      Picker(L10n.Settings.Appearance.Scheme.header, selection: schemeBinding) {
        ForEach(AppColorSchemePreference.allCases) { preference in
          Text(schemeLabel(preference)).tag(preference)
        }
      }
      .pickerStyle(.menu)
    }
    .themedRowBackground(activeTheme)
  }

  private var themesSection: some View {
    Section {
      LazyVGrid(columns: columns, spacing: ThemeCardMetrics.gridSpacing) {
        ForEach(availableThemes) { theme in
          ThemeSelectionCard(
            theme: theme,
            isSelected: theme.id == activeTheme.id,
            onSelect: { select(theme) }
          )
        }
      }
      .listRowInsets(ThemeCardMetrics.gridRowInsets)
      .listRowBackground(Color.clear)
    } header: {
      Text(L10n.Settings.Appearance.Themes.header)
    }
  }

  private var schemeBinding: Binding<AppColorSchemePreference> {
    Binding(
      get: { themeService.colorSchemePreference },
      set: {
        themeService.setColorSchemePreference($0)
        schemeTrigger += 1
      }
    )
  }

  private func schemeLabel(_ preference: AppColorSchemePreference) -> String {
    switch preference {
    case .system: L10n.Settings.Appearance.Scheme.system
    case .light: L10n.Settings.Appearance.Scheme.light
    case .dark: L10n.Settings.Appearance.Scheme.dark
    }
  }

  private func select(_ theme: Theme) {
    // The list is already filtered to accessible themes; the throw path is a defensive
    // guard against a refund-driven revert landing mid-tap.
    try? themeService.setCurrent(theme)
    selectionTrigger += 1
  }
}
