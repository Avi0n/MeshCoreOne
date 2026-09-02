import Foundation
@testable import MC1Services
import Testing

@Suite("TranslationTargetPreference")
struct TranslationTargetPreferenceTests {
  @Test
  func `app sentinel resolves to the app locale language`() {
    let preference = TranslationTargetPreference.matchAppLanguage
    #expect(preference.rawValue == "app")
    #expect(
      preference.resolvedLanguageCode(appLocale: Locale(identifier: "uk-UA")) == "uk"
    )
  }

  @Test
  func `missing and empty raw values match the app locale`() {
    #expect(
      TranslationTargetPreference(rawValue: "").resolvedLanguageCode(
        appLocale: Locale(identifier: "de-DE")
      ) == "de"
    )
  }

  @Test
  func `Danish override ignores app locale`() {
    let preference = TranslationTargetPreference(rawValue: "da")
    #expect(
      preference.resolvedLanguageCode(appLocale: Locale(identifier: "en-US")) == "da"
    )
  }

  @Test
  func `regional override collapses to the language subtag`() {
    #expect(
      TranslationTargetPreference(rawValue: "en-GB").resolvedLanguageCode(
        appLocale: Locale(identifier: "uk-UA")
      ) == "en"
    )
  }

  @Test
  func `suggested languages skip the app locale, unknown codes, and languages outside the catalog`() {
    let suggested = TranslationTargetPreference.suggestedLanguageCodes(
      preferredLanguages: ["da-DK", "en-US", "xx-YY", "fr-FR"],
      appLocale: Locale(identifier: "en-US"),
      catalog: ["da", "de", "en"]
    )
    #expect(suggested == ["da"])
  }

  @Test
  func `unique compact codes collapse regions and drop duplicates`() {
    #expect(
      TranslationTargetPreference.uniqueCompactCodes(from: ["de-DE", "de", "en-US", "fr"])
        == ["de", "en", "fr"]
    )
  }

  @Test
  func `system overlay follows the app locale for offer gating`() {
    let preference = TranslationTargetPreference.systemOverlay
    #expect(preference.rawValue == "overlay")
    #expect(preference.isSystemOverlay)
    #expect(!preference.isMatchAppLanguage)
    #expect(
      preference.resolvedLanguageCode(appLocale: Locale(identifier: "en-US")) == "en"
    )
  }

  @Test
  func `usesSystemOverlay is the bool or the overlay language sentinel`() {
    #expect(
      TranslationTargetPreference.usesSystemOverlay(
        languageRawValue: "da",
        useDefaultTranslationApp: true
      )
    )
    #expect(
      !TranslationTargetPreference.usesSystemOverlay(
        languageRawValue: "da",
        useDefaultTranslationApp: false
      )
    )
    #expect(
      TranslationTargetPreference.usesSystemOverlay(
        languageRawValue: "overlay",
        useDefaultTranslationApp: false
      )
    )
  }

  @Test
  func `Danish display name uses autonym with localized subtitle`() {
    let name = TranslationTargetPreference.displayName(
      forLanguageCode: "da",
      in: Locale(identifier: "en")
    )
    #expect(name.autonym == "Dansk")
    #expect(name.localizedName == "Danish")
    #expect(name.subtitle == "Danish")
  }

  @Test
  func `matching autonym and localized name omit the subtitle`() {
    let name = TranslationTargetPreference.displayName(
      forLanguageCode: "en",
      in: Locale(identifier: "en")
    )
    #expect(name.subtitle == nil)
  }

  @Test
  func `search matches autonym, localized name, and language code`() {
    let english = Locale(identifier: "en")
    #expect(
      TranslationTargetPreference.matchesSearchQuery("dans", languageCode: "da", locale: english)
    )
    #expect(
      TranslationTargetPreference.matchesSearchQuery("Danish", languageCode: "da", locale: english)
    )
    #expect(
      TranslationTargetPreference.matchesSearchQuery("da", languageCode: "da", locale: english)
    )
    #expect(
      !TranslationTargetPreference.matchesSearchQuery("French", languageCode: "da", locale: english)
    )
  }

  @Test
  func `unavailable search resolves Danish when it is outside the catalog`() {
    let english = Locale(identifier: "en")
    #expect(
      TranslationTargetPreference.unavailableLanguageCode(
        matchingSearchQuery: "Danish",
        locale: english,
        catalog: ["en", "de"]
      ) == "da"
    )
    #expect(
      TranslationTargetPreference.unavailableLanguageCode(
        matchingSearchQuery: "da",
        locale: english,
        catalog: ["en", "de"]
      ) == "da"
    )
    #expect(
      TranslationTargetPreference.unavailableLanguageCode(
        matchingSearchQuery: "Dansk",
        locale: english,
        catalog: ["en", "de"]
      ) == "da"
    )
  }

  @Test
  func `unavailable search ignores catalog languages, short queries, and unknown text`() {
    let english = Locale(identifier: "en")
    #expect(
      TranslationTargetPreference.unavailableLanguageCode(
        matchingSearchQuery: "Danish",
        locale: english,
        catalog: ["da", "en"]
      ) == nil
    )
    #expect(
      TranslationTargetPreference.unavailableLanguageCode(
        matchingSearchQuery: "d",
        locale: english,
        catalog: ["en"]
      ) == nil
    )
    #expect(
      TranslationTargetPreference.unavailableLanguageCode(
        matchingSearchQuery: "xxqq",
        locale: english,
        catalog: ["en"]
      ) == nil
    )
  }

  @Test
  func `detail label follows the app locale until an override is set`() {
    let english = Locale(identifier: "en")
    #expect(
      TranslationTargetPreference.matchAppLanguage.detailLabel(
        appLocale: Locale(identifier: "uk"),
        displayLocale: english
      ) == "Ukrainian"
    )
    #expect(
      TranslationTargetPreference(rawValue: "da").detailLabel(
        appLocale: english,
        displayLocale: english
      ) == "Danish"
    )
  }
}
