import SwiftUI
import MC1Services

/// Outgoing-message status row: retry button (when failed), failure glyph, and
/// status text. The static `statusText(for:)` helper is also consumed by
/// `UnifiedMessageBubble.accessibilityMessageLabel` so VoiceOver surfaces the
/// same text as the visual row.
struct BubbleStatusRow: View {
    let message: MessageDTO
    let onRetry: (() -> Void)?

    private static let minimumTapTargetHeight: CGFloat = 44

    var body: some View {
        HStack(spacing: 4) {
            if message.status == .failed, let onRetry {
                Button {
                    onRetry()
                } label: {
                    HStack(spacing: 2) {
                        Image(systemName: "arrow.clockwise")
                        Text(L10n.Chats.Chats.Message.Status.retry)
                    }
                    .font(.caption2)
                    .frame(minHeight: Self.minimumTapTargetHeight)
                    .contentShape(.rect)
                }
                .buttonStyle(.plain)
                .foregroundStyle(.blue)
            }

            if message.status == .failed {
                Image(systemName: "exclamationmark.circle")
                    .font(.caption2)
                    .foregroundStyle(.red)
            }

            Text(Self.statusText(for: message))
                .font(.caption2)
                .foregroundStyle(.secondary)
                .contentTransition(.opacity)
        }
        .padding(.trailing, 4)
    }

    static func statusText(for message: MessageDTO) -> String {
        switch message.status {
        case .pending, .sending:
            return L10n.Chats.Chats.Message.Status.sending
        case .sent:
            var parts: [String] = []
            if message.heardRepeats > 0 {
                let repeatWord = message.heardRepeats == 1
                    ? L10n.Chats.Chats.Message.Repeat.singular
                    : L10n.Chats.Chats.Message.Repeat.plural
                parts.append("\(message.heardRepeats) \(repeatWord)")
            }
            if message.sendCount > 1 {
                parts.append(L10n.Chats.Chats.Message.Status.sentMultiple(message.sendCount))
            } else {
                parts.append(L10n.Chats.Chats.Message.Status.sent)
            }
            return parts.joined(separator: " • ")
        case .delivered:
            if message.heardRepeats > 0 {
                let repeatWord = message.heardRepeats == 1
                    ? L10n.Chats.Chats.Message.Repeat.singular
                    : L10n.Chats.Chats.Message.Repeat.plural
                let repeatText = "\(message.heardRepeats) \(repeatWord)"
                return "\(repeatText) • \(L10n.Chats.Chats.Message.Status.delivered)"
            }
            return L10n.Chats.Chats.Message.Status.delivered
        case .failed:
            return L10n.Chats.Chats.Message.Status.failed
        case .retrying:
            let displayAttempt = message.retryAttempt + 1
            let maxAttempts = message.maxRetryAttempts
            if maxAttempts > 0 {
                return L10n.Chats.Chats.Message.Status.retryingAttempt(displayAttempt, maxAttempts)
            }
            return L10n.Chats.Chats.Message.Status.retrying
        }
    }
}
