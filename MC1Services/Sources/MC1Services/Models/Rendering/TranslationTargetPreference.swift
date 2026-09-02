import Foundation

/// In-bubble Translate target: `"app"` and empty follow the app locale,
/// `"overlay"` is the system Translate sheet, anything else is a language subtag.
public struct TranslationTargetPreference: RawRepresentable, Sendable, Equatable {
  public static let matchAppLanguage = TranslationTargetPreference(rawValue: "app")
  public static let systemOverlay = TranslationTargetPreference(rawValue: "overlay")

  public let rawValue: String

  public init(rawValue: String) {
    self.rawValue = rawValue
  }

  public func resolvedLanguageCode(appLocale: Locale) -> String {
    let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
    if trimmed.isEmpty
      || trimmed == Self.matchAppLanguage.rawValue
      || trimmed == Self.systemOverlay.rawValue {
      return EnvInputs.preferredLanguageCode(from: appLocale)
    }
    return MessageLanguageDetector.collapsedLanguageCode(trimmed)
  }

  public var isMatchAppLanguage: Bool {
    let trimmed = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
    return trimmed.isEmpty || trimmed == Self.matchAppLanguage.rawValue
  }

  public var isSystemOverlay: Bool {
    rawValue.trimmingCharacters(in: .whitespacesAndNewlines) == Self.systemOverlay.rawValue
  }

  /// True when tap-Translate opens the system sheet: `useDefaultTranslationApp`,
  /// or `"overlay"` stored as the language.
  public static func usesSystemOverlay(
    languageRawValue: String,
    useDefaultTranslationApp: Bool
  ) -> Bool {
    useDefaultTranslationApp || TranslationTargetPreference(rawValue: languageRawValue).isSystemOverlay
  }

  /// `preferredLanguages` ∩ `catalog`, minus the app locale language.
  public static func suggestedLanguageCodes(
    preferredLanguages: [String],
    appLocale: Locale,
    catalog: [String]
  ) -> [String] {
    let catalogSet = Set(catalog)
    let app = EnvInputs.preferredLanguageCode(from: appLocale)
    var seen = Set<String>()
    var result: [String] = []
    for identifier in preferredLanguages {
      let code = MessageLanguageDetector.collapsedLanguageCode(identifier)
      guard catalogSet.contains(code), code != app, seen.insert(code).inserted else { continue }
      result.append(code)
    }
    return result
  }

  public static func uniqueCompactCodes(from identifiers: [String]) -> [String] {
    var seen = Set<String>()
    var result: [String] = []
    for identifier in identifiers {
      let code = MessageLanguageDetector.collapsedLanguageCode(identifier)
      guard !code.isEmpty, seen.insert(code).inserted else { continue }
      result.append(code)
    }
    return result
  }

  public static func localizedName(forLanguageCode code: String, locale: Locale = .current) -> String {
    locale.localizedString(forLanguageCode: code) ?? code
  }

  /// Autonym (name in that language) plus the name in `locale`. Subtitle is
  /// omitted when they compare equal, so English UI does not subtitle "English".
  public static func displayName(
    forLanguageCode code: String,
    in locale: Locale
  ) -> DisplayName {
    let rawAutonym = Locale(identifier: code).localizedString(forLanguageCode: code) ?? code
    let autonym = rawAutonym.localizedCapitalized
    let localized = localizedName(forLanguageCode: code, locale: locale)
    return DisplayName(autonym: autonym, localizedName: localized)
  }

  public static func matchesSearchQuery(
    _ query: String,
    languageCode: String,
    locale: Locale
  ) -> Bool {
    let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return true }
    let name = displayName(forLanguageCode: languageCode, in: locale)
    return name.autonym.localizedStandardContains(trimmed)
      || name.localizedName.localizedStandardContains(trimmed)
      || languageCode.localizedStandardContains(trimmed)
  }

  /// ISO language whose name or code matches `query` and is not in `catalog`.
  /// Exact name/code wins; a single prefix match is accepted; ambiguous prefixes return nil.
  public static func unavailableLanguageCode(
    matchingSearchQuery query: String,
    locale: Locale,
    catalog: [String]
  ) -> String? {
    let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
    guard trimmed.count >= Self.unavailableSearchQueryMinimumLength else { return nil }

    let excluded = Set(catalog.map { MessageLanguageDetector.collapsedLanguageCode($0) })
    var exact: String?
    var prefixMatches: [String] = []
    var seen = Set<String>()

    for languageCode in Locale.LanguageCode.isoLanguageCodes {
      let compact = MessageLanguageDetector.collapsedLanguageCode(languageCode.identifier)
      guard !compact.isEmpty, !excluded.contains(compact), seen.insert(compact).inserted else {
        continue
      }
      let name = displayName(forLanguageCode: compact, in: locale)
      if isExactLanguageMatch(trimmed, code: compact, name: name) {
        exact = compact
        break
      }
      if isPrefixLanguageMatch(trimmed, name: name, locale: locale) {
        prefixMatches.append(compact)
      }
    }

    if let exact { return exact }
    if prefixMatches.count == 1 { return prefixMatches[0] }
    return nil
  }

  private static let unavailableSearchQueryMinimumLength = 2

  private static func isExactLanguageMatch(_ query: String, code: String, name: DisplayName) -> Bool {
    name.autonym.caseInsensitiveCompare(query) == .orderedSame
      || name.localizedName.caseInsensitiveCompare(query) == .orderedSame
      || code.caseInsensitiveCompare(query) == .orderedSame
  }

  private static func isPrefixLanguageMatch(
    _ query: String,
    name: DisplayName,
    locale: Locale
  ) -> Bool {
    let options: String.CompareOptions = [.anchored, .caseInsensitive, .diacriticInsensitive]
    return name.autonym.range(of: query, options: options, locale: locale) != nil
      || name.localizedName.range(of: query, options: options, locale: locale) != nil
  }

  public func detailLabel(appLocale: Locale, displayLocale: Locale) -> String {
    let code = resolvedLanguageCode(appLocale: appLocale)
    return Self.localizedName(forLanguageCode: code, locale: displayLocale)
  }

  public struct DisplayName: Sendable, Equatable {
    public let autonym: String
    public let localizedName: String

    public var subtitle: String? {
      autonym.caseInsensitiveCompare(localizedName) == .orderedSame ? nil : localizedName
    }
  }
}
