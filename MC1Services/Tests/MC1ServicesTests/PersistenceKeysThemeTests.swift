import Testing
@testable import MC1Services

@Suite("PersistenceKeysTheme")
struct PersistenceKeysThemeTests {

    @Test("theme keys are the exact bare-string on-disk names")
    func themeKeyLiterals() {
        #expect(PersistenceKeys.selectedThemeID == "selectedThemeID")
        #expect(PersistenceKeys.appColorSchemePreference == "appColorSchemePreference")
    }

    @Test("theme keys carry no com.pocketmesh prefix (bare-string convention)")
    func themeKeysAreBare() {
        #expect(!PersistenceKeys.selectedThemeID.contains("com.pocketmesh"))
        #expect(!PersistenceKeys.appColorSchemePreference.contains("com.pocketmesh"))
    }
}
