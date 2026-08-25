import Foundation
import NaturalLanguage

/// Synchronous language detection. A new `NLLanguageRecognizer` per call —
/// Apple does not allow sharing one across threads.
public enum MessageLanguageDetector: Sendable {
  public static let minimumLetterCount = 12
  public static let minimumConfidence = 0.85

  /// High-confidence dominant language, or `.undetermined`. Bake/tile compare against preferred.
  public static func dominantLanguage(for text: String) -> DetectedLanguage {
    let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
    guard !trimmed.isEmpty else { return .undetermined }

    let letterCount = trimmed.reduce(into: 0) { count, character in
      if character.isLetter { count += 1 }
    }
    guard letterCount >= minimumLetterCount else { return .undetermined }

    let recognizer = NLLanguageRecognizer()
    // Do not set `languageHints`: a non-zero hint replaces the hypothesis at
    // confidence 1, so a foreign body would never produce a Translation offer.
    recognizer.processString(trimmed)

    let hypotheses = recognizer.languageHypotheses(withMaximum: 1)
    guard let (language, confidence) = hypotheses.max(by: { $0.value < $1.value }) else {
      return .undetermined
    }
    guard confidence >= minimumConfidence else { return .undetermined }
    guard language != .undetermined else { return .undetermined }
    return .identified(languageCode: language.rawValue)
  }

  public static func isSameLanguage(_ lhs: String, _ rhs: String) -> Bool {
    collapsedLanguageCode(lhs) == collapsedLanguageCode(rhs)
  }

  public static func collapsedLanguageCode(_ identifier: String) -> String {
    Locale.Language(identifier: identifier).languageCode?.identifier ?? identifier
  }
}
