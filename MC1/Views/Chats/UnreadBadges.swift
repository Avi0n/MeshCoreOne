import MC1Services
import SwiftUI

struct UnreadBadges: View {
  let unreadCount: Int
  var unreadMentionCount: Int = 0
  var notificationLevel: NotificationLevel = .all

  private var mentionBadgeColor: Color {
    notificationLevel == .muted ? .secondary : .blue
  }

  private var unreadBadgeColor: Color {
    notificationLevel == .all ? .blue : .secondary
  }

  var body: some View {
    HStack(spacing: 4) {
      if unreadMentionCount > 0 {
        Text("@")
          .font(.caption.bold())
          .foregroundStyle(.white)
          .frame(width: 18, height: 18)
          .background(mentionBadgeColor, in: .circle)
      }

      if unreadCount > 0 {
        Text(unreadCount, format: .number)
          .font(.caption2)
          .bold()
          .foregroundStyle(.white)
          .padding(.horizontal, 6)
          .padding(.vertical, 2)
          .background(unreadBadgeColor, in: .capsule)
      }
    }
  }
}
