import Foundation
@testable import MC1
import Testing

@Suite("TranslationLanguageResolver")
struct TranslationLanguageResolverTests {
  private let supported = [
    Locale.Language(identifier: "ja-JP"),
    Locale.Language(identifier: "en-US"),
    Locale.Language(identifier: "en-GB"),
    Locale.Language(identifier: "zh-Hans-CN"),
    Locale.Language(identifier: "zh-Hant-TW"),
    Locale.Language(identifier: "de-DE")
  ]

  @Test
  func `compact Japanese maps to the supported ja-JP pack`() {
    let resolved = TranslationLanguageResolver.resolve(
      "ja",
      from: supported,
      preferring: Locale(identifier: "en-US")
    )
    #expect(resolved.region == Locale.Region("JP"))
  }

  @Test
  func `simplified Chinese prefers Hans over Hant`() {
    let resolved = TranslationLanguageResolver.resolve(
      "zh-Hans",
      from: supported,
      preferring: Locale(identifier: "en-US")
    )
    #expect(resolved.script == Locale.Language(identifier: "zh-Hans-CN").script)
  }

  @Test
  func `traditional Chinese maps to Hant even when Hans is listed first`() {
    let resolved = TranslationLanguageResolver.resolve(
      "zh-Hant",
      from: supported,
      preferring: Locale(identifier: "en-US")
    )
    #expect(resolved.script == Locale.Language(identifier: "zh-Hant-TW").script)
    #expect(resolved.region == Locale.Region("TW"))
  }

  @Test
  func `compact Chinese prefers the locale script`() {
    let resolved = TranslationLanguageResolver.resolve(
      "zh",
      from: supported,
      preferring: Locale(identifier: "zh-Hant-TW")
    )
    #expect(resolved.script == Locale.Language(identifier: "zh-Hant-TW").script)
    #expect(resolved.region == Locale.Region("TW"))
  }

  @Test
  func `compact English prefers the locale region`() {
    let resolved = TranslationLanguageResolver.resolve(
      "en",
      from: supported,
      preferring: Locale(identifier: "en-GB")
    )
    #expect(resolved.region == Locale.Region("GB"))
  }

  @Test
  func `specified British English stays GB when preferring US`() {
    let resolved = TranslationLanguageResolver.resolve(
      "en-GB",
      from: supported,
      preferring: Locale(identifier: "en-US")
    )
    #expect(resolved.region == Locale.Region("GB"))
  }

  @Test
  func `unknown language is returned unchanged`() {
    let resolved = TranslationLanguageResolver.resolve(
      "xx",
      from: supported,
      preferring: Locale(identifier: "en-US")
    )
    #expect(resolved.languageCode == Locale.Language(identifier: "xx").languageCode)
  }
}
