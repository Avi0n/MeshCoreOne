import Foundation

/// Test seam for on-device translation. Production wraps `TranslationSession`
/// inside the conversation `.translationTask` closure; tests inject a fake.
@MainActor
protocol MessageTranslating {
  func translate(_ text: String) async throws -> String
}
