@testable import MC1Services
import Testing

@Suite("MessageTranslationChrome.resolved")
struct MessageTranslationChromeTests {
  @Test
  func `detected German against English is an offer`() {
    let chrome = MessageTranslationChrome.resolved(
      detected: .identified(languageCode: "de"),
      phase: nil,
      preferredLanguageCode: "en"
    )
    #expect(chrome?.phase == .offer)
    #expect(chrome?.sourceLanguageCode == "de")
  }

  @Test
  func `same language as preferred is none`() {
    #expect(
      MessageTranslationChrome.resolved(
        detected: .identified(languageCode: "de"),
        phase: nil,
        preferredLanguageCode: "de"
      ) == nil
    )
    #expect(
      MessageTranslationChrome.resolved(
        detected: .identified(languageCode: "de"),
        phase: nil,
        preferredLanguageCode: "de-DE"
      ) == nil
    )
    #expect(
      MessageTranslationChrome.resolved(
        detected: .identified(languageCode: "zh"),
        phase: nil,
        preferredLanguageCode: "zh-Hans"
      ) == nil
    )
  }

  @Test
  func `present nil detection is none`() {
    #expect(
      MessageTranslationChrome.resolved(
        detected: nil,
        phase: nil,
        preferredLanguageCode: "en"
      ) == nil
    )
    #expect(
      MessageTranslationChrome.resolved(
        detected: nil,
        phase: .offer,
        preferredLanguageCode: "en"
      ) == nil
    )
    #expect(
      MessageTranslationChrome.resolved(
        detected: .undetermined,
        phase: .offer,
        preferredLanguageCode: "en"
      ) == nil
    )
  }

  @Test
  func `inProgress is kept`() {
    let chrome = MessageTranslationChrome.resolved(
      detected: .identified(languageCode: "de"),
      phase: .inProgress,
      preferredLanguageCode: "en"
    )
    #expect(chrome?.phase == .inProgress)
  }

  @Test
  func `showing is kept when the target still matches preferred`() {
    let showing = MessageTranslationChrome.Phase.showing(
      translatedText: "Hello",
      targetLanguageCode: "en"
    )
    let chrome = MessageTranslationChrome.resolved(
      detected: .identified(languageCode: "de"),
      phase: showing,
      preferredLanguageCode: "en"
    )
    #expect(chrome?.phase == showing)
  }

  @Test
  func `showing for a different target falls back to offer`() {
    let chrome = MessageTranslationChrome.resolved(
      detected: .identified(languageCode: "de"),
      phase: .showing(translatedText: "Hello", targetLanguageCode: "en"),
      preferredLanguageCode: "fr"
    )
    #expect(chrome?.phase == .offer)
  }

  @Test
  func `showing for a different target that matches the source is none`() {
    #expect(
      MessageTranslationChrome.resolved(
        detected: .identified(languageCode: "de"),
        phase: .showing(translatedText: "Hello", targetLanguageCode: "en"),
        preferredLanguageCode: "de"
      ) == nil
    )
  }
}
