import Foundation

/// Maps compact detector/app-locale codes onto `LanguageAvailability.supportedLanguages`.
/// Compact codes make `status` report `.supported` even when the regional pack is installed.
enum TranslationLanguageResolver {
  private enum Score {
    static let specifiedScript = 4
    static let specifiedRegion = 2
    static let preferredRegion = 1
  }

  static func resolve(
    _ code: String,
    from supported: [Locale.Language],
    preferring locale: Locale = .current
  ) -> Locale.Language {
    let requested = Locale.Language(identifier: code)
    let matches = supported.filter { $0.languageCode == requested.languageCode }
    guard !matches.isEmpty else { return requested }

    if !isCompact(code),
       let exact = matches.first(where: { $0.maximalIdentifier == requested.maximalIdentifier }) {
      return exact
    }

    var best = matches[0]
    var bestScore = score(best, code: code, requested: requested, locale: locale)
    for candidate in matches.dropFirst() {
      let value = score(candidate, code: code, requested: requested, locale: locale)
      if value > bestScore {
        best = candidate
        bestScore = value
      }
    }
    return best
  }

  /// Language subtag only (`zh`, `en`). Foundation fills a default script/region
  /// on `maximalIdentifier`, which must not count as an exact pack match.
  private static func isCompact(_ code: String) -> Bool {
    !code.contains("-")
  }

  private static func score(
    _ candidate: Locale.Language,
    code: String,
    requested: Locale.Language,
    locale: Locale
  ) -> Int {
    var value = 0
    if isCompact(code) {
      if candidate.script != nil, candidate.script == locale.language.script {
        value += Score.specifiedScript
      }
      if candidate.region != nil, candidate.region == locale.region {
        value += Score.preferredRegion
      }
    } else {
      if candidate.script != nil, candidate.script == requested.script {
        value += Score.specifiedScript
      }
      if requested.region != nil, candidate.region == requested.region {
        value += Score.specifiedRegion
      }
      if requested.region == nil, candidate.region == locale.region {
        value += Score.preferredRegion
      }
    }
    return value
  }
}
