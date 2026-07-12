import SwiftUI

/// Button to scroll to the new messages divider
struct ScrollToDividerButton: View {
  let onTap: () -> Void

  var body: some View {
    Button(action: onTap) {
      Image(systemName: "chevron.up")
        .font(.body.bold())
        .frame(width: 44, height: 44)
        .contentShape(.circle)
    }
    .buttonStyle(.plain)
    .liquidGlassInteractive(in: .circle)
    .accessibilityLabel(L10n.Chats.Chats.ScrollButton.ScrollToDivider.accessibilityLabel)
    .accessibilityHint(L10n.Chats.Chats.ScrollButton.ScrollToDivider.accessibilityHint)
  }
}

#Preview {
  ScrollToDividerButton(onTap: {})
    .padding(50)
}
