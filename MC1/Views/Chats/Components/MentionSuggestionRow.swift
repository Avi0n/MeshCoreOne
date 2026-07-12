import MC1Services
import SwiftUI

/// A single row in the mention suggestions popup
struct MentionSuggestionRow: View {
  let contact: ContactDTO

  var body: some View {
    HStack(spacing: 12) {
      ContactAvatar(contact: contact, size: 32)

      Text(contact.displayName)
        .lineLimit(1)

      Spacer()
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 8)
    .contentShape(.rect)
    .accessibilityElement(children: .combine)
    .accessibilityLabel(L10n.Chats.Chats.Mention.Accessibility.label(contact.displayName))
    .accessibilityHint(contact.publicKey.isEmpty
      ? L10n.Chats.Chats.Mention.Accessibility.hintChannel
      : L10n.Chats.Chats.Mention.Accessibility.hintContact)
  }
}
