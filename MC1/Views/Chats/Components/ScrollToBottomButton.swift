import SwiftUI

/// Button to scroll to latest message with unread badge
struct ScrollToBottomButton: View {
  let isVisible: Bool
  let unreadCount: Int
  let onTap: () -> Void

  var body: some View {
    Button(action: onTap) {
      Image(systemName: "chevron.down")
        .font(.body.bold())
        .frame(width: 44, height: 44)
    }
    .buttonStyle(.plain)
    .contentShape(.circle)
    .liquidGlassInteractive(in: .circle)
    .overlay(alignment: .topTrailing) {
      UnreadBadge(count: unreadCount, tint: .blue)
    }
    .opacity(isVisible ? 1 : 0)
    .scaleEffect(isVisible ? 1 : 0.5)
    .animation(.snappy(duration: 0.2), value: isVisible)
    .accessibilityLabel(L10n.Chats.Chats.ScrollButton.ScrollToBottom.accessibilityLabel)
    .accessibilityValue(unreadCount > 0 ? L10n.Chats.Chats.ScrollButton.ScrollToBottom.accessibilityValue(unreadCount) : "")
    .accessibilityHidden(!isVisible)
  }
}

#Preview("Visible with unread") {
  ScrollToBottomButton(isVisible: true, unreadCount: 5, onTap: {})
    .padding(50)
}

#Preview("Visible no unread") {
  ScrollToBottomButton(isVisible: true, unreadCount: 0, onTap: {})
    .padding(50)
}

#Preview("Hidden") {
  ScrollToBottomButton(isVisible: false, unreadCount: 3, onTap: {})
    .padding(50)
}
