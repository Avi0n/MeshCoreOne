import Foundation

/// In-bubble Translation offer chrome. `nil` on the payload means no row;
/// stored message text is never replaced.
public struct MessageTranslationChrome: Sendable, Hashable {
  public enum Phase: Sendable, Hashable {
    case offer
    case inProgress
    case showing(translatedText: String, targetLanguageCode: String)
  }

  public let phase: Phase
  public let sourceLanguageCode: String

  public init(phase: Phase, sourceLanguageCode: String) {
    self.phase = phase
    self.sourceLanguageCode = sourceLanguageCode
  }

  /// In-flight wins; showing wins only if its target still matches preferred.
  /// Otherwise an offer exists only when detection differs from preferred.
  public static func resolved(
    detected: DetectedLanguage?,
    phase: Phase?,
    preferredLanguageCode: String
  ) -> MessageTranslationChrome? {
    guard case let .identified(languageCode: sourceLanguageCode) = detected else { return nil }
    if let phase {
      switch phase {
      case .inProgress:
        return MessageTranslationChrome(phase: phase, sourceLanguageCode: sourceLanguageCode)
      case let .showing(_, targetLanguageCode):
        if MessageLanguageDetector.isSameLanguage(targetLanguageCode, preferredLanguageCode) {
          return MessageTranslationChrome(phase: phase, sourceLanguageCode: sourceLanguageCode)
        }
      case .offer:
        break
      }
    }
    if MessageLanguageDetector.isSameLanguage(sourceLanguageCode, preferredLanguageCode) {
      return nil
    }
    return MessageTranslationChrome(phase: .offer, sourceLanguageCode: sourceLanguageCode)
  }
}
