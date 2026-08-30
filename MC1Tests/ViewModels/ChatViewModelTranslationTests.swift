import Foundation
@testable import MC1
@testable import MC1Services
import Testing

@Suite("ChatViewModel translation")
@MainActor
struct ChatViewModelTranslationTests {
  private let german = "Guten Morgen, wie geht es dir heute?"
  private let showingEnglish = MessageTranslationChrome.Phase.showing(
    translatedText: "Hello",
    targetLanguageCode: "en"
  )

  @Test
  func `toggle offer goes inProgress then showing and leaves stored text unchanged`() async throws {
    let (viewModel, coordinator, message, translator) = try await seededGermanChat()
    let original = message.text

    viewModel.performTranslationAction(for: message.id)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: message.id)?.phase == .inProgress)
    #expect(viewModel.translationSessionRequest?.messageID == message.id)

    try await performCurrent(viewModel, using: translator)
    await coordinator.buildItemsTask?.value

    #expect(viewModel.translation(for: message.id)?.phase == showingEnglish)
    #expect(viewModel.displayedText(for: message) == "Hello")
    #expect(viewModel.messagesByID[message.id]?.text == original)
    #expect(translator.translateCount == 1)
    let reply = MentionUtilities.buildReplyText(mentionName: "Alice", messageText: original)
    #expect(reply.contains("Guten Morg"))
    #expect(!reply.contains("Hello"))
  }

  @Test
  func `toggle showing returns to offer and second translate hits cache`() async throws {
    let (viewModel, coordinator, message, translator) = try await seededGermanChat()
    viewModel.performTranslationAction(for: message.id)
    try await performCurrent(viewModel, using: translator)
    await coordinator.buildItemsTask?.value

    viewModel.performTranslationAction(for: message.id)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: message.id)?.phase == .offer)
    #expect(viewModel.displayedText(for: message) == german)

    viewModel.performTranslationAction(for: message.id)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: message.id)?.phase == showingEnglish)
    #expect(translator.translateCount == 1)
    #expect(viewModel.translationSessionRequest == nil)
  }

  @Test
  func `translator throw presents system overlay and returns to offer`() async throws {
    let (viewModel, coordinator, message, translator) = try await seededGermanChat()
    translator.result = .failure(TranslationTestError.failed)

    viewModel.performTranslationAction(for: message.id)
    let result = try await performCurrent(viewModel, using: translator)
    await coordinator.buildItemsTask?.value

    #expect(result == .presentSystemOverlay(text: german))
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.translation(for: message.id)?.phase == .offer)
    #expect(viewModel.translationSessionRequest == nil)
    #expect(viewModel.messagesByID[message.id]?.text == german)
  }

  @Test
  func `needs download leaves the in-flight request for the system sheet`() async throws {
    let (viewModel, coordinator, message, translator) = try await seededGermanChat()
    translator.result = .failure(MessageTranslationNeedsDownloadError())

    viewModel.performTranslationAction(for: message.id)
    let result = try await performCurrent(viewModel, using: translator)
    await coordinator.buildItemsTask?.value

    #expect(result == .needsDownload)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.translationSessionRequest?.messageID == message.id)
    #expect(viewModel.translation(for: message.id)?.phase == .inProgress)
  }

  @Test
  func `cancellation leaves offer without errorMessage`() async throws {
    let (viewModel, coordinator, message, translator) = try await seededGermanChat()
    translator.result = .failure(CancellationError())

    viewModel.performTranslationAction(for: message.id)
    let result = try await performCurrent(viewModel, using: translator)
    await coordinator.buildItemsTask?.value

    #expect(result == .finished)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.translation(for: message.id)?.phase == .offer)
  }

  @Test
  func `download UI cancel leaves offer without errorMessage`() async throws {
    let (viewModel, coordinator, message, translator) = try await seededGermanChat()
    translator.result = .failure(CocoaError(.userCancelled))

    viewModel.performTranslationAction(for: message.id)
    let result = try await performCurrent(viewModel, using: translator)
    await coordinator.buildItemsTask?.value

    #expect(result == .finished)
    #expect(viewModel.errorMessage == nil)
    #expect(viewModel.translation(for: message.id)?.phase == .offer)
    #expect(viewModel.translationSessionRequest == nil)
    #expect(viewModel.messagesByID[message.id]?.text == german)
  }

  @Test
  func `second tap while inProgress resets the first and applies the second`() async throws {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.bindCoordinatorForTesting(coordinator)

    let first = germanMessage(timestamp: 1000)
    let second = germanMessage(timestamp: 1001)
    viewModel.appendMessageIfNew(first)
    viewModel.appendMessageIfNew(second)
    await coordinator.buildItemsTask?.value

    let gated = GatedMessageTranslator()
    viewModel.performTranslationAction(for: first.id)
    #expect(viewModel.bake.translationPhases[first.id] == .inProgress)
    let firstRequest = try #require(viewModel.translationSessionRequest)

    async let pending = viewModel.performPendingTranslation(using: gated, for: firstRequest)
    await gated.waitUntilEntered()

    viewModel.performTranslationAction(for: second.id)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: first.id)?.phase == .offer)
    #expect(viewModel.translation(for: second.id)?.phase == .inProgress)
    #expect(viewModel.translationSessionRequest?.messageID == second.id)

    gated.resume(returning: "Stale hello")
    #expect(await pending == .finished)
    await coordinator.buildItemsTask?.value

    #expect(viewModel.bake.translationCache[first.id] == nil)
    #expect(viewModel.bake.translationCache[second.id] == nil)
    #expect(viewModel.translation(for: first.id)?.phase == .offer)
    #expect(viewModel.translation(for: second.id)?.phase == .inProgress)

    let counting = CountingMessageTranslator()
    try await performCurrent(viewModel, using: counting)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: second.id)?.phase == showingEnglish)
    #expect(viewModel.bake.translationCache[first.id] == nil)
    #expect(counting.translateCount == 1)
  }

  @Test
  func `cancelPendingTranslation while inProgress returns to offer`() async throws {
    let (viewModel, coordinator, message, _) = try await seededGermanChat()
    viewModel.performTranslationAction(for: message.id)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: message.id)?.phase == .inProgress)

    viewModel.cancelPendingTranslation()
    await coordinator.buildItemsTask?.value

    #expect(viewModel.translation(for: message.id)?.phase == .offer)
    #expect(viewModel.translationSessionRequest == nil)
  }

  @Test
  func `cancelPendingTranslation while showing leaves showing and cache`() async throws {
    let (viewModel, coordinator, message, translator) = try await seededGermanChat()
    viewModel.performTranslationAction(for: message.id)
    try await performCurrent(viewModel, using: translator)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: message.id)?.phase == showingEnglish)
    #expect(viewModel.displayedText(for: message) == "Hello")

    viewModel.cancelPendingTranslation()
    await coordinator.buildItemsTask?.value

    #expect(viewModel.translation(for: message.id)?.phase == showingEnglish)
    #expect(viewModel.displayedText(for: message) == "Hello")
    #expect(viewModel.translationSessionRequest == nil)
    #expect(viewModel.bake.translationCache[message.id]?["en"] == "Hello")
    #expect(translator.translateCount == 1)
  }

  @Test
  func `preferred language change does not reuse a translation for a different target`() async throws {
    let (viewModel, coordinator, message, translator) = try await seededGermanChat()
    viewModel.performTranslationAction(for: message.id)
    try await performCurrent(viewModel, using: translator)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.displayedText(for: message) == "Hello")

    viewModel.applyEnvInputs(envInputs(preferredLanguageCode: "fr"))
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: message.id)?.phase == .offer)
    #expect(viewModel.displayedText(for: message) == german)

    viewModel.performTranslationAction(for: message.id)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: message.id)?.phase == .inProgress)
    #expect(viewModel.translationSessionRequest?.targetLanguageCode == "fr")
    #expect(viewModel.bake.translationCache[message.id]?["en"] == "Hello")

    viewModel.applyEnvInputs(envInputs(preferredLanguageCode: "en"))
    await coordinator.buildItemsTask?.value
    viewModel.performTranslationAction(for: message.id)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: message.id)?.phase == showingEnglish)
    #expect(translator.translateCount == 1)
  }

  @Test
  func `preferred language change while inProgress returns to offer and drops the request`() async throws {
    let (viewModel, coordinator, message, _) = try await seededGermanChat()
    viewModel.performTranslationAction(for: message.id)
    await coordinator.buildItemsTask?.value
    #expect(viewModel.translation(for: message.id)?.phase == .inProgress)
    #expect(viewModel.translationSessionRequest != nil)

    viewModel.applyEnvInputs(envInputs(preferredLanguageCode: "fr"))
    await coordinator.buildItemsTask?.value

    #expect(viewModel.translation(for: message.id)?.phase == .offer)
    #expect(viewModel.translationSessionRequest == nil)
  }

  @Test
  func `outgoing foreign-language message has no translation chrome`() async throws {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.bindCoordinatorForTesting(coordinator)
    let message = germanMessage(timestamp: 1000, direction: .outgoing)
    viewModel.appendMessageIfNew(message)
    await coordinator.buildItemsTask?.value

    let item = try #require(viewModel.items.first { $0.id == message.id })
    #expect(item.translation == nil)
    #expect(viewModel.bake.detectedLanguages[message.id] == nil)
  }

  @Test
  func `stale needs-download does not consume the newer request`() async throws {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.bindCoordinatorForTesting(coordinator)
    let first = germanMessage(timestamp: 1000)
    let second = germanMessage(timestamp: 1001)
    viewModel.appendMessageIfNew(first)
    viewModel.appendMessageIfNew(second)
    await coordinator.buildItemsTask?.value

    let gated = GatedMessageTranslator()
    viewModel.performTranslationAction(for: first.id)
    let requestA = try #require(viewModel.translationSessionRequest)
    async let pending = viewModel.performPendingTranslation(using: gated, for: requestA)
    await gated.waitUntilEntered()

    viewModel.performTranslationAction(for: second.id)
    await coordinator.buildItemsTask?.value
    gated.resume(throwing: MessageTranslationNeedsDownloadError())
    let result = await pending
    await coordinator.buildItemsTask?.value

    #expect(result == .finished)
    #expect(viewModel.translationSessionRequest?.messageID == second.id)
    #expect(viewModel.translation(for: second.id)?.phase == .inProgress)
  }

  @discardableResult
  private func performCurrent(
    _ viewModel: ChatViewModel,
    using translator: any MessageTranslating
  ) async throws -> TranslationPerformResult {
    let request = try #require(viewModel.translationSessionRequest)
    return await viewModel.performPendingTranslation(using: translator, for: request)
  }

  private func seededGermanChat() async throws -> (
    ChatViewModel, ChatCoordinator, MessageDTO, CountingMessageTranslator
  ) {
    let viewModel = ChatViewModel()
    let coordinator = ChatCoordinator.makeForTesting()
    viewModel.bindCoordinatorForTesting(coordinator)
    let message = germanMessage(timestamp: 1000)
    viewModel.appendMessageIfNew(message)
    await coordinator.buildItemsTask?.value
    let chrome = try #require(viewModel.translation(for: message.id))
    #expect(chrome.phase == .offer)
    return (viewModel, coordinator, message, CountingMessageTranslator())
  }

  private func germanMessage(
    timestamp: UInt32,
    direction: MessageDirection = .incoming
  ) -> MessageDTO {
    makeMessage(timestamp: timestamp, text: german, direction: direction)
  }

  private func envInputs(preferredLanguageCode: String) -> EnvInputs {
    let base = EnvInputs.default
    return EnvInputs(
      autoPlayGIFs: base.autoPlayGIFs,
      showIncomingPath: base.showIncomingPath,
      showIncomingHopCount: base.showIncomingHopCount,
      showIncomingRegion: base.showIncomingRegion,
      showIncomingSendTime: base.showIncomingSendTime,
      previewsEnabled: base.previewsEnabled,
      isHighContrast: base.isHighContrast,
      isDark: base.isDark,
      showMapPreviews: base.showMapPreviews,
      isOffline: base.isOffline,
      currentUserName: base.currentUserName,
      themeID: base.themeID,
      contentSizeCategory: base.contentSizeCategory,
      preferredLanguageCode: preferredLanguageCode
    )
  }

  private func makeMessage(
    timestamp: UInt32,
    text: String,
    direction: MessageDirection = .incoming
  ) -> MessageDTO {
    MessageDTO(
      id: UUID(),
      radioID: UUID(),
      contactID: UUID(),
      channelIndex: nil,
      text: text,
      timestamp: timestamp,
      createdAt: Date(timeIntervalSince1970: TimeInterval(timestamp)),
      direction: direction,
      status: direction == .outgoing ? .sent : .delivered,
      textType: .plain,
      ackCode: nil,
      pathLength: 0,
      snr: nil,
      senderKeyPrefix: nil,
      senderNodeName: nil,
      isRead: true,
      replyToID: nil,
      roundTripTime: nil,
      heardRepeats: 0,
      retryAttempt: 0,
      maxRetryAttempts: 0
    )
  }
}

private enum TranslationTestError: Error {
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
