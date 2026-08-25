import Foundation

/// Pending Translation session request owned by the conversation view model.
/// Contains no Translation types so the view model stays Translation-free.
struct TranslationSessionRequest: Equatable, Hashable, Sendable {
  let messageID: UUID
  let sourceLanguageCode: String
  let targetLanguageCode: String
  let generation: UInt64
}
