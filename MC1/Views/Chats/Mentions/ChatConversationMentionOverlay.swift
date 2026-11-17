import MC1Services
import SwiftUI

/// Floating mention suggestions overlay for the chat input.
struct ChatConversationMentionOverlay: View {
  /// Space under the card. The timeline safe area ends at the composer, so this is only the gap.
  private static let gapAboveComposer: CGFloat = 0

  let suggestions: [ContactDTO]
  let onSelectMention: (ContactDTO) -> Void

  var body: some View {
    if !suggestions.isEmpty {
      VStack(spacing: 0) {
        Spacer()
        MentionSuggestionView(contacts: suggestions) { contact in
          onSelectMention(contact)
        }
        .padding(.horizontal)
        .padding(.bottom, Self.gapAboveComposer)
        .transition(
          .asymmetric(
            insertion: .move(edge: .bottom)
              .combined(with: .opacity)
              .combined(with: .scale(scale: 0.95, anchor: .bottom)),
            removal: .move(edge: .bottom).combined(with: .opacity)
          )
        )
      }
      .animation(.spring(response: 0.3, dampingFraction: 0.8), value: suggestions.isEmpty)
    }
  }
}
