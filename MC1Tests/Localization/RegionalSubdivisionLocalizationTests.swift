import Foundation
@testable import MC1
@testable import MC1Services
import Testing

struct RegionalSubdivisionLocalizationTests {
  private static let table = "Settings"
  private static let locales = [
    "de", "en", "es", "fr", "it", "nl", "pl", "pt", "ru", "uk", "zh-Hans",
  ]
  private static let sentinel = "\u{0}__subdivision_key_missing__\u{0}"

  @Test
  func `every catalog subdivision key resolves in every locale`() throws {
    let keys = (RegionalAreas.usSubdivisions + RegionalAreas.auSubdivisions).map {
      "region.subdivision.\($0.id)"
    }
    #expect(keys.count == 59)
    for locale in Self.locales {
      let bundle = try #require(
        Self.localeBundle(locale),
        "Missing \(locale).lproj in the app bundle"
      )
      for key in keys {
        let resolved = bundle.localizedString(
          forKey: key,
          value: Self.sentinel,
          table: Self.table
        )
        #expect(
          resolved != Self.sentinel && resolved != key,
          "\(key) falls back to the raw key in \(locale).lproj"
        )
      }
    }
  }

  private static func localeBundle(_ locale: String) -> Bundle? {
    guard let url = Bundle.main.url(forResource: locale, withExtension: "lproj") else {
      return nil
    }
    return Bundle(url: url)
  }
}
