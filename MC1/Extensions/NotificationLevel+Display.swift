import MC1Services

extension NotificationLevel {
  /// Localized display label for pickers and rows.
  var localizedName: String {
    switch self {
    case .muted: L10n.Chats.Chats.NotificationLevel.muted
    case .mentionsOnly: L10n.Chats.Chats.NotificationLevel.mentions
    case .all: L10n.Chats.Chats.NotificationLevel.all
    }
  }

  /// Localized VoiceOver description of the level's effect.
  var localizedAccessibilityDescription: String {
    switch self {
    case .muted: L10n.Chats.Chats.NotificationLevel.Accessibility.muted
    case .mentionsOnly: L10n.Chats.Chats.NotificationLevel.Accessibility.mentionsOnly
    case .all: L10n.Chats.Chats.NotificationLevel.Accessibility.all
    }
  }
}
