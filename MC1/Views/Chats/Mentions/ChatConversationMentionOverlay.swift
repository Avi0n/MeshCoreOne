import SwiftUI
import MC1Services

/// Floating mention suggestions overlay for the chat input.
struct ChatConversationMentionOverlay: View {
    let suggestions: [ContactDTO]
    let onSelectMention: (ContactDTO) -> Void

    var body: some View {
        if !suggestions.isEmpty {
            VStack {
                Spacer()
                MentionSuggestionView(contacts: suggestions) { contact in
                    onSelectMention(contact)
                }
                .padding(.horizontal)
                .padding(.bottom, 60)
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
