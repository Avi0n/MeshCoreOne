import Foundation
import MC1Services

extension ChatViewModel {
  /// In-bubble Translation action. Show original and cache hits skip a session.
  /// One in-flight translation per conversation.
  func performTranslationAction(for messageID: UUID) {
    if case .showing = bake.translationPhases[messageID] {
      bake.translationPhases[messageID] = .offer
      timeline.rebakeRow(messageID)
      return
    }
    let targetLanguageCode = MessageLanguageDetector.collapsedLanguageCode(
      envInputs.preferredLanguageCode
    )
    if let cached = bake.translationCache[messageID]?[targetLanguageCode] {
      resetInProgressTranslations()
      translationSessionRequest = nil
      bake.translationPhases[messageID] = .showing(
        translatedText: cached,
        targetLanguageCode: targetLanguageCode
      )
      timeline.rebakeRow(messageID)
      return
    }
    if case .inProgress = bake.translationPhases[messageID] {
      return
    }
    resetInProgressTranslations()
    guard let sourceLanguageCode = bake.detectedLanguages[messageID]?.code else {
      return
    }
    bake.translationPhases[messageID] = .inProgress
    timeline.rebakeRow(messageID)
    translationGeneration += 1
    translationSessionRequest = TranslationSessionRequest(
      messageID: messageID,
      sourceLanguageCode: sourceLanguageCode,
      targetLanguageCode: targetLanguageCode,
      generation: translationGeneration
    )
  }

  /// Apply `translator` only while `request.generation` is still current.
  /// Never writes `MessageDTO.text`.
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
    guard let text = messagesByID[capturedID]?.text else {
      guard isCurrent(request) else { return .finished }
      bake.translationPhases[capturedID] = .offer
      translationSessionRequest = nil
      timeline.rebakeRow(capturedID)
      return .finished
    }

    do {
      let result = try await translator.translate(text)
      guard isCurrent(request) else { return .finished }
      var perTarget = bake.translationCache[capturedID] ?? [:]
      perTarget[targetLanguageCode] = result
      bake.translationCache[capturedID] = perTarget
      bake.translationPhases[capturedID] = .showing(
        translatedText: result,
        targetLanguageCode: targetLanguageCode
      )
      translationSessionRequest = nil
      timeline.rebakeRow(capturedID)
      return .finished
    } catch is MessageTranslationNeedsDownloadError {
      guard isCurrent(request) else { return .finished }
      return .needsDownload
    } catch {
      guard isCurrent(request) else { return .finished }
      bake.translationPhases[capturedID] = .offer
      if !isQuietTranslationCancel(error) {
        errorMessage = error.userFacingMessage
      }
      translationSessionRequest = nil
      timeline.rebakeRow(capturedID)
      return .finished
    }
  }

  /// Returns `.inProgress` rows to the Translation offer and rebakes them.
  /// Leaves `.showing` and the translation cache for a surviving view model.
  func cancelPendingTranslation() {
    translationGeneration += 1
    translationSessionRequest = nil
    let idsToReset = bake.translationPhases.compactMap { id, phase -> UUID? in
      if case .inProgress = phase { id } else { nil }
    }
    for id in idsToReset {
      bake.translationPhases[id] = .offer
      timeline.rebakeRow(id)
    }
  }

  /// Drops in-flight work and showing chrome whose target is not `preferredLanguageCode`.
  func invalidateTranslations(preferredLanguageCode: String) {
    translationGeneration += 1
    translationSessionRequest = nil
    let preferred = MessageLanguageDetector.collapsedLanguageCode(preferredLanguageCode)
    for (id, phase) in bake.translationPhases {
      switch phase {
      case .inProgress:
        bake.translationPhases[id] = .offer
      case let .showing(_, target) where !MessageLanguageDetector.isSameLanguage(target, preferred):
        bake.translationPhases[id] = .offer
      case .offer, .showing:
        break
      }
    }
  }

  /// Clipboard-only. Reads the same chrome the bubble draws. Reply, Send Again,
  /// reaction hash, and preview stay on stored `message.text`.
  func displayedText(for message: MessageDTO) -> String {
    if case let .showing(translatedText, _) = translation(for: message.id)?.phase {
      return translatedText
    }
    return message.text
  }

  func translation(for messageID: UUID) -> MessageTranslationChrome? {
    items.first { $0.id == messageID }?.translation
  }

  /// Resets every in-flight row back to the Translation offer and bumps
  /// generation so an in-flight result is discarded.
  private func resetInProgressTranslations() {
    let inProgressIDs = bake.translationPhases.compactMap { id, phase -> UUID? in
      if case .inProgress = phase { id } else { nil }
    }
    guard !inProgressIDs.isEmpty else { return }
    translationGeneration += 1
    for id in inProgressIDs {
      bake.translationPhases[id] = .offer
      timeline.rebakeRow(id)
    }
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
