import Foundation
import MC1Services
@preconcurrency import Translation

/// Compact language codes this device can translate on-device (Apple Intelligence
/// and Translate packs). Not the third-party default-app list.
enum TranslationLanguageAvailability {
  static func supportedCompactCodes() async -> [String] {
    let languages = await supportedLanguages()
    return TranslationTargetPreference.uniqueCompactCodes(
      from: languages.compactMap { $0.languageCode?.identifier }
    )
  }

  private static func supportedLanguages() async -> [Locale.Language] {
    if #available(iOS 26.4, *) {
      async let packs = LanguageAvailability(preferredStrategy: .lowLatency).supportedLanguages
      async let intelligence = LanguageAvailability(preferredStrategy: .highFidelity).supportedLanguages
      let (packList, intelligenceList) = await (packs, intelligence)
      return packList + intelligenceList
    }
    return await LanguageAvailability().supportedLanguages
  }
}
