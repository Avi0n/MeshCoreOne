import Foundation
@testable import MC1Services
import Testing

@Suite("MessageLanguageDetector")
struct MessageLanguageDetectorTests {
  private let german = "Guten Morgen, wie geht es dir heute?"
  private let english = "Good morning, how are you today?"

  @Test
  func `German sentence is detected`() {
    #expect(MessageLanguageDetector.dominantLanguage(for: german) == .identified(languageCode: "de"))
  }

  @Test
  func `English sentence is detected and matches preferred en`() {
    #expect(MessageLanguageDetector.dominantLanguage(for: english) == .identified(languageCode: "en"))
    #expect(MessageLanguageDetector.isSameLanguage("en", "en-US"))
  }

  @Test
  func `too few letters is undetermined`() {
    #expect(MessageLanguageDetector.dominantLanguage(for: "ok") == .undetermined)
    #expect(MessageLanguageDetector.dominantLanguage(for: "kk") == .undetermined)
    #expect(MessageLanguageDetector.dominantLanguage(for: "👍👍") == .undetermined)
    #expect(
      MessageLanguageDetector.dominantLanguage(for: "37.7749, -122.4194") == .undetermined
    )
    #expect(MessageLanguageDetector.dominantLanguage(for: "") == .undetermined)
    #expect(MessageLanguageDetector.dominantLanguage(for: "   ") == .undetermined)
    #expect(MessageLanguageDetector.dominantLanguage(for: "@[1wNodeTest] ok") == .undetermined)
  }

  @Test
  func `eleven letters are below the floor`() {
    let elevenLetters = "abcdefghijk"
    #expect(elevenLetters.filter(\.isLetter).count == 11)
    #expect(MessageLanguageDetector.dominantLanguage(for: elevenLetters) == .undetermined)
  }

  @Test
  func `zh-Hans and zh-Hant collapse to the same language`() {
    #expect(MessageLanguageDetector.collapsedLanguageCode("zh-Hans") == "zh")
    #expect(MessageLanguageDetector.collapsedLanguageCode("zh-Hant") == "zh")
    #expect(MessageLanguageDetector.isSameLanguage("zh-Hans", "zh-Hant"))
  }

  @Test
  func `mention-prefixed short English is identified`() {
    #expect(
      MessageLanguageDetector.dominantLanguage(for: "@[1wNodeTest]: no problems!")
        == .identified(languageCode: "en")
    )
  }

  @Test
  func `low-confidence short remainder is undetermined`() {
    #expect(MessageLanguageDetector.dominantLanguage(for: "en route now") == .undetermined)
  }
}
