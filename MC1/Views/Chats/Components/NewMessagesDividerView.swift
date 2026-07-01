import SwiftUI

/// Horizontal divider line with centered "New Messages" label.
/// Rendered above the first unread message in a conversation.
struct NewMessagesDividerView: View {
  var body: some View {
    HStack {
      VStack { Divider() }
      Text(L10n.Chats.Chats.Divider.newMessages)
        .font(.caption2)
        .bold()
        .foregroundStyle(.blue)
      VStack { Divider() }
    }
    .accessibilityLabel(L10n.Chats.Chats.Divider.newMessagesAccessibility)
  }
}

#Preview {
  VStack(spacing: 20) {
    Text("Previous message")
    NewMessagesDividerView()
    Text("First unread message")
  }
  .padding()
}
