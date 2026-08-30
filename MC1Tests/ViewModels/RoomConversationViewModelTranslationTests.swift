import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("RoomConversationViewModel translation")
@MainActor
struct RoomConversationViewModelTranslationTests {
  private let german = "Guten Morgen, wie geht es dir heute?"
  private let sessionID = UUID()
  private let showingEnglish = MessageTranslationChrome.Phase.showing(
    translatedText: "Hello",
    targetLanguageCode: "en"
  )

  @Test
  func `translation-only change makes RoomTiledRow unequal`() {
    let message = roomMessage(text: german)
    let without = RoomTiledRow(
      message: message,
      showTimestamp: true,
      showSenderName: true,
      showAvatar: true,
      translation: nil
    )
    let withOffer = RoomTiledRow(
      message: message,
      showTimestamp: true,
      showSenderName: true,
      showAvatar: true,
      translation: MessageTranslationChrome(phase: .offer, sourceLanguageCode: "de")
    )
    #expect(without != withOffer)
  }

  @Test
  func `from-self foreign-language message has no translation chrome`() throws {
    let viewModel = RoomConversationViewModel()
    viewModel.preferredLanguageCode = "en"
    let message = roomMessage(text: german, isFromSelf: true)
    viewModel.appendMessageIfNew(message)

    let row = try #require(viewModel.tiledRows.first)
    #expect(row.translation == nil)
    #expect(viewModel.detectedLanguages[message.id] == nil)
  }

  @Test
  func `detector maps land chrome on the materialized row`() throws {
    let viewModel = RoomConversationViewModel()
    viewModel.preferredLanguageCode = "en"
    viewModel.appendMessageIfNew(roomMessage(text: german))

    let row = try #require(viewModel.tiledRows.first)
    #expect(row.translation?.phase == .offer)
    #expect(row.translation?.sourceLanguageCode == "de")
  }

  @Test
  func `toggle and fake translator show then restore without writing stored text`() async throws {
    let viewModel = seededGermanRoom()
    let message = try #require(viewModel.messages.first)
    let translator = CountingMessageTranslator()

    viewModel.performTranslationAction(for: message.id)
    #expect(viewModel.tiledRows.first?.translation?.phase == .inProgress)
    try await performCurrent(viewModel, using: translator)

    #expect(viewModel.tiledRows.first?.translation?.phase == showingEnglish)
    #expect(viewModel.displayedText(for: message) == "Hello")
    #expect(viewModel.messages.first?.text == german)
    #expect(viewModel.errorMessage == nil)

    viewModel.performTranslationAction(for: message.id)
    #expect(viewModel.tiledRows.first?.translation?.phase == .offer)
    #expect(viewModel.messages.first?.text == german)

    viewModel.performTranslationAction(for: message.id)
    #expect(viewModel.tiledRows.first?.translation?.phase == showingEnglish)
    #expect(translator.translateCount == 1)
  }

  @Test
  func `needs download leaves the in-flight request for the system sheet`() async throws {
    let viewModel = seededGermanRoom()
    let message = try #require(viewModel.messages.first)
    let translator = CountingMessageTranslator()
    translator.result = .failure(MessageTranslationNeedsDownloadError())

    viewModel.performTranslationAction(for: message.id)
    let result = try await performCurrent(viewModel, using: translator)

    #expect(result == .needsDownload)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.translationSessionRequest?.messageID == message.id)
    #expect(viewModel.tiledRows.first?.translation?.phase == .inProgress)
  }

  @Test
  func `translator throw presents system overlay and returns to offer`() async throws {
    let viewModel = seededGermanRoom()
    let message = try #require(viewModel.messages.first)
    let translator = CountingMessageTranslator()
    translator.result = .failure(RoomTranslationTestError.failed)

    viewModel.performTranslationAction(for: message.id)
    let result = try await performCurrent(viewModel, using: translator)

    #expect(result == .presentSystemOverlay(text: german))
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.tiledRows.first?.translation?.phase == .offer)
    #expect(viewModel.translationSessionRequest == nil)
    #expect(viewModel.messages.first?.text == german)
  }

  @Test
  func `cancellation leaves offer without errorMessage`() async throws {
    let viewModel = seededGermanRoom()
    let message = try #require(viewModel.messages.first)
    let translator = CountingMessageTranslator()
    translator.result = .failure(CancellationError())

    viewModel.performTranslationAction(for: message.id)
    let result = try await performCurrent(viewModel, using: translator)

    #expect(result == .finished)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.tiledRows.first?.translation?.phase == .offer)
  }

  @Test
  func `download UI cancel leaves offer without errorMessage`() async throws {
    let viewModel = seededGermanRoom()
    let message = try #require(viewModel.messages.first)
    let translator = CountingMessageTranslator()
    translator.result = .failure(CocoaError(.userCancelled))

    viewModel.performTranslationAction(for: message.id)
    let result = try await performCurrent(viewModel, using: translator)

    #expect(result == .finished)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.tiledRows.first?.translation?.phase == .offer)
    #expect(viewModel.translationSessionRequest == nil)
    #expect(viewModel.messages.first?.text == german)
  }

  @discardableResult
  private func performCurrent(
    _ viewModel: RoomConversationViewModel,
    using translator: any MessageTranslating
  ) async throws -> TranslationPerformResult {
    let request = try #require(viewModel.translationSessionRequest)
    return await viewModel.performPendingTranslation(using: translator, for: request)
  }

  private func seededGermanRoom() -> RoomConversationViewModel {
    let viewModel = RoomConversationViewModel()
    viewModel.preferredLanguageCode = "en"
    viewModel.appendMessageIfNew(roomMessage(text: german))
    return viewModel
  }

  private func roomMessage(
    text: String,
    timestamp: UInt32 = 100,
    isFromSelf: Bool = false
  ) -> RoomMessageDTO {
    RoomMessageDTO(
      sessionID: sessionID,
      authorKeyPrefix: Data([0xAA]),
      authorName: "Alice",
      text: text,
      timestamp: timestamp,
      isFromSelf: isFromSelf
    )
  }
}

private enum RoomTranslationTestError: Error {
  case failed
}

@MainActor
private final class CountingMessageTranslator: MessageTranslating {
  var translateCount = 0
  var result: Result<String, Error> = .success("Hello")

  func translate(_: String) async throws -> String {
    translateCount += 1
    return try result.get()
  }
}
