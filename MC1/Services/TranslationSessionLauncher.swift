import Foundation
@preconcurrency import Translation

/// Installed-only `translate()` first. `.translationTask` can present a download
/// UI even when the pack is on disk, so fall back only on `notInstalled`.
enum TranslationSessionLauncher {
  /// Runs `perform` on an installed-only session, or returns a `.translationTask` configuration.
  @MainActor
  static func launch(
    request: TranslationSessionRequest,
    replacing configuration: TranslationSession.Configuration?,
    perform: (any MessageTranslating) async -> TranslationPerformResult
  ) async -> TranslationSession.Configuration? {
    let supported = await Self.translatePackLanguages()
    guard !Task.isCancelled else { return nil }
    let source = TranslationLanguageResolver.resolve(
      request.sourceLanguageCode,
      from: supported
    )
    let target = TranslationLanguageResolver.resolve(
      request.targetLanguageCode,
      from: supported
    )

    if #available(iOS 26.0, *) {
      for session in installedSessions(source: source, target: target) {
        let result = await perform(InstalledPackTranslator(session: session))
        guard !Task.isCancelled else { return nil }
        switch result {
        case .finished:
          return nil
        case .needsDownload:
          continue
        }
      }
    }

    return TranslationSession.Configuration.replacing(
      configuration,
      source: source,
      target: target
    )
  }

  /// Translate language packs, not the Apple Intelligence model list.
  private nonisolated static func translatePackLanguages() async -> [Locale.Language] {
    if #available(iOS 26.4, *) {
      return await LanguageAvailability(preferredStrategy: .lowLatency).supportedLanguages
    }
    return await LanguageAvailability().supportedLanguages
  }

  @available(iOS 26.0, *)
  private static func installedSessions(
    source: Locale.Language,
    target: Locale.Language
  ) -> [TranslationSession] {
    if #available(iOS 26.4, *) {
      return [
        TranslationSession(
          installedSource: source,
          target: target,
          preferredStrategy: .highFidelity
        ),
        TranslationSession(
          installedSource: source,
          target: target,
          preferredStrategy: .lowLatency
        )
      ]
    }
    return [TranslationSession(installedSource: source, target: target)]
  }

  /// `canRequestDownloads` is false, so `translate` throws `notInstalled`
  /// instead of presenting the system download UI.
  @MainActor
  private struct InstalledPackTranslator: MessageTranslating {
    let session: TranslationSession

    func translate(_ text: String) async throws -> String {
      do {
        let response = try await session.translate(text)
        return response.targetText
      } catch {
        throw MessageTranslationNeedsDownloadError.wrapping(error)
      }
    }
  }
}
