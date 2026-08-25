import Foundation
import MC1Services

extension RoomConversationViewModel {
  func applyPreferredLanguageCode(_ code: String) {
    guard !MessageLanguageDetector.isSameLanguage(preferredLanguageCode, code) else { return }
    invalidateTranslations(preferredLanguageCode: code)
    preferredLanguageCode = code
    refreshTiledRows()
  }

  func performTranslationAction(for messageID: UUID) {
    if case .showing = translationPhases[messageID] {
      translationPhases[messageID] = .offer
      refreshTiledRows()
      return
    }
    let targetLanguageCode = MessageLanguageDetector.collapsedLanguageCode(preferredLanguageCode)
    if let cached = translationCache[messageID]?[targetLanguageCode] {
      resetInProgressTranslations()
      translationSessionRequest = nil
      translationPhases[messageID] = .showing(
        translatedText: cached,
        targetLanguageCode: targetLanguageCode
      )
      refreshTiledRows()
      return
    }
    if case .inProgress = translationPhases[messageID] {
      return
    }
    resetInProgressTranslations()
    guard let sourceLanguageCode = detectedLanguages[messageID]?.code else {
      return
    }
    translationPhases[messageID] = .inProgress
    refreshTiledRows()
    translationGeneration += 1
    translationSessionRequest = TranslationSessionRequest(
      messageID: messageID,
      sourceLanguageCode: sourceLanguageCode,
      targetLanguageCode: targetLanguageCode,
      generation: translationGeneration
    )
  }

  @discardableResult
  func performPendingTranslation(
    using translator: any MessageTranslating,
    for request: TranslationSessionRequest
  ) async -> TranslationPerformResult {
    guard isCurrent(request) else { return .finished }
    let capturedID = request.messageID
    let targetLanguageCode = MessageLanguageDetector.collapsedLanguageCode(
      request.targetLanguageCode
    )
    guard let text = messages.first(where: { $0.id == capturedID })?.text else {
      guard isCurrent(request) else { return .finished }
      translationPhases[capturedID] = .offer
      translationSessionRequest = nil
      refreshTiledRows()
      return .finished
    }

    do {
      let result = try await translator.translate(text)
      guard isCurrent(request) else { return .finished }
      var perTarget = translationCache[capturedID] ?? [:]
      perTarget[targetLanguageCode] = result
      translationCache[capturedID] = perTarget
      translationPhases[capturedID] = .showing(
        translatedText: result,
        targetLanguageCode: targetLanguageCode
      )
      translationSessionRequest = nil
      refreshTiledRows()
      return .finished
    } catch is MessageTranslationNeedsDownloadError {
      guard isCurrent(request) else { return .finished }
      return .needsDownload
    } catch {
      guard isCurrent(request) else { return .finished }
      translationPhases[capturedID] = .offer
      if !isQuietTranslationCancel(error) {
        errorMessage = error.userFacingMessage
      }
      translationSessionRequest = nil
      refreshTiledRows()
      return .finished
    }
  }

  func cancelPendingTranslation() {
    translationGeneration += 1
    translationSessionRequest = nil
    let idsToReset = translationPhases.compactMap { id, phase -> UUID? in
      if case .inProgress = phase { id } else { nil }
    }
    for id in idsToReset {
      translationPhases[id] = .offer
    }
    refreshTiledRows()
  }

  func invalidateTranslations(preferredLanguageCode: String) {
    translationGeneration += 1
    translationSessionRequest = nil
    let preferred = MessageLanguageDetector.collapsedLanguageCode(preferredLanguageCode)
    for (id, phase) in translationPhases {
      switch phase {
      case .inProgress:
        translationPhases[id] = .offer
      case let .showing(_, target) where !MessageLanguageDetector.isSameLanguage(target, preferred):
        translationPhases[id] = .offer
      case .offer, .showing:
        break
      }
    }
  }

  func displayedText(for message: RoomMessageDTO) -> String {
    if case let .showing(translatedText, _) = translation(for: message.id)?.phase {
      return translatedText
    }
    return message.text
  }

  func translation(for messageID: UUID) -> MessageTranslationChrome? {
    tiledRows.first { $0.id == messageID }?.translation
  }

  func refreshTiledRows() {
    seedDetectedLanguages()
    var translations: [UUID: MessageTranslationChrome] = [:]
    for message in messages {
      if let chrome = MessageTranslationChrome.resolved(
        detected: detectedLanguages[message.id],
        phase: translationPhases[message.id],
        preferredLanguageCode: preferredLanguageCode
      ) {
        translations[message.id] = chrome
      }
    }
    tiledRows = Self.tiledRows(in: messages, translations: translations)
  }

  private func seedDetectedLanguages() {
    for message in messages {
      guard detectedLanguages[message.id] == nil else { continue }
      detectedLanguages[message.id] = MessageLanguageDetector.dominantLanguage(for: message.text)
    }
  }

  private func resetInProgressTranslations() {
    let inProgressIDs = translationPhases.compactMap { id, phase -> UUID? in
      if case .inProgress = phase { id } else { nil }
    }
    guard !inProgressIDs.isEmpty else { return }
    translationGeneration += 1
    for id in inProgressIDs {
      translationPhases[id] = .offer
    }
    refreshTiledRows()
  }

  private func isCurrent(_ request: TranslationSessionRequest) -> Bool {
    translationSessionRequest?.generation == request.generation
  }

  /// `CancellationError`, or `CocoaError.userCancelled` bridged as NSError.
  private func isQuietTranslationCancel(_ error: Error) -> Bool {
    if error is CancellationError { return true }
    let nsError = error as NSError
    return nsError.domain == NSCocoaErrorDomain && nsError.code == NSUserCancelledError
  }
}
