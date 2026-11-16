@testable import MC1Services
import Testing

@Suite("MessageTranslationChrome.resolved")
struct MessageTranslationChromeTests {
  @Test
  func `a reading is none even when a translation is in flight`() {
    #expect(
      resolve(
        detected: .identified(languageCode: "de"),
        phase: .inProgress,
        preferredLanguageCode: "en",
        text: "MECP/0/M01"
      ) == nil
    )
  }

  @Test
  func `detected German against English is an offer`() {
    let chrome = resolve(
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
      resolve(
        detected: .identified(languageCode: "de"),
        phase: nil,
        preferredLanguageCode: "de"
      ) == nil
    )
    #expect(
      resolve(
        detected: .identified(languageCode: "de"),
        phase: nil,
        preferredLanguageCode: "de-DE"
      ) == nil
    )
    #expect(
      resolve(
        detected: .identified(languageCode: "zh"),
        phase: nil,
        preferredLanguageCode: "zh-Hans"
      ) == nil
    )
  }

  @Test
  func `present nil detection is none`() {
    #expect(
      resolve(
        detected: nil,
        phase: nil,
        preferredLanguageCode: "en"
      ) == nil
    )
    #expect(
      resolve(
        detected: nil,
        phase: .offer,
        preferredLanguageCode: "en"
      ) == nil
    )
    #expect(
      resolve(
        detected: .undetermined,
        phase: .offer,
        preferredLanguageCode: "en"
      ) == nil
    )
  }

  @Test
  func `inProgress is kept`() {
    let chrome = resolve(
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
    let chrome = resolve(
      detected: .identified(languageCode: "de"),
      phase: showing,
      preferredLanguageCode: "en"
    )
    #expect(chrome?.phase == showing)
  }

  @Test
  func `showing for a different target falls back to offer`() {
    let chrome = resolve(
      detected: .identified(languageCode: "de"),
      phase: .showing(translatedText: "Hello", targetLanguageCode: "en"),
      preferredLanguageCode: "fr"
    )
    #expect(chrome?.phase == .offer)
  }

  @Test
  func `showing for a different target that matches the source is none`() {
    #expect(
      resolve(
        detected: .identified(languageCode: "de"),
        phase: .showing(translatedText: "Hello", targetLanguageCode: "en"),
        preferredLanguageCode: "de"
      ) == nil
    )
  }

  private func resolve(
    detected: DetectedLanguage?,
    phase: MessageTranslationChrome.Phase?,
    preferredLanguageCode: String,
    text: String = "Hello"
  ) -> MessageTranslationChrome? {
    MessageTranslationChrome.resolved(
      detected: detected,
      phase: phase,
      preferredLanguageCode: preferredLanguageCode,
      text: text
    )
  }
}
