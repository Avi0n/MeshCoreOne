import MC1Services
import SwiftUI

/// Outgoing-message status row: retry button (when failed), failure glyph, and
/// status text. The static `statusText(for:)` helper is also consumed by
/// `UnifiedMessageBubble.accessibilityMessageLabel` so VoiceOver surfaces the
/// same text as the visual row.
struct BubbleStatusRow: View {
  let item: MessageItem
  let onRetry: (() -> Void)?

  @State private var retryInvocationCounter: Int = 0

  var body: some View {
    HStack(spacing: 4) {
      if item.footer.status == .failed, let onRetry {
        Button {
          retryInvocationCounter &+= 1
          onRetry()
        } label: {
          HStack(spacing: 2) {
            Image(systemName: "arrow.clockwise")
            Text(L10n.Chats.Chats.Message.Status.retry)
          }
          .font(.caption2)
          .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .foregroundStyle(.blue)
        .sensoryFeedback(.selection, trigger: retryInvocationCounter)
      }

      if item.footer.status == .failed {
        Image(systemName: "exclamationmark.circle")
          .font(.caption2)
          .foregroundStyle(.red)
      }

      Text(Self.statusText(for: item))
        .font(.caption2)
        .foregroundStyle(.secondary)
        .contentTransition(.opacity)
    }
    .padding(.trailing, 4)
  }

  static func statusText(for item: MessageItem) -> String {
    switch item.footer.status {
    case .pending, .sending:
      return L10n.Chats.Chats.Message.Status.sending
    case .sent:
      // A DM `.sent` means only that the radio queued the packet, not that it
      // was delivered; a missing end-to-end ACK later flips the row to `.failed`.
      // Render it as in-progress so the user never sees a settled "Sent" that
      // becomes "Failed". A channel `.sent` has no ACK and is terminal success.
      guard item.footer.isChannelMessage else {
        return L10n.Chats.Chats.Message.Status.sending
      }
      var parts: [String] = []
      if item.footer.heardRepeats > 0 {
        let repeatWord = item.footer.heardRepeats == 1
          ? L10n.Chats.Chats.Message.Repeat.singular
          : L10n.Chats.Chats.Message.Repeat.plural
        parts.append("\(item.footer.heardRepeats) \(repeatWord)")
      }
      if item.footer.sendCount > 1 {
        parts.append(L10n.Chats.Chats.Message.Status.sentMultiple(item.footer.sendCount))
      } else {
        parts.append(L10n.Chats.Chats.Message.Status.sent)
      }
      return parts.joined(separator: " • ")
    case .delivered:
      var parts: [String] = []
      if item.footer.heardRepeats > 0 {
        let repeatWord = item.footer.heardRepeats == 1
          ? L10n.Chats.Chats.Message.Repeat.singular
          : L10n.Chats.Chats.Message.Repeat.plural
        parts.append("\(item.footer.heardRepeats) \(repeatWord)")
      }
      parts.append(L10n.Chats.Chats.Message.Status.delivered)
      if item.footer.sendCount > 1 {
        parts.append(L10n.Chats.Chats.Message.Status.sentMultiple(item.footer.sendCount))
      }
      return parts.joined(separator: " • ")
    case .failed:
      return L10n.Chats.Chats.Message.Status.failed
    case .retrying:
      let displayAttempt = item.footer.retryAttempt + 1
      let maxAttempts = item.footer.maxRetryAttempts
      if maxAttempts > 0 {
        return L10n.Chats.Chats.Message.Status.retryingAttempt(displayAttempt, maxAttempts)
      }
      return L10n.Chats.Chats.Message.Status.retrying
    }
  }
}
