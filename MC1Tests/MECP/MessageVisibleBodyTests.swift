@testable import MC1
@testable import MC1Services
import Testing

@Suite("Message visible body")
struct MessageVisibleBodyTests {
  @Test
  func `a reading replaces the stored text and drops translation chrome`() throws {
    let stored = "MECP/0/M01"
    let offer = MessageTranslationChrome(phase: .offer, sourceLanguageCode: "de")
    let visible = MessageVisibleBody(stored: stored, translation: offer)
    let reading = try #require(MECPCatalog.reading(stored))

    #expect(visible.plainText == reading)
    #expect(visible.translation == nil)
    #expect(visible.spokenText == L10n.Chats.Chats.Message.readingAccessibility(
      reading.replacingOccurrences(of: "\n", with: " ")
    ))
    #expect(MessageVisibleBody.detailLine(storedText: stored) == L10n.Chats.Chats.Message.Info.mecp(stored))
  }

  @Test
  func `ordinary text keeps a showing translation and has no detail line`() {
    let showing = MessageTranslationChrome.Phase.showing(
      translatedText: "Hello",
      targetLanguageCode: "en"
    )
    let chrome = MessageTranslationChrome(phase: showing, sourceLanguageCode: "de")
    let visible = MessageVisibleBody(stored: "Guten Morgen", translation: chrome)

    #expect(visible.plainText == "Hello")
    #expect(visible.translation == chrome)
    #expect(visible.spokenText == L10n.Chats.Chats.Message.translatedAccessibility("Hello"))
    #expect(MessageVisibleBody.detailLine(storedText: "Guten Morgen") == nil)
  }
}
