import SwiftUI
import MC1Services

/// Message bubble for room server messages
struct RoomMessageBubble: View {
    let message: RoomMessageDTO
    let showTimestamp: Bool
    var onRetry: (() -> Void)?

    @Environment(\.colorSchemeContrast) private var colorSchemeContrast

    private var isFromSelf: Bool { message.isFromSelf }

    var body: some View {
        VStack(spacing: 4) {
            if showTimestamp {
                makeTimestampView()
            }

            HStack(alignment: .bottom, spacing: 8) {
                if isFromSelf {
                    Spacer(minLength: 60)
                }

                VStack(alignment: isFromSelf ? .trailing : .leading, spacing: 2) {
                    makeBubbleContent()
                    makeStatusIndicator()
                }

                if !isFromSelf {
                    Spacer(minLength: 60)
                }
            }
            .padding(.horizontal)
        }
    }

    // MARK: - Subviews

    private func makeTimestampView() -> some View {
        TimestampView(date: message.date)
    }

    private func makeBubbleContent() -> some View {
        BubbleContent(
            message: message,
            isFromSelf: isFromSelf,
            highContrast: colorSchemeContrast == .increased
        )
    }

    private func makeStatusIndicator() -> some View {
        StatusIndicator(
            message: message,
            isFromSelf: isFromSelf,
            statusText: statusText,
            accessibilityStatusLabel: accessibilityStatusLabel,
            onRetry: onRetry
        )
    }

    private var statusText: String {
        switch message.status {
        case .pending, .sending:
            return L10n.Chats.Chats.Message.Status.sending
        case .sent:
            return L10n.Chats.Chats.Message.Status.sent
        case .delivered:
            return L10n.Chats.Chats.Message.Status.delivered
        case .failed:
            return L10n.Chats.Chats.Message.Status.failed
        case .retrying:
            return L10n.Chats.Chats.Message.Status.retrying
        }
    }

    private var accessibilityStatusLabel: String {
        switch message.status {
        case .failed:
            return L10n.RemoteNodes.RemoteNodes.Room.Message.Status.failedLabel
        case .pending, .sending, .retrying:
            return L10n.RemoteNodes.RemoteNodes.Room.Message.Status.sendingLabel
        default:
            return L10n.RemoteNodes.RemoteNodes.Room.Message.Status.deliveredLabel
        }
    }
}

// MARK: - Timestamp View

private struct TimestampView: View {
    let date: Date

    var body: some View {
        Text(date, format: .dateTime.month().day().hour().minute())
            .font(.caption2)
            .foregroundStyle(.secondary)
            .padding(.vertical, 8)
    }
}

// MARK: - Bubble Content

private struct BubbleContent: View {
    let message: RoomMessageDTO
    let isFromSelf: Bool
    let highContrast: Bool

    private var bubbleBackground: Color {
        if isFromSelf {
            if message.status == .failed {
                return AppColors.Message.outgoingBubbleFailed(highContrast: highContrast)
            }
            return AppColors.Message.outgoingBubble
        }
        return AppColors.Message.incomingBubble
    }

    private var textColor: Color {
        isFromSelf ? .white : .primary
    }

    var body: some View {
        VStack(alignment: isFromSelf ? .trailing : .leading, spacing: 4) {
            if !isFromSelf {
                Text(message.authorDisplayName)
                    .font(.footnote)
                    .bold()
                    .foregroundStyle(AppColors.NameColor.color(for: message.authorDisplayName, highContrast: highContrast))
                    .padding(.horizontal, 12)
            }

            MessageText(message.text, baseColor: textColor, isOutgoing: isFromSelf)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(bubbleBackground)
                .clipShape(.rect(cornerRadius: 16, style: .continuous))
        }
    }
}

// MARK: - Status Indicator

private struct StatusIndicator: View {
    let message: RoomMessageDTO
    let isFromSelf: Bool
    let statusText: String
    let accessibilityStatusLabel: String
    let onRetry: (() -> Void)?

    var body: some View {
        if isFromSelf {
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
                        .foregroundStyle(.blue)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L10n.Chats.Chats.Message.Status.retry)
                    .accessibilityHint(L10n.RemoteNodes.RemoteNodes.Room.Message.retryHint)
                }

                Text(statusText)
                    .font(.caption2)
                    .foregroundStyle(.secondary)

                if message.status == .failed {
                    Image(systemName: "exclamationmark.circle")
                        .font(.caption2)
                        .foregroundStyle(.red)
                }
            }
            .padding(.trailing, 4)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(accessibilityStatusLabel)
        }
    }
}

#Preview("Self Message") {
    RoomMessageBubble(
        message: RoomMessageDTO(
            sessionID: UUID(),
            authorKeyPrefix: Data(repeating: 0x42, count: 4),
            authorName: "Me",
            text: "Hello from me!",
            timestamp: UInt32(Date().timeIntervalSince1970),
            isFromSelf: true
        ),
        showTimestamp: true
    )
}

#Preview("Other Message") {
    RoomMessageBubble(
        message: RoomMessageDTO(
            sessionID: UUID(),
            authorKeyPrefix: Data(repeating: 0x55, count: 4),
            authorName: "Alice",
            text: "Hello from Alice!",
            timestamp: UInt32(Date().timeIntervalSince1970),
            isFromSelf: false
        ),
        showTimestamp: true
    )
}

#Preview("Pending Message") {
    RoomMessageBubble(
        message: RoomMessageDTO(
            sessionID: UUID(),
            authorKeyPrefix: Data(repeating: 0x42, count: 4),
            authorName: "Me",
            text: "Sending...",
            timestamp: UInt32(Date().timeIntervalSince1970),
            isFromSelf: true,
            status: .pending
        ),
        showTimestamp: true
    )
}

#Preview("Failed Message") {
    RoomMessageBubble(
        message: RoomMessageDTO(
            sessionID: UUID(),
            authorKeyPrefix: Data(repeating: 0x42, count: 4),
            authorName: "Me",
            text: "This failed to send",
            timestamp: UInt32(Date().timeIntervalSince1970),
            isFromSelf: true,
            status: .failed
        ),
        showTimestamp: true,
        onRetry: { print("Retry tapped") }
    )
}
