@testable import MC1Services
import Testing

@Suite("PersistenceKeysTheme")
struct PersistenceKeysThemeTests {
  @Test
  func `theme keys are the exact bare-string on-disk names`() {
    #expect(PersistenceKeys.selectedThemeID == "selectedThemeID")
    #expect(PersistenceKeys.appColorSchemePreference == "appColorSchemePreference")
  }

  @Test
  func `theme keys carry no com.pocketmesh prefix (bare-string convention)`() {
    #expect(!PersistenceKeys.selectedThemeID.contains("com.pocketmesh"))
    #expect(!PersistenceKeys.appColorSchemePreference.contains("com.pocketmesh"))
  }
}
