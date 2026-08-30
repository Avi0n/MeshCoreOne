import Foundation
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
      contentSizeCategory: EnvInputs.defaultContentSizeCategory,
      preferredLanguageCode: EnvInputs.defaultPreferredLanguageCode
    )
  }

  private func make(preferredLanguageCode: String) -> EnvInputs {
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
      themeID: EnvInputs.defaultThemeID,
      contentSizeCategory: EnvInputs.defaultContentSizeCategory,
      preferredLanguageCode: preferredLanguageCode
    )
  }

  @Test
  func `changing only themeID makes EnvInputs unequal (drives cache invalidation)`() {
    #expect(make(themeID: "default") != make(themeID: "ember"))
    #expect(make(themeID: "default") == make(themeID: "default"))
  }

  @Test
  func `changing only preferredLanguageCode makes EnvInputs unequal`() {
    #expect(make(preferredLanguageCode: "en") != make(preferredLanguageCode: "uk"))
    #expect(make(preferredLanguageCode: "en") == make(preferredLanguageCode: "en"))
  }

  @Test
  func `EnvInputs.default carries the default theme id`() {
    #expect(EnvInputs.default.themeID == "default")
  }

  @Test
  func `preferredLanguageCode from locale collapses region and script`() {
    #expect(EnvInputs.preferredLanguageCode(from: Locale(identifier: "en-US")) == "en")
    #expect(EnvInputs.preferredLanguageCode(from: Locale(identifier: "zh-Hans")) == "zh")
    #expect(EnvInputs.preferredLanguageCode(from: Locale(identifier: "de-DE")) == "de")
  }
}
