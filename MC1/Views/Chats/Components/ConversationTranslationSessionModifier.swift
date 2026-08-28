import SwiftUI
@preconcurrency import Translation

/// Shared Chat/Room `.translationTask` wiring. `.task(id:)` cancels launch when
/// the request changes; `.translationTask` performs `sessionBoundRequest`.
struct ConversationTranslationSessionModifier: ViewModifier {
  @Binding var configuration: TranslationSession.Configuration?
  @Binding var request: TranslationSessionRequest?
  let perform: @MainActor (any MessageTranslating, TranslationSessionRequest) async -> TranslationPerformResult

  /// Request that produced the current `configuration`. Assigned in the same
  /// turn as `configuration`.
  @State private var sessionBoundRequest: TranslationSessionRequest?

  func body(content: Content) -> some View {
    content
      .translationTask(configuration) { session in
        guard let sessionBoundRequest,
              request?.generation == sessionBoundRequest.generation else { return }
        // Overlay presentation is a `perform` side effect so `.translationTask`
        // and TranslationSessionLauncher share one closure.
        _ = await perform(
          TranslationSessionMessageTranslator(session: session),
          sessionBoundRequest
        )
      }
      .task(id: request) {
        guard let launchRequest = request else {
          configuration = nil
          sessionBoundRequest = nil
          return
        }
        let result = await TranslationSessionLauncher.launch(
          request: launchRequest,
          replacing: configuration
        ) { translator in
          await perform(translator, launchRequest)
        }
        guard !Task.isCancelled,
              self.request?.generation == launchRequest.generation else { return }
        configuration = result
        sessionBoundRequest = result == nil ? nil : launchRequest
      }
  }
}

extension View {
  func conversationTranslationSession(
    configuration: Binding<TranslationSession.Configuration?>,
    request: Binding<TranslationSessionRequest?>,
    perform: @escaping @MainActor (any MessageTranslating, TranslationSessionRequest) async -> TranslationPerformResult
  ) -> some View {
    modifier(
      ConversationTranslationSessionModifier(
        configuration: configuration,
        request: request,
        perform: perform
      )
    )
  }
}

extension ConversationTranslationSessionModifier {
  @MainActor
  private struct TranslationSessionMessageTranslator: MessageTranslating {
    let session: TranslationSession

    func translate(_ text: String) async throws -> String {
      let response = try await session.translate(text)
      return response.targetText
    }
  }
}
