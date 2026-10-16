import MC1Services
import SwiftUI

struct NotificationLevelIndicator: View {
  let level: NotificationLevel

  var body: some View {
    switch level {
    case .muted:
      Image(systemName: "bell.slash")
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityLabel(L10n.Chats.Chats.Row.muted)
    case .mentionsOnly:
      Image(systemName: "at")
        .font(.caption2)
        .foregroundStyle(.secondary)
        .accessibilityLabel(L10n.Chats.Chats.Row.mentionsOnly)
    case .all:
      EmptyView()
    }
  }
}
