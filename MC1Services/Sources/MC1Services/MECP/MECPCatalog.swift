import Foundation

/// Phrase reading for a decoded MECP string. The JSON packs are the phrase source.
public enum MECPCatalog {
  /// Nil when `text` does not decode. Otherwise each part is one line.
  public static func reading(
    _ text: String,
    bundle: Bundle = .main,
    preferredLocalizations: [String]? = nil
  ) -> String? {
    renderedLines(
      text,
      bundle: bundle,
      preferredLocalizations: preferredLocalizations,
      resourceBundle: .module
    )?.joined(separator: "\n")
  }

  public static func displayText(
    _ text: String,
    bundle: Bundle = .main,
    preferredLocalizations: [String]? = nil
  ) -> String {
    displayText(text, bundle: bundle, preferredLocalizations: preferredLocalizations, resourceBundle: .module)
  }

  static func displayText(
    _ text: String,
    bundle: Bundle,
    preferredLocalizations: [String]?,
    resourceBundle: Bundle
  ) -> String {
    renderedLines(
      text,
      bundle: bundle,
      preferredLocalizations: preferredLocalizations,
      resourceBundle: resourceBundle
    )?.joined(separator: " ") ?? text
  }

  static func firstPreferredLocalization(_ preferredLocalizations: [String]) -> String {
    preferredLocalizations.first ?? fallbackLocalization
  }

  static func renderedLines(
    _ text: String,
    bundle: Bundle,
    preferredLocalizations: [String]?,
    resourceBundle: Bundle
  ) -> [String]? {
    guard let message = MECPDecode.decode(text) else { return nil }
    let preferred = firstPreferredLocalization(preferredLocalizations ?? bundle.preferredLocalizations)
    let packs = languagePacks(in: resourceBundle)
    let stem = packStem(for: preferred)
    guard let english = packs[englishStem] else { return nil }
    let pack = packs[stem] ?? english
    return lines(for: message, pack: pack, english: english, preferredLocalization: preferred, bundle: bundle)
  }

  struct LanguagePack: Equatable, Sendable {
    var severityLocal: [String: String]
    var codes: [String: String]
    var unknownCode: String?

    init(severityLocal: [String: String], codes: [String: String], unknownCode: String?) {
      self.severityLocal = severityLocal
      self.codes = codes
      self.unknownCode = unknownCode
    }

    init?(data: Data) {
      guard let file = try? JSONDecoder().decode(File.self, from: data) else { return nil }
      severityLocal = file.severities.mapValues(\.local)
      codes = file.codes
      unknownCode = file.ui[unknownCodeKey]
    }

    private struct File: Decodable {
      var severities: [String: Label]
      var codes: [String: String]
      var ui: [String: String]

      struct Label: Decodable {
        var local: String
      }
    }
  }

  static func languagePack(stem: String, resourceBundle: Bundle = .module) -> LanguagePack? {
    languagePacks(in: resourceBundle)[stem]
  }

  static func phrase(
    for code: String,
    message: MECPMessage,
    pack: LanguagePack,
    english: LanguagePack
  ) -> String {
    if let template = pack.codes[code] ?? english.codes[code] {
      return fillingSlots(in: template, code: code, message: message)
    }
    let unknown = pack.unknownCode ?? absentUnknownCode
    return "\(code): \(unknown)"
  }

  static let packStems = ["en", "de", "es", "fr", "it", "nl", "pl", "pt", "ru", "uk", "zh-cn"]
  static let englishStem = "en"
  static let simplifiedChineseStem = "zh-cn"
  static let simplifiedChineseLocalization = "zh-Hans"
  static let absentUnknownCode = "Unknown code"

  private static let fallbackLocalization = "en"
  private static let etaCode = "R03"
  private static let shelterCode = "H07"
  private static let unknownCodeKey = "unknown_code"
  private static let knownLocalizationStems: Set<String> = ["de", "en", "es", "fr", "it", "nl", "pl", "pt", "ru", "uk"]
  private static let cachedPacks: [String: LanguagePack] = loadPacks(in: .module)

  private static func languagePacks(in bundle: Bundle) -> [String: LanguagePack] {
    if bundle.bundleURL == Bundle.module.bundleURL {
      return cachedPacks
    }
    return loadPacks(in: bundle)
  }

  private static func loadPacks(in bundle: Bundle) -> [String: LanguagePack] {
    var packs: [String: LanguagePack] = [:]
    for stem in packStems {
      guard let url = bundle.url(forResource: stem, withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let pack = LanguagePack(data: data) else {
        continue
      }
      packs[stem] = pack
    }
    return packs
  }

  private static func packStem(for preferredLocalization: String) -> String {
    let normalized = preferredLocalization.replacingOccurrences(of: "_", with: "-")
    if normalized == simplifiedChineseLocalization || normalized.hasPrefix("\(simplifiedChineseLocalization)-") {
      return simplifiedChineseStem
    }
    let stem = normalized.split(separator: "-").first.map(String.init) ?? normalized
    return knownLocalizationStems.contains(stem) ? stem : englishStem
  }

  static func lprojName(for preferredLocalization: String) -> String {
    packStem(for: preferredLocalization) == simplifiedChineseStem ? simplifiedChineseLocalization : packStem(for: preferredLocalization)
  }

  private static func lines(
    for message: MECPMessage,
    pack: LanguagePack,
    english: LanguagePack,
    preferredLocalization: String,
    bundle: Bundle
  ) -> [String]? {
    var parts: [String] = []
    let severityKey = String(message.severity.rawValue)
    if let label = pack.severityLocal[severityKey] ?? english.severityLocal[severityKey], !label.isEmpty {
      parts.append(label)
    }
    for code in message.codes {
      parts.append(phrase(for: code, message: message, pack: pack, english: english))
    }
    if let count = message.personCount {
      parts.append(quantityLine(count: count, preferredLocalization: preferredLocalization, bundle: bundle))
    }
    if let hhmm = message.timestampHHMM {
      parts.append(clockLine(hhmm))
    }
    if let tail = message.displayTail {
      parts.append(tail)
    }
    return parts.isEmpty ? nil : parts
  }

  private static func fillingSlots(in template: String, code: String, message: MECPMessage) -> String {
    if code == etaCode, let eta = message.eta, let replaced = replacingBracket(in: template, with: { "\(eta) \($0)" }) {
      return replaced
    }
    if code == shelterCode, let count = message.personCount, let replaced = replacingBracket(in: template, with: { _ in String(count) }) {
      return replaced
    }
    return template
  }

  private static func replacingBracket(in template: String, with replacement: (String) -> String) -> String? {
    guard let open = template.firstIndex(of: "["),
          let close = template[template.index(after: open)...].firstIndex(of: "]") else {
      return nil
    }
    let inner = String(template[template.index(after: open)..<close])
    var result = template
    let throughClose = template.index(after: close)
    result.replaceSubrange(open..<throughClose, with: replacement(inner))
    return result
  }

  private static func clockLine(_ hhmm: String) -> String {
    "\(hhmm.prefix(2)):\(hhmm.suffix(2)) UTC"
  }
}
