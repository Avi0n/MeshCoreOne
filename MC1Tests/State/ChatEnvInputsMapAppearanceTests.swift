import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Chat env map appearance", .serialized)
@MainActor
struct ChatEnvInputsMapAppearanceTests {
  private let preferences = IsolatedUserDefaults()

  @Test
  func `prefetch reads map appearance separately from chrome`() {
    let defaults = preferences.defaults
    let defaultsKey = AppStorageKey.mapColorSchemePreference.rawValue
    let appState = AppState(defaults: defaults)
    defer { appState.shutdown() }

    defaults.set(AppColorSchemePreference.light.rawValue, forKey: defaultsKey)
    let lightBasemap = chatEnv(appState, isDark: true)
    #expect(lightBasemap.isDark == true)
    #expect(lightBasemap.mapBasemapIsDark == false)

    defaults.set(AppColorSchemePreference.dark.rawValue, forKey: defaultsKey)
    let darkBasemap = chatEnv(appState, isDark: false)
    #expect(darkBasemap.isDark == false)
    #expect(darkBasemap.mapBasemapIsDark == true)
  }

  private func chatEnv(_ appState: AppState, isDark: Bool) -> EnvInputs {
    appState.chatEnvInputs(
      for: nil,
      themeID: EnvInputs.defaultThemeID,
      isDark: isDark,
      isHighContrast: false,
      contentSizeCategory: EnvInputs.defaultContentSizeCategory
    )
  }
}
