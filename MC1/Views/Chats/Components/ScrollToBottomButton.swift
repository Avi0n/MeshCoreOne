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
        .contentShape(.circle)
    }
    .buttonStyle(.plain)
    .liquidGlassInteractive(in: .circle)
    .overlay(alignment: .topTrailing) {
      UnreadBadge(count: unreadCount, tint: .blue)
    }
    .opacity(isVisible ? 1 : 0)
    .scaleEffect(isVisible ? 1 : 0.5)
    .animation(.snappy(duration: 0.2), value: isVisible)
    .accessibilityLabel(L10n.Chats.Chats.ScrollButton.ScrollToBottom.accessibilityLabel)
    .accessibilityValue(unreadCount > 0 ? L10n.Chats.Chats.ScrollButton.ScrollToBottom.accessibilityValue(unreadCount) : "")
    .accessibilityIdentifier(ChatScrollConstants.scrollToBottomButtonIdentifier)
    #if DEBUG
      .background {
        HostedIdentifierView(identifier: ChatScrollConstants.scrollToBottomButtonIdentifier)
      }
    #endif
      .accessibilityHidden(!isVisible)
  }

  #if DEBUG
    /// Hosted tests walk `UIView.accessibilityIdentifier`. iOS 26 Liquid Glass
    /// does not copy SwiftUI `.accessibilityIdentifier` onto that property.
    private struct HostedIdentifierView: UIViewRepresentable {
      var identifier: String

      func makeUIView(context: Context) -> UIView {
        let view = UIView()
        view.isUserInteractionEnabled = false
        view.backgroundColor = .clear
        view.accessibilityIdentifier = identifier
        return view
      }

      func updateUIView(_ uiView: UIView, context: Context) {
        uiView.accessibilityIdentifier = identifier
      }
    }
  #endif
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
