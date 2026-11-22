import Foundation

extension MECPCatalog {
  static let quantityKey = "chats.message.mecpPersonCount"
  static let quantityTable = "Chats"

  /// The quantity line follows the pack locale, so a Korean app still uses the English forms.
  static func quantityLine(count: Int, preferredLocalization: String, bundle: Bundle) -> String {
    let localization = lprojName(for: preferredLocalization)
    if let line = formattedQuantity(count: count, localization: localization, bundle: bundle) {
      return line
    }
    if localization != englishStem,
       let english = formattedQuantity(count: count, localization: englishStem, bundle: bundle) {
      return english
    }
    return String(count)
  }

  private static let missingQuantitySentinel = "\u{0000}"

  private static func formattedQuantity(count: Int, localization: String, bundle: Bundle) -> String? {
    guard let lprojBundle = localizationBundle(for: localization, in: bundle) else {
      return nil
    }
    let template = lprojBundle.localizedString(
      forKey: quantityKey,
      value: missingQuantitySentinel,
      table: quantityTable
    )
    guard template != missingQuantitySentinel else { return nil }
    return String(format: template, locale: Locale(identifier: localization), Int64(count))
  }

  private static func localizationBundle(for localization: String, in bundle: Bundle) -> Bundle? {
    if let lprojURL = bundle.url(forResource: localization, withExtension: "lproj") {
      return Bundle(url: lprojURL)
    }
    if let tableURL = bundle.url(forResource: quantityTable, withExtension: "stringsdict", subdirectory: nil, localization: localization)
      ?? bundle.url(forResource: quantityTable, withExtension: "strings", subdirectory: nil, localization: localization) {
      return Bundle(url: tableURL.deletingLastPathComponent())
    }
    return nil
  }
}
