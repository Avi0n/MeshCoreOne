@testable import MC1
import SwiftUI
import Testing

@Suite("AppColorSchemePreference")
struct AppColorSchemePreferenceTests {
  @Test
  func `raw values are pinned to the on-disk format`() {
    #expect(AppColorSchemePreference.system.rawValue == "system")
    #expect(AppColorSchemePreference.light.rawValue == "light")
    #expect(AppColorSchemePreference.dark.rawValue == "dark")
  }

  @Test
  func `colorScheme maps system to nil and light/dark to their schemes`() {
    #expect(AppColorSchemePreference.system.colorScheme == nil)
    #expect(AppColorSchemePreference.light.colorScheme == .light)
    #expect(AppColorSchemePreference.dark.colorScheme == .dark)
  }

  @Test
  func `allCases is exactly system, light, dark and id equals rawValue`() {
    #expect(AppColorSchemePreference.allCases == [.system, .light, .dark])
    #expect(AppColorSchemePreference.dark.id == "dark")
  }
}
