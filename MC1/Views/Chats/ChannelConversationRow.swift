import MC1Services
import SwiftUI

struct ChannelConversationRow: View {
  private typealias Strings = L10n.Chats.Chats.Row
  let channel: ChannelDTO
  let viewModel: ChatViewModel
  var referenceDate: Date?

  var body: some View {
    HStack(spacing: 12) {
      ChannelAvatar(channel: channel, size: 44)

      VStack(alignment: .leading, spacing: 4) {
        HStack(spacing: 4) {
          Text(channel.displayName)
            .font(.headline)
            .lineLimit(1)

          Spacer()

          NotificationLevelIndicator(level: channel.notificationLevel)

          if channel.isFavorite {
            Image(systemName: "star.fill")
              .foregroundStyle(.yellow)
              .font(.caption)
              .accessibilityLabel(Strings.favorite)
          }

          if let date = channel.lastMessageDate {
            ConversationTimestamp(date: date, referenceDate: referenceDate)
          }
        }

        HStack {
          Text(viewModel.lastMessagePreview(id: channel.id) ?? Strings.noMessages)
            .font(.subheadline)
            .foregroundStyle(.secondary)
            .lineLimit(1)

          Spacer()

          UnreadBadges(
            unreadCount: channel.unreadCount,
            unreadMentionCount: channel.unreadMentionCount,
            notificationLevel: channel.notificationLevel
          )
        }
      }
    }
    .padding(.vertical, 4)
  }
}
