import Foundation
import NaturalLanguage
import Synchronization

/// Synchronous language detection. One shared `NLLanguageRecognizer` guarded
/// by a mutex, since a recognizer is not safe for concurrent use and creating
/// one per call reloads its model, which is expensive.
public enum MessageLanguageDetector: Sendable {
  public static let minimumLetterCount = 12
  public static let minimumConfidence = 0.80
  public static let minimumShortLetterCount = 8
  public static let minimumShortConfidence = 0.50

  private static let recognizer = Mutex(NLLanguageRecognizer())

  /// Dominant language of the mention-stripped body, or `.undetermined`.
  public static func dominantLanguage(for text: String) -> DetectedLanguage {
    let sample = detectionSample(from: text)
    guard !sample.isEmpty else { return .undetermined }

    let letterCount = sample.reduce(into: 0) { count, character in
      if character.isLetter { count += 1 }
    }
    let eligible = letterCount >= minimumLetterCount
      || (letterCount >= minimumShortLetterCount && sample.contains(where: \.isWhitespace))
    guard eligible else { return .undetermined }

    // Do not set `languageHints`: a non-zero hint replaces the hypothesis at
    // confidence 1, so a foreign body would never produce a Translation offer.
    let hypotheses = recognizer.withLock { recognizer in
      recognizer.reset()
      recognizer.processString(sample)
      return recognizer.languageHypotheses(withMaximum: 1)
    }
    guard let (language, confidence) = hypotheses.max(by: { $0.value < $1.value }) else {
      return .undetermined
    }
    guard language != .undetermined else { return .undetermined }
    if confidence >= minimumConfidence {
      return .identified(languageCode: language.rawValue)
    }
    // Short remainders sit under minimumConfidence; require a majority so a
    // weak NL guess is not the Translation session source.
    if letterCount < minimumLetterCount, confidence >= minimumShortConfidence {
      return .identified(languageCode: language.rawValue)
    }
    return .undetermined
  }

  public static func isSameLanguage(_ lhs: String, _ rhs: String) -> Bool {
    collapsedLanguageCode(lhs) == collapsedLanguageCode(rhs)
  }

  public static func collapsedLanguageCode(_ identifier: String) -> String {
    Locale.Language(identifier: identifier).languageCode?.identifier ?? identifier
  }

  /// `@[name]` tokens inflate letter count and lower NL confidence; floor and recognizer use the remainder.
  private static func detectionSample(from text: String) -> String {
    let replaced: String
    if let regex = MentionUtilities.mentionRegex {
      let range = NSRange(text.startIndex..., in: text)
      replaced = regex.stringByReplacingMatches(in: text, range: range, withTemplate: " ")
    } else {
      replaced = text
    }
    return replaced.trimmingCharacters(in: .whitespacesAndNewlines)
  }
}
