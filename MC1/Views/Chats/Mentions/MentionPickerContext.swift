import Foundation
import MC1Services

/// Context passed to the mention picker `.sheet(item:)` driver on
/// `ChatConversationView`. Built by `resolveMentionTap(name:)` when the tap
/// requires user disambiguation or a "not a saved contact" / "that's you"
/// surface. Single-match resolutions never construct one — they navigate
/// directly.
struct MentionPickerContext: Identifiable {
  let id = UUID()
  let name: String
  let radioID: UUID
  let matches: [ContactDTO]
  let isSelfMention: Bool
}
