import Foundation

extension MECPCatalog {
  static let quantityKey = "chats.message.mecpPersonCount"
  static let quantityTable = "Chats"

  /// The quantity line follows the pack locale, so a Korean app still uses the English forms.
  static func quantityLine(count: Int, preferredLocalization: String, bundle: Bundle) -> String {
    let localization = lprojName(for: preferredLocalization)
    let category = pluralCategory(count: count, localization: localization)
    if let line = formattedQuantity(count: count, category: category, localization: localization, bundle: bundle) {
      return line
    }
    if localization != englishStem,
       let english = formattedQuantity(count: count, category: pluralCategory(count: count, localization: englishStem), localization: englishStem, bundle: bundle) {
      return english
    }
    return String(count)
  }

  private static let pluralSpec = "NSStringPluralRuleType"
  private static let pluralValueType = "lld"
  private static let otherCategory = "other"
  private static let oneCategory = "one"
  private static let fewCategory = "few"
  private static let manyCategory = "many"

  private static func formattedQuantity(count: Int, category: String, localization: String, bundle: Bundle) -> String? {
    let template = stringsdictVariant(category: category, localization: localization, bundle: bundle)
      ?? stringsdictVariant(category: otherCategory, localization: localization, bundle: bundle)
      ?? stringsValue(localization: localization, bundle: bundle)
    guard let template else { return nil }
    return String(format: template, locale: Locale(identifier: localization), Int64(count))
  }

  private static func stringsdictVariant(category: String, localization: String, bundle: Bundle) -> String? {
    guard let url = localizationFile(name: quantityTable, extension: "stringsdict", localization: localization, bundle: bundle),
          let data = try? Data(contentsOf: url),
          let root = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: Any],
          let entry = root[quantityKey] as? [String: Any] else {
      return nil
    }
    for value in entry.values {
      guard let variants = value as? [String: Any],
            variants["NSStringFormatSpecTypeKey"] as? String == pluralSpec,
            variants["NSStringFormatValueTypeKey"] as? String == pluralValueType,
            let line = variants[category] as? String else {
        continue
      }
      return line
    }
    return nil
  }

  private static func stringsValue(localization: String, bundle: Bundle) -> String? {
    guard let url = localizationFile(name: quantityTable, extension: "strings", localization: localization, bundle: bundle),
          let data = try? Data(contentsOf: url),
          let table = try? PropertyListSerialization.propertyList(from: data, format: nil) as? [String: String] else {
      return nil
    }
    return table[quantityKey]
  }

  private static func localizationFile(name: String, extension ext: String, localization: String, bundle: Bundle) -> URL? {
    if let url = bundle.url(forResource: name, withExtension: ext, subdirectory: nil, localization: localization) {
      return url
    }
    guard let lproj = bundle.url(forResource: localization, withExtension: "lproj"),
          let nested = Bundle(url: lproj) else {
      return nil
    }
    return nested.url(forResource: name, withExtension: ext)
  }

  private static func pluralCategory(count: Int, localization: String) -> String {
    let language = localization.split(separator: "-").first.map(String.init) ?? localization
    switch language {
    case "pl":
      return polishCategory(count)
    case "ru", "uk":
      return eastSlavicCategory(count)
    case "fr":
      return count == 0 || count == 1 ? oneCategory : otherCategory
    case "zh", "ko":
      return otherCategory
    default:
      return count == 1 ? oneCategory : otherCategory
    }
  }

  private static func polishCategory(_ count: Int) -> String {
    if count == 1 { return oneCategory }
    let mod10 = count % 10
    let mod100 = count % 100
    if (2...4).contains(mod10), !(12...14).contains(mod100) { return fewCategory }
    return manyCategory
  }

  private static func eastSlavicCategory(_ count: Int) -> String {
    let mod10 = abs(count) % 10
    let mod100 = abs(count) % 100
    if mod10 == 1, mod100 != 11 { return oneCategory }
    if (2...4).contains(mod10), !(12...14).contains(mod100) { return fewCategory }
    return manyCategory
  }
}
