import MC1Services
import SwiftUI

/// Sender-name label for an incoming channel message. When the sender name
/// matched a local contact by name only, shows the nickname prominently with
/// the unverified sender name parenthesized and a "?" affordance explaining
/// that channel senders are not cryptographically verified.
struct SenderNameLabel: View {
  let resolution: NodeNameResolution
  var font: Font = .footnote
  var nameColor: Color = .primary

  var body: some View {
    HStack(spacing: 4) {
      if let nickname = resolution.unverifiedNickname {
        Text(nickname)
          .font(font)
          .bold()
          .foregroundStyle(nameColor)
        Text(L10n.Chats.Chats.Message.Sender.unverifiedNicknameFormat(resolution.displayName))
          .font(font)
          .foregroundStyle(.secondary)
        FallbackMatchIndicatorView(
          accessibilityLabel: L10n.Chats.Chats.Message.Sender.unverifiedNicknameAccessibilityLabel,
          accessibilityHint: L10n.Chats.Chats.Message.Sender.unverifiedNicknameExplanation,
          title: L10n.Chats.Chats.Message.Sender.unverifiedNickname,
          explanation: L10n.Chats.Chats.Message.Sender.unverifiedNicknameExplanation
        )
      } else {
        Text(resolution.displayName)
          .font(font)
          .bold()
          .foregroundStyle(nameColor)
        if resolution.isFallback {
          FallbackMatchIndicatorView()
        }
      }
    }
  }
}

#Preview {
  VStack(alignment: .leading, spacing: 16) {
    SenderNameLabel(
      resolution: NodeNameResolution(displayName: "Alpha", matchKind: .exact, unverifiedNickname: "Rico")
    )
    SenderNameLabel(
      resolution: NodeNameResolution(displayName: "Bravo", matchKind: .exact)
    )
    SenderNameLabel(
      resolution: NodeNameResolution(displayName: "Charlie", matchKind: .fallback)
    )
  }
  .padding()
}
