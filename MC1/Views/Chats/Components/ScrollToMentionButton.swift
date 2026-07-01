import SwiftUI

/// Button to scroll to unread mentions
struct ScrollToMentionButton: View {
  let unreadMentionCount: Int
  let onTap: () -> Void

  var body: some View {
    Button(action: onTap) {
      Image(systemName: "at")
        .font(.body.bold())
        .frame(width: 44, height: 44)
    }
    .buttonStyle(.plain)
    .contentShape(.circle)
    .liquidGlassInteractive(in: .circle)
    .overlay(alignment: .topTrailing) {
      UnreadBadge(count: unreadMentionCount, tint: .red)
    }
    .accessibilityLabel(L10n.Chats.Chats.ScrollButton.ScrollToMention.accessibilityLabel)
    .accessibilityValue(L10n.Chats.Chats.ScrollButton.ScrollToMention.accessibilityValue(unreadMentionCount))
    .accessibilityHint(L10n.Chats.Chats.ScrollButton.ScrollToMention.accessibilityHint)
  }
}

#Preview("With multiple") {
  ScrollToMentionButton(unreadMentionCount: 5, onTap: {})
    .padding(50)
}

#Preview("With one") {
  ScrollToMentionButton(unreadMentionCount: 1, onTap: {})
    .padding(50)
}
