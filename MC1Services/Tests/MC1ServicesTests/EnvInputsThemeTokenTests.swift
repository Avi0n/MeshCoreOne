@testable import MC1Services
import Testing

@Suite("EnvInputs theme token")
struct EnvInputsThemeTokenTests {
  private func make(themeID: String) -> EnvInputs {
    EnvInputs(
      autoPlayGIFs: true,
      showIncomingPath: true,
      showIncomingHopCount: true,
      showIncomingRegion: true,
      showIncomingSendTime: true,
      previewsEnabled: true,
      isHighContrast: false,
      isDark: false,
      showMapPreviews: true,
      isOffline: false,
      currentUserName: "Tester",
      themeID: themeID,
      contentSizeCategory: EnvInputs.defaultContentSizeCategory
    )
  }

  @Test
  func `changing only themeID makes EnvInputs unequal (drives cache invalidation)`() {
    #expect(make(themeID: "default") != make(themeID: "ember"))
    #expect(make(themeID: "default") == make(themeID: "default"))
  }

  @Test
  func `EnvInputs.default carries the default theme id`() {
    #expect(EnvInputs.default.themeID == "default")
  }
}
