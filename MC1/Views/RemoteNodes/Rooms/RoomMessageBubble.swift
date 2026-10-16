import MC1Services
import SwiftUI

/// Top padding for same-cluster follow-ups (no timestamp). `ChatTiledView` has no cell spacing.
private let sameClusterPaddingTop: CGFloat = 2

/// Message bubble for room server messages
struct RoomMessageBubble: View {
  let message: RoomMessageDTO
  let showTimestamp: Bool
  let showSenderName: Bool
  let showAvatar: Bool
  var translation: MessageTranslationChrome?
  var onRetry: (() -> Void)?
  var onLongPress: ((RoomMessageDTO) -> Void)?
  var onTranslationAction: (() -> Void)?

  @Environment(\.colorSchemeContrast) private var colorSchemeContrast
  @Environment(\.appTheme) private var theme
  @Environment(\.colorScheme) private var colorScheme
  @Environment(\.openURL) private var openURL

  @State private var isLongPressing = false
  @State private var longPressTrigger = 0

  private var isFromSelf: Bool {
    message.isFromSelf
  }

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
        .accessibilityElement(children: .combine)
        .accessibilityLabel(accessibilityMessageLabel)
        .accessibilityAction {
          onLongPress?(message)
        }
        .accessibilityActions {
          if let chrome = translation {
            switch chrome.phase {
            case .offer:
              Button(L10n.Chats.Chats.Message.Action.translate) {
                onTranslationAction?()
              }
            case .showing:
              Button(L10n.Chats.Chats.Message.Action.showOriginal) {
                onTranslationAction?()
              }
            case .inProgress:
              EmptyView()
            }
          }
          if accessibilityShowsRetryAction {
            Button(L10n.Chats.Chats.Message.Action.retry) { performAccessibilityRetry() }
          }
          ForEach(accessibilityLinkActions(formatted: formattedBodyText)) { action in
            Button(action.name) { openURL(action.url) }
          }
        }

        if !isFromSelf {
          Spacer(minLength: 60)
        }
      }
      .padding(.horizontal)
      .padding(.top, showTimestamp ? 0 : sameClusterPaddingTop)
    }
  }

  // MARK: - Subviews

  private func makeTimestampView() -> some View {
    TimestampView(date: message.date)
  }

  var accessibilityMessageLabel: String {
    let body: String = switch translation?.phase {
    case let .showing(translated, _):
      L10n.Chats.Chats.Message.translatedAccessibility(translated)
    case .inProgress:
      "\(message.text), \(L10n.Chats.Chats.Message.Action.translating)"
    default:
      message.text
    }
    if isFromSelf {
      return "\(body), \(message.accessibilityStatusLabel)"
    }
    return "\(message.authorDisplayName): \(body)"
  }

  var accessibilityShowsRetryAction: Bool {
    message.status == .failed && onRetry != nil
  }

  func performAccessibilityRetry() {
    onRetry?()
  }

  func accessibilityLinkActions(formatted: AttributedString) -> [MessageLinkAccessibility.Action] {
    MessageLinkAccessibility.actions(previewURL: nil, formatted: formatted)
  }

  private var formattedBodyText: AttributedString {
    MessageText.buildFormattedText(
      text: message.text,
      isOutgoing: isFromSelf,
      currentUserName: nil,
      isHighContrast: colorSchemeContrast == .increased,
      outgoingTextColor: theme.outgoingTextColor,
      hashtagColor: theme.hashtagColor,
      identityGamut: theme.identityGamut,
      identityBackgroundLuminances: theme.avatarSurfaceLuminances(
        colorScheme: colorScheme,
        contrast: colorSchemeContrast
      )
    ).text
  }

  private func makeBubbleContent() -> some View {
    BubbleContent(
      message: message,
      isFromSelf: isFromSelf,
      showSenderName: showSenderName,
      showAvatar: showAvatar,
      highContrast: colorSchemeContrast == .increased,
      formattedBodyText: formattedBodyText,
      translation: translation,
      onTranslationAction: onTranslationAction
    )
    .messageBubbleLongPressGesture(
      isPressing: $isLongPressing,
      trigger: $longPressTrigger,
      onFire: { onLongPress?(message) }
    )
    .messageBubbleLongPressEffect(isPressing: isLongPressing, trigger: longPressTrigger)
  }

  private func makeStatusIndicator() -> some View {
    StatusIndicator(
      message: message,
      isFromSelf: isFromSelf,
      statusText: message.localizedStatusText,
      accessibilityStatusLabel: message.accessibilityStatusLabel,
      onRetry: onRetry
    )
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
  let showSenderName: Bool
  let showAvatar: Bool
  let highContrast: Bool
  let formattedBodyText: AttributedString
  var translation: MessageTranslationChrome?
  var onTranslationAction: (() -> Void)?

  @Environment(\.appTheme) private var theme
  @Environment(\.colorScheme) private var colorScheme

  private var bubbleBackground: Color {
    if isFromSelf {
      if message.status == .failed {
        return AppColors.Message.outgoingBubbleFailed(highContrast: highContrast)
      }
      return theme.accentColor // matches UnifiedMessageBubble.resolvedBubbleColor
    }
    return theme.incomingBubbleColor
  }

  private var textColor: Color {
    isFromSelf ? theme.outgoingTextColor : .primary
  }

  var body: some View {
    VStack(alignment: isFromSelf ? .trailing : .leading, spacing: 0) {
      if !isFromSelf, showSenderName {
        Text(message.authorDisplayName)
          .font(.footnote)
          .bold()
          .foregroundStyle(theme.identityColor(
            forName: message.authorDisplayName,
            colorScheme: colorScheme,
            contrast: highContrast ? .increased : .standard
          ))
          .senderNamePlacement()
          .padding(.leading, IncomingBubbleAvatarMetrics.columnWidth)
          .accessibilityHidden(true)
      }

      IncomingAvatarGutter(
        identity: showAvatar ? .initials(name: message.authorDisplayName) : nil,
        reserveColumn: !isFromSelf,
        messageID: message.id
      ) {
        messageBox
      }
    }
  }

  private var messageBox: some View {
    VStack(alignment: isFromSelf ? .trailing : .leading, spacing: 2) {
      if let chrome = translation {
        BubbleTranslationControl(
          phase: chrome.phase,
          onTap: { onTranslationAction?() }
        )
      }
      messageBody
    }
    .padding(.horizontal, 12)
    .padding(.vertical, 8)
    .background(bubbleBackground)
    .clipShape(.rect(cornerRadius: 16, style: .continuous))
  }

  @ViewBuilder
  private var messageBody: some View {
    if case let .showing(translated, _) = translation?.phase {
      Text(translated)
        .font(.body)
        .foregroundStyle(textColor)
    } else {
      MessageText(message.text, baseColor: textColor, isOutgoing: isFromSelf, precomputedText: formattedBodyText)
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
    showTimestamp: true,
    showSenderName: false,
    showAvatar: false
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
    showTimestamp: true,
    showSenderName: true,
    showAvatar: true
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
    showTimestamp: true,
    showSenderName: false,
    showAvatar: false
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
    showSenderName: false,
    showAvatar: false,
    onRetry: { print("Retry tapped") }
  )
}
