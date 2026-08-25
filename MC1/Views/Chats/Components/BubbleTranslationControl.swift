import MC1Services
import SwiftUI

/// In-bubble Translation offer. Not a `Button`, so `tapYieldingToLongPress` can
/// yield to the bubble long-press. VoiceOver-hidden; the bubble exposes the action.
struct BubbleTranslationControl: View {
  let phase: MessageTranslationChrome.Phase
  let isOutgoing: Bool
  let onTap: () -> Void

  @Environment(\.appTheme) private var theme

  var body: some View {
    label
      .font(.caption)
      .imageScale(.small)
      .labelStyle(.titleAndIcon)
      .foregroundStyle(foreground)
      .tint(isOutgoing ? theme.outgoingTextColor : .accentColor)
      .multilineTextAlignment(isOutgoing ? .trailing : .leading)
      .contentShape(.rect)
      .accessibilityHidden(true)
      .tapYieldingToLongPress {
        if case .inProgress = phase { return }
        onTap()
      }
  }

  static func title(for phase: MessageTranslationChrome.Phase) -> String {
    switch phase {
    case .offer:
      L10n.Chats.Chats.Message.Action.translate
    case .inProgress:
      L10n.Chats.Chats.Message.Action.translating
    case .showing:
      L10n.Chats.Chats.Message.Action.showOriginal
    }
  }

  private var foreground: Color {
    isOutgoing ? theme.outgoingTextColor : .accentColor
  }

  @ViewBuilder
  private var label: some View {
    switch phase {
    case .offer, .showing:
      Label(Self.title(for: phase), systemImage: "translate")
    case .inProgress:
      Label {
        Text(Self.title(for: phase))
      } icon: {
        ProgressView()
          .controlSize(.mini)
      }
    }
  }
}
