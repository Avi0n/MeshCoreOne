import Testing
import SwiftUI
@testable import MC1

@Suite("AppColorSchemePreference")
struct AppColorSchemePreferenceTests {

    @Test("raw values are pinned to the on-disk format")
    func rawValuesPinned() {
        #expect(AppColorSchemePreference.system.rawValue == "system")
        #expect(AppColorSchemePreference.light.rawValue == "light")
        #expect(AppColorSchemePreference.dark.rawValue == "dark")
    }

    @Test("colorScheme maps system to nil and light/dark to their schemes")
    func colorSchemeMapping() {
        #expect(AppColorSchemePreference.system.colorScheme == nil)
        #expect(AppColorSchemePreference.light.colorScheme == .light)
        #expect(AppColorSchemePreference.dark.colorScheme == .dark)
    }

    @Test("allCases is exactly system, light, dark and id equals rawValue")
    func allCasesAndID() {
        #expect(AppColorSchemePreference.allCases == [.system, .light, .dark])
        #expect(AppColorSchemePreference.dark.id == "dark")
    }
}
