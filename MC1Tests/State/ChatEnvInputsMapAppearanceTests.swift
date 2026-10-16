import Foundation
@testable import MC1
import MC1Services
import Testing

@Suite("Chat env map appearance", .serialized)
@MainActor
struct ChatEnvInputsMapAppearanceTests {
  @Test
  func `prefetch reads map appearance separately from chrome`() {
    let defaultsKey = AppStorageKey.mapColorSchemePreference.rawValue
    let previous = UserDefaults.standard.object(forKey: defaultsKey)
    defer { UserDefaults.standard.set(previous, forKey: defaultsKey) }
    let appState = AppState()

    UserDefaults.standard.set(AppColorSchemePreference.light.rawValue, forKey: defaultsKey)
    let lightBasemap = chatEnv(appState, isDark: true)
    #expect(lightBasemap.isDark == true)
    #expect(lightBasemap.mapBasemapIsDark == false)

    UserDefaults.standard.set(AppColorSchemePreference.dark.rawValue, forKey: defaultsKey)
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
