import SwiftUI
import MC1Services

struct ConversationRow: View {
    let contact: ContactDTO
    let viewModel: ChatViewModel
    var referenceDate: Date?

    var body: some View {
        HStack(spacing: 12) {
            ContactAvatar(contact: contact, size: 44)

            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 4) {
                    Text(contact.displayName)
                        .font(.headline)
                        .lineLimit(1)

                    Spacer()

                    NotificationLevelIndicator(level: contact.isMuted ? .muted : .all)

                    if viewModel.togglingFavoriteID == contact.id {
                        ProgressView()
                            .controlSize(.small)
                    } else if contact.isFavorite {
                        Image(systemName: "star.fill")
                            .foregroundStyle(.yellow)
                            .font(.caption)
                            .accessibilityLabel(L10n.Chats.Chats.Row.favorite)
                    }

                    if let date = contact.lastMessageDate {
                        ConversationTimestamp(date: date, referenceDate: referenceDate)
                    }
                }

                HStack {
                    Text(viewModel.lastMessagePreview(id: contact.id) ?? L10n.Chats.Chats.Row.noMessages)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)

                    Spacer()

                    UnreadBadges(
                        unreadCount: contact.unreadCount,
                        unreadMentionCount: contact.unreadMentionCount,
                        notificationLevel: contact.isMuted ? .muted : .all
                    )
                }
            }
        }
        .padding(.vertical, 4)
    }
}
